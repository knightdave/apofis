package xyz.rycerz.apofis.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.media.Attachment
import xyz.rycerz.apofis.media.AttachmentKind
import xyz.rycerz.apofis.media.AttachmentState
import xyz.rycerz.apofis.media.Attachments
import xyz.rycerz.apofis.ui.markdown.COPY_NOTE_MS
import xyz.rycerz.apofis.ui.markdown.CopyResult
import xyz.rycerz.apofis.ui.markdown.copyText
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** The chat's picture coordinator; null (previews, goldens, no image store) shows every picture row as Off. */
val LocalAttachments = staticCompositionLocalOf<Attachments?> { null }

/**
 * The row of [attachment], named by stored [reply] (spec 6.2). A FILE never asks for a state. A PICTURE collects
 * `state()` while the row is composed (collecting is the demand) and shows the file tile of that state.
 */
@Composable
internal fun AttachmentView(reply: AssistantReply, attachment: Attachment) {
    if (attachment.kind == AttachmentKind.FILE) return FileTile(attachment.path, status = null)
    val attachments = LocalAttachments.current
    val state = if (attachments == null) {
        AttachmentState.Off
    } else {
        val flow = remember(attachments, reply.key, attachment.path) { attachments.state(reply.key, attachment.path) }
        flow.collectAsState(AttachmentState.Loading).value
    }
    when (state) {
        // ---- Bound: drawn as Loading until the decode (T8 replaces this branch) ----
        is AttachmentState.Bound -> FileTile(attachment.path, R.string.attachment_loading)
        // ---- every other state ----
        AttachmentState.Off -> FileTile(attachment.path, R.string.attachment_off)
        AttachmentState.Loading -> FileTile(attachment.path, R.string.attachment_loading)
        AttachmentState.Unavailable -> FileTile(attachment.path, R.string.attachment_unavailable) { attachments?.retry(reply.key, attachment.path) }
        AttachmentState.Failed -> FileTile(attachment.path, R.string.attachment_failed) { attachments?.retry(reply.key, attachment.path) }
        AttachmentState.TooLarge -> FileTile(attachment.path, R.string.attachment_too_large)
        AttachmentState.NotPicture -> FileTile(attachment.path, R.string.attachment_not_picture)
    }
}

/**
 * A file tile at the text width: kind icon, the name (labelled `File: <name>`), a [status] line when given, Copy file
 * path, and Retry when [onRetry] is given. Both buttons pass the chat's gate at tap time, so a covered chat drops them.
 */
@Composable
private fun FileTile(path: String, @StringRes status: Int?, onRetry: (() -> Unit)? = null) {
    val colors = ApofisTheme.colors
    val gate = LocalChatGate.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<CopyResult?>(null) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        delay(COPY_NOTE_MS)
        note = null
    }
    val name = path.substringAfterLast('/')
    val label = stringResource(R.string.attachment_file, name)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.codeFill)
            .padding(start = 12.dp, top = 8.dp, end = 4.dp),
    ) {
        val icon = if (status == null) R.drawable.lucide_file_text else R.drawable.lucide_image // only a FILE has no status
        Icon(painterResource(icon), null, Modifier.padding(top = 2.dp).size(20.dp), tint = colors.muted)
        Box(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                Modifier.semantics { contentDescription = label },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) Text(stringResource(status), style = MaterialTheme.typography.bodySmall, color = colors.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallTextButton(R.string.attachment_copy_path, { gate { scope.launch { note = copyText(clipboard, path); taps++ } } })
                if (onRetry != null) SmallTextButton(R.string.chat_retry, { gate(onRetry) })
                note?.let {
                    Text(
                        stringResource(it.note),
                        Modifier.padding(horizontal = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
