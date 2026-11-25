package xyz.rycerz.apofis.ui.chat

import android.net.Uri
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.snapshotFlow
import xyz.rycerz.apofis.BuildConfig
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clipToBounds
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
import xyz.rycerz.apofis.ui.theme.ApofisDialog
import xyz.rycerz.apofis.ui.theme.LocalChatFontScale
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
import androidx.compose.ui.platform.LocalViewConfiguration
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
import xyz.rycerz.apofis.api.ApprovalChoice
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.rycerz.apofis.chat.Command
import xyz.rycerz.apofis.chat.ChatItem
import kotlin.math.ceil
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.chat.Answer
import xyz.rycerz.apofis.chat.ComposeError
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.DraftImage
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.chat.UnsentText
import xyz.rycerz.apofis.chat.UserMessage
import java.time.LocalDate
import java.time.ZoneId
import xyz.rycerz.apofis.ui.theme.wash
import xyz.rycerz.apofis.ui.image.ImageViewer
import xyz.rycerz.apofis.ui.image.LocalImageOpen
import xyz.rycerz.apofis.ui.image.ViewerImage
import xyz.rycerz.apofis.ui.image.ViewerImageSaver

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
    dragArea: Modifier = Modifier, // where a horizontal drag may start (the push drawer): the top bar and the transcript, never the composer
    covered: Boolean = false, // the drawer covers the chat and owns Back (for composition)
    coveredNow: () -> Boolean = { covered }, // the same, read live when an action fires: every action is dropped while true
    chatFontScale: Float = 1f, // the chat text size setting: the message list and the Select view only
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<StepSubject?>(null) } // the tool step shown in the sheet, fixed when opened
    val steps = remember { StepIndex() }
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) } // shown until the next send or the next link that opens
    var selectKey by rememberSaveable { mutableStateOf<String?>(null) } // the Select view's item; never its text
    var viewer by rememberSaveable(stateSaver = ViewerImageSaver) { mutableStateOf<ViewerImage?>(null) } // the open image
    // The request sits at the bottom; a same-id request with a new generation is a new request and is revealed again.
    val pending = state.live?.pending?.let { RequestRef(it.requestId, it.executionGeneration) }
    LaunchedEffect(pending) { if (pending != null) listState.animateScrollToItem(0) }
    val follow = rememberFollow(listState, state)
    // The list follows the bottom when a send's row was stored (the view model's counter), never on the tap itself: a
    // refused send, a picker result or a finished photo moves nothing. Starts equal, so recreation pins nothing. The pin
    // waits until the transcript shows the stored row (by its key), so it acts on the list that holds it whichever
    // reaches the screen first. Only the stored part is searched, once per change of it, never per streamed token.
    val seenStored = remember { longArrayOf(state.storedSends) }
    val pinPending = state.storedSends != seenStored[0]
    @Suppress("UNCHECKED_CAST") val storedPart = (state.items as? Joined<ChatItem>)?.head ?: state.items
    val storedKey = state.storedKey
    val storedShown = remember(storedPart, storedKey, pinPending) {
        pinPending && storedKey != null && storedPart.asReversed().any { (it as? UserMessage)?.idempotencyKey == storedKey }
    }
    LaunchedEffect(state.storedSends, storedShown) {
        if (storedShown && state.storedSends != seenStored[0]) {
            seenStored[0] = state.storedSends
            follow.onSend()
            listState.scrollToItem(0)
        }
    }
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
    val barInput = rememberUpdatedState(ChatUiState(state.sessionKey, state.title, state.status, emptyList(), ComposerMode.SEND_DISABLED, "", state.host))
    val matches = remember(state.draft) { scanPalette(state.draft) } // once per draft, never per streamed update
    val bottomInput = rememberUpdatedState(
        BottomInput(
            state.draft, state.draftRevision, if (state.status == StatusLine.LOAD_FAILED) ComposerMode.SEND_DISABLED else state.composer,
            state.sending, state.composerNote, state.unsent, state.live?.runId, linkFailed, enterToSend, expanded, state.sessionKey,
            state.status == StatusLine.LOAD_FAILED, state.images, state.choice.model.takeIf { Command.MODEL in matches }, matches,
            state.answer,
        ),
    )
    // One gate for everything the chat can do: while the drawer covers it, a press that was already down (another
    // finger dragged the drawer) or any late activation does nothing. Read at call time, so it recomposes nothing.
    val coveredRead = rememberUpdatedState(coveredNow)
    val actionsNow = rememberUpdatedState(actions)
    val gate = remember { Gate { coveredRead.value() } }
    val passGate: (() -> Unit) -> Unit = remember { gate::pass } // one instance: a new one would recompose the bar
    val openImage: (ViewerImage) -> Unit = remember { { image -> gate.pass { viewer = image } } } // one instance, as above
    val overlays = LocalOverlays.current ?: remember { Overlays() } // the windows over this chat (AppNav provides one)
    val gated = remember { CoveredActions({ actionsNow.value }, gate) }
    // The photo picker, registered here (not in the cached bottom slot, whose lambdas are rebuilt). Its result goes to the
    // actions current when it arrives: this chat's view model, on the connection current then.
    val one = rememberLauncherForActivityResult(remember { PickVisualMedia() }) { uri -> uri?.let { gated.onImagesPicked(listOf(it)) } }
    val left = ImageLimits.MAX_IMAGES - state.images.size // every entry counts, failed and processing ones too
    val many = rememberLauncherForActivityResult(remember(left) { PickMultipleVisualMedia(left.coerceAtLeast(2)) }) { gated.onImagesPicked(it) }
    val imagesNow = rememberUpdatedState(state.images)
    val answerClock = rememberUpdatedState(LocalAnswerClock.current)
    val attach = remember {
        {
            gate.pass {
                val n = ImageLimits.MAX_IMAGES - imagesNow.value.size // read at tap time
                val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                // A launch that fails (no picker on the device) does nothing; one that opens covers the chat like a window.
                val opened = when {
                    n >= 2 -> runCatching { many.launch(request) }.isSuccess
                    n == 1 -> runCatching { one.launch(request) }.isSuccess
                    else -> false.also { gated.onImagesLimit() }
                }
                if (opened) overlays.openedAt = answerClock.value() // see [Overlays]
            }
        }
    }
    val barCallbacks = rememberUpdatedState(BarCallbacks(onOpenConnection, onRetryLoad, navigationIcon, appBarActions))
    val openConnection = remember { { gate.pass { barCallbacks.value.onOpenConnection() } } }
    val retryLoad = remember { { gate.pass { barCallbacks.value.onRetryLoad() } } }
    val callbacks = rememberUpdatedState(Callbacks(gated, retryLoad, zone, today))
    // The objects the slots use, read through a holder too: replacing the list state (and with it the follower), the
    // backdrop (the blur setting) or the link handler reaches the screen; equal identities notify nothing.
    val objects = rememberUpdatedState(Objects(listState, follow, backdrop, uriHandler))
    // Read by the transcript slot, and by the palette only inside a tap: the app passes a new lambda with every state,
    // which must not reach the composer.
    val current = rememberUpdatedState(currentState)
    val drag = rememberUpdatedState(dragArea)
    val fontScale = rememberUpdatedState(chatFontScale) // read by the transcript slot only: never the header or the composer

    val barHidden = remember { BarHidden() }
    val barSlot = remember {
        @Composable {
            if (BuildConfig.DEBUG) SideEffect { ChromeProbe.barCompositions++ }
            val c = barCallbacks.value
            val input = barInput.value // read here, so this slot (and its probe) recomposes when the bar's inputs change
            ChatTopBar(
                input, openConnection, retryLoad, c.navigationIcon, c.appBarActions,
                drag.value
                    .blockTouchesBelow()
                    .coverForAccessibility(order = -1f)
                    .onFocusChanged { barHidden.focused = it.hasFocus }
                    .onPreviewKeyEvent { barHidden.now }, // hidden: no key reaches a control that kept focus
            )
        }
    }
    // A hidden bar keeps no focus: released as soon as it hides (never the editor's, and nothing is focused when it returns).
    val focusManager = LocalFocusManager.current
    LaunchedEffect(barHidden) { snapshotFlow { barHidden.state.value }.collect { if (it && barHidden.focused) focusManager.clearFocus(force = true) } }
    val statusInsets = rememberUpdatedState(WindowInsets.statusBars)
    val bottomInsets = rememberUpdatedState(WindowInsets.ime.union(WindowInsets.navigationBars)) // max of the two, as the bottom area pads
    val bodyStyle = rememberUpdatedState(MaterialTheme.typography.bodyLarge)
    // The run-end announcer, in its own slot: it reads only the live run id, so a token recomposes nothing here.
    val runId = rememberUpdatedState(state.live?.runId)
    val announcerSlot = remember { @Composable { RunEndAnnouncer(runId.value) } }
    // The bottom area for a bar height (its share of the height depends on it): the same lambda while it holds.
    val bottoms = remember { BodyCache() }
    val bottomFor = { barHeight: Int, statusTop: Int ->
        bottoms.get(barHeight, statusTop) {
        @Composable {
            if (BuildConfig.DEBUG) SideEffect { ChromeProbe.bottomCompositions++ }
            val input = bottomInput.value
            val actions = callbacks.value.actions
            val matches = if (input.expanded || input.sending || input.loadFailed) emptyList() else input.matches
            val paletteRoom = remember { ExpandRoom() } // like the strip: composed only while the allocation gives it a share
            val fieldFocus = remember { FocusRequester() } // a row run or filled from a keyboard leaves; the field takes focus
            // Read at tap time: onCommand claims and clears the whole draft, so a tap acts only while the draft now is
            // still the slash word the row was drawn for (the drawn draft without an owner's state, as in tests).
            val still = { c: Command -> c in scanPalette(current.value?.invoke()?.draft ?: input.draft) }
            // Answering: the request the buffer belongs to, and its press rule from when the composer first showed it.
            val asked = input.answer?.takeIf { input.mode == ComposerMode.ANSWER }?.let { Triple(it.runId, it.requestId, it.generation) }
            val settle = LocalViewConfiguration.current.doubleTapTimeoutMillis
            val answerGate = remember(asked) { asked?.let { AnswerGate({ answerClock.value() }, settle, overlays) } }
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
                // One allocation (bottomShares) for the bar, the notes, the palette, the composer's parts and the transcript,
                // in the safe band between the status inset and the keyboard or navigation inset; each part is measured and
                // placed with exactly its share, and the area reports what it occupies.
                var look: ComposerLook? = null
                Layout({
                    // Notes keep a backing of their own over scrolled text, and scroll inside their share when taller. The
                    // unsent entry comes first, so its row is the one the guaranteed share shows.
                    Column(Modifier.semantics { traversalIndex = -2f }.testTag("chat.notes").background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
                        input.unsent?.let { UnsentNotice(it, actions::onPutBack, actions::onDiscardUnsent, putBackEnabled = input.answer == null) }
                        if (input.answer?.expired == true) NoteLine(R.string.chat_unsent_answer_expired)
                        input.note?.let { ComposerNoteLine(it) }
                        if (input.linkFailed) NoteLine(R.string.settings_link_no_app, oneLine = true)
                    }
                    look = ComposerParts(
                        input.draft,
                        input.mode,
                        { actions.onDraftChange(it, input.draftRevision) },
                        onSend = {
                            // The holder decides at dispatch time.
                            // The list follows once the row is stored (the counter effect above), never here.
                            val accepted = !input.loadFailed && callbacks.value.actions.onSendClick(input.runId) // the drawn run: what Steer steers
                            if (accepted) linkFailed = false
                            accepted
                        },
                        onStop = { input.runId?.let(actions::onStopClick) }, // the run drawn, not whatever runs at tap time
                        busy = input.sending,
                        enterToSend = input.enterToSend,
                        expanded = input.expanded,
                        onExpandedChange = { grow -> gate.pass { expandedIn.value = if (grow) input.sessionKey else null } }, // a user action of the covered layout
                        backdrop = objects.value.backdrop,
                        subject = when (input.mode) {
                            ComposerMode.ANSWER -> asked
                            ComposerMode.STOP, ComposerMode.STEER, ComposerMode.SEND_AFTER -> input.runId
                            else -> input.sessionKey
                        },
                        images = input.images,
                        attachEnabled = !input.sending && !input.loadFailed && input.sessionKey.isNotEmpty() && input.answer == null,
                        onAttach = attach,
                        onRemoveImage = actions::onRemoveImage,
                        focusRequester = fieldFocus,
                        answerGate = answerGate,
                    )
                    Box(Modifier.padding(horizontal = MARGIN_X)) {
                        if (matches.isNotEmpty() && paletteRoom.state.value) {
                            SlashPalette(
                                matches,
                                input.model ?: stringResource(R.string.model_default),
                                onRun = { c -> if (still(c) && actions.onCommand(c)) fieldFocus.requestFocus() },
                                onFill = { c ->
                                    gate.pass {
                                        if (still(c)) {
                                            actions.onDraftChange("/${c.word} ", input.draftRevision)
                                            fieldFocus.requestFocus()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize().onPreviewKeyEvent { !paletteRoom.now }.semantics { traversalIndex = -2f },
                            )
                        }
                    }
                }) { measurables, constraints ->
                    val width = constraints.maxWidth
                    val d = look!!.dims
                    // The palette's natural height, computed (a removed palette has none to measure): at most 4.5 rows of
                    // SheetRow's metrics (48 dp, or one bodyLarge line and 24 dp of padding) and its two 1 dp borders.
                    val row = maxOf(d.target, ceil(d.line).toInt() + 24.dp.roundToPx())
                    val natural = if (matches.isEmpty()) 0 else minOf(2 * matches.size, 9) * row / 2 + 2.dp.roundToPx()
                    val (shares, composer) = measureComposer(
                        measurables.subList(1, 7), look!!, width, available = constraints.maxHeight - statusTop, bar = barHeight,
                        notes = measurables[0].maxIntrinsicHeight(width), unsent = input.unsent != null, palette = natural,
                    )
                    val shown = shares.palette > 0
                    paletteRoom.now = shown
                    if (paletteRoom.state.value != shown) paletteRoom.state.value = shown
                    val notes = measurables[0].measure(Constraints.fixed(width, shares.notes))
                    val palette = measurables[7].measure(Constraints.fixed(width, shares.palette))
                    layout(width, shares.notes + shares.palette + shares.composer) {
                        notes.place(0, 0)
                        if (shown) palette.place(0, shares.notes)
                        with(composer) { place(width, shares.notes + shares.palette) }
                    }
                }
            }
        }
        }
    }
    // The body for a pair of bar heights: the same lambda while they hold, so a token recomposes the body only through
    // the state it reads; new heights compose it at once with the new padding (same pass, no stale frame).
    val bodies = remember { BodyCache() }
    val bodyFor = { barHeight: Int, bottomArea: Int, barShown: Boolean, buttonFits: Boolean ->
        bodies.get(barHeight, bottomArea, buttonFits) {
            @Composable {
                val state = latest.value
                val c = callbacks.value
                val o = objects.value
                val listState = o.list
                val follow = o.follow
                val backdrop = o.backdrop
                val dragArea = drag.value
                if (BuildConfig.DEBUG) SideEffect {
                    ChromeProbe.bodyCompositions++
                    ChromeProbe.bodyBackdropId = System.identityHashCode(backdrop)
                    ChromeProbe.bodyBackdropHasLayer = backdrop.layer != null
                }
                val surface = MaterialTheme.colorScheme.surface
                if (state.status == StatusLine.LOAD_FAILED) {
                    Uncovered(barHeight, bottomArea, fullScreen = false, dragArea) {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            // With room, the text and Retry. With less, the title's own retry stays while the bar is shown;
                            // with the bar hidden, a compact Retry alone wherever its 48 dp target fits. Below that nothing:
                            // a control in a viewport too small to reach would only promise a tap that cannot happen.
                            if (maxHeight >= 96.dp) {
                                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(R.string.chat_load_failed_text), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                    WashTextButton(R.string.chat_load_retry, c.onRetryLoad)
                                }
                            } else if (!barShown && maxHeight >= 48.dp) {
                                WashTextButton(R.string.chat_load_retry, c.onRetryLoad)
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
                    Uncovered(barHeight, bottomArea, fullScreen = true, dragArea) {
                        Box(propagateMinConstraints = true) { // the list keeps the whole screen; the skeleton lies over it
                            CompositionLocalProvider(LocalChatFontScale provides fontScale.value) {
                                MessageList(
                                    state.items,
                                    state.live,
                                    state.notes,
                                    state.answering,
                                    c.actions,
                                    onCancel = { it.idempotencyKey?.let(c.actions::onCancel) },
                                    onStep = { step -> gate.pass { detail = step } },
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
                                    onLink = { url -> gate.pass { linkFailed = runCatching { objects.value.uri.openUri(url) }.isFailure } },
                                    onSelect = { key -> gate.pass { selectKey = key } },
                                    current = current.value?.let { now -> { now().items } },
                                    onRevealGrew = follow::onGrew, // the follower read through the objects holder above
                                )
                            }
                            // The first fetch of a chat with no stored rows, over the still empty list (decision 27: no delay).
                            if (state.firstLoad && state.items.isEmpty()) {
                                // Clipped above the line where the list's last row rests: in a short window (landscape, the
                                // keyboard, the expanded editor) no bar draws under or around the composer.
                                ChatSkeleton(
                                    Modifier
                                        .padding(
                                            start = padding.calculateStartPadding(direction) + 16.dp,
                                            end = padding.calculateEndPadding(direction) + 16.dp,
                                            top = with(density) { barHeight.toDp() } + 24.dp,
                                            bottom = padding.calculateBottomPadding(),
                                        )
                                        .clipToBounds(),
                                )
                            }
                        }
                    }
                    // Only where its 48 dp target fits in the uncovered band: otherwise not composed at all, so a button
                    // that kept keyboard focus gives it up and no key reaches it; it returns unfocused.
                    if (buttonFits) ScrollToBottomButton(
                        visible = follow.buttonVisible,
                        backdrop = backdrop,
                        onClick = {
                            gate.pass {
                                follow.onButton()
                                scope.launch { listState.animateScrollToItem(0) }
                            }
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
            if (BuildConfig.DEBUG) ChromeProbe.rootMeasures++
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            // The status inset is the system's: reserved at the top whether the bar is shown or not.
            val status = statusInsets.value.getTop(this)
            val measuredBar = subcompose(ChromeSlot.BAR, barSlot).single().measure(loose)
            val bottom = subcompose(ChromeSlot.BOTTOM, bottomFor((measuredBar.height - status).coerceAtLeast(0), status)).single().measure(loose)
            // The allocation (bottomShares) decides: the bar is shown only when it fits beside the bare composer row.
            // Hidden, it is not placed (not drawn, not hit-tested, not in the accessibility tree), takes no keys and
            // gives up focus; it returns, unfocused, when it fits.
            val barShown = barFits(constraints.maxHeight - status - bottomInsets.value.getBottom(this), measuredBar.height - status, bodyStyle.value.let { bottomDims(it) })
            barHidden.now = !barShown
            if (barHidden.state.value == barShown) barHidden.state.value = !barShown
            val bar = measuredBar.takeIf { barShown }
            val top = bar?.height ?: status
            val buttonFits = constraints.maxHeight - top - bottom.height >= 48.dp.roundToPx()
            val body = subcompose(ChromeSlot.BODY, bodyFor(top, bottom.height, barShown, buttonFits)).map { it.measure(loose) }
            val announcer = subcompose(ChromeSlot.ANNOUNCER, announcerSlot).single().measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                body.first().place(0, top) // the uncovered region
                // The scroll button, at the end edge (mirrored in right-to-left layouts), only when its target fits in the
                // uncovered transcript band.
                // The 2 dp gap under the target shrinks first when the band is just 48 dp.
                body.getOrNull(1)?.let { it.placeRelative(constraints.maxWidth - it.width, maxOf(top, constraints.maxHeight - bottom.height - it.height)) }
                bottom.place(0, constraints.maxHeight - bottom.height, zIndex = 1f)
                bar?.place(0, 0, zIndex = 1f)
                // Just below the bar, above everything, so neither the bar nor the composer ever covers it.
                announcer.place((constraints.maxWidth - announcer.width) / 2, top, zIndex = 2f)
            }
        }
        measure
    }
    // Controls inside the layout (the bar slots' buttons, the approval's More) reach the same gate.
    CompositionLocalProvider(LocalChatGate provides passGate, LocalImageOpen provides openImage, LocalOverlays provides overlays) {
        SubcomposeLayout(
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("chat.key:${state.sessionKey}").semantics { isTraversalGroup = true }, // the end-to-end tests read the open chat's key here
            policy,
        )
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
                    overlays = overlays,
                    destructive = true,
                )
            }
        }
    }
    state.confirmDiscard?.let { unsent ->
        key(unsent.id) {
            ConfirmDialog(
                title = R.string.chat_discard_title,
                text = if (unsent.images.isEmpty()) R.string.chat_discard_text else R.string.chat_discard_text_images,
                quote = unsent.text,
                confirm = R.string.chat_unsent_discard,
                dismiss = R.string.chat_keep,
                onConfirm = { actions.onConfirmDiscard(unsent.id) },
                onDismiss = actions::onDismissDiscard,
                overlays = overlays,
                destructive = true,
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
                overlays = overlays,
            )
        }
    }
    detail?.let { subject ->
        // Looked up on every change, so full arguments and the result show as soon as they exist; closes once gone.
        val found = steps.find(state.items, subject)
        LaunchedEffect(found == null) { if (found == null) detail = null }
        found?.let {
            OverChat(overlays)
            ToolDetailSheet(it.step, it.live, onDismiss = { detail = null })
        }
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
            OverChat(overlays)
            Dialog(onDismissRequest = { selectKey = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                CompositionLocalProvider(LocalChatFontScale provides chatFontScale) { SelectTextScreen(text, onBack = { selectKey = null }) }
            }
        }
    }
    viewer?.let { image ->
        OverChat(overlays)
        ImageViewer(image, onClose = { viewer = null })
    }
    when (val sheet = state.sheet) {
        is ChoiceSheet.Model -> {
            OverChat(overlays) // once per showing: the list arriving is the same window
            ModelSheet(sheet, state.choice, gated::pickModel, gated::openModelSheet, gated::closeSheet)
        }
        ChoiceSheet.Reasoning -> {
            OverChat(overlays)
            ReasoningSheet(state.choice, gated::pickReasoning, gated::closeSheet)
        }
        null -> {}
    }
}

@Composable
private fun ComposerNoteLine(note: ComposerNote) = NoteLine(
    when (note.kind) {
        NoteKind.REMOVED_MAY_HAVE_SENT -> R.string.chat_note_removed_may_have_sent
        NoteKind.CANCEL_TOO_LATE -> R.string.chat_note_cancel_too_late
        NoteKind.IMAGE_FAILED -> R.string.chat_image_failed
        NoteKind.IMAGES_LIMIT -> R.string.chat_images_limit
        NoteKind.IMAGES_TOO_LARGE -> R.string.chat_images_too_large
        NoteKind.SHARE_TOO_MANY -> R.string.share_too_many
        NoteKind.SHARE_IMAGES_FAILED -> R.string.share_images_failed
        NoteKind.SHARE_TEXT_CUT -> R.string.share_text_cut
        NoteKind.CMD_UNKNOWN -> R.string.cmd_unknown
        NoteKind.CMD_NO_TEXT -> R.string.cmd_no_text
        NoteKind.CMD_NOTHING_RUNNING -> R.string.cmd_nothing_running
        NoteKind.CMD_NEEDS_TEXT -> R.string.cmd_needs_text
        NoteKind.CMD_STEER_NO_IMAGES -> R.string.cmd_steer_no_images
        NoteKind.CMD_TITLE_REFUSED -> R.string.cmd_title_refused
        NoteKind.FAST_ON -> R.string.fast_on
        NoteKind.FAST_OFF -> R.string.fast_off
        NoteKind.TEMPORARY_NOT_DELETED -> R.string.chat_temporary_not_deleted
    },
    arg = note.arg,
)

/**
 * A note above the composer; [oneLine] keeps it to one line (the full text stays readable to accessibility). [arg]
 * fills the string's one argument, when it takes one.
 */
@Composable
private fun NoteLine(text: Int, oneLine: Boolean = false, arg: String? = null) {
    val full = if (arg != null) stringResource(text, arg) else stringResource(text)
    Text(
        full,
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = full },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (oneLine) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Text a send could not store, or an answer whose question expired: the reason, the start of the text, and Put back /
 * Discard. Put back is disabled while [putBackEnabled] is false (the field shows an answer, the draft is set aside).
 */
@Composable
private fun UnsentNotice(unsent: UnsentText, onPutBack: () -> Unit, onDiscard: () -> Unit, putBackEnabled: Boolean) {
    val reason = when (unsent.error) {
        ComposeError.NOT_STORED -> R.string.chat_unsent_not_stored
        ComposeError.ANSWER_EXPIRED -> R.string.chat_unsent_answer_expired
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
        WashTextButton(R.string.chat_unsent_put_back, onPutBack, enabled = putBackEnabled)
        WashTextButton(R.string.chat_unsent_discard, onDiscard)
    }
}

@Composable
private fun ConfirmDialog(title: Int, text: Int, quote: String, confirm: Int, dismiss: Int, onConfirm: () -> Unit, onDismiss: () -> Unit, overlays: Overlays, destructive: Boolean = false) {
    OverChat(overlays)
    ApofisDialog(stringResource(title), stringResource(confirm), onConfirm, stringResource(dismiss), onDismiss, destructive) {
        Column {
            // The message itself, so a dialog for another message is visibly another dialog.
            Text(quote, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(stringResource(text), Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun WashTextButton(label: Int, onClick: () -> Unit, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, Modifier.wash(source, ButtonDefaults.textShape), enabled = enabled, interactionSource = source) { Text(stringResource(label)) }
}

/**
 * The part of the screen the bars leave uncovered, between [top] and [bottom] px (the root places it at [top]). With
 * [fullScreen] the content keeps the whole screen (the list draws under the bars) but its parent spans only the
 * uncovered part, so whatever lies under the bars gets no accessibility focus (semantic bounds are clipped to the
 * parent; touches are stopped by the bars themselves); otherwise the content is laid out inside it.
 */
@Composable
private fun Uncovered(top: Int, bottom: Int, fullScreen: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val height = (constraints.maxHeight - top - bottom).coerceAtLeast(0)
        val full = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
        val placeable = measurables.single().measure(if (fullScreen) full else Constraints.fixed(constraints.maxWidth, height))
        layout(constraints.maxWidth, height) { placeable.place(0, if (fullScreen) -top else 0) }
    }
}

private enum class ChromeSlot { BOTTOM, BAR, BODY, ANNOUNCER }

/** Whether the bar is hidden: [now] for key events in this very frame, [state] for releasing focus; [focused]: it holds focus. */
private class BarHidden {
    var now = false
    val state = mutableStateOf(false)
    var focused = false
}

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
    val images: List<DraftImage>,
    // The chat's model choice for the palette's /model row, only while the draft shows that row: a choice made
    // otherwise recomposes nothing here.
    val model: String?,
    val matches: List<Command>, // the palette's commands for the draft (see [scanPalette])
    val answer: Answer?, // the clarify answer the field shows (live or expired), null otherwise
)

/** What the transcript reads: without the composer's fields or the choice sheets', so neither recomposes it; with firstLoad (the skeleton). */
private fun ChatUiState.forTranscript() =
    copy(composer = ComposerMode.SEND_DISABLED, draft = "", composerNote = null, sending = false, unsent = null, confirmDiscard = null, draftRevision = 0, choice = ChatModel(), sheet = null, images = emptyList(), storedSends = 0, storedKey = null, answer = null)

private data class BarCallbacks(
    val onOpenConnection: () -> Unit,
    val onRetryLoad: () -> Unit,
    val navigationIcon: @Composable () -> Unit,
    val appBarActions: @Composable RowScope.() -> Unit,
)

private data class Objects(val list: LazyListState, val follow: Follower, val backdrop: BackdropState, val uri: UriHandler)

private data class Callbacks(val actions: ChatActions, val onRetryLoad: () -> Unit, val zone: ZoneId, val today: LocalDate)

/**
 * The chat's one action gate for controls a caller puts in [ChatScreen]'s top bar slots: `LocalChatGate.current { action() }`
 * runs the action only while the drawer does not cover the chat.
 */
val LocalChatGate = staticCompositionLocalOf<(() -> Unit) -> Unit> { { it() } }

/** Runs an action only while the chat is not covered by the drawer, asking [covered] at that moment. */
internal class Gate(private val covered: () -> Boolean) {
    fun pass(action: () -> Unit) {
        if (!covered()) action()
    }

    fun <T> passOr(covering: T, action: () -> T): T = if (covered()) covering else action()
}

/**
 * [actions] through [gate]. Exempt, and passed straight on: typing ([onDraftChange]), which dropping could lose, the
 * six confirm and dismiss callbacks of the confirmation dialogs and the choice sheets' dismissal, each its own window
 * above the drawer, and the photo picker's result ([onImagesPicked]), which comes from a window above the chat too:
 * dropping it would lose the user's pick.
 */
internal class CoveredActions(private val actions: () -> ChatActions, private val gate: Gate) : ChatActions {
    override fun onDraftChange(text: String, basedOnRevision: Long) = actions().onDraftChange(text, basedOnRevision)
    override fun onSendClick(): Boolean = gate.passOr(false) { actions().onSendClick() } // covered: not accepted, nothing called
    override fun onSendClick(steerRun: String?): Boolean = gate.passOr(false) { actions().onSendClick(steerRun) }
    override fun onCommand(command: Command): Boolean = gate.passOr(false) { actions().onCommand(command) }
    override fun onStopClick(runId: String) = gate.pass { actions().onStopClick(runId) }
    override fun onRetry(idempotencyKey: String) = gate.pass { actions().onRetry(idempotencyKey) }
    override fun onConfirmRetry(idempotencyKey: String) = actions().onConfirmRetry(idempotencyKey)
    override fun onDismissRetry(idempotencyKey: String) = actions().onDismissRetry(idempotencyKey)
    override fun onCancel(idempotencyKey: String) = gate.pass { actions().onCancel(idempotencyKey) }
    override fun onConfirmCancel(idempotencyKey: String, mayHaveSentShown: Boolean) = actions().onConfirmCancel(idempotencyKey, mayHaveSentShown)
    override fun onDismissCancel(idempotencyKey: String) = actions().onDismissCancel(idempotencyKey)
    override fun onPutBack() = gate.pass { actions().onPutBack() }
    override fun onDiscardUnsent() = gate.pass { actions().onDiscardUnsent() }
    override fun onConfirmDiscard(id: Long) = actions().onConfirmDiscard(id)
    override fun onDismissDiscard() = actions().onDismissDiscard()
    override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) = gate.pass { actions().onApproval(requestId, executionGeneration, choice) }
    override fun onClarify(requestId: String, executionGeneration: Long, answer: String) = gate.pass { actions().onClarify(requestId, executionGeneration, answer) }
    override fun onResolveUnknown() = gate.pass { actions().onResolveUnknown() }
    override fun openModelSheet() = gate.pass { actions().openModelSheet() }
    override fun openReasoningSheet() = gate.pass { actions().openReasoningSheet() }
    override fun closeSheet() = actions().closeSheet()
    override fun pickModel(provider: String?, model: String?) = gate.pass { actions().pickModel(provider, model) }
    override fun pickReasoning(level: String?) = gate.pass { actions().pickReasoning(level) }
    override fun toggleFast() = gate.pass { actions().toggleFast() }
    override fun onImagesPicked(uris: List<Uri>) = actions().onImagesPicked(uris)
    override fun onRemoveImage(id: Long) = gate.pass { actions().onRemoveImage(id) }
    override fun onImagesLimit() = gate.pass { actions().onImagesLimit() }
}

/** The body lambda for the last pair of bar heights. */
private class BodyCache {
    private var key: Triple<Int, Int, Boolean>? = null
    private var body: (@Composable () -> Unit)? = null

    fun get(top: Int, bottom: Int, flag: Boolean = false, make: () -> @Composable () -> Unit): @Composable () -> Unit {
        val k = Triple(top, bottom, flag)
        if (k != key || body == null) {
            key = k
            body = make()
        }
        return body!!
    }
}

/** Counters the tests read to prove that streamed tokens leave the chrome alone; written in debug builds only (each call site). */
internal object ChromeProbe {
    var barCompositions = 0
    var bottomCompositions = 0
    var rootMeasures = 0
    var pins = 0 // requests the follow rules made to move the list to the bottom
    // Which backdrop the transcript was last composed with, as scalars: no reference keeps it alive after the screen.
    var bodyBackdropId = 0
    var bodyBackdropHasLayer = false
    var composerBackdropId = 0 // the backdrop the composer was last composed with
    var bodyCompositions = 0
    var skeletonCompositions = 0
    var paletteScans = 0 // drafts matched against the commands
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
