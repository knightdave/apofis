package xyz.rycerz.apofis.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.disabledFill

// Test tags (end-to-end tests find the buttons by these ids).
internal const val SEND_TAG = "composer.send"
internal const val STOP_TAG = "composer.stop"
internal const val FIELD_TAG = "composer.field"

/** Whether the main button works: a send in flight ([busy]) blocks another send or steer, never Stop. */
internal fun primaryEnabled(mode: ComposerMode, busy: Boolean) = when (mode) {
    ComposerMode.SEND_DISABLED -> false
    ComposerMode.SEND, ComposerMode.STEER -> !busy
    ComposerMode.STOP -> true
}

/** One row: the text (1 to 5 lines), then the button whose look and label follow [mode]; [busy]: see [primaryEnabled]. */
@Composable
internal fun Composer(
    draft: String,
    mode: ComposerMode,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(ApofisTheme.colors.cardFill, shape)
            .border(1.dp, ApofisTheme.colors.hairline, shape)
            .padding(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val clear = Color.Transparent
        TextField(
            draft,
            onDraftChange,
            Modifier.weight(1f).testTag(FIELD_TAG),
            readOnly = busy, // it shows the text being stored, which no keystroke may change
            placeholder = { Text(stringResource(R.string.chat_message_hint)) },
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = clear,
                unfocusedContainerColor = clear,
                disabledContainerColor = clear,
                focusedIndicatorColor = clear,
                unfocusedIndicatorColor = clear,
                disabledIndicatorColor = clear,
                unfocusedPlaceholderColor = scheme.onSurfaceVariant,
                focusedPlaceholderColor = scheme.onSurfaceVariant,
            ),
        )
        if (mode == ComposerMode.STEER) RoundButton(R.drawable.lucide_square, R.string.chat_stop, enabled = true, filled = false, tag = STOP_TAG, onClick = onStop)
        when (mode) {
            ComposerMode.SEND_DISABLED -> RoundButton(R.drawable.lucide_arrow_up, R.string.chat_send, enabled = false, tag = SEND_TAG, onClick = onSend)
            ComposerMode.SEND -> RoundButton(R.drawable.lucide_arrow_up, R.string.chat_send, enabled = primaryEnabled(mode, busy), tag = SEND_TAG, onClick = onSend)
            ComposerMode.STEER -> RoundButton(R.drawable.lucide_arrow_up, R.string.chat_steer, enabled = primaryEnabled(mode, busy), tag = SEND_TAG, onClick = onSend)
            ComposerMode.STOP -> RoundButton(R.drawable.lucide_square, R.string.chat_stop, enabled = primaryEnabled(mode, busy), tag = STOP_TAG, onClick = onStop)
        }
    }
}

/** A 32 dp circle with an 18 dp glyph inside a 48 dp touch target. */
@Composable
private fun RoundButton(@DrawableRes icon: Int, label: Int, enabled: Boolean, tag: String, filled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (enabled && filled) scheme.primary else Color.Transparent
    // Disabled: the button fades the opaque glyph to 0.38, drawn over the 0.12 fill behind it (outside that fade).
    val glyph = when {
        !enabled -> scheme.onSurface
        filled -> scheme.onPrimary
        else -> scheme.primary
    }
    Box(contentAlignment = Alignment.Center) {
        if (!enabled) Box(Modifier.size(32.dp).background(scheme.disabledFill, CircleShape))
        ApofisIconButton(onClick = onClick, contentDescription = stringResource(label), Modifier.testTag(tag), enabled = enabled, visualSize = 32.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(fill, CircleShape)
                    .then(if (filled) Modifier else Modifier.border(1.5.dp, scheme.primary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), contentDescription = null, Modifier.size(18.dp), tint = glyph)
            }
        }
    }
}
