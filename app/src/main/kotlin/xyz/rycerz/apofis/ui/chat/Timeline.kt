package xyz.rycerz.apofis.ui.chat

import xyz.rycerz.apofis.BuildConfig
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
import xyz.rycerz.apofis.ui.text.graphemeCut
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
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
import xyz.rycerz.apofis.ui.theme.dotWave
import xyz.rycerz.apofis.ui.theme.rememberTimeMs
import xyz.rycerz.apofis.ui.theme.waveDot

private const val MIN_SHOWN_DURATION_S = 0.1

// The one-line title and preview are cut before layout: a 1 MB name would otherwise be measured whole on every pass.
// The sheet shows the whole value; the end-to-end suite only reads short previews, which pass unchanged.
internal const val ONE_LINE_MAX = 1000

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

/** [visibleSteps]'s page while following the newest steps (until the reader moves to another page). */
internal const val NEWEST = -1

/**
 * More than two steps and not [expanded]: the last two, the rest hidden. Expanded: page [page] of fixed chunks of
 * [STEP_PAGE] counted from the oldest step (page k is steps k*40 until (k+1)*40); the last page is the short, growing
 * one, shown for [NEWEST]. The index is clamped into range on every read. Reads and builds only the shown entries.
 */
internal fun visibleSteps(steps: List<Step>, expanded: Boolean, page: Int = NEWEST): VisibleSteps {
    val size = steps.size
    if (!expanded) {
        val start = if (size <= 2) 0 else size - 2
        return VisibleSteps(start, List(size - start) { IndexedValue(start + it, steps[start + it]) })
    }
    val k = shownPage(size, page)
    val start = k * STEP_PAGE
    val end = minOf(size, start + STEP_PAGE)
    return VisibleSteps(start, List(end - start) { IndexedValue(start + it, steps[start + it]) }, size - end)
}

private fun shownPage(size: Int, page: Int): Int {
    val last = if (size == 0) 0 else (size - 1) / STEP_PAGE
    return if (page < 0 || page > last) last else page
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
    Steps(reply.key, ref, tag, open, initiallyExpanded, initiallyOpenReasoning, reply.live)
}

/** A steps list compared by identity: a new list is a new value, found without visiting its steps. */
private class StepsRef(val steps: List<Step>)

@Composable
private fun Steps(replyKey: String, ref: StepsRef, tag: String, onStep: (Int) -> Unit, initiallyExpanded: Boolean, initiallyOpenReasoning: Set<Int>, live: Boolean) {
    val steps = ref.steps
    var expanded by rememberSaveable(replyKey) { mutableStateOf(initiallyExpanded) }
    var page by rememberSaveable(replyKey) { mutableIntStateOf(NEWEST) } // the one saved paging state
    var opened by rememberSaveable(replyKey) { mutableStateOf(initiallyOpenReasoning.sorted()) }
    val visible = visibleSteps(steps, expanded, page)
    val shown = shownPage(steps.size, page)
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
                page = NEWEST
            }
        }
        // From the page shown (the index clamped on read): one tap always moves exactly one page.
        if (expanded && visible.hidden > 0) PageRow(stringResource(R.string.steps_earlier, visible.hidden)) { page = shown - 1 }
        val last = visible.shown.lastIndex
        visible.shown.forEachIndexed { position, (i, step) ->
            // Keyed by what the row shows: another call at the same index is a new row, so a press on the old one ends.
            key(i, step is ReasoningStep, (step as? ToolStep)?.callId, (step as? ToolStep)?.takeIf { it.callId.isEmpty() }?.name) {
                val rail = Rail(above = position > 0, below = position < last)
                when (step) {
                    is ToolStep -> ToolRow(step, rail, Modifier.testTag("$tag:$i:${step.name}"), live) { onStep(i) }
                    is ReasoningStep -> ReasoningRow(step, rail, i, i in opened, Modifier.testTag("$tag:$i")) {
                        opened = if (i in opened) opened - i else opened + i
                    }
                }
            }
        }
        if (expanded && visible.later > 0) PageRow(stringResource(R.string.steps_later, visible.later)) { page = shown + 1 }
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
private fun ToolRow(step: ToolStep, rail: Rail, modifier: Modifier, live: Boolean, onClick: () -> Unit) {
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
        modifier.clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { if (state != null) stateDescription = state },
        icon = {
            when {
                step.running -> MiniDots(animate = live) // only a step of the live run is the active one
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
    val turn = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -rotation else rotation // the mirrored chevron turns the other way to point down
    StepRow(
        rail,
        modifier.clickable(role = Role.Button, onClick = onToggle).semantics(mergeDescendants = true) { stateDescription = state },
        icon = { Icon(painterResource(R.drawable.lucide_lightbulb), null, Modifier.size(18.dp), tint = colors.timelineStrong) },
        trailing = {
            Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 4.dp).size(16.dp).rotate(turn), tint = colors.timelineMuted)
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
    if (BuildConfig.DEBUG) Work.timelineRows++
    val connector = ApofisTheme.colors.timelineConnector
    var header by remember { mutableIntStateOf(0) }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - 12.dp.toPx() else 12.dp.toPx()
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

/**
 * Three 3 dp dots in [MaterialTheme.colorScheme.primary]: a running tool step, in the [dotWave] of the reply's dots
 * while [animate] (the step belongs to the live run). Otherwise, and under reduced motion, all three are solid, as in
 * milestone 1 (so its goldens keep their pixels): a stored call without a result in an idle chat stays still.
 */
@Composable
internal fun MiniDots(modifier: Modifier = Modifier, animate: Boolean = true) {
    val primary = MaterialTheme.colorScheme.primary
    val still = ApofisTheme.reduceMotion || !animate
    val time = rememberTimeMs(Motion.DOTS_PERIOD, active = animate)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val dot = if (still) {
                Modifier.background(primary, CircleShape)
            } else {
                Modifier.drawBehind { waveDot(primary, dotWave(time.value, i)) } // read in the draw phase only
            }
            Box(Modifier.size(3.dp).then(dot))
        }
    }
}

/**
 * [text] one page at a time, with Previous and Next buttons and "Part k". The only saved state is the page index,
 * clamped on every read. Page boundaries are walked forward from offset 0 ([pageEnd], one page per step) and kept in
 * memory for this text; showing page k measures at most k+1 pages, once. [page] draws a page's text with the
 * modifier it is given (tagged "[tag].page"); the pager is tagged [tag].
 */
@Composable
internal fun PagedText(text: String, tag: String, labelColor: Color, page: @Composable (String, Modifier) -> Unit) {
    val pages = remember { TextPages() }
    pages.follow(text)
    var index by rememberSaveable(tag) { mutableIntStateOf(0) }
    val shown = pages.reach(index)
    val end = pages.endOf(shown)
    Column(Modifier.testTag(tag)) {
        page(text.substring(pages.startOf(shown), end), Modifier.testTag("$tag.page"))
        if (shown > 0 || end < text.length) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Pure functions of the page shown: activations queued before the next frame land on the same page.
                ApofisIconButton({ index = maxOf(0, shown - 1) }, stringResource(R.string.page_previous), enabled = shown > 0, visualSize = 24.dp) {
                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp).rotate(180f), tint = labelColor)
                }
                Text(stringResource(R.string.page_part, shown + 1), style = MaterialTheme.typography.labelSmall, color = labelColor)
                ApofisIconButton({ if (end < text.length) index = shown + 1 }, stringResource(R.string.page_next), enabled = end < text.length, visualSize = 24.dp) {
                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = labelColor)
                }
            }
        }
    }
}

