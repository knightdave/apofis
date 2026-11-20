package xyz.rycerz.apofis.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.sync.SendError
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.scaled
import xyz.rycerz.apofis.ui.theme.wash

private val bubbleShape = RoundedCornerShape(16.dp)

/** Right aligned, at most 75 % wide, tinted with primary; unsent messages show their state and controls below. */
@Composable
internal fun UserBubble(message: UserMessage, onRetry: (String) -> Unit, onCancel: (UserMessage) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
        Box(Modifier.fillMaxWidth(0.75f), contentAlignment = Alignment.CenterEnd) {
            Text(
                message.text,
                Modifier
                    .alpha(if (message.state == SendState.QUEUED) 0.7f else 1f) // 0.7: the contrast floor
                    .background(ApofisTheme.colors.bubbleFill, bubbleShape)
                    .padding(12.dp),
                style = scaled(ChatText.user),
                color = scheme.onSurface,
            )
        }
        if (message.steered && message.state == SendState.SENT) Caption(R.string.chat_steered) // delivered
        val key = message.idempotencyKey ?: return@Column
        when (message.state) {
            SendState.QUEUED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.lucide_clock), null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.width(6.dp))
                Caption(R.string.chat_queued, padded = false)
                CancelButton(R.string.chat_cancel_send) { onCancel(message) }
            }
            SendState.SENDING -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.lucide_loader_circle), null, Modifier.size(14.dp), tint = scheme.primary) // static: motion comes later
                Box(Modifier.width(6.dp))
                Caption(if (message.steered) R.string.chat_steering else R.string.chat_sending, padded = false)
            }
            SendState.FAILED -> Row(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.lucide_triangle_exclamation_point), null, Modifier.size(15.dp), tint = scheme.error)
                Box(Modifier.width(6.dp))
                Text(
                    stringResource(failureText(message.error)),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.error,
                )
                val retry = remember { MutableInteractionSource() }
                TextButton(onClick = { onRetry(key) }, Modifier.wash(retry, ButtonDefaults.textShape), interactionSource = retry) {
                    Text(stringResource(R.string.chat_retry))
                }
                CancelButton(R.string.chat_remove_failed) { onCancel(message) }
            }
            SendState.SENT -> Unit
        }
    }
}

@Composable
private fun Caption(@StringRes text: Int, padded: Boolean = true) {
    Text(
        stringResource(text),
        if (padded) Modifier.padding(top = 6.dp) else Modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CancelButton(@StringRes label: Int, onClick: () -> Unit) {
    ApofisIconButton(onClick = onClick, contentDescription = stringResource(label)) {
        Icon(painterResource(R.drawable.lucide_x), contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The fixed failure codes the send queue stores, as short sentences; anything else is a generic failure. */
@StringRes
internal fun failureText(code: String?): Int = when (code) {
    SendError.EXPIRED -> R.string.chat_error_expired
    SendError.EXPIRED_UNCERTAIN -> R.string.chat_error_expired_uncertain
    SendError.STEER_UNCERTAIN -> R.string.chat_error_steer_uncertain
    SendError.UNCONFIRMED -> R.string.chat_error_unconfirmed
    SendError.PERMISSION_DENIED -> R.string.chat_error_permission_denied
    SendError.SESSION_DELETED -> R.string.chat_error_session_deleted
    SendError.SESSION_UNAVAILABLE -> R.string.chat_error_session_unavailable
    SendError.CONFLICT -> R.string.chat_error_conflict
    SendError.UNAUTHORIZED -> R.string.chat_error_unauthorized
    SendError.NOT_FOUND -> R.string.chat_error_not_found
    SendError.PROTOCOL -> R.string.chat_error_protocol
    SendError.HTTP_ERROR -> R.string.chat_error_http_error
    else -> R.string.chat_error_unknown
}
