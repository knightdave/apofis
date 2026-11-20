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

/**
 * The tool step a sheet shows, fixed when the sheet opens and never changed: its reply's key, its index there, its
 * call id (null without one) and its tool name.
 */
internal data class StepSubject(val replyKey: String, val index: Int, val callId: String?, val name: String)

/**
 * Resolves a [StepSubject] within its own reply only. With a call id: the one step of that reply with that id.
 * Without: the step at its index, if it also has no call id and the same tool name. Anything else is null, and the
 * sheet closes. The stored part (a [Joined]'s head, else the whole list) is indexed by reply key once per list
 * instance; the live tail is a few items. A live reply keeps its key at run end when the stored reply was already
 * known; a reply still keyed "live" has no stored key to map to, so its sheet closes then.
 */
internal class StepIndex {
    data class Found(val step: ToolStep, val live: Boolean)

    private var head: List<ChatItem>? = null
    private val byKey = HashMap<String, AssistantReply>()

    fun find(items: List<ChatItem>, subject: StepSubject): Found? {
        val joined = items as? Joined<*>
        @Suppress("UNCHECKED_CAST")
        val stored = joined?.head as List<ChatItem>? ?: items
        @Suppress("UNCHECKED_CAST")
        val tail = joined?.tail as List<ChatItem>? ?: emptyList()
        if (stored !== head) {
            head = stored
            byKey.clear()
            for (item in stored) if (item is AssistantReply) byKey[item.key] = item
        }
        val reply = tail.lastOrNull { it is AssistantReply && it.key == subject.replyKey } as AssistantReply?
            ?: byKey[subject.replyKey] ?: return null
        val step = if (subject.callId != null) {
            reply.steps.filter { it is ToolStep && it.callId == subject.callId }.singleOrNull() as ToolStep?
        } else {
            (reply.steps.getOrNull(subject.index) as? ToolStep)?.takeIf { it.callId.isEmpty() && it.name == subject.name }
        }
        return step?.let { Found(it, reply.live) }
    }
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
