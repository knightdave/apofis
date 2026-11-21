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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.BreakIterator
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

// ---- bounded pieces of long text

/** The most characters one composable is given of a message (an expanded bubble, one piece of the Select view). */
internal const val PAGE_CHARS = 8000

/** The most line breaks in one piece; CR, LF and CRLF count as one each. */
internal const val PAGE_LINES = 200

/**
 * The end of the piece of [text] that starts at [start]: after the [maxLines]th line break, else after the last line
 * break within [maxChars] characters, else at the last grapheme boundary within them. Never inside CRLF or a
 * surrogate pair; always past [start] when text remains. Reads at most [maxChars] + 1 characters (plus a small
 * window for the grapheme boundary).
 */
internal fun pageEnd(text: CharSequence, start: Int, maxChars: Int = PAGE_CHARS, maxLines: Int = PAGE_LINES): Int {
    val limit = minOf(text.length, start + maxChars)
    var lines = 0
    var lastBreak = -1
    var i = start
    while (i < limit) {
        val c = text[i]
        if (c == '\n' || c == '\r') {
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
            lastBreak = i + 1
            if (++lines == maxLines) return lastBreak
        }
        i++
    }
    if (limit == text.length) return limit
    if (lastBreak > start) return lastBreak
    return graphemeBefore(text, limit, start)
}

/** The last grapheme boundary at or before [pos] and after [start], from a small window around [pos]. */
private fun graphemeBefore(text: CharSequence, pos: Int, start: Int): Int {
    val from = maxOf(start, pos - 64)
    val window = text.subSequence(from, minOf(text.length, pos + 64)).toString()
    val bi = BreakIterator.getCharacterInstance().apply { setText(window) }
    val b = bi.preceding(pos - from + 1)
    // A cluster longer than the window (or none found): fall back to a code point boundary.
    if (b != BreakIterator.DONE && b > 0) return from + b
    return if (text[pos - 1].isHighSurrogate() && pos - 1 > start) pos - 1 else pos
}

/** The end offsets of the pieces of [text]; the pieces concatenate to [text] exactly. At least one (possibly empty). */
internal fun textPages(text: CharSequence): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    val ends = ArrayList<Int>()
    var start = 0
    while (start < text.length) {
        start = pageEnd(text, start)
        ends += start
    }
    return ends.toIntArray()
}

// ---- what an action may act on

/** Copy and Select apply to a sent user message and a finished reply with text. */
private fun eligible(item: ChatItem) = when (item) {
    is UserMessage -> item.state == SendState.SENT
    is AssistantReply -> !item.live && item.text.isNotBlank()
    else -> false
}

/**
 * The raw text of [drawn] if [items] (the transcript now) still holds exactly that message, with the same text and
 * still eligible; null otherwise. Run at action time.
 */
internal fun stillShown(items: List<ChatItem>, drawn: ChatItem): String? {
    for (i in items.indices.reversed()) {
        val item = items[i]
        if (item.key != drawn.key) continue
        val same = item::class == drawn::class && textOf(item) == textOf(drawn) && eligible(item)
        return if (same) textOf(item) else null
    }
    return null
}

private fun textOf(item: ChatItem): String? = when (item) {
    is UserMessage -> item.text
    is AssistantReply -> item.text
    else -> null
}

/** The raw text the Select view shows for [key]: any user message (Show more on a long one opens it) or a finished reply with text. */
internal fun selectSource(items: List<ChatItem>, key: String): String? {
    for (i in items.indices.reversed()) {
        val item = items[i]
        if (item.key != key) continue
        return when (item) {
            is UserMessage -> item.text
            is AssistantReply -> item.text.takeIf { !item.live && it.isNotBlank() }
            else -> null
        }
    }
    return null
}

/**
 * [text] with CRLF and CR as LF (as everywhere else), or null when the result would exceed [limit]. Inspects at most
 * 2 [limit] characters and builds at most [limit] + 1: a refusal never copies the whole text.
 */
