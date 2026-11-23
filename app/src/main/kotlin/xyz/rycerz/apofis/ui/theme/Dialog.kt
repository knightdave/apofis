package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The app's confirmation dialog (UI map section 7): the page fill without tint, a 17 sp title, [text] scrolling when it
 * does not fit (large text, short windows), text buttons with the theme wash and at least 48 dp tall; [destructive]
 * colors the confirm label in error while it is enabled. No feedback event: a dialog answer is not a gesture of its own.
 */
@Composable
fun ApofisDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    text: @Composable () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
        DialogButton(confirmLabel, onConfirm, confirmEnabled, if (destructive && confirmEnabled) MaterialTheme.colorScheme.error else Color.Unspecified)
    },
    dismissButton = { DialogButton(dismissLabel, onDismiss) },
    title = { Text(title, style = ChatText.dialogTitle, color = MaterialTheme.colorScheme.onSurface) },
    text = { Box(Modifier.verticalScroll(rememberScrollState())) { text() } },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 0.dp,
)

/** [color] Unspecified: the button's own content color, which also shows the disabled state. */
@Composable
private fun DialogButton(label: String, onClick: () -> Unit, enabled: Boolean = true, color: Color = Color.Unspecified) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick, Modifier.heightIn(min = 48.dp).wash(source, ButtonDefaults.textShape), enabled = enabled, interactionSource = source) {
        Text(label, color = color)
    }
}
