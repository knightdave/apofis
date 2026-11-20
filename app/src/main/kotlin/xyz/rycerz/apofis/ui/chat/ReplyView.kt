package xyz.rycerz.apofis.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.ui.theme.ApofisTheme

internal val codeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 1.5.em)

/** One paragraph of a reply (flat, full width) or one fenced code block (monospace, scrolls sideways). */
@Composable
internal fun TextBlock(row: TextRow) {
    if (!row.code) {
        Text(
            row.text,
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.7.sp, lineHeight = 1.5.em),
            color = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        CodeSurface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (row.lang.isNotEmpty()) {
                Text(
                    row.lang,
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                row.text,
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                style = codeStyle,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun CodeSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(ApofisTheme.colors.cardFill, shape)
            .border(1.dp, ApofisTheme.colors.hairline, shape),
    ) { content() }
}

/** Under a reply: the live indicators, the pending approval or question, and the outcome notice. */
@Composable
internal fun ReplyFooter(reply: AssistantReply, live: LiveReply?, note: AnswerResult?, answering: Boolean, actions: ChatActions) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (live != null) {
            val polling = live.connection == Connection.POLLING
            if ((reply.text.isEmpty() && live.pending == null) || polling) Dots(if (polling) R.string.chat_catching_up else null)
            if (live.connection == Connection.RECONNECTING) Label(R.string.chat_reconnecting)
            live.pending?.let { pending ->
                if (pending.isClarify()) {
                    ClarifyBlock(pending, note.takeIf { it == AnswerResult.CHANGED }, answering, actions::onClarify)
                } else {
                    ApprovalBlock(pending, note.takeIf { it == AnswerResult.CHANGED }, answering, actions::onApproval)
                }
            }
            if (note == AnswerResult.GONE) Notice(R.string.chat_answer_gone)
            if (live.status == "unknown") Notice(R.string.chat_unknown_notice, R.string.chat_unknown_action, actions::onResolveUnknown)
        }
        when (reply.outcome) {
            ReplyOutcome.STOPPED -> Notice(R.string.chat_outcome_stopped)
            ReplyOutcome.FAILED -> Notice(R.string.chat_outcome_failed)
            ReplyOutcome.INTERRUPTED -> Notice(R.string.chat_outcome_interrupted)
            null -> Unit
        }
    }
}

internal fun PendingControl.isClarify() = kind == "clarify"

/** The static three-dot indicator (motion comes later); [label] is shown beside it. */
@Composable
private fun Dots(@StringRes label: Int?) {
    val description = stringResource(label ?: R.string.chat_replying)
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val primary = MaterialTheme.colorScheme.primary
        for (alpha in listOf(0.45f, 0.68f, 0.9f)) Box(Modifier.size(9.dp).background(primary.copy(alpha = alpha), CircleShape))
        if (label != null) {
            Box(Modifier.width(2.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Label(@StringRes text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(horizontal = 20.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** An info line under a reply, optionally with one action. */
@Composable
internal fun Notice(@StringRes text: Int, @StringRes action: Int? = null, onAction: () -> Unit = {}) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.lucide_info), null, Modifier.size(15.dp), tint = tint)
        Box(Modifier.width(6.dp))
        Text(
            stringResource(text),
            Modifier.weight(1f).padding(vertical = 14.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        if (action != null) TextButton(onClick = onAction) { Text(stringResource(action)) }
    }
}
