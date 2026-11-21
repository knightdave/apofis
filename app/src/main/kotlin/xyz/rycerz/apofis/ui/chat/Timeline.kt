package xyz.rycerz.apofis.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import android.icu.text.BreakIterator
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ReasoningStep
import xyz.rycerz.apofis.chat.Step
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.Motion

private const val MIN_SHOWN_DURATION_S = 0.1

// The one-line title and preview are cut before layout: a 1 MB name would otherwise be measured whole on every pass.
// The sheet shows the whole value; the end-to-end suite only reads short previews, which pass unchanged.
private const val ONE_LINE_MAX = 1000

/** An expanded card shows at most this many steps at a time, with Earlier and Later rows to move through the rest. */
internal const val STEP_PAGE = 40

/** Long text (reasoning, a tool's arguments or result) is laid out one page at a time: at most this many characters... */
internal const val TEXT_PAGE_CHARS = 1500

/** ...and at most this many line breaks. */
internal const val TEXT_PAGE_LINES = 24

/**
 * The steps a card shows: [shown] with their indices in the reply, after [hidden] earlier ones and before [later] ones
 * (only on an expanded card paged back from the newest steps).
 */
internal data class VisibleSteps(val hidden: Int, val shown: List<IndexedValue<Step>>, val later: Int = 0)

/** [visibleSteps]'s page while on the newest steps: it follows the run as steps arrive. */
internal const val NEWEST = -1

/**
 * More than two steps and not [expanded]: the last two, the rest hidden. Expanded: the page of at most [STEP_PAGE]
 * steps that ends before [pageEnd] ([NEWEST], or an end at or past the last step: the newest page). Older pages keep
 * their end, so they stay put while steps arrive; stepping from a page to the next one ends where it started, so pages
 * never overlap and the oldest is the short one. Reads and builds only the shown entries.
 */
internal fun visibleSteps(steps: List<Step>, expanded: Boolean, pageEnd: Int = NEWEST): VisibleSteps {
    val size = steps.size
    val end = if (expanded && pageEnd in 0 until size) pageEnd else size
    val start = if (expanded) maxOf(0, end - STEP_PAGE) else if (size <= 2) 0 else size - 2
    return VisibleSteps(start, List(end - start) { IndexedValue(start + it, steps[start + it]) }, size - end)
}

/**
 * The reply's reasoning and tool steps on a tinted card with a rail. More than two steps fold to the last two below a
 * "Show N more steps" row; expanded, a long run is paged ([STEP_PAGE]). Tool step i is tagged "[tag]:i:<tool name>", a
 * reasoning step "[tag]:i" (indices in [AssistantReply.steps], also while folded). [onStep] gets a tapped tool step's
 * index; reasoning opens in place. Expanded, page and open states are saved by reply key, so the stored reply that
 * replaces a live one keeps them.
 */
@NonSkippableComposable // comparing two replies would compare their steps one by one; the body below is constant work
@Composable
internal fun TimelineCard(
    reply: AssistantReply,
    tag: String,
    onStep: (Int) -> Unit,
    initiallyExpanded: Boolean = false,
    initiallyOpenReasoning: Set<Int> = emptySet(),
) {
    val click by rememberUpdatedState(onStep)
    val open = remember { { i: Int -> click(i) } } // stable, so streamed text recomposes no step row
    val last = remember { arrayOfNulls<StepsRef>(1) }
    val ref = last[0]?.takeIf { it.steps === reply.steps } ?: StepsRef(reply.steps).also { last[0] = it }
    Steps(reply.key, ref, tag, open, initiallyExpanded, initiallyOpenReasoning)
}

/** A steps list compared by identity: a new list is a new value, found without visiting its steps. */
private class StepsRef(val steps: List<Step>)

