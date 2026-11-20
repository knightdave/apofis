package xyz.rycerz.apofis.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The chat: app bar with the title and the status line, the message list and the composer. Stateless: [state] comes
 * from [ChatViewModel], which also implements [actions]. [onOpenConnection] is called when the user taps a rejected key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    onOpenConnection: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    appBarActions: @Composable RowScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var cancelling by remember { mutableStateOf<UserMessage?>(null) }
    var detail by remember { mutableStateOf<ToolStep?>(null) }
    val pendingId = state.live?.pending?.requestId
    LaunchedEffect(pendingId) { if (pendingId != null) listState.animateScrollToItem(0) } // the request sits at the bottom

    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Title(state, onOpenConnection) },
                navigationIcon = navigationIcon,
                actions = appBarActions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Composer(
                state.draft,
                state.composer,
                actions::onDraftChange,
                onSend = {
                    actions.onSendClick()
                    scope.launch { listState.scrollToItem(0) }
                },
                onStop = actions::onStopClick,
                modifier = Modifier.navigationBarsPadding().imePadding(),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            MessageList(
                state.items,
                state.live,
                state.answerNote,
                state.answering,
                actions,
                onCancel = { cancelling = it },
                onStep = { detail = it },
                listState = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
            )
            val away by remember { derivedStateOf { listState.canScrollBackward } }
            if (away) {
                IconButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp),
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(ApofisTheme.colors.cardFill, CircleShape)
                            .border(1.dp, ApofisTheme.colors.hairlineStrong, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.lucide_arrow_down),
                            stringResource(R.string.chat_scroll_to_bottom),
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    cancelling?.let { message ->
        val key = message.idempotencyKey
        ConfirmDialog(
            title = R.string.chat_cancel_title,
            text = if (message.attempted) R.string.chat_cancel_text_attempted else R.string.chat_cancel_text,
            confirm = R.string.chat_cancel_confirm,
            dismiss = R.string.chat_keep,
            onConfirm = {
                cancelling = null
                if (key != null) actions.onCancel(key)
            },
            onDismiss = { cancelling = null },
        )
    }
    if (state.confirmRetry != null) {
        ConfirmDialog(
            title = R.string.chat_retry_title,
            text = R.string.chat_retry_text,
            confirm = R.string.chat_retry_confirm,
            dismiss = R.string.cancel,
            onConfirm = actions::onConfirmRetry,
            onDismiss = actions::onDismissRetry,
        )
    }
    detail?.let { ToolDetailSheet(it, onDismiss = { detail = null }) }
}

@Composable
private fun Title(state: ChatUiState, onOpenConnection: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val rejected = state.status == StatusLine.KEY_REJECTED
    val openLabel = stringResource(R.string.chat_status_open_connection)
    // The whole title area is the touch target for a rejected key: the status line alone is too small.
    Column(if (rejected) Modifier.clickable(onClickLabel = openLabel, onClick = onOpenConnection) else Modifier) {
        Text(
            state.title.ifEmpty { stringResource(R.string.chat_new_title) },
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val (icon, text, color) = when (state.status) {
            StatusLine.IDLE -> Triple(null, state.host, scheme.onSurfaceVariant)
            StatusLine.WORKING -> Triple(null, stringResource(R.string.chat_status_working), scheme.primary)
            StatusLine.QUEUED -> Triple(null, stringResource(R.string.chat_status_queued), scheme.primary)
            StatusLine.OFFLINE -> Triple(R.drawable.lucide_wifi_off, stringResource(R.string.chat_status_offline), scheme.error)
            StatusLine.UNREACHABLE -> Triple(R.drawable.lucide_cloud_off, stringResource(R.string.chat_status_unreachable), scheme.error)
            StatusLine.KEY_REJECTED -> Triple(R.drawable.lucide_key_round, stringResource(R.string.chat_status_key_rejected), scheme.error)
        }
        StatusText(icon, text, color)
    }
}

@Composable
private fun StatusText(@DrawableRes icon: Int?, text: String, color: Color) {
    Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(12.dp), tint = color)
            Box(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConfirmDialog(title: Int, text: Int, confirm: Int, dismiss: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) } },
    )
}
