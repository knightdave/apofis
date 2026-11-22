package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clipToBounds
import xyz.rycerz.apofis.BuildConfig
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
import androidx.compose.ui.platform.UriHandler
import xyz.rycerz.apofis.ui.theme.BackdropState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import xyz.rycerz.apofis.chat.SendState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.rycerz.apofis.chat.ChatItem
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
    currentState: (() -> ChatUiState)? = null, // the owner's state now, for Copy and Select at action time
    enterToSend: Boolean = false, // Return sends (the setting); off, it starts a new line
    covered: Boolean = false, // another surface (the drawer) is over the chat and owns Back
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<StepSubject?>(null) } // the tool step shown in the sheet, fixed when opened
    val steps = remember { StepIndex() }
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) } // shown until the next send or the next link that opens
    var selectKey by rememberSaveable { mutableStateOf<String?>(null) } // the Select view's item; never its text
    // The request sits at the bottom; a same-id request with a new generation is a new request and is revealed again.
    val pending = state.live?.pending?.let { RequestRef(it.requestId, it.executionGeneration) }
    LaunchedEffect(pending) { if (pending != null) listState.animateScrollToItem(0) }
    val follow = rememberFollow(listState, state)
    val backdrop = rememberBackdropState()
    val density = LocalDensity.current
    var bottomContent by remember { mutableIntStateOf(0) } // the bottom area without the larger of keyboard and navigation bar, px
    // The editor grown to the available height, as the chat it was expanded in: one state object for the screen's life
    // (the remembered slot writes this same object), so another chat starts collapsed. Not saved: it resets on recreation.
    val expandedIn = remember { mutableStateOf<String?>(null) }
    val expanded = expandedIn.value == state.sessionKey
    LaunchedEffect(state.sessionKey) { if (expandedIn.value != state.sessionKey) expandedIn.value = null }
    BackHandler(enabled = expanded && !covered) { expandedIn.value = null } // collapses before Back leaves the chat

    // The slots read their inputs through these holders, so each recomposes only when its own inputs change: a streamed
    // token changes the transcript, never the header's or the composer's inputs (equal values notify nothing).
    val latest = rememberUpdatedState(state.forTranscript()) // typing changes nothing the transcript reads
    val barInput = rememberUpdatedState(ChatUiState("", state.title, state.status, emptyList(), ComposerMode.SEND_DISABLED, "", state.host))
    val bottomInput = rememberUpdatedState(
        BottomInput(
            state.draft, state.draftRevision, if (state.status == StatusLine.LOAD_FAILED) ComposerMode.SEND_DISABLED else state.composer,
            state.sending, state.composerNote, state.unsent, state.live?.runId, linkFailed, enterToSend, expanded, state.sessionKey,
            state.status == StatusLine.LOAD_FAILED,
        ),
    )
    val barCallbacks = rememberUpdatedState(BarCallbacks(onOpenConnection, onRetryLoad, navigationIcon, appBarActions))
    val callbacks = rememberUpdatedState(Callbacks(actions, onRetryLoad, zone, today))
    // The objects the slots use, read through a holder too: replacing the list state (and with it the follower), the
    // backdrop (the blur setting) or the link handler reaches the screen; equal identities notify nothing.
    val objects = rememberUpdatedState(Objects(listState, follow, backdrop, uriHandler))
    // Read by the transcript slot only: the app passes a new lambda with every state, which must not reach the composer.
    val current = rememberUpdatedState(currentState)

    val barSlot = remember {
        @Composable {
            SideEffect { ChromeProbe.barCompositions++ }
            val c = barCallbacks.value
            ChatTopBar(barInput.value, c.onOpenConnection, c.onRetryLoad, c.navigationIcon, c.appBarActions, Modifier.blockTouchesBelow().coverForAccessibility(order = -1f))
        }
    }
    val bottomSlot = remember {
        @Composable {
            SideEffect { ChromeProbe.bottomCompositions++ }
            val input = bottomInput.value
            val actions = callbacks.value.actions
            val composerMin = with(LocalDensity.current) { composerMinHeight(MaterialTheme.typography.bodyLarge) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("chat.bottom")
                    .blockTouchesBelow()
                    .coverForAccessibility(order = 1f)
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .navigationBarsPadding()
                    .imePadding() // max(keyboard, navigation bar), applied once
                    .onSizeChanged { bottomContent = it.height },
            ) {
                // One height budget: the notes get what a one-row composer leaves, the composer gets the rest. Collapsed, both
                // leave the transcript room for one row (TRANSCRIPT_MIN); expanded, the composer takes it all.
                Layout({
                    Column(Modifier.clipToBounds().background(MaterialTheme.colorScheme.surface)) { // notes keep a backing of their own over scrolled text
                        input.note?.let { ComposerNoteLine(it) }
                        if (input.linkFailed) NoteLine(R.string.settings_link_no_app, oneLine = true)
                        input.unsent?.let { UnsentNotice(it, actions::onPutBack, actions::onDiscardUnsent) }
                    }
                    Composer(
                        input.draft,
                        input.mode,
                        { actions.onDraftChange(it, input.draftRevision) },
                        onSend = {
                            // The holder decides at dispatch time; only an accepted send follows the bottom.
                            val o = objects.value // the current list and follower, at tap time
                            val accepted = !input.loadFailed && callbacks.value.actions.onSendClick()
                            if (accepted) {
                                linkFailed = false
                                o.follow.onSend()
                                scope.launch { o.list.scrollToItem(0) }
                            }
                            accepted
                        },
                        onStop = { input.runId?.let(actions::onStopClick) }, // the run drawn, not whatever runs at tap time
                        busy = input.sending,
                        enterToSend = input.enterToSend,
                        expanded = input.expanded,
                        onExpandedChange = { expandedIn.value = if (it) input.sessionKey else null },
                        backdrop = objects.value.backdrop,
                    )
                }) { measurables, constraints ->
                    val loose = constraints.copy(minHeight = 0)
                    val room = constraints.maxHeight - if (input.expanded) 0 else TRANSCRIPT_MIN.roundToPx()
                    val notes = measurables[0].measure(loose.copy(maxHeight = (room - composerMin).coerceAtLeast(0)))
                    val composer = measurables[1].measure(loose.copy(maxHeight = (room - notes.height).coerceAtLeast(0)))
                    layout(constraints.maxWidth, notes.height + composer.height) {
                        notes.place(0, 0)
                        composer.place(0, notes.height)
                    }
                }
            }
        }
    }
    // The body for a pair of bar heights: the same lambda while they hold, so a token recomposes the body only through
    // the state it reads; new heights compose it at once with the new padding (same pass, no stale frame).
    val bodies = remember { BodyCache() }
    val bodyFor = { barHeight: Int, bottomArea: Int ->
        bodies.get(barHeight, bottomArea) {
            @Composable {
                val state = latest.value
                val c = callbacks.value
                val o = objects.value
                val listState = o.list
                val follow = o.follow
                val backdrop = o.backdrop
                SideEffect {
                    if (BuildConfig.DEBUG) ChromeProbe.bodyCompositions++
                    ChromeProbe.bodyBackdropId = System.identityHashCode(backdrop)
                    ChromeProbe.bodyBackdropHasLayer = backdrop.layer != null
                }
                val surface = MaterialTheme.colorScheme.surface
                if (state.status == StatusLine.LOAD_FAILED) {
                    Uncovered(barHeight, bottomArea, fullScreen = false) {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            // Too little room (landscape with the keyboard): the title's own retry stays; a second Retry
                            // in a viewport too small to reach would only promise a control that cannot be tapped.
                            if (maxHeight >= 96.dp) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.chat_load_failed_text), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                    WashTextButton(R.string.chat_load_retry, c.onRetryLoad)
                                }
                            }
                        }
                    }
                } else {
                    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
                    val direction = LocalLayoutDirection.current
                    val density = LocalDensity.current
                    val padding = with(density) {
                        PaddingValues(
                            start = sides.calculateStartPadding(direction),
                            end = sides.calculateEndPadding(direction),
                            top = barHeight.toDp() + 8.dp,
                            bottom = bottomArea.toDp() + 8.dp,
                        )
                    }
                    Uncovered(barHeight, bottomArea, fullScreen = true) {
                        MessageList(
                            state.items,
                            state.live,
                            state.notes,
                            state.answering,
                            c.actions,
                            onCancel = { it.idempotencyKey?.let(c.actions::onCancel) },
                            onStep = { detail = it },
                            listState = listState,
                            contentPadding = padding,
                            zone = c.zone,
                            today = c.today,
                            modifier = Modifier
                                .backdropSource(backdrop, surface)
                                .drawWithContent {
                                    drawContent()
                                    // Inside the source, so the sharp list and any blurred sample of it both fade. Clear where
                                    // the last row rests (8 dp above the bottom area), opaque at the composer card's bottom
                                    // edge (above the insets and the card's outer margin, Composer.kt) and below it: text
                                    // and controls at the bottom are never washed out, text passing behind the composer fades.
                                    val rest = size.height - bottomArea - 8.dp.toPx()
                                    val edge = size.height - (bottomArea - bottomContent) - COMPOSER_MARGIN.toPx()
                                    if (edge > rest) {
                                        drawRect(Brush.verticalGradient(0f to surface.transparent(), 1f to surface, startY = rest, endY = edge), Offset(0f, rest), Size(size.width, edge - rest))
                                    }
                                    drawRect(surface, Offset(0f, edge), Size(size.width, size.height - edge))
                                },
                            onLink = { url -> linkFailed = runCatching { objects.value.uri.openUri(url) }.isFailure },
                            onSelect = { selectKey = it },
                            current = current.value?.let { now -> { now().items } },
                        )
                    }
                    ScrollToBottomButton(
                        visible = follow.buttonVisible,
                        backdrop = backdrop,
                        onClick = {
                            follow.onButton()
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        // The 28 dp circle sits 12 dp from the end and 12 dp above the bottom area; its 48 dp target extends 10 dp around it.
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(end = 2.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }

    // Like a scaffold: the bottom area and the bar are measured first, then the body is composed with their heights, so
    // the list's padding and the uncovered region never lag a frame behind (keyboard, notes, large text). The policy is
    // remembered and reads no per-token state, so streaming never measures the root again.
    val policy = remember {
        val measure: SubcomposeMeasureScope.(Constraints) -> MeasureResult = { constraints ->
            ChromeProbe.rootMeasures++
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val bar = subcompose(ChromeSlot.BAR, barSlot).single().measure(loose)
            // The bottom area never covers the bar: at most the height under it.
            val bottom = subcompose(ChromeSlot.BOTTOM, bottomSlot).single().measure(loose.copy(maxHeight = (constraints.maxHeight - bar.height).coerceAtLeast(0)))
            val body = subcompose(ChromeSlot.BODY, bodyFor(bar.height, bottom.height)).map { it.measure(loose) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                body.first().place(0, bar.height) // the uncovered region
                // The scroll button, at the end edge (mirrored in right-to-left layouts).
                body.getOrNull(1)?.let { it.placeRelative(constraints.maxWidth - it.width, constraints.maxHeight - bottom.height - it.height) }
                bottom.place(0, constraints.maxHeight - bottom.height, zIndex = 1f)
                bar.place(0, 0, zIndex = 1f)
            }
        }
        measure
    }
    SubcomposeLayout(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("chat.key:${state.sessionKey}").semantics { isTraversalGroup = true }, // the end-to-end tests read the open chat's key here
        policy,
    )

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
    selectKey?.let { key ->
        // Only the stored part is searched (a live merge appends just the live reply, which has no Select), once per
        // transcript change, not per streamed token. Closes once the key no longer resolves.
        @Suppress("UNCHECKED_CAST") val stored = (state.items as? Joined<ChatItem>)?.head ?: state.items
        val running = state.live != null
        val text = remember(stored, key, running) { actionable(stored, key.removePrefix(FULL), fullMessage = key.startsWith(FULL), running = running) } // as Copy and Select do
        // A restored view waits for the transcript to load; once loaded, a miss closes it for good.
        LaunchedEffect(text == null, state.loaded) { if (text == null && state.loaded) selectKey = null }
        if (text != null) {
            Dialog(onDismissRequest = { selectKey = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                SelectTextScreen(text, onBack = { selectKey = null })
            }
        }
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

/**
 * The part of the screen the bars leave uncovered, between [top] and [bottom] px (the root places it at [top]). With
 * [fullScreen] the content keeps the whole screen (the list draws under the bars) but its parent spans only the
 * uncovered part, so whatever lies under the bars gets no accessibility focus (semantic bounds are clipped to the
 * parent; touches are stopped by the bars themselves); otherwise the content is laid out inside it.
 */
@Composable
private fun Uncovered(top: Int, bottom: Int, fullScreen: Boolean, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val height = (constraints.maxHeight - top - bottom).coerceAtLeast(0)
        val full = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
        val placeable = measurables.single().measure(if (fullScreen) full else Constraints.fixed(constraints.maxWidth, height))
        layout(constraints.maxWidth, height) { placeable.place(0, if (fullScreen) -top else 0) }
    }
}

private enum class ChromeSlot { BOTTOM, BAR, BODY }

/** What a collapsed composer and its notes leave the transcript: one 48 dp row with the list's padding. */
private val TRANSCRIPT_MIN = 64.dp

/** What the composer slot shows: equal values notify nothing, so tokens do not recompose it. */
private data class BottomInput(
    val draft: String,
    val draftRevision: Long,
    val mode: ComposerMode,
    val sending: Boolean,
    val note: ComposerNote?,
    val unsent: UnsentText?,
    val runId: String?,
    val linkFailed: Boolean,
    val enterToSend: Boolean,
    val expanded: Boolean,
    val sessionKey: String,
    val loadFailed: Boolean,
)

/** What the transcript reads: without the composer's fields, so typing does not recompose it. */
private fun ChatUiState.forTranscript() =
    copy(composer = ComposerMode.SEND_DISABLED, draft = "", composerNote = null, sending = false, unsent = null, confirmDiscard = null, draftRevision = 0)

private data class BarCallbacks(
    val onOpenConnection: () -> Unit,
    val onRetryLoad: () -> Unit,
    val navigationIcon: @Composable () -> Unit,
    val appBarActions: @Composable RowScope.() -> Unit,
)

private data class Objects(val list: LazyListState, val follow: Follower, val backdrop: BackdropState, val uri: UriHandler)

private data class Callbacks(val actions: ChatActions, val onRetryLoad: () -> Unit, val zone: ZoneId, val today: LocalDate)

/** The body lambda for the last pair of bar heights. */
private class BodyCache {
    private var key = -1L
    private var body: (@Composable () -> Unit)? = null

    fun get(top: Int, bottom: Int, make: () -> @Composable () -> Unit): @Composable () -> Unit {
        val k = top.toLong() shl 32 or (bottom.toLong() and 0xffffffffL)
        if (k != key || body == null) {
            key = k
            body = make()
        }
        return body!!
    }
}

/** Counters the tests read to prove that streamed tokens leave the chrome alone; a few increments, no other cost. */
internal object ChromeProbe {
    var barCompositions = 0
    var bottomCompositions = 0
    var rootMeasures = 0
    var pins = 0 // requests the follow rules made to move the list to the bottom
    // Which backdrop the transcript was last composed with, as scalars: no reference keeps it alive after the screen.
    var bodyBackdropId = 0
    var bodyBackdropHasLayer = false
    var composerBackdropId = 0 // the backdrop the composer was last composed with (debug builds only)
    var bodyCompositions = 0 // debug builds only
}

/**
 * An opaque overlay hides from accessibility what lies under it: Compose leaves out of the tree the parts of nodes
 * covered by an "important" node drawn above them. A blank pane title makes this container important without making it
 * focusable or read; its own buttons and texts stay separate nodes.
 */
private fun Modifier.coverForAccessibility(order: Float) = semantics {
    paneTitle = ""
    isTraversalGroup = true // so the index orders the container's controls as one unit, not one by one by position
    traversalIndex = order // read top to bottom: the bar (-1), the list (0), the bottom area (1), whatever the drawing order
}

/**
 * Makes this opaque overlay a hit-test target, so a touch on its blank parts stops here and never reaches the list
 * drawn underneath (Compose tests lower siblings only when no higher one is hit). Nothing is consumed: the overlay's
 * own buttons and fields still get their touches.
 */
private fun Modifier.blockTouchesBelow() = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