@Composable
private fun Steps(replyKey: String, ref: StepsRef, tag: String, onStep: (Int) -> Unit, initiallyExpanded: Boolean, initiallyOpenReasoning: Set<Int>) {
    val steps = ref.steps
    var expanded by rememberSaveable(replyKey) { mutableStateOf(initiallyExpanded) }
    var pageEnd by rememberSaveable(replyKey) { mutableIntStateOf(NEWEST) } // where the shown page ends, or NEWEST
    var opened by rememberSaveable(replyKey) { mutableStateOf(initiallyOpenReasoning.sorted()) }
    val visible = visibleSteps(steps, expanded, pageEnd)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(ApofisTheme.colors.timelineFill, RoundedCornerShape(16.dp))
            .animateContentSize(tween(Motion.TIMELINE_SIZE, easing = EaseOutCubic))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (steps.size > 2) {
            ExpandRow(expanded, steps.size - 2) {
                expanded = !expanded
                pageEnd = NEWEST
            }
        }
        // Navigation starts from the page shown, never from a saved end the steps no longer reach (a stored reply can
        // have fewer steps than the live one), so one tap always moves.
        if (expanded && visible.hidden > 0) PageRow(stringResource(R.string.steps_earlier, visible.hidden)) { pageEnd = visible.hidden }
        val last = visible.shown.lastIndex
        visible.shown.forEachIndexed { position, (i, step) ->
            // Keyed by what the row shows: another call at the same index is a new row, so a press on the old one ends.
            key(i, step is ReasoningStep, (step as? ToolStep)?.callId, (step as? ToolStep)?.takeIf { it.callId.isEmpty() }?.name) {
                val rail = Rail(above = position > 0, below = position < last)
                when (step) {
                    is ToolStep -> ToolRow(step, rail, Modifier.testTag("$tag:$i:${step.name}")) { onStep(i) }
                    is ReasoningStep -> ReasoningRow(step, rail, i, i in opened, Modifier.testTag("$tag:$i")) {
                        opened = if (i in opened) opened - i else opened + i
                    }
                }
            }
        }
        if (expanded && visible.later > 0) {
            PageRow(stringResource(R.string.steps_later, visible.later)) {
                val next = steps.size - visible.later + STEP_PAGE
                pageEnd = if (next >= steps.size) NEWEST else next
            }
        }
    }
}

@Composable
private fun PageRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ApofisTheme.colors.timelineStrong)
    }
}

/** Whether the connector runs up to the step before and down to the step after. */
private data class Rail(val above: Boolean, val below: Boolean)

@Composable
private fun ExpandRow(expanded: Boolean, more: Int, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.CHEVRON, easing = EaseOutCubic), label = "expand")
    val strong = ApofisTheme.colors.timelineStrong
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.lucide_chevron_down), null, Modifier.size(16.dp).rotate(rotation), tint = strong)
        }
        Box(Modifier.width(8.dp))
        Text(
            if (expanded) stringResource(R.string.steps_fewer) else pluralStringResource(R.plurals.steps_more, more, more),
            style = MaterialTheme.typography.labelLarge,
            color = strong,
        )
    }
}

