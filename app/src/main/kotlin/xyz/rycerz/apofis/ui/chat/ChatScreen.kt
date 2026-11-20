package xyz.rycerz.apofis.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.key
import xyz.rycerz.apofis.chat.SendState
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.ComposeError
import xyz.rycerz.apofis.chat.UnsentText
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The chat: app bar with the title and the status line, the message list and the composer. Stateless: [state] comes
 * from [ChatViewModel], which also implements [actions]. [onOpenConnection] is called when the user taps a rejected
 * key; [onRetryLoad] when the chat could not be opened ([StatusLine.LOAD_FAILED]) and the user asks to try again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    onOpenConnection: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLoad: () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    appBarActions: @Composable RowScope.() -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<StepSubject?>(null) } // the tool step shown in the sheet, fixed when opened
    val steps = remember { StepIndex() }
    val pendingId = state.live?.pending?.requestId
    LaunchedEffect(pendingId) { if (pendingId != null) listState.animateScrollToItem(0) } // the request sits at the bottom

    Scaffold(
        modifier.testTag("chat.key:${state.sessionKey}"), // the end-to-end tests read the open chat's key here
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Title(state, onOpenConnection, onRetryLoad) },
                navigationIcon = navigationIcon,
                actions = appBarActions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                state.composerNote?.let { ComposerNoteLine(it) }
                state.unsent?.let { UnsentNotice(it, actions::onPutBack, actions::onDiscardUnsent) }
                Composer(
                    state.draft,
                    if (state.status == StatusLine.LOAD_FAILED) ComposerMode.SEND_DISABLED else state.composer,
                    { actions.onDraftChange(it, state.draftRevision) },
                    onSend = {
                        actions.onSendClick()
                        scope.launch { listState.scrollToItem(0) }
                    },
                    onStop = actions::onStopClick,
                    busy = state.sending,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.status == StatusLine.LOAD_FAILED) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.chat_load_failed_text), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = onRetryLoad) { Text(stringResource(R.string.chat_load_retry)) }
                }
                return@Box
            }
            MessageList(
                state.items,
                state.live,
                state.notes,
                state.answering,
                actions,
                onCancel = { it.idempotencyKey?.let(actions::onCancel) },
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

    state.confirmCancel?.let { id ->
        // The dialog follows its own row: it rewords once a request may have gone out, and closes once the row is
        // gone or has started sending. Confirm says which wording was shown; the view model deletes only if it still holds.
        val row = state.items.asReversed().firstOrNull { it is UserMessage && it.idempotencyKey == id } as UserMessage?
        val open = row != null && (row.state == SendState.QUEUED || row.state == SendState.FAILED)
        LaunchedEffect(id, open) { if (!open) actions.onDismissCancel(id) }
        if (open) {
            key(id) {
                val attempted = row.attempted
                ConfirmDialog(
                    title = R.string.chat_cancel_title,
                    text = if (attempted) R.string.chat_cancel_text_attempted else R.string.chat_cancel_text,
                    quote = row.text,
                    confirm = R.string.chat_cancel_confirm,
                    dismiss = R.string.chat_keep,
                    onConfirm = { actions.onConfirmCancel(id, mayHaveSentShown = attempted) },
                    onDismiss = { actions.onDismissCancel(id) },
                )
            }
        }
    }
    state.confirmDiscard?.let { unsent ->
        key(unsent.id) {
            ConfirmDialog(
                title = R.string.chat_discard_title,
                text = R.string.chat_discard_text,
                quote = unsent.text,
                confirm = R.string.chat_unsent_discard,
                dismiss = R.string.chat_keep,
                onConfirm = { actions.onConfirmDiscard(unsent.id) },
                onDismiss = actions::onDismissDiscard,
            )
        }
    }
    state.confirmRetry?.let { message ->
        val key = message.idempotencyKey ?: return@let
        key(key) { // a dialog for another message is a new dialog, never the old one with a swapped subject
            ConfirmDialog(
                title = R.string.chat_retry_title,
                text = R.string.chat_retry_text,
                quote = message.text,
                confirm = R.string.chat_retry_confirm,
                dismiss = R.string.cancel,
                onConfirm = { actions.onConfirmRetry(key) },
                onDismiss = { actions.onDismissRetry(key) },
            )
        }
    }
    detail?.let { subject ->
        // Looked up on every change, so full arguments and the result show as soon as they exist; closes once gone.
        val found = steps.find(state.items, subject)
        LaunchedEffect(found == null) { if (found == null) detail = null }
        found?.let { ToolDetailSheet(it.step, it.live, onDismiss = { detail = null }) }
    }
}

@Composable
private fun ComposerNoteLine(note: ComposerNote) {
    val text = when (note) {
        ComposerNote.REMOVED_MAY_HAVE_SENT -> R.string.chat_note_removed_may_have_sent
        ComposerNote.CANCEL_TOO_LATE -> R.string.chat_note_cancel_too_late
    }
    Text(
        stringResource(text),
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Text a send could not store: the reason, the start of the text, and Put back / Discard. */
@Composable
private fun UnsentNotice(unsent: UnsentText, onPutBack: () -> Unit, onDiscard: () -> Unit) {
    val reason = when (unsent.error) {
        ComposeError.NOT_STORED -> R.string.chat_unsent_not_stored
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(reason), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Text(
                unsent.text,
                style = MaterialTheme.typography.labelMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onPutBack) { Text(stringResource(R.string.chat_unsent_put_back)) }
        TextButton(onClick = onDiscard) { Text(stringResource(R.string.chat_unsent_discard)) }
    }
}

@Composable
private fun Title(state: ChatUiState, onOpenConnection: () -> Unit, onRetryLoad: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val action = when (state.status) {
        StatusLine.KEY_REJECTED -> stringResource(R.string.chat_status_open_connection) to onOpenConnection
        StatusLine.LOAD_FAILED -> stringResource(R.string.chat_load_retry) to onRetryLoad
        else -> null
    }
    // The whole title area is the touch target: the status line alone is too small.
    Column(
        Modifier
            .heightIn(min = 48.dp)
            .then(if (action != null) Modifier.clickable(onClickLabel = action.first, onClick = action.second) else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            state.title.ifEmpty { stringResource(R.string.chat_new_title) },
            Modifier.testTag("chat.title"), // the end-to-end tests read the open chat's title here
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
            StatusLine.SERVER_ERROR -> Triple(R.drawable.lucide_triangle_exclamation_point, stringResource(R.string.chat_status_server_error), scheme.error)
            StatusLine.CHAT_GONE -> Triple(R.drawable.lucide_triangle_exclamation_point, stringResource(R.string.chat_status_chat_gone), scheme.error)
            StatusLine.LOAD_FAILED -> Triple(R.drawable.lucide_triangle_exclamation_point, stringResource(R.string.chat_status_load_failed), scheme.error)
        }
        StatusText(icon, text, color, tappable = action != null)
    }
}

@Composable
private fun StatusText(@DrawableRes icon: Int?, text: String, color: Color, tappable: Boolean) {
    Row(Modifier.testTag("status.line").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(12.dp), tint = color)
            Box(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (tappable) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(12.dp), tint = color)
    }
}

@Composable
private fun ConfirmDialog(title: Int, text: Int, quote: String, confirm: Int, dismiss: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                // The message itself, so a dialog for another message is visibly another dialog.
                Text(quote, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(stringResource(text), Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(dismiss)) } },
    )
}
