package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import xyz.rycerz.apofis.ui.theme.backdropSource
import xyz.rycerz.apofis.ui.theme.rememberBackdropState
import xyz.rycerz.apofis.ui.theme.transparent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import java.time.LocalDate
import java.time.ZoneId
import xyz.rycerz.apofis.ui.theme.wash

/**
 * The chat: the message list fills the screen under a transparent top bar (title and status line) and the bottom area
 * (notes and the composer). Stateless: [state] comes from [ChatViewModel], which also implements [actions].
 * [onOpenConnection] is called when the user taps a rejected key; [onRetryLoad] when the chat could not be opened
 * ([StatusLine.LOAD_FAILED]) and the user asks to try again. [zone] and [today] label the date separators.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    onOpenConnection: () -> Unit,
    modifier: Modifier = Modifier,
    onRetryLoad: () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    appBarActions: @Composable RowScope.() -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    listState: LazyListState = rememberLazyListState(),
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<StepSubject?>(null) } // the tool step shown in the sheet, fixed when opened
    val steps = remember { StepIndex() }
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) } // shown until the next send or the next link that opens
    // The request sits at the bottom; a same-id request with a new generation is a new request and is revealed again.
    val pending = state.live?.pending?.let { RequestRef(it.requestId, it.executionGeneration) }
    LaunchedEffect(pending) { if (pending != null) listState.animateScrollToItem(0) }
    val follow = rememberFollow(listState, state)
    val backdrop = rememberBackdropState()
    val density = LocalDensity.current
    var bottomArea by remember { mutableIntStateOf(0) } // the bottom area's height with its insets, px
    var bottomContent by remember { mutableIntStateOf(0) } // the same without the larger of keyboard and navigation bar, px
    val surface = MaterialTheme.colorScheme.surface
    val fade = with(density) { 180.dp.toPx() }
    val composerGap = with(density) { 8.dp.toPx() }

    val topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight + 8.dp
    // The bottom is read when the list measures, after the bottom area (measured first) has stored its height.
    val listPadding = remember(topPadding, density) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
            override fun calculateTopPadding() = topPadding
            override fun calculateBottomPadding() = with(density) { bottomArea.toDp() } + 8.dp
        }
    }

    Box(modifier.fillMaxSize().background(surface).testTag("chat.key:${state.sessionKey}")) { // the end-to-end tests read the open chat's key here
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .zIndex(1f) // composed first (measured before the list) but drawn over it
                .layout { measurable, constraints ->
                    // Read by the list's padding during its measure in this same pass: no frame with a stale padding.
                    val placeable = measurable.measure(constraints)
                    bottomArea = placeable.height
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .navigationBarsPadding()
                .imePadding() // max(keyboard, navigation bar), applied once
                .onSizeChanged { bottomContent = it.height },
        ) {
            state.composerNote?.let { ComposerNoteLine(it) }
            if (linkFailed) NoteLine(R.string.settings_link_no_app, oneLine = true)
            state.unsent?.let { UnsentNotice(it, actions::onPutBack, actions::onDiscardUnsent) }
            Composer(
                state.draft,
                if (state.status == StatusLine.LOAD_FAILED) ComposerMode.SEND_DISABLED else state.composer,
                { actions.onDraftChange(it, state.draftRevision) },
                onSend = {
                    linkFailed = false
                    actions.onSendClick()
                    follow.onSend()
                    scope.launch { listState.scrollToItem(0) }
                },
                onStop = { state.live?.runId?.let(actions::onStopClick) }, // the run drawn, not whatever runs at tap time
                busy = state.sending,
            )
        }
        if (state.status == StatusLine.LOAD_FAILED) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.chat_load_failed_text), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                WashTextButton(R.string.chat_load_retry, onRetryLoad)
            }
        } else {
            MessageList(
                state.items,
                state.live,
                state.notes,
                state.answering,
                actions,
                onCancel = { it.idempotencyKey?.let(actions::onCancel) },
                onStep = { detail = it },
                listState = listState,
                contentPadding = listPadding,
                zone = zone,
                today = today,
                modifier = Modifier
                    .backdropSource(backdrop)
                    .drawWithContent {
                        drawContent()
                        // Inside the source, so the sharp list and any blurred sample of it both fade; no text below the composer.
                        // The composer card's bottom edge: above the insets and the card's 8 dp outer margin (Composer.kt).
                        val edge = size.height - (bottomArea - bottomContent) - composerGap
                        drawRect(Brush.verticalGradient(0f to surface.transparent(), 1f to surface, startY = edge - fade, endY = edge), Offset(0f, edge - fade), Size(size.width, fade))
                        drawRect(surface, Offset(0f, edge), Size(size.width, size.height - edge))
                    },
                onLink = { url -> linkFailed = runCatching { uriHandler.openUri(url) }.isFailure },
            )
            ScrollToBottomButton(
                visible = follow.buttonVisible,
                backdrop = backdrop,
                onClick = {
                    follow.onButton()
                    scope.launch { listState.animateScrollToItem(0) }
                },
                // The 28 dp circle sits 12 dp from the end and 12 dp above the bottom area; its 48 dp target extends 10 dp around it.
                Modifier.align(Alignment.BottomEnd).padding(end = 2.dp).offset { IntOffset(0, -bottomArea - 2.dp.roundToPx()) },
            )
        }
        ChatTopBar(state, onOpenConnection, onRetryLoad, navigationIcon, appBarActions, Modifier.align(Alignment.TopCenter))
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
private fun ComposerNoteLine(note: ComposerNote) = NoteLine(
    when (note) {
        ComposerNote.REMOVED_MAY_HAVE_SENT -> R.string.chat_note_removed_may_have_sent
        ComposerNote.CANCEL_TOO_LATE -> R.string.chat_note_cancel_too_late
    },
)

/** A note above the composer; [oneLine] keeps it to one line (the full text stays readable to accessibility). */
@Composable
private fun NoteLine(text: Int, oneLine: Boolean = false) {
    val full = stringResource(text)
    Text(
        full,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = full },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (oneLine) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
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
        WashTextButton(R.string.chat_unsent_put_back, onPutBack)
        WashTextButton(R.string.chat_unsent_discard, onDiscard)
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
        confirmButton = { WashTextButton(confirm, onConfirm) },
        dismissButton = { WashTextButton(dismiss, onDismiss) },
    )
}

@Composable
private fun WashTextButton(label: Int, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, Modifier.wash(source, ButtonDefaults.textShape), interactionSource = source) { Text(stringResource(label)) }
}