@Composable
private fun ToolRow(step: ToolStep, rail: Rail, modifier: Modifier, onClick: () -> Unit) {
    val colors = ApofisTheme.colors
    val scheme = MaterialTheme.colorScheme
    val state = when {
        step.running -> stringResource(R.string.chat_tool_running)
        step.error -> stringResource(R.string.chat_tool_failed)
        else -> null
    }
    val duration = step.durationSeconds?.takeIf { it >= MIN_SHOWN_DURATION_S && it.isFinite() }?.let { stringResource(R.string.chat_tool_duration, it) }
    StepRow(
        rail,
        modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) { if (state != null) stateDescription = state },
        icon = {
            when {
                step.running -> MiniDots()
                step.error -> Icon(painterResource(R.drawable.lucide_triangle_exclamation_point), null, Modifier.size(18.dp), tint = scheme.error)
                else -> Icon(painterResource(R.drawable.lucide_wrench), null, Modifier.size(18.dp), tint = colors.timelineStrong)
            }
        },
        trailing = {
            if (duration != null) Text(duration, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = colors.timelineMuted)
            Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 4.dp).size(16.dp), tint = colors.timelineMuted)
        },
    ) {
        Text(
            oneLine(step.name),
            style = MaterialTheme.typography.labelMedium,
            color = if (step.error) scheme.error else colors.timelineStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (step.argsPreview.isNotEmpty()) {
            Text(
                oneLine(step.argsPreview),
                style = ChatText.code.copy(fontSize = 12.5.sp),
                color = colors.timelineBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReasoningRow(step: ReasoningStep, rail: Rail, index: Int, open: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val colors = ApofisTheme.colors
    val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
    val rotation by animateFloatAsState(if (open) 90f else 0f, tween(Motion.CHEVRON, easing = EaseOutCubic), label = "reasoning")
    StepRow(
        rail,
        modifier.clickable(role = Role.Button, onClick = onToggle).semantics(mergeDescendants = true) { stateDescription = state },
        icon = { Icon(painterResource(R.drawable.lucide_lightbulb), null, Modifier.size(18.dp), tint = colors.timelineStrong) },
        trailing = {
            Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 4.dp).size(16.dp).rotate(rotation), tint = colors.timelineMuted)
        },
        body = if (open && step.text.isNotEmpty()) {
            {
                PagedText(step.text, "reasoning:$index", colors.timelineMuted) { part, m ->
                    Text(part, m, style = MaterialTheme.typography.bodySmall, color = colors.timelineBody)
                }
            }
        } else {
            null
        },
    ) {
        Text(stringResource(R.string.chat_reasoning), style = MaterialTheme.typography.labelLarge, color = colors.timelineStrong)
    }
}

/**
 * One step: a 24 dp rail cell with its icon, 8 dp, the texts, the trailing items; [body] below the header, beside the
 * rail. The 1 dp connector is drawn behind the rail from the icon to the neighbouring steps.
 */
@Composable
private fun StepRow(
    rail: Rail,
    modifier: Modifier,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    body: (@Composable () -> Unit)? = null,
    texts: @Composable () -> Unit,
) {
    Work.timelineRows++
    val connector = ApofisTheme.colors.timelineConnector
    var header by remember { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 12.dp.toPx()
                val y = header / 2f
                val gap = 12.dp.toPx() // icon half height plus air
                val width = 1.dp.toPx()
                if (rail.above && y - gap > 0f) drawLine(connector, Offset(x, 0f), Offset(x, y - gap), width)
                if (rail.below && y + gap < size.height) drawLine(connector, Offset(x, y + gap), Offset(x, size.height), width)
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).onSizeChanged { header = it.height }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { icon() }
            Box(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) { texts() }
            trailing()
        }
        if (body != null) Box(Modifier.padding(start = 32.dp, bottom = 8.dp)) { body() }
    }
}

/** Three 3 dp dots in [MaterialTheme.colorScheme.primary]: a running tool step. Static here. */
@Composable
internal fun MiniDots(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { Box(Modifier.size(3.dp).background(primary, CircleShape)) }
    }
}

/**
 * [text] one page at a time, with Previous and Next buttons and "Part k". Only the page on screen is ever measured:
 * its end is found from its start ([pageEnd]); the starts of the pages already visited are kept for Previous.
 * [page] draws a page's text with the modifier it is given (tagged "[tag].page"); the pager is tagged [tag].
 */
@Composable
internal fun PagedText(text: String, tag: String, labelColor: Color, page: @Composable (String, Modifier) -> Unit) {
    // The starts of pages 1..k, saved; a different text starts over.
    var starts by rememberSaveable(tag, text.length) { mutableStateOf(listOf(0)) }
    if (starts.last() > text.length) starts = listOf(0)
    val start = starts.last()
    val end = remember(text, start) { pageEnd(text, start) }
    Column(Modifier.testTag(tag)) {
        page(text.substring(start, end), Modifier.testTag("$tag.page"))
        if (starts.size > 1 || end < text.length) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ApofisIconButton({ starts = starts.dropLast(1) }, stringResource(R.string.page_previous), enabled = starts.size > 1, visualSize = 24.dp) {
                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp).rotate(180f), tint = labelColor)
                }
                Text(stringResource(R.string.page_part, starts.size), style = MaterialTheme.typography.labelSmall, color = labelColor)
                ApofisIconButton({ starts = starts + end }, stringResource(R.string.page_next), enabled = end < text.length, visualSize = 24.dp) {
                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = labelColor)
                }
            }
        }
    }
}

