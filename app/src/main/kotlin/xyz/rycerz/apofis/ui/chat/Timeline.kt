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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/** The steps a card shows: [shown] with their indices in the reply, after [hidden] earlier ones. */
internal data class VisibleSteps(val hidden: Int, val shown: List<IndexedValue<Step>>)

/** More than two steps and not [expanded]: the last two, the rest hidden. Builds only the shown entries. */
internal fun visibleSteps(steps: List<Step>, expanded: Boolean): VisibleSteps {
    val hidden = if (expanded || steps.size <= 2) 0 else steps.size - 2
    return VisibleSteps(hidden, List(steps.size - hidden) { IndexedValue(hidden + it, steps[hidden + it]) })
}

/**
 * The reply's reasoning and tool steps on a tinted card with a rail. More than two steps fold to the last two below a
 * "Show N more steps" row. Tool step i is tagged "[tag]:i:<tool name>", a reasoning step "[tag]:i" (indices in
 * [AssistantReply.steps], also while folded). [onStep] gets a tapped tool step's index; reasoning opens in place.
 * Expanded and open states are saved by reply key, so the stored reply that replaces a live one keeps them.
 */
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
    Steps(reply.key, reply.steps, tag, open, initiallyExpanded, initiallyOpenReasoning)
}

@Composable
private fun Steps(replyKey: String, steps: List<Step>, tag: String, onStep: (Int) -> Unit, initiallyExpanded: Boolean, initiallyOpenReasoning: Set<Int>) {
    var expanded by rememberSaveable(replyKey) { mutableStateOf(initiallyExpanded) }
    val visible = remember(steps, expanded) { visibleSteps(steps, expanded) }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(ApofisTheme.colors.timelineFill, RoundedCornerShape(16.dp))
            .animateContentSize(tween(Motion.TIMELINE_SIZE, easing = EaseOutCubic))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (steps.size > 2) ExpandRow(expanded, steps.size - 2) { expanded = !expanded }
        val last = visible.shown.lastIndex
        visible.shown.forEachIndexed { position, (i, step) ->
            key(i) {
                val rail = Rail(above = position > 0, below = position < last)
                when (step) {
                    is ToolStep -> ToolRow(step, rail, Modifier.testTag("$tag:$i:${step.name}")) { onStep(i) }
                    is ReasoningStep -> ReasoningRow(step, rail, replyKey, i, i in initiallyOpenReasoning, Modifier.testTag("$tag:$i"))
                }
            }
        }
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
private fun ReasoningRow(step: ReasoningStep, rail: Rail, replyKey: String, index: Int, initiallyOpen: Boolean, modifier: Modifier) {
    val colors = ApofisTheme.colors
    var open by rememberSaveable(replyKey, index) { mutableStateOf(initiallyOpen) }
    val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
    val rotation by animateFloatAsState(if (open) 90f else 0f, tween(Motion.CHEVRON, easing = EaseOutCubic), label = "reasoning")
    StepRow(
        rail,
        modifier.clickable(role = Role.Button) { open = !open }.semantics(mergeDescendants = true) { stateDescription = state },
        icon = { Icon(painterResource(R.drawable.lucide_lightbulb), null, Modifier.size(18.dp), tint = colors.timelineStrong) },
        trailing = {
            Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 4.dp).size(16.dp).rotate(rotation), tint = colors.timelineMuted)
        },
        body = if (open && step.text.isNotEmpty()) {
            { Text(step.text, style = MaterialTheme.typography.bodySmall, color = colors.timelineBody) }
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

/** At most [ONE_LINE_MAX] characters, never ending on half a surrogate pair. */
private fun oneLine(text: String): String {
    if (text.length <= ONE_LINE_MAX) return text
    val end = if (Character.isHighSurrogate(text[ONE_LINE_MAX - 1])) ONE_LINE_MAX - 1 else ONE_LINE_MAX
    return text.substring(0, end)
}
