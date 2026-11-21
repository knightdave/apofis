package xyz.rycerz.apofis.ui.chat

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.markdown.COPY_NOTE_MS
import xyz.rycerz.apofis.ui.markdown.CopyResult
import xyz.rycerz.apofis.ui.markdown.MAX_CLIP_CHARS
import xyz.rycerz.apofis.ui.markdown.copyText
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.wash

// ---- bounded pieces of long text

/** The most characters one composable is given of a message (an expanded bubble, one piece of the Select view). */
internal const val PAGE_CHARS = 8000

/** How far past the cap a piece may run to finish the grapheme that straddles it. */
internal const val GRAPHEME_SLACK = 1024

/**
 * The one cutting function: the end of the piece of [text] starting at [start] (a grapheme boundary), at the first
 * grapheme boundary at or after [start] + [cap]. Segmentation runs over a window that begins at [start], so flag
 * pairs, ZWJ sequences, combining marks and CRLF stay together; it reads at most [cap] + [GRAPHEME_SLACK] characters.
 * Shortcut: a single grapheme longer than [GRAPHEME_SLACK] is cut at the cap (on a code point boundary).
 */
internal fun pieceEnd(text: CharSequence, start: Int, cap: Int = PAGE_CHARS): Int {
    if (text.length - start <= cap) return text.length
    val windowEnd = minOf(text.length, start + cap + GRAPHEME_SLACK)
    val bi = BreakIterator.getCharacterInstance().apply { setText(text.subSequence(start, windowEnd).toString()) }
    val b = bi.following(cap - 1)
    if (b != BreakIterator.DONE && (start + b < windowEnd || windowEnd == text.length)) return start + b
    val end = start + cap
    return if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end + 1 else end
}

// ---- what an action may act on

/** Reply text worth an action; a body over [PAGE_CHARS] counts as text unread, so the check never scans a huge body. */
private fun hasText(text: String) = text.length > PAGE_CHARS || text.isNotBlank()

/**
 * The one eligibility rule for Copy and Select, at action time and for an open Select view: the raw text of item
 * [key] in [items] when it is a user message, or a reply that is finished (not live), has text and is not part of
 * the turn of the running run [runId] (stored early by polling); else null. One pass from the end per call: there is
 * no key index, and this runs once per tap or per transcript change, never per frame.
 */
internal fun actionable(items: List<ChatItem>, runId: String?, key: String): String? {
    var found: AssistantReply? = null
    for (i in items.indices.reversed()) {
        val item = items[i]
        if (found == null) {
            if (item.key != key) continue
            when (item) {
                is UserMessage -> return item.text
                is AssistantReply -> if (item.live || !hasText(item.text)) return null else if (runId == null) return item.text else found = item
                else -> return null
            }
        } else if (item is UserMessage && item.runId == runId) {
            return null // after the running turn's user message
        }
    }
    return found?.text
}

/** The replies after the running run's user message: no actions yet (the row's form of [actionable]'s turn rule). */
internal fun activeReplies(items: List<ChatItem>, runId: String?): Set<String> {
    if (runId == null) return emptySet()
    val turn = items.indexOfLast { it is UserMessage && it.runId == runId }
    if (turn < 0) return emptySet()
    return (turn + 1 until items.size).mapNotNullTo(HashSet()) { (items[it] as? AssistantReply)?.key }
}

/** What Copy does with [current] (the text now, by [actionable]) for a press on [drawn]: length first, then content. */
internal sealed interface CopyPlan {
    data class Write(val text: String) : CopyPlan
    data object TooLarge : CopyPlan
    data object Skip : CopyPlan
}

internal fun copyPlan(current: CharSequence?, drawn: CharSequence): CopyPlan {
    if (current == null) return CopyPlan.Skip
    if (current.length > 2L * MAX_CLIP_CHARS) return CopyPlan.TooLarge // even all-CRLF text would not fold to the limit
    if (current !== drawn && !current.contentEquals(drawn)) return CopyPlan.Skip // another text than the one pressed
    return lfWithin(current, MAX_CLIP_CHARS)?.let { CopyPlan.Write(it) } ?: CopyPlan.TooLarge
}

/** [text] with CRLF and CR as LF, or null when that exceeds [limit]; reads at most 2 [limit], builds at most [limit] + 1. */
internal fun lfWithin(text: CharSequence, limit: Int): String? {
    if (text.length > 2L * limit) return null
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
 * One accepted action per double tap, per message and action: after an action on a message ran, the same action on
 * the same message is ignored for [timeout] ms. A tap on another row always works; a refused tap starts nothing.
 */
internal class TapGuard(
    private val now: () -> Long = SystemClock::uptimeMillis,
    private val timeout: Long = ViewConfiguration.getDoubleTapTimeout().toLong(),
) {
    private val last = HashMap<String, Long>()

    fun allows(id: String): Boolean = last[id]?.let { now() - it >= timeout } ?: true

    fun ran(id: String) {
        last[id] = now()
    }
}

/** The transcript and run as the owner holds them now (read at action time). */
internal data class Shown(val items: List<ChatItem>, val runId: String?)

/** What the action rows of one list need; the guard lives here, not in a button a refresh can replace. */
internal class RowActions(val current: () -> Shown, val onSelect: (String) -> Unit, val guard: TapGuard = TapGuard())

// ---- the row

/**
 * Copy then Select under [drawn], 48 dp apart. A press is bound to the message its button showed when it began; at
 * the tap, Copy checks it against the owner's transcript by [actionable] and [copyPlan] in one step and writes exactly
 * that text. A Copy result shows below the buttons for [COPY_NOTE_MS], never taking their room.
 */
@Composable
internal fun MessageActionRow(drawn: ChatItem, actions: RowActions, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<CopyResult?>(null) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        delay(COPY_NOTE_MS)
        note = null
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(R.drawable.lucide_copy, R.string.chat_copy, drawn) { item ->
                val id = "copy:${item.key}"
                if (!actions.guard.allows(id)) return@ActionButton
                val shown = actions.current()
                val plan = copyPlan(actionable(shown.items, shown.runId, item.key), textOf(item))
                if (plan == CopyPlan.Skip) return@ActionButton
                actions.guard.ran(id)
                scope.launch {
                    note = if (plan is CopyPlan.Write) copyText(clipboard, plan.text) else CopyResult.TOO_LARGE
                    taps++
                }
            }
            ActionButton(R.drawable.lucide_text_select, R.string.chat_select, drawn) { item ->
                val id = "select:${item.key}"
                if (!actions.guard.allows(id)) return@ActionButton
                val shown = actions.current()
                if (actionable(shown.items, shown.runId, item.key) == null) return@ActionButton
                actions.guard.ran(id)
                actions.onSelect(item.key)
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

private fun textOf(item: ChatItem): String = when (item) {
    is UserMessage -> item.text
    is AssistantReply -> item.text
    else -> ""
}

/**
 * One action. The message is taken when the press begins (before the click handler sees the down event) and used by
 * the click that ends it; a click without a press (keyboard, accessibility) takes the message drawn now.
 */
@Composable
private fun ActionButton(@DrawableRes icon: Int, @StringRes description: Int, drawn: ChatItem, onAct: (ChatItem) -> Unit) {
    val drawnNow by rememberUpdatedState(drawn)
    val pressedOn = remember { arrayOfNulls<ChatItem>(1) }
    ApofisIconButton(
        onClick = {
            val item = pressedOn[0] ?: drawnNow
            pressedOn[0] = null
            onAct(item)
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
