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

/** A tool step by position: its reply's key and its index there. [callId] and [live] only help a live step follow its reply. */
internal data class StepRef(val replyKey: String, val index: Int, val callId: String, val live: Boolean)

/**
 * Resolves [StepRef]s in the chat items. The stored part (a [Joined]'s head, else the whole list) is indexed once per
 * list instance; the live tail is a few items. A live step whose reply was replaced (by the stored rows at run end,
 * or a key change) is found again by its call id when exactly one step has it, and [Found.ref] then names its new place.
 */
internal class StepIndex {
    data class Found(val step: ToolStep, val live: Boolean, val ref: StepRef)

    private var head: List<ChatItem>? = null
    private val byKey = HashMap<String, AssistantReply>()
    private val byCall = HashMap<String, StepRef?>() // null: more than one step has that call id

    fun find(items: List<ChatItem>, ref: StepRef): Found? {
        val joined = items as? Joined<*>
        @Suppress("UNCHECKED_CAST")
        val stored = joined?.head as List<ChatItem>? ?: items
        @Suppress("UNCHECKED_CAST")
        val tail = joined?.tail as List<ChatItem>? ?: emptyList()
        if (stored !== head) index(stored)
        at(tail, ref)?.let { return it }
        if (!ref.live || ref.callId.isEmpty()) return null
        for (item in tail) {
            if (item !is AssistantReply) continue
            val i = item.steps.indexOfFirst { it is ToolStep && it.callId == ref.callId }
            if (i >= 0) return Found(item.steps[i] as ToolStep, item.live, StepRef(item.key, i, ref.callId, item.live))
        }
        return byCall[ref.callId]?.let { at(tail, it) }
    }

    private fun at(tail: List<ChatItem>, ref: StepRef): Found? {
        val reply = tail.lastOrNull { it is AssistantReply && it.key == ref.replyKey } as AssistantReply? ?: byKey[ref.replyKey] ?: return null
        val step = reply.steps.getOrNull(ref.index) as? ToolStep ?: return null
        return Found(step, reply.live, ref.copy(live = reply.live))
    }

    private fun index(stored: List<ChatItem>) {
        head = stored
        byKey.clear()
        byCall.clear()
        for (item in stored) {
            if (item !is AssistantReply) continue
            byKey[item.key] = item
            item.steps.forEachIndexed { i, step ->
                if (step is ToolStep && step.callId.isNotEmpty()) {
                    byCall[step.callId] = if (step.callId in byCall) null else StepRef(item.key, i, step.callId, item.live)
                }
            }
        }
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
