package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import xyz.rycerz.apofis.chat.Step
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.TOOL_RESULT_DISPLAY_LIMIT
import androidx.compose.ui.text.style.TextOverflow
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.ChatText

/**
 * The tool step a sheet shows, fixed when the sheet opens and never changed: its reply's key, its index there, its
 * call id (null without one) and its tool name.
 */
internal data class StepSubject(val replyKey: String, val index: Int, val callId: String?, val name: String)

/**
 * Resolves a [StepSubject] within its own reply only. With a call id: the one step of that reply with that id.
 * Without: the step at its index, if it also has no call id and the same tool name. Anything else is null, and the
 * sheet closes. The reply is resolved on every call (the live tail first, then the stored part, which is indexed by
 * reply key once per list instance). Only the step lookup is cached, by the identity of the reply's steps list and the
 * subject, so a streamed text token scans no step; [Found] is always built from the current reply. A live reply keeps
 * its key at run end when the stored reply was already known; a reply still keyed "live" has no stored key to map to,
 * so its sheet closes then.
 */
internal class StepIndex {
    data class Found(val step: ToolStep, val live: Boolean)

    /** Step lists scanned so far; for tests. */
    var scans = 0
        private set

    private var head: List<ChatItem>? = null
    private val byKey = HashMap<String, AssistantReply>()
    private var steps: List<Step>? = null
    private var subject: StepSubject? = null
    private var step: ToolStep? = null

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
        if (reply.steps !== steps || subject != this.subject) {
            scans++
            steps = reply.steps
            this.subject = subject
            step = if (subject.callId != null) {
                reply.steps.filter { it is ToolStep && it.callId == subject.callId }.singleOrNull() as ToolStep?
            } else {
                (reply.steps.getOrNull(subject.index) as? ToolStep)?.takeIf { it.callId.isEmpty() && it.name == subject.name }
            }
        }
        return step?.let { Found(it, reply.live) }
    }
}

/**
 * A tool step in full: name, arguments and the (display-truncated) result. [live]: from the reply still running. Live
 * previews have no limit of their own, so they are cut to the stored display limit here (with the shortened hint), and
 * every long text is laid out one page at a time.
 */
@Composable
internal fun ToolDetailSheet(step: ToolStep, live: Boolean, onDismiss: () -> Unit) {
    ApofisSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                oneLine(step.name),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.args.isNotEmpty()) {
                Section(stringResource(R.string.tool_arguments), step.args, "sheet.args")
                if (step.argsTruncated) Hint(stringResource(R.string.tool_args_truncated))
            } else {
                // Live steps carry only the server's short preview; the stored transcript has the full arguments.
                Section(stringResource(R.string.tool_arguments), step.argsPreview.take(TOOL_RESULT_DISPLAY_LIMIT), "sheet.args")
                if (step.argsPreview.length > TOOL_RESULT_DISPLAY_LIMIT) Hint(stringResource(R.string.tool_args_truncated))
                if (live) Hint(stringResource(R.string.tool_args_later))
            }
            val result = step.result
            Section(
                stringResource(R.string.tool_result),
                result?.take(TOOL_RESULT_DISPLAY_LIMIT) ?: stringResource(if (step.running) R.string.chat_tool_running else R.string.tool_no_result),
                "sheet.result",
                code = result != null,
            )
            if (step.resultTruncated || (result?.length ?: 0) > TOOL_RESULT_DISPLAY_LIMIT) Hint(stringResource(R.string.tool_truncated))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String, body: String, tag: String, code: Boolean = true) {
    Text(
        title,
        Modifier.padding(top = 16.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!code || body.isEmpty()) {
        PagedText(body, tag, muted) { part, m -> Text(part, m, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        return
    }
    PagedText(body, tag, muted) { part, m ->
        CodeSurface {
            Text(
                part,
                m.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                style = ChatText.code,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}
