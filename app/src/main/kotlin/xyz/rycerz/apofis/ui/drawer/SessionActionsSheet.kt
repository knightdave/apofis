package xyz.rycerz.apofis.ui.drawer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R

/** What the prompts can ask for; [DrawerViewModel] implements it. */
interface PromptActions {
    fun onRenameRequest(key: String) {}
    fun onDeleteRequest(key: String) {}
    fun onRename(key: String, title: String) {}
    fun onPin(key: String, pinned: Boolean) {}
    fun onArchive(key: String, archived: Boolean) {}
    fun onDelete(key: String) {}
    fun onDismiss() {}
}

/** Shows the open [prompt]: the long-press sheet, the rename dialog or the delete confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptHost(prompt: Prompt, actions: PromptActions) {
    when (prompt.kind) {
        PromptKind.ACTIONS -> SessionActionsSheet(prompt, actions)
        PromptKind.RENAME -> RenameDialog(prompt, actions)
        PromptKind.DELETE -> DeleteDialog(prompt, actions)
    }
}

/** Rename, Pin or Unpin, Archive, Delete; for an archived chat Unarchive and Delete. A failed action shows its error here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionActionsSheet(prompt: Prompt, actions: PromptActions) {
    val key = prompt.key
    ModalBottomSheet(onDismissRequest = actions::onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(
                prompt.title.ifEmpty { stringResource(R.string.chat_new_title) },
                Modifier.padding(start = 14.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val enabled = !prompt.busy
            if (prompt.archived) {
                SheetRow(R.drawable.lucide_archive_arrow_up, R.string.drawer_unarchive, enabled) { actions.onArchive(key, false) }
            } else {
                SheetRow(R.drawable.lucide_pencil, R.string.drawer_rename, enabled) { actions.onRenameRequest(key) }
                if (prompt.pinned) {
                    SheetRow(R.drawable.lucide_pin_off, R.string.drawer_unpin, enabled) { actions.onPin(key, false) }
                } else {
                    SheetRow(R.drawable.lucide_pin, R.string.drawer_pin, enabled) { actions.onPin(key, true) }
                }
                SheetRow(R.drawable.lucide_archive, R.string.drawer_archive, enabled) { actions.onArchive(key, true) }
            }
            SheetRow(R.drawable.lucide_trash, R.string.drawer_delete, enabled, MaterialTheme.colorScheme.error) { actions.onDeleteRequest(key) }
            ErrorLine(prompt.error, Modifier.padding(start = 14.dp, top = 8.dp))
        }
    }
}

@Composable
private fun SheetRow(@DrawableRes icon: Int, label: Int, enabled: Boolean, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
        Text(stringResource(label), Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

@Composable
private fun RenameDialog(prompt: Prompt, actions: PromptActions) {
    var title by rememberSaveable(prompt.key) { mutableStateOf(prompt.title) }
    AlertDialog(
        onDismissRequest = actions::onDismiss,
        title = { Text(stringResource(R.string.drawer_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    title,
                    { title = it.take(TITLE_MAX) },
                    Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.drawer_rename_hint)) },
                    singleLine = true,
                )
                ErrorLine(prompt.error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onRename(prompt.key, title) }, enabled = title.isNotBlank() && !prompt.busy) {
                Text(stringResource(R.string.drawer_save))
            }
        },
        dismissButton = { TextButton(onClick = actions::onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteDialog(prompt: Prompt, actions: PromptActions) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = actions::onDismiss,
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.drawer_delete_text))
                if (prompt.running) Text(stringResource(R.string.drawer_delete_running), Modifier.padding(top = 8.dp))
                ErrorLine(prompt.error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.onDelete(prompt.key) }, enabled = !prompt.busy) {
                Text(stringResource(R.string.drawer_delete), color = if (prompt.busy) Color.Unspecified else scheme.error)
            }
        },
        dismissButton = { TextButton(onClick = actions::onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ErrorLine(error: ActionError?, modifier: Modifier = Modifier) {
    error ?: return
    val text = when (error) {
        ActionError.NETWORK -> R.string.chat_status_unreachable
        ActionError.KEY_REJECTED -> R.string.chat_error_unauthorized
        ActionError.NOT_FOUND -> R.string.chat_error_not_found
        ActionError.CLOSED -> R.string.drawer_error_closed
        ActionError.FAILED -> R.string.chat_error_http_error
    }
    Text(stringResource(text), modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
