package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ToolStep

/** The tool step with [callId] in [items], newest first, and whether it belongs to the live reply. */
internal fun findStep(items: List<ChatItem>, callId: String): Pair<ToolStep, Boolean>? {
    for (item in items.asReversed()) {
        if (item !is AssistantReply) continue
        val step = item.steps.firstOrNull { it is ToolStep && it.callId == callId } as ToolStep? ?: continue
        return step to item.live
    }
    return null
}

/** A tool step in full: name, arguments and the (display-truncated) result. [live]: from the reply still running. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailSheet(step: ToolStep, live: Boolean, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                step.name,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (step.args.isNotEmpty()) {
                Section(stringResource(R.string.tool_arguments), step.args)
                if (step.argsTruncated) Hint(stringResource(R.string.tool_args_truncated))
            } else {
                // Live steps carry only the server's short preview; the stored transcript has the full arguments.
                Section(stringResource(R.string.tool_arguments), step.argsPreview)
                if (live) Hint(stringResource(R.string.tool_args_later))
            }
            val result = step.result
            Section(
                stringResource(R.string.tool_result),
                result ?: stringResource(if (step.running) R.string.chat_tool_running else R.string.tool_no_result),
                code = result != null,
            )
            if (step.resultTruncated) Hint(stringResource(R.string.tool_truncated))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String, body: String, code: Boolean = true) {
    Text(
        title,
        Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!code || body.isEmpty()) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        return
    }
    CodeSurface {
        Text(
            body,
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            style = codeStyle,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}
