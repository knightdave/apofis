package xyz.rycerz.apofis.ui.drawer

import androidx.compose.runtime.remember
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource
import xyz.rycerz.apofis.ui.theme.wash
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.SheetRow

/** What the prompts can ask for, each bound to the prompt's token; [DrawerViewModel] implements it. */
interface PromptActions {
    fun openRename(token: Long) {}
    fun openDelete(token: Long) {}
    fun confirmRename(token: Long, title: String) {}
    fun pin(token: Long, pinned: Boolean) {}
    fun archive(token: Long, archived: Boolean) {}
    fun confirmDelete(token: Long) {}
    fun dismiss(token: Long) {}
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
    val token = prompt.token
    // Busy: an action is running and its failure shows here, so the sheet cannot be dismissed until it ends.
    // The body scrolls, so the last action stays reachable in a short window.
    ApofisSheet(onDismiss = { actions.dismiss(token) }, dismissible = !prompt.busy) {
        Column(Modifier.verticalScroll(rememberScrollState())) { SessionActions(prompt, actions) }
    }
}

@Composable
private fun SessionActions(prompt: Prompt, actions: PromptActions) {
    val token = prompt.token
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
        SheetRow(R.drawable.lucide_archive_arrow_up, stringResource(R.string.drawer_unarchive), { actions.archive(token, false) }, enabled = enabled)
    } else {
        SheetRow(R.drawable.lucide_pencil, stringResource(R.string.drawer_rename), { actions.openRename(token) }, enabled = enabled)
        if (prompt.pinned) {
            SheetRow(R.drawable.lucide_pin_off, stringResource(R.string.drawer_unpin), { actions.pin(token, false) }, enabled = enabled)
        } else {
            SheetRow(R.drawable.lucide_pin, stringResource(R.string.drawer_pin), { actions.pin(token, true) }, enabled = enabled)
        }
        SheetRow(R.drawable.lucide_archive, stringResource(R.string.drawer_archive), { actions.archive(token, true) }, enabled = enabled)
    }
    SheetRow(R.drawable.lucide_trash, stringResource(R.string.drawer_delete), { actions.openDelete(token) }, enabled = enabled, color = MaterialTheme.colorScheme.error)
    ErrorLine(prompt.error, Modifier.padding(start = 14.dp, top = 8.dp))
}

@Composable
private fun RenameDialog(prompt: Prompt, actions: PromptActions) {
    var title by rememberSaveable(prompt.token) { mutableStateOf(prompt.title) }
    AlertDialog(
        onDismissRequest = { actions.dismiss(prompt.token) },
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
            DialogButton({ actions.confirmRename(prompt.token, title) }, enabled = title.isNotBlank() && !prompt.busy) {
                Text(stringResource(R.string.drawer_save))
            }
        },
        dismissButton = { DialogButton({ actions.dismiss(prompt.token) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteDialog(prompt: Prompt, actions: PromptActions) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { actions.dismiss(prompt.token) },
        title = { Text(stringResource(R.string.drawer_delete_title)) },
        text = {
            Column {
                Text(stringResource(R.string.drawer_delete_text))
                if (prompt.running) Text(stringResource(R.string.drawer_delete_running), Modifier.padding(top = 8.dp))
                ErrorLine(prompt.error, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            DialogButton({ actions.confirmDelete(prompt.token) }, enabled = !prompt.busy) {
                Text(stringResource(R.string.drawer_delete), color = if (prompt.busy) Color.Unspecified else scheme.error)
            }
        },
        dismissButton = { DialogButton({ actions.dismiss(prompt.token) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DialogButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, Modifier.wash(source, ButtonDefaults.textShape), enabled = enabled, interactionSource = source) { content() }
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