internal fun lfWithin(text: CharSequence, limit: Int): String? {
    if (text.length > 2L * limit) return null // even all-CRLF text would not fold to the limit
    if (text.indexOf('\r') < 0) return if (text.length <= limit) text.toString() else null
    val out = StringBuilder(minOf(text.length, limit + 1))
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\r') {
            out.append('\n')
            if (i + 1 < text.length && text[i + 1] == '\n') i++
        } else {
            out.append(c)
        }
        if (out.length > limit) return null
        i++
    }
    return out.toString()
}

/**
 * Accepts one action per double tap: a second activation within [timeout] ms of the last accepted one is ignored, also
 * when the button under the finger was replaced in between (the guard lives in the list, not the button). [now] is a
 * monotonic clock.
 */
internal class TapGuard(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val timeout: Long = ViewConfiguration.getDoubleTapTimeout().toLong(),
) {
    private var last: Long? = null

    fun accept(): Boolean {
        val t = now()
        last?.let { if (t - it < timeout) return false }
        last = t
        return true
    }
}

/**
 * What the action rows of one list need: the transcript as it is now (read at action time), Select, and one [TapGuard]
 * per kind of action, so a double tap never runs Copy (or Select) twice even across a replaced row, while Copy then
 * Select stays possible.
 */
internal class RowActions(
    val current: () -> List<ChatItem>,
    val onSelect: (String) -> Unit,
    val copyGuard: TapGuard = TapGuard(),
    val selectGuard: TapGuard = TapGuard(),
)

// ---- the row

/**
 * Copy then Select under [drawn], 48 dp apart. Each press is bound to the message its button showed when the press
 * began; the action runs only if the transcript at that moment still holds exactly that message. A Copy result shows
 * below the buttons for [COPY_NOTE_MS], never taking their room. [finishedNow]: the reply just finished on this screen,
 * so the row enters with a size and fade transition, once (also across scrolling and recreation).
 */
