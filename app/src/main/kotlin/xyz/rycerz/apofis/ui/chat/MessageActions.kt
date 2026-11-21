package xyz.rycerz.apofis.ui.chat

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.markdown.COPY_NOTE_MS
import xyz.rycerz.apofis.ui.markdown.CopyResult
import xyz.rycerz.apofis.ui.markdown.MAX_CLIP_CHARS
import xyz.rycerz.apofis.ui.markdown.copyText
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.LocalReduceMotion
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.wash

/** The most text the Select view lays out; longer text is refused with a message, never cut. Same bound as Copy. */
internal const val SELECT_MAX_CHARS = MAX_CLIP_CHARS

/**
 * The canonical text (line endings LF, as Copy everywhere) of the sent user message or finished, non-blank reply
 * [key] in [items], or null when no such item is there any more. Read when an action runs, never kept.
 */
internal fun actionText(items: List<ChatItem>, key: String): String? {
    for (i in items.indices.reversed()) {
        val item = items[i]
        if (item.key != key) continue
        return when (item) {
            is UserMessage -> if (item.state == SendState.SENT) lf(item.text) else null
            is AssistantReply -> if (!item.live && item.text.isNotBlank()) lf(item.text) else null
            else -> null
        }
    }
    return null
}

/**
 * Copy then Select under a message, 48 dp apart. [source] gives the message's text as it is in the transcript when
 * tapped (null: the message is gone, the action does nothing). A Copy result shows beside the row for [COPY_NOTE_MS].
 * A second tap within the double-tap timeout does not run an action again. [finishedNow]: the reply just finished on
 * this screen, so the row enters with a size and fade transition, once (also across scrolling and recreation).
 */
@Composable
internal fun MessageActionRow(source: () -> String?, onSelect: () -> Unit, modifier: Modifier = Modifier, alignEnd: Boolean = false, finishedNow: Boolean = false) {
    var entered by rememberSaveable { mutableStateOf(!finishedNow) }
    val reduceMotion = LocalReduceMotion.current
    val visibility = remember { MutableTransitionState(entered || reduceMotion).apply { targetState = true } }
    LaunchedEffect(Unit) { entered = true }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(source)
    var note by remember { mutableStateOf<CopyResult?>(null) }
    var copying by remember { mutableStateOf(false) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        delay(COPY_NOTE_MS)
        note = null
    }
    val tween = tween<Float>(Motion.ACTION_ROW, easing = EaseOutCubic)
    AnimatedVisibility(
        visibility,
        modifier,
        enter = expandVertically(tween(Motion.ACTION_ROW, easing = EaseOutCubic)) + fadeIn(tween),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val noteText = @Composable {
                note?.let {
                    Text(
                        stringResource(it.note),
                        Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = ApofisTheme.colors.muted,
                        maxLines = 1,
                    )
                }
            }
            if (alignEnd) noteText()
            ActionButton(R.drawable.lucide_copy, R.string.chat_copy) {
                if (copying) return@ActionButton
                val text = current() ?: return@ActionButton
                copying = true
                scope.launch {
                    try {
                        note = copyText(clipboard, text)
                    } finally {
                        copying = false
                    }
                    taps++
                }
            }
            ActionButton(R.drawable.lucide_text_select, R.string.chat_select) { if (current() != null) onSelect() }
            if (!alignEnd) noteText()
        }
    }
}

/** Whether a tap at [now] runs its action after the last one that did at [last] (uptime ms): not within a double tap. */
internal fun acceptsTap(last: Long, now: Long) = now - last >= ViewConfiguration.getDoubleTapTimeout()

@Composable
private fun ActionButton(@DrawableRes icon: Int, @StringRes description: Int, onClick: () -> Unit) {
    val last = remember { longArrayOf(Long.MIN_VALUE / 2) } // the last tap that ran, uptime ms
    val run = {
        val now = SystemClock.uptimeMillis()
        if (acceptsTap(last[0], now)) {
            last[0] = now
            onClick()
        }
    }
    ApofisIconButton(onClick = run, contentDescription = stringResource(description), visualSize = 28.dp) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = ApofisTheme.colors.actionIcon)
    }
}

/** A 13 sp primary text button with a 48 dp touch height and the theme's wash (notice actions, Show more). */
@Composable
internal fun SmallTextButton(@StringRes label: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, modifier.heightIn(min = 48.dp).wash(source, ButtonDefaults.textShape), interactionSource = source) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Which reply just finished on this screen, as a pure function of what the screen was shown. While a run is live the
 * tracker is [armed] with the key of the last stored reply before it ([before]). Once the run has ended, the first
 * shown items whose last reply has another key name that reply in [key]; it stays until the next run starts.
 */
internal data class FinishTracker(val before: String? = null, val armed: Boolean = false, val key: String? = null) {
    fun next(live: Boolean, items: List<ChatItem>): FinishTracker = when {
        live -> if (armed) this else FinishTracker(before = lastReply(items), armed = true) // once per run
        !armed -> this
        else -> lastReply(items)?.takeIf { it != before }?.let { FinishTracker(key = it) } ?: this
    }

    companion object {
        /** The key of the last stored (not live) reply; usually a few items from the end. */
        private fun lastReply(items: List<ChatItem>): String? {
            for (i in items.indices.reversed()) (items[i] as? AssistantReply)?.takeIf { !it.live }?.let { return it.key }
            return null
        }

        fun save(t: FinishTracker): List<Any?> = arrayListOf(t.before, t.armed, t.key)
        fun restore(saved: List<Any?>) = FinishTracker(saved[0] as String?, saved[1] as Boolean, saved[2] as String?)
    }
}

/** Holds a [FinishTracker] across recomposition and process recreation; advanced during composition (idempotent). */
internal class FinishHolder(var tracker: FinishTracker = FinishTracker()) {
    fun update(live: Boolean, items: List<ChatItem>): String? = tracker.next(live, items).also { tracker = it }.key

    companion object {
        val Saver = Saver<FinishHolder, List<Any?>>(save = { FinishTracker.save(it.tracker) }, restore = { FinishHolder(FinishTracker.restore(it)) })
    }
}
