package xyz.rycerz.apofis.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.ui.markdown.BlockKind
import xyz.rycerz.apofis.ui.markdown.DisplayChunk
import xyz.rycerz.apofis.ui.markdown.CopyButton
import xyz.rycerz.apofis.ui.markdown.MarkdownChunk
import xyz.rycerz.apofis.ui.markdown.Tone
import xyz.rycerz.apofis.ui.markdown.blockFrame
import xyz.rycerz.apofis.ui.markdown.styled
import xyz.rycerz.apofis.ui.markdown.syntaxColors
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.scaled

/**
 * One display chunk of a logical block: markdown (flat, full width) or a fenced code block (monospace, scrolls
 * sideways). Chunks of one block sit flush against each other: only the first has the top spacing and the header, only
 * the last the bottom. [tableScroll] is shared by the slices of one table; [source] gives the raw source of the whole
 * block for Copy.
 */
@Composable
internal fun TextBlock(row: TextRow, onLink: (String) -> Unit = {}, tableScroll: ScrollState = rememberScrollState(), source: () -> String? = { null }) {
    if (row.kind != BlockKind.CODE) {
        // Half the 0.9 b block spacing above and below each block.
        val half = with(LocalDensity.current) { (scaled(ChatText.assistant).fontSize * 0.45f).toDp() }
        MarkdownChunk(
            row.chunk,
            onLink,
            Modifier.padding(start = 20.dp, end = 20.dp, top = if (row.first) half else 0.dp, bottom = if (row.joinBelow) 0.dp else half),
            tableScroll,
            source,
        )
        return
    }
    val code = row.chunk as? DisplayChunk.Code
    val colors = ApofisTheme.colors
    val onSurface = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val syntax = remember(dark, colors.codeFill, onSurface) { syntaxColors(dark, colors.codeFill, onSurface) }
    val tokens = code?.highlighted
    val text = if (tokens != null) remember(tokens, syntax) { styled(tokens, syntax) } else AnnotatedString(code?.text ?: "")
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = if (row.first) 6.dp else 0.dp, bottom = if (row.joinBelow) 0.dp else 6.dp)
            .fillMaxWidth()
            // The bottom edge and corners close the block only once its fence is closed (or the reply is not live).
            .blockFrame(row.first, !row.joinBelow && row.fenceClosed, colors.hairline, 16.dp)
            .background(colors.codeFill),
    ) {
        if (row.first) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).background(colors.codeHeaderFill).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // At most 32 characters of one word (see infoWord), on one line.
                Text(row.lang, Modifier.weight(1f), style = TextStyle(fontSize = 12.sp), color = colors.codeLabel, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                CopyButton(R.string.code_copy, source)
            }
        }
        Text(
            text,
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = if (row.first) 8.dp else 0.dp, bottom = if (row.joinBelow) 0.dp else 8.dp),
            style = scaled(ChatText.code),
            color = if (tokens != null) syntax[Tone.CODE.ordinal] else onSurface,
            softWrap = false,
        )
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

/**
 * Under a reply: the live indicators, the pending request, the outcome notice and, once its run is over (not live,
 * not [active], see [activeReplies]), Copy and Select ([MessageActionRow]). A CHANGED note shows
 * inside the block of the request that replaced the answered one, and only while that request is drawn (GONE notes:
 * [GoneRow]).
 */
@Composable
internal fun ReplyFooter(
    reply: AssistantReply,
    live: LiveReply?,
    notes: Map<RequestRef, AnswerNote>,
    answering: Boolean,
    actions: ChatActions,
    rowActions: RowActions? = null,
    active: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (live != null) {
            val polling = live.connection == Connection.POLLING
            val pending = live.pending
            if ((reply.text.isEmpty() && pending == null) || polling) Dots(if (polling) R.string.chat_catching_up else null)
            if (live.connection == Connection.RECONNECTING) Label(R.string.chat_reconnecting)
            if (pending != null) {
                val changed = showsChanged(notes, pending)
                // A request with another id or generation is a new block: an open menu or typed answer never carries over.
                key(pending.requestId, pending.executionGeneration) {
                    when (pending.kind) {
                        "approval" -> ApprovalBlock(pending, changed, answering, actions::onApproval)
                        "clarify" -> ClarifyBlock(pending, changed, answering, actions::onClarify)
                        else -> UnsupportedBlock()
                    }
                }
            }
            if (live.status == "unknown") Notice(R.string.chat_unknown_notice, R.string.chat_unknown_action, actions::onResolveUnknown, error = true)
        }
        when (reply.outcome) {
            ReplyOutcome.STOPPED -> Notice(R.string.chat_outcome_stopped)
            ReplyOutcome.FAILED -> Notice(R.string.chat_outcome_failed, error = true)
            ReplyOutcome.INTERRUPTED -> Notice(R.string.chat_outcome_interrupted, error = true)
            null -> Unit
        }
        // The first icon lines up with the 20 dp text inset (a 16 dp icon centred in its 48 dp target).
        if (rowActions != null && !reply.live && !active && reply.text.isNotBlank()) {
            MessageActionRow(reply, rowActions, Modifier.padding(start = 4.dp))
        }
    }
}

/** True when [pending] is the request that replaced an answered one (same id with a new generation included). */
internal fun showsChanged(notes: Map<RequestRef, AnswerNote>, pending: PendingControl): Boolean {
    val shown = RequestRef(pending.requestId, pending.executionGeneration)
    return notes.any { (answered, note) -> note.result == AnswerResult.CHANGED && note.successor == shown && answered != shown }
}

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
        for (alpha in listOf(0.45f, 0.68f, 0.9f)) Box(Modifier.size(9.dp).alpha(alpha).background(primary, CircleShape))
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

/** An info line under a reply, optionally with one action; [error]: the alert icon and the error color. */
@Composable
internal fun Notice(@StringRes text: Int, @StringRes action: Int? = null, onAction: () -> Unit = {}, error: Boolean = false) {
    val tint = if (error) MaterialTheme.colorScheme.error else ApofisTheme.colors.muted
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(if (error) R.drawable.lucide_triangle_exclamation_point else R.drawable.lucide_info), null, Modifier.size(15.dp), tint = tint)
        Box(Modifier.width(6.dp))
        Text(
            stringResource(text),
            Modifier.weight(1f).padding(vertical = 14.dp),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
        if (action != null) SmallTextButton(action, onAction)
    }
}