/**
 * The page starts of one text, found walking forward from 0. The same text keeps them; an appended text keeps those
 * whose measurement saw enough of it to be final; any other change starts over.
 */
private class TextPages {
    private var text = ""
    private val starts = arrayListOf(0) // starts[k + 1] is where page k ends
    private var complete = false // the walk has reached the end of the text

    fun follow(next: String) {
        if (next === text) return
        if (next.length > text.length && next.startsWith(text)) {
            // A page end depends on the text up to one code point past its limit: earlier ends are final.
            var keep = 1
            while (keep < starts.size && starts[keep - 1] + TEXT_PAGE_CHARS + 2 <= text.length) keep++
            starts.subList(keep, starts.size).clear()
        } else {
            starts.subList(1, starts.size).clear()
        }
        complete = false
        text = next
    }

    /** Measures pages up to [k] (or the end of the text) and returns the page index to show, clamped. */
    fun reach(k: Int): Int {
        while (!complete && starts.size <= k + 1) {
            val end = pageEnd(text, starts.last())
            if (end >= text.length) complete = true else starts += end
        }
        return k.coerceIn(0, starts.size - 1)
    }

    fun startOf(k: Int) = starts[k]

    fun endOf(k: Int) = if (k + 1 < starts.size) starts[k + 1] else text.length
}

/**
 * The end of the page of [text] that starts at [start] (a grapheme boundary): at most [TEXT_PAGE_CHARS] characters
 * and [TEXT_PAGE_LINES] line breaks (CR, LF and CRLF each count once), after the last line break in reach, else at
 * the last grapheme boundary at or before the cap ([graphemeCut] down). Reads at most the page and one code point
 * past it.
 */
internal fun pageEnd(text: CharSequence, start: Int): Int {
    if (BuildConfig.DEBUG) Work.pageEnds++
    val cap = start + TEXT_PAGE_CHARS
    val limit = minOf(text.length, cap)
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
    if (i == text.length) return text.length
    return if (afterBreak > start) afterBreak else graphemeCut(text, start, cap, up = false)
}

/** [text] cut to at most [limit] characters at the last grapheme boundary (the live tool previews' display cap). */
internal fun displayPrefix(text: String, limit: Int): String = if (text.length <= limit) text else text.substring(0, graphemeCut(text, 0, limit, up = false))

/** At most [ONE_LINE_MAX] characters, never ending on half a surrogate pair. */
internal fun oneLine(text: String): String {
    if (text.length <= ONE_LINE_MAX) return text
    val end = if (Character.isHighSurrogate(text[ONE_LINE_MAX - 1])) ONE_LINE_MAX - 1 else ONE_LINE_MAX
    return text.substring(0, end)
}