/** How far around a cut the grapheme boundary is looked for, in UTF-16 units. */
internal const val GRAPHEME_WINDOW = 64

/**
 * The end of the page of [text] that starts at [start] (a grapheme boundary): at most [TEXT_PAGE_CHARS] characters
 * and [TEXT_PAGE_LINES] line breaks (CR, LF and CRLF each count once), after the last line break in reach, else at
 * the last grapheme boundary in reach ([cutBefore]). Reads at most the page and a small window around its end.
 */
internal fun pageEnd(text: CharSequence, start: Int): Int {
    val limit = minOf(text.length, start + TEXT_PAGE_CHARS)
    var i = start
    var lines = 0
    var afterBreak = -1
    while (i < limit) {
        val c = text[i]
        if (c == '\r' || c == '\n') {
            if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                if (i + 1 >= limit) break // the pair does not fit: never split CRLF
                i++
            }
            afterBreak = i + 1
            if (++lines == TEXT_PAGE_LINES) return afterBreak
        }
        i++
    }
    if (limit == text.length && i == limit) return text.length
    return if (afterBreak > start) afterBreak else cutBefore(text, start, start + TEXT_PAGE_CHARS)
}

/**
 * A cut of [text] at or before [at] (and after [start]) that falls between two graphemes, found with the platform's
 * BreakIterator on a window of [GRAPHEME_WINDOW] units around [at] (a run of regional indicators is widened back to
 * its start, up to 1,024 units, so flags pair correctly). Oversized clusters: when no boundary lies between [start]
 * and [at] in the window, the first boundary after [at] within the window is used (a page may run a little long);
 * a cluster longer than the whole window is cut at [at] or one unit earlier, never inside a surrogate pair.
 */
internal fun cutBefore(text: CharSequence, start: Int, at: Int): Int {
    if (at >= text.length) return text.length
    var lo = maxOf(start, at - GRAPHEME_WINDOW)
    while (lo > start && lo > at - 1024 && lo >= 2 && isRegionalIndicator(text, lo - 2)) lo -= 2
    val hi = minOf(text.length, at + GRAPHEME_WINDOW)
    val graphemes = BreakIterator.getCharacterInstance()
    graphemes.setText(text.subSequence(lo, hi).toString())
    val before = graphemes.preceding(at - lo + 1).let { if (it == BreakIterator.DONE) -1 else it + lo }
    if (before > start && (before > lo || lo == start)) return before // the window's own start is no real boundary
    val after = graphemes.following(at - lo).let { if (it == BreakIterator.DONE) -1 else it + lo }
    if (after in (at + 1)..hi && (after < hi || hi == text.length)) return after
    return if (Character.isLowSurrogate(text[at]) && at - 1 > start) at - 1 else at
}

private fun isRegionalIndicator(text: CharSequence, i: Int): Boolean =
    i + 1 < text.length && Character.isHighSurrogate(text[i]) && Character.toCodePoint(text[i], text[i + 1]) in 0x1F1E6..0x1F1FF

/** [text] cut to at most [limit] characters between two graphemes (the live tool previews' display cap). */
internal fun displayPrefix(text: String, limit: Int): String = if (text.length <= limit) text else text.substring(0, cutBefore(text, 0, limit))

/** At most [ONE_LINE_MAX] characters, never ending on half a surrogate pair. */
internal fun oneLine(text: String): String {
    if (text.length <= ONE_LINE_MAX) return text
    val end = if (Character.isHighSurrogate(text[ONE_LINE_MAX - 1])) ONE_LINE_MAX - 1 else ONE_LINE_MAX
    return text.substring(0, end)
}
