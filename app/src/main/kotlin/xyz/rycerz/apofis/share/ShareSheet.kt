package xyz.rycerz.apofis.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.SheetRow
import xyz.rycerz.apofis.ui.text.graphemeCut

/** The longest preview the sheet lays out and reads out, in UTF-16 units: four lines need no more. */
internal const val PREVIEW_CHARS = 1_000

/**
 * The incoming [share], shown before anything changes: the first lines of its text, its image count and one note, with
 * Open as a new chat and Cancel. [temporary]: the open chat is temporary, and accepting leaves (deletes) it. Back, the
 * scrim and a swipe are Cancel. With no text and no image only Cancel is offered.
 */
@Composable
fun ShareSheet(share: Share, temporary: Boolean, onAccept: () -> Unit, onCancel: () -> Unit) {
    val inset = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    ApofisSheet(onCancel) {
        // Scrolls, so both actions stay reachable in a short window with a large font.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.share_title), Modifier.padding(start = 14.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (share.text.isNotBlank()) {
                val preview = remember(share.text) { share.text.substring(0, graphemeCut(share.text, 0, PREVIEW_CHARS, up = false)) }
                Text(preview, inset.testTag("share_preview"), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (share.images.isNotEmpty()) {
                Text(stringResource(R.string.share_images_count, share.images.size), inset, style = MaterialTheme.typography.bodyMedium, color = quiet)
            }
            SHARE_NOTE_ORDER.firstOrNull { it in share.notes }?.let { note ->
                val text = when (note) {
                    NoteKind.SHARE_TOO_MANY -> R.string.share_too_many
                    NoteKind.SHARE_IMAGES_FAILED -> R.string.share_images_failed
                    else -> R.string.share_text_cut
                }
                Text(stringResource(text), inset, style = MaterialTheme.typography.bodyMedium, color = quiet)
            }
            if (temporary) {
                Text(stringResource(R.string.share_deletes_temporary), inset, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (share.text.isNotBlank() || share.images.isNotEmpty()) {
                SheetRow(R.drawable.lucide_square_pen, stringResource(R.string.share_accept), onAccept, Modifier.testTag("share_accept"))
            }
            SheetRow(R.drawable.lucide_x, stringResource(R.string.share_cancel), onCancel, Modifier.testTag("share_cancel"))
        }
    }
}
