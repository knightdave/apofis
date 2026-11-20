package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.ReasoningStep
import xyz.rycerz.apofis.chat.Step
import xyz.rycerz.apofis.chat.ToolStep

private const val MIN_SHOWN_DURATION_S = 0.1

/** The reply's steps as a simple list in a pale card: tool name, one-line preview, running or error state. */
/** [onStep] gets the tapped step's index in [steps]; tool step i is tagged "[tag]:i:<tool name>", a reasoning step "[tag]:i", for the end-to-end tests. */
@Composable
internal fun StepList(steps: List<Step>, tag: String = "step", onStep: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.primaryContainer.copy(alpha = if (isDarkSurface()) 0.25f else 0.30f).compositeOver(scheme.surface)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(fill, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        steps.forEachIndexed { i, step ->
            when (step) {
                is ToolStep -> ToolRow(step, Modifier.testTag("$tag:$i:${step.name}")) { onStep(i) }
                is ReasoningStep -> StepRow(null, stringResource(R.string.chat_reasoning), step.text.trim().lineSequence().firstOrNull().orEmpty(), null, null, Modifier.testTag("$tag:$i"))
            }
        }
    }
}

@Composable
private fun ToolRow(step: ToolStep, modifier: Modifier, onClick: () -> Unit) {
    val state = when {
        step.running -> stringResource(R.string.chat_tool_running)
        step.error -> stringResource(R.string.chat_tool_failed)
        else -> null
    }
    val duration = step.durationSeconds?.takeIf { it >= MIN_SHOWN_DURATION_S }?.let { stringResource(R.string.chat_tool_duration, it) }
    StepRow(step, step.name, step.argsPreview, duration, state, modifier.clickable(onClick = onClick))
}

@Composable
private fun StepRow(step: ToolStep?, title: String, preview: String, trailing: String?, state: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val strong = scheme.secondary.copy(alpha = 0.9f).compositeOver(scheme.surface) // 0.9: the card's contrast floor
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { if (state != null) stateDescription = state }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            when {
                step?.running == true -> Icon(painterResource(R.drawable.lucide_loader_circle), null, Modifier.size(18.dp), tint = scheme.primary)
                step?.error == true -> Icon(painterResource(R.drawable.lucide_triangle_exclamation_point), null, Modifier.size(18.dp), tint = scheme.error)
                step != null -> Icon(painterResource(R.drawable.lucide_wrench), null, Modifier.size(18.dp), tint = strong)
                else -> Icon(painterResource(R.drawable.lucide_info), null, Modifier.size(18.dp), tint = strong)
            }
        }
        Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (step?.error == true) scheme.error else strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview.isNotEmpty()) {
                Text(preview, style = codeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = strong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) Text(trailing, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = strong)
        if (step != null) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 4.dp).size(16.dp), tint = strong)
    }
}