@Composable
internal fun MessageActionRow(drawn: ChatItem, actions: RowActions, modifier: Modifier = Modifier, alignEnd: Boolean = false, finishedNow: Boolean = false) {
    var entered by rememberSaveable { mutableStateOf(!finishedNow) }
    val reduceMotion = LocalReduceMotion.current
    val visibility = remember { MutableTransitionState(entered || reduceMotion).apply { targetState = true } }
    LaunchedEffect(Unit) { entered = true }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
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
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButton(R.drawable.lucide_copy, R.string.chat_copy, drawn, actions.copyGuard) { item ->
                    if (copying) return@ActionButton
                    val raw = stillShown(actions.current(), item) ?: return@ActionButton
                    val text = lfWithin(raw, MAX_CLIP_CHARS)
                    copying = true
                    scope.launch {
                        try {
                            note = if (text == null) CopyResult.TOO_LARGE else copyText(clipboard, text)
                        } finally {
                            copying = false
                        }
                        taps++
                    }
                }
                ActionButton(R.drawable.lucide_text_select, R.string.chat_select, drawn, actions.selectGuard) { item ->
                    if (stillShown(actions.current(), item) != null) actions.onSelect(item.key)
                }
            }
            note?.let {
                Text(
                    stringResource(it.note),
                    Modifier.padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelMedium,
                    color = ApofisTheme.colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * One action. The message is taken when the press begins (before the click handler sees the down event) and used by
 * the click that ends it; a click without a press (keyboard, accessibility) takes the message drawn now.
 */
@Composable
private fun ActionButton(@DrawableRes icon: Int, @StringRes description: Int, drawn: ChatItem, guard: TapGuard, onAct: (ChatItem) -> Unit) {
    val drawnNow by rememberUpdatedState(drawn)
    val pressedOn = remember { arrayOfNulls<ChatItem>(1) }
    ApofisIconButton(
        onClick = {
            val item = pressedOn[0] ?: drawnNow
            pressedOn[0] = null
            if (guard.accept()) onAct(item)
        },
        contentDescription = stringResource(description),
        modifier = Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Kept until the click consumes it or the next press replaces it; a stale one can only make an action
                // do nothing, since the action re-checks the transcript.
                pressedOn[0] = drawnNow
            }
        },
        visualSize = 28.dp,
    ) {
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

// ---- which reply just finished

/**
 * Which reply just finished on this screen, as a pure function of the updates the screen was shown. A run seen live
 * ([runId]) remembers the last stored reply before its turn ([before]). After the run ends, an update whose last
 * reply has another key names that reply in [key]; one further update without a new reply (the stored row may
 * arrive one update late) clears the tracker, so a run without a reply never finishes later. [key] lives for one
 * update: the next update consumes it, so a row first composed later never enters as if it had just finished.
 * [next] is called only for a new transcript or a change of run (see [FinishHolder]), so it never rescans per frame.
 */
internal data class FinishTracker(val runId: String? = null, val before: String? = null, val ended: Boolean = false, val key: String? = null) {
    fun next(liveRunId: String?, items: List<ChatItem>): FinishTracker {
        val t = if (key != null) copy(key = null) else this
        return when {
            liveRunId != null -> if (liveRunId == t.runId) t.copy(ended = false) else FinishTracker(runId = liveRunId, before = lastBefore(items, liveRunId))
            t.runId == null -> t
            else -> {
                val last = lastBefore(items, null)
                when {
                    last != null && last != t.before -> FinishTracker(key = last)
                    !t.ended -> t.copy(ended = true)
                    else -> FinishTracker()
                }
            }
        }
    }

    companion object {
        /** The last stored reply, before the turn of run [runId] when given (its stored parts are not history yet). */
        private fun lastBefore(items: List<ChatItem>, runId: String?): String? {
            var end = items.size
            if (runId != null) {
                val turn = items.indexOfLast { it is UserMessage && it.runId == runId }
                if (turn >= 0) end = turn
            }
            for (i in end - 1 downTo 0) (items[i] as? AssistantReply)?.takeIf { !it.live }?.let { return it.key }
            return null
        }

        fun save(t: FinishTracker): List<Any?> = arrayListOf(t.runId, t.before, t.ended)
        fun restore(saved: List<Any?>) = FinishTracker(saved[0] as String?, saved[1] as String?, saved[2] as Boolean)
    }
}

/**
 * Holds a [FinishTracker] across recomposition and process recreation (a pending [FinishTracker.key] is not saved).
 * [update] runs during composition: it advances the tracker only for a new transcript or run, and otherwise only
 * consumes a pending key.
 */
internal class FinishHolder(var tracker: FinishTracker = FinishTracker()) {
    private var lastItems: List<ChatItem>? = null
    private var lastRun: String? = null

    fun update(liveRunId: String?, items: List<ChatItem>): String? {
        if (items === lastItems && liveRunId == lastRun) {
            if (tracker.key != null) tracker = tracker.copy(key = null)
            return null
        }
        lastItems = items
        lastRun = liveRunId
        tracker = tracker.next(liveRunId, items)
        return tracker.key
    }

    companion object {
        val Saver = Saver<FinishHolder, List<Any?>>(save = { FinishTracker.save(it.tracker) }, restore = { FinishHolder(FinishTracker.restore(it)) })
    }
}

/** The replies stored as part of the turn of run [runId] (polling syncs them while it runs): not history yet. */
internal fun activeReplies(items: List<ChatItem>, runId: String?): Set<String> {
    if (runId == null) return emptySet()
    val turn = items.indexOfLast { it is UserMessage && it.runId == runId }
    if (turn < 0) return emptySet()
    return (turn + 1 until items.size).mapNotNullTo(HashSet()) { (items[it] as? AssistantReply)?.key }
}
