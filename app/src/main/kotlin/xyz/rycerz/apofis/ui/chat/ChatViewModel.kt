package xyz.rycerz.apofis.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.api.ModelOptions
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.Command
import xyz.rycerz.apofis.chat.ComposerState
import xyz.rycerz.apofis.chat.Parsed
import xyz.rycerz.apofis.chat.parseCommand
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.DraftImage
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.chat.UnsentText
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.choice
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.sync.CancelResult
import xyz.rycerz.apofis.sync.RetryResult
import xyz.rycerz.apofis.sync.cleanTitle
import xyz.rycerz.apofis.sync.takeCodePoints

data class ChatUiState(
    val sessionKey: String,
    val title: String, // empty: untitled, shown as "New chat"
    val status: StatusLine,
    val items: List<ChatItem>,
    val composer: ComposerMode,
    val draft: String,
    val host: String = "",
    val live: LiveReply? = null, // the run in progress: its pending approval or clarify, connection and status
    val notes: Map<RequestRef, AnswerNote> = emptyMap(), // by the answered request, kept until the next message is sent
    val answering: Boolean = false,
    val confirmRetry: UserMessage? = null, // the failed send whose retry waits for the user's consent
    val composerNote: ComposerNote? = null, // the composer's one note (the holder's): about the last send, cancel or photo
    val sending: Boolean = false, // a send is being stored; the send button is disabled meanwhile
    val confirmCancel: String? = null, // the idempotency key of the unsent message the user asked to remove
    val unsent: UnsentText? = null, // text a send could not store, in its own notice above the composer
    val confirmDiscard: UnsentText? = null, // the unsent entry the user asked to discard, while it is still the one shown
    val draftRevision: Long = 0, // the draft's revision: an edit is applied only on the revision it was typed on
    val loaded: Boolean = true, // false only before the stored transcript first arrives (items empty but not known empty)
    val choice: ChatModel = ChatModel(), // the chat's model choice, stored on this phone only
    val sheet: ChoiceSheet? = null, // the model or reasoning sheet while it is open
    // From the moment an open decides to fetch the history of a chat with no stored rows until that fetch ends, the wait
    // for the repository's lock included (another request may hold it meanwhile).
    val firstLoad: Boolean = false,
    val images: List<DraftImage> = emptyList(), // the draft's photos, in pick order
    val storedSends: Long = 0, // sends of this view model whose row was stored: the screen pins the list on each
    val storedKey: String? = null, // the idempotency key of the latest of them: the pin waits until the transcript shows it
)

/** The reasoning sheet's levels: null is the server's default, "none" is Off; five of the server's eight (decision 19). */
val REASONING_LEVELS = listOf(null, "none", "low", "medium", "high")

/** An open choice sheet. */
sealed interface ChoiceSheet {
    /**
     * [options]: the server's models once fetched, held only here and never stored; [failed]: the fetch failed. Not a
     * data class: each open is its own sheet, so a second open while the first is loading replaces it.
     */
    class Model(val options: ModelOptions? = null, val failed: Boolean = false) : ChoiceSheet
    data object Reasoning : ChoiceSheet
}

/** A request Hermes waits on, by id and execution generation: a new generation is a different request. */
data class RequestRef(val requestId: String, val executionGeneration: Long)

/**
 * What came of an answer that was not sent. CHANGED names the request displayed right after the refusal
 * ([successor]); the note shows only with that request's block.
 */
data class AnswerNote(val result: AnswerResult, val successor: RequestRef? = null)

enum class StatusLine { IDLE, WORKING, QUEUED, OFFLINE, UNREACHABLE, KEY_REJECTED, SERVER_ERROR, CHAT_GONE, LOAD_FAILED }

/** SEND_AFTER: a run is live and the draft holds a ready photo; it is sent after the reply (a photo never steers). */
enum class ComposerMode { SEND_DISABLED, SEND, STEER, STOP, SEND_AFTER }

/** Everything the chat screen can ask for; no-ops by default so previews and screenshots need no view model. */
interface ChatActions {
    /** [basedOnRevision]: [ChatUiState.draftRevision] of the state the field showed. */
    fun onDraftChange(text: String, basedOnRevision: Long) {}

    /**
     * Asks the draft holder to send the draft now, or runs it when it is a command line; true when it took it (nothing
     * to send, a send in flight or a refused command: false).
     */
    fun onSendClick(): Boolean = false

    /** [onSendClick] from the screen; [steerRun]: the run the Steer button was drawn for (a send steers only that run). */
    fun onSendClick(steerRun: String?): Boolean = onSendClick()

    /** A no-text command tapped in the palette: the same dispatch, claim, refusals and return value as [onSendClick]. */
    fun onCommand(command: Command): Boolean = false
    /** [runId]: the run the Stop button was drawn for. */
    fun onStopClick(runId: String) {}
    fun onRetry(idempotencyKey: String) {}
    fun onConfirmRetry(idempotencyKey: String) {}
    fun onDismissRetry(idempotencyKey: String) {}

    /** Asks before removing an unsent message ([ChatUiState.confirmCancel]). */
    fun onCancel(idempotencyKey: String) {}

    /** [mayHaveSentShown]: the dialog said it may already have been sent. Removes only if that still describes the row. */
    fun onConfirmCancel(idempotencyKey: String, mayHaveSentShown: Boolean) {}
    fun onDismissCancel(idempotencyKey: String) {}

    /** Puts the unsent text back into the composer, before what is typed there. */
    fun onPutBack() {}

    /** Asks before discarding the unsent text ([ChatUiState.confirmDiscard]). */
    fun onDiscardUnsent() {}

    /** [id]: the unsent entry the dialog showed; discarded only if it is still that entry. */
    fun onConfirmDiscard(id: Long) {}
    fun onDismissDiscard() {}

    /** Answers the request the block was drawing, by its own ids. */
    fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {}
    fun onClarify(requestId: String, executionGeneration: Long, answer: String) {}
    fun onResolveUnknown() {}

    /** Opens the model sheet and asks the server for its models; again while open, asks again (Retry). */
    fun openModelSheet() {}
    fun openReasoningSheet() {}
    fun closeSheet() {}

    /** Stores the chat's model on this phone (null and null: the server's default) and closes the sheet; no request. */
    fun pickModel(provider: String?, model: String?) {}
    fun pickReasoning(level: String?) {}

    /** Flips this chat's stored fast mode on this phone (unset counts as off). */
    fun toggleFast() {}

    /** The photo picker's result: adds the photos to the draft (on the connection current when it arrives). */
    fun onImagesPicked(uris: List<Uri>) {}
    fun onRemoveImage(id: Long) {}

    /** Plus with ten photos attached: the limit note instead of a picker. */
    fun onImagesLimit() {}
}

private enum class Failure { NETWORK, UNAUTHORIZED, SERVER, GONE }

private data class Local(
    val failure: Failure? = null,
    val notes: Map<RequestRef, AnswerNote> = emptyMap(),
    val answering: Boolean = false,
    val confirmRetry: UserMessage? = null,
    val confirmCancel: String? = null,
    val confirmDiscard: Long? = null,
    val sheet: ChoiceSheet? = null,
    val firstLoad: Boolean = false,
    val storedSends: Long = 0,
    val storedKey: String? = null,
)

private data class Env(val title: String, val online: Boolean, val rejected: Boolean, val choice: ChatModel)

/** One chat. [online] is the device's connectivity (see [onlineFlow]); it defaults to always online for tests. */
class ChatViewModel(
    private val sessionKey: String,
    private val connected: Connected,
    online: Flow<Boolean> = flowOf(true),
    private val onNew: () -> Unit = {}, // /new: the drawer's New chat
) : ViewModel(), ChatActions {
    private val accepted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the composer took a send; the screen asks for notifications on the first (the view model holds no UI). */
    val sendAccepted: SharedFlow<Unit> = accepted.asSharedFlow()

    private val repo = connected.repo
    private val follower = connected.follower
    private val composer = connected.composer // draft, send in flight and send failure: outlive this view model
    private val local = MutableStateFlow(Local())
    private val host = connected.credentials.baseUrl.toHttpUrlOrNull()?.host.orEmpty()
    private val merger = LiveMerger()

    private val content = combine(repo.transcript(sessionKey), follower.live(sessionKey)) { t, l -> merger.merge(t, l) to l }

    private val env = combine(repo.session(sessionKey), online.distinctUntilChanged(), follower.credentialsRejected) { s, o, r ->
        Env(s?.title.orEmpty(), o, r, s?.choice() ?: ChatModel())
    }

    val state: StateFlow<ChatUiState> = combine(content, composer.state(sessionKey), env, local) { (items, live), c, e, l ->
        val d = c.draft
        // What sendWithImages would send: nothing while a photo is processing; text or a ready photo otherwise.
        val sendable = c.images.none { it.processing } && (d.isNotBlank() || c.images.any { it.digest != null })
        val photos = c.images.any { !it.failed }
        ChatUiState(
            sessionKey = sessionKey,
            title = e.title,
            status = when {
                e.rejected || l.failure == Failure.UNAUTHORIZED -> StatusLine.KEY_REJECTED
                !e.online -> StatusLine.OFFLINE
                l.failure == Failure.NETWORK -> StatusLine.UNREACHABLE
                l.failure == Failure.GONE -> StatusLine.CHAT_GONE
                l.failure == Failure.SERVER -> StatusLine.SERVER_ERROR
                live?.status == "queued" -> StatusLine.QUEUED
                live != null -> StatusLine.WORKING
                else -> StatusLine.IDLE
            },
            items = items,
            composer = when {
                live == null -> if (sendable) ComposerMode.SEND else ComposerMode.SEND_DISABLED
                photos -> if (sendable) ComposerMode.SEND_AFTER else ComposerMode.STOP
                d.isBlank() -> ComposerMode.STOP
                else -> ComposerMode.STEER // a draft of text and only failed photos: the failed ones leave with it
            },
            draft = d,
            host = host,
            live = live,
            notes = l.notes,
            answering = l.answering,
            confirmRetry = l.confirmRetry,
            composerNote = c.note,
            sending = c.sending,
            confirmCancel = l.confirmCancel,
            unsent = c.unsent,
            confirmDiscard = c.unsent?.takeIf { it.id == l.confirmDiscard },
            draftRevision = c.revision,
            choice = e.choice,
            sheet = l.sheet,
            firstLoad = l.firstLoad,
            images = c.images,
            storedSends = l.storedSends,
            storedKey = l.storedKey,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        composer.current(sessionKey).let {
            ChatUiState(sessionKey, "", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, it.draft, host, sending = it.sending, unsent = it.unsent, draftRevision = it.revision, loaded = false, images = it.images)
        },
    )

    @Volatile // tests read it from another thread
    internal var opening: Job? = null // the running or last open; read by tests to wait for it
        private set
    private var fetch: Job? = null // the model sheet's request
    @Volatile private var recheck = false // an open was asked for while one was running

    init {
        // One check now, again whenever the chat turns stale, and again after every completed session refresh (on return
        // to the foreground, from the drawer, after a run): a chat still stale after a failed fetch is fetched then.
        viewModelScope.launch {
            combine(repo.refreshed, repo.session(sessionKey).map { it?.stale == true }.distinctUntilChanged()) { _, _ -> }
                .collect { onOpened() }
        }
    }

    /**
     * The chat was opened, now or again (a tap on its row while it is shown): fetches its history when it needs it.
     * Decided by the session row alone; unsent bubbles say nothing about the cache. A local-only chat makes no request.
     * A failure shows on the status line. When it decides to fetch a chat with no stored rows, [ChatUiState.firstLoad]
     * (the skeleton) is set until that fetch ends (success, failure or cancellation), the wait for the repository's lock
     * included, and the last failure of this view model's own requests is retired so a retry does not pulse under the old
     * error (a 401 of such a request too); the device's connectivity and the follower's rejected-key flag come from
     * elsewhere and stay. A stale chat with stored rows is fetched without either. As before, any request of this view
     * model that ends without a failure clears that last failure as well, an open that fetches nothing included.
     */
    fun onOpened() {
        // One load at a time; a trigger during it asks for one more check when it ends (a cancelled load asks for none).
        if (opening?.isActive == true) {
            recheck = true
            return
        }
        recheck = false
        opening = launchRequest {
            val session = repo.session(sessionKey).first()
            if (session == null || !session.existsOnServer) return@launchRequest
            val first = !repo.hasCachedMessages(sessionKey)
            if (!first && !session.stale) return@launchRequest
            if (first) local.update { it.copy(firstLoad = true, failure = null) }
            try {
                repo.syncMessages(sessionKey)
            } finally {
                local.update { it.copy(firstLoad = false) } // single flight: no other open can have set it meanwhile
            }
        }
        opening!!.invokeOnCompletion { cause -> if (cause == null && recheck) onOpened() }
    }

    override fun onDraftChange(text: String, basedOnRevision: Long) {
        composer.edit(sessionKey, text, basedOnRevision)
        if (composer.current(sessionKey).note != null) composer.setNote(sessionKey, null) // any keystroke clears the note
    }

    // The claimed draft and revision of the command still running that stores nothing (/queue and /steer are claimed by
    // the holder like a send): Enter again on the same draft does nothing meanwhile.
    private var commandInFlight: Pair<String, Long>? = null

    /** The live run this view model shows: what the screen draws its Steer and Stop for. */
    override fun onSendClick(): Boolean = onSendClick(state.value.live?.runId)

    override fun onSendClick(steerRun: String?): Boolean = dispatch(steerRun)

    override fun onCommand(command: Command): Boolean = dispatch(state.value.live?.runId, command)

    /**
     * The one submit path (button, IME action, hardware Enter, palette): reads the holder now and decides from the
     * claimed draft whether it is a message or a command line. A command line is never sent as text; a refusal sets
     * its note and keeps the draft's text and photos; a command that ran clears the draft only if it still holds the
     * claimed text at the claimed revision. [runId]: the run a steer or /stop is for. A cleared view model takes nothing.
     */
    private fun dispatch(runId: String?, tapped: Command? = null): Boolean {
        if (!viewModelScope.isActive) return false
        val claim = composer.current(sessionKey)
        if (claim.sending) return false // the holder takes nothing while a send is being stored
        val (command, text) = when (val parsed = tapped?.let { Parsed.Run(it, "") } ?: parseCommand(claim.draft)) {
            Parsed.Message -> return send(runId)
            is Parsed.Unknown -> return refuse(NoteKind.CMD_UNKNOWN, "/${parsed.word}".takeCodePoints(32))
            is Parsed.Run -> parsed
        }
        val claimed = claim.draft to claim.revision
        if (commandInFlight == claimed) return false
        when {
            !command.takesText && text.isNotBlank() -> return refuse(NoteKind.CMD_NO_TEXT, "/${command.word}")
            command.takesText && text.isBlank() -> return refuse(NoteKind.CMD_NEEDS_TEXT)
            command == Command.STEER && claim.images.isNotEmpty() -> return refuse(NoteKind.CMD_STEER_NO_IMAGES)
            (command == Command.STEER || command == Command.STOP) && runId == null -> return refuse(NoteKind.CMD_NOTHING_RUNNING)
            command == Command.STEER && state.value.live?.status != "running" -> return refuse(NoteKind.CMD_NOTHING_RUNNING)
            command == Command.STEER || command == Command.QUEUE -> return send(runId, command) // stored like a message
        }
        val title = if (command == Command.TITLE) cleanTitle(text) ?: return refuse(NoteKind.CMD_NEEDS_TEXT) else null
        commandInFlight = claimed
        val running: Job? = when (command) {
            Command.NEW -> {
                clearIfClaimed(claim) // first: the chat left behind is empty, so New chat can reuse it
                onNew()
                null
            }
            Command.MODEL -> {
                openModelSheet()
                clearIfClaimed(claim)
                null
            }
            Command.REASONING -> {
                openReasoningSheet()
                clearIfClaimed(claim)
                null
            }
            Command.STOP -> launchRequest {
                follower.stop(sessionKey, checkNotNull(runId))
                clearIfClaimed(claim)
            }
            Command.FAST -> choose(close = false, onStored = {
                composer.setNote(sessionKey, ComposerNote(if (it.fast == true) NoteKind.FAST_ON else NoteKind.FAST_OFF))
                clearIfClaimed(claim)
            }) { it.copy(fast = it.fast != true) }
            Command.TITLE -> launchRequest {
                try {
                    repo.rename(sessionKey, checkNotNull(title))
                    clearIfClaimed(claim)
                } catch (e: ApiError.Http) {
                    if (e.code != "invalid_title") throw e
                    composer.setNote(sessionKey, ComposerNote(NoteKind.CMD_TITLE_REFUSED))
                }
            }
            Command.STEER, Command.QUEUE -> error("stored through send")
        }
        if (running == null) commandInFlight = null else running.invokeOnCompletion { if (commandInFlight == claimed) commandInFlight = null }
        return true
    }

    private fun refuse(kind: NoteKind, arg: String? = null): Boolean {
        composer.setNote(sessionKey, ComposerNote(kind, arg))
        return false
    }

    /**
     * Clears the draft a command ran from, only if the holder still has [claim]'s text at its revision (a user edit keeps
     * the revision, so the text is compared too). Called on the main thread, where user edits arrive too. Not through
     * [onDraftChange], so a note set just before stays.
     */
    private fun clearIfClaimed(claim: ComposerState) {
        if (composer.current(sessionKey).let { it.draft == claim.draft && it.revision == claim.revision }) composer.edit(sessionKey, "", claim.revision)
    }

    /**
     * A row of this view model's send, plain or by command, was stored with [key]: the screen pins the list once the
     * transcript shows it. Harmless once this view model was cleared.
     */
    private fun published(key: String) = local.update { it.copy(storedSends = it.storedSends + 1, storedKey = key) }

    /**
     * A message, or with [command] a /queue (always queued) or /steer (only the drawn run [runId], never a run send),
     * claimed and stored by the draft holder: the field, the photos and a second send wait for the store, and a store
     * that fails leaves the text in the unsent notice. A command stores only its argument, parsed again from the claimed
     * text. A steer whose run ended before the insert stores nothing, so it fails like any store.
     */
    private fun send(runId: String?, command: Command? = null): Boolean {
        // Runs in the composer's scope: leaving the chat right after the tap must not cancel storing the message.
        val started = composer.sendWithImages(sessionKey) { draft, images ->
            // The field empties only once the text is stored; delivery problems after that show on the bubble. Published
            // before stored() releases the claim: the next send of this chat cannot be claimed, so cannot publish its
            // key, before this one has.
            var storedKey: String? = null
            val onStored = { key: String ->
                storedKey = key
                published(key)
                stored()
            }
            val text = if (command == null) draft else (parseCommand(draft) as? Parsed.Run)?.takeIf { it.command == command }?.text ?: error("not $command")
            when (command) {
                null -> repo.send(sessionKey, text, images, steerRun = runId, onStored = onStored)
                Command.QUEUE -> repo.queue(sessionKey, text, images, onStored)
                else -> repo.send(sessionKey, text, steerRun = runId, steerOnly = true, onStored = onStored)
            }
            checkNotNull(storedKey) { "nothing running" } // a steer with no run at the insert: nothing was stored
            local.update { it.copy(notes = emptyMap()) }
        }
        if (started != null) accepted.tryEmit(Unit)
        return started != null
    }

    override fun onStopClick(runId: String) = request { follower.stop(sessionKey, runId) }

    override fun onRetry(idempotencyKey: String) = request {
        // NOT_FOUND needs nothing: the transcript already follows the stored rows.
        if (repo.retrySend(idempotencyKey) == RetryResult.NEEDS_CONFIRMATION) {
            val message = state.value.items.asReversed().firstOrNull { it is UserMessage && it.idempotencyKey == idempotencyKey } as UserMessage?
            if (message != null) local.update { it.copy(confirmRetry = message) }
        }
    }

    override fun onConfirmRetry(idempotencyKey: String) {
        onDismissRetry(idempotencyKey)
        request { repo.retrySend(idempotencyKey, confirmedDuplicateRisk = true) }
    }

    /** Closes the confirmation only if it is still the one for [idempotencyKey]. */
    override fun onDismissRetry(idempotencyKey: String) =
        local.update { if (it.confirmRetry?.idempotencyKey == idempotencyKey) it.copy(confirmRetry = null) else it }

    override fun onImagesPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) connected.attachImages(sessionKey, uris)
    }

    override fun onRemoveImage(id: Long) = composer.removeImage(sessionKey, id)

    override fun onImagesLimit() = composer.setNote(sessionKey, ComposerNote(NoteKind.IMAGES_LIMIT))

    override fun onPutBack() = composer.putBack(sessionKey)

    override fun onDiscardUnsent() {
        val id = composer.current(sessionKey).unsent?.id ?: return
        local.update { it.copy(confirmDiscard = id) }
    }

    override fun onConfirmDiscard(id: Long) {
        composer.discard(sessionKey, id)
        onDismissDiscard()
    }

    override fun onDismissDiscard() = local.update { it.copy(confirmDiscard = null) }

    override fun onCancel(idempotencyKey: String) = local.update { it.copy(confirmCancel = idempotencyKey) }

    override fun onDismissCancel(idempotencyKey: String) =
        local.update { if (it.confirmCancel == idempotencyKey) it.copy(confirmCancel = null) else it }

    override fun onConfirmCancel(idempotencyKey: String, mayHaveSentShown: Boolean) = request {
        val note = when (repo.cancelSend(idempotencyKey, mayHaveSentShown)) {
            // Tried since the dialog said it was not: the dialog stays open and rewords as the row redraws.
            CancelResult.NOW_ATTEMPTED -> return@request
            CancelResult.REMOVED_MAY_HAVE_SENT -> ComposerNote(NoteKind.REMOVED_MAY_HAVE_SENT)
            CancelResult.STARTED -> ComposerNote(NoteKind.CANCEL_TOO_LATE)
            CancelResult.REMOVED, CancelResult.NOT_FOUND -> null
        }
        composer.setNote(sessionKey, note) // before the dialog closes: the note is in the state when it is gone
        local.update { it.copy(confirmCancel = it.confirmCancel.takeIf { k -> k != idempotencyKey }) }
    }

    override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) =
        answer(requestId, executionGeneration) { follower.answerApproval(sessionKey, requestId, executionGeneration, choice) }

    override fun onClarify(requestId: String, executionGeneration: Long, answer: String) {
        if (answer.isBlank()) return
        answer(requestId, executionGeneration) { follower.answerClarify(sessionKey, requestId, executionGeneration, answer) }
    }

    override fun onResolveUnknown() = request { follower.resolveUnknown(sessionKey) }

    override fun openModelSheet() {
        val asked = ChoiceSheet.Model() // this request's sheet: its answer applies only while this very instance is open
        local.update { it.copy(sheet = asked) }
        fetch?.cancel()
        fetch = viewModelScope.launch {
            val options = try {
                connected.api.modelOptions()
            } catch (_: ApiError) {
                null
            } catch (_: IllegalStateException) {
                return@launch // the connection was closed by a switch
            }
            // A closed connection is an old generation: its answer is dropped, as is one for a sheet closed or opened again.
            if (connected.isOpen) local.update { if (it.sheet === asked) it.copy(sheet = ChoiceSheet.Model(options, failed = options == null)) else it }
        }
    }

    override fun openReasoningSheet() {
        fetch?.cancel()
        local.update { it.copy(sheet = ChoiceSheet.Reasoning) }
    }

    override fun closeSheet() {
        fetch?.cancel()
        local.update { it.copy(sheet = null) }
    }

    override fun pickModel(provider: String?, model: String?) {
        choose { it.copy(model = model, provider = provider) }
    }

    override fun pickReasoning(level: String?) {
        choose { it.copy(reasoning = level) }
    }

    override fun toggleFast() {
        choose(close = false) { it.copy(fast = it.fast != true) }
    }

    /**
     * Changes this chat's stored choice on this phone, in this connection only; makes no request. Started undispatched,
     * the change takes its place in the repository's choice order before this returns (so a send tapped next, from any
     * view model, waits for it), and it is not cancelled with the view model. A cleared view model starts nothing; a
     * closed connection or a failed write drops it and the old choice stays. [onStored] gets the choice written, only
     * when one was (a missing chat is left alone). Returns the write's job, null when nothing was started.
     */
    private fun choose(close: Boolean = true, onStored: (ChatModel) -> Unit = {}, change: (ChatModel) -> ChatModel): Job? {
        if (!viewModelScope.isActive) return null
        if (close) closeSheet()
        return viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    var next: ChatModel? = null
                    connected.repo.changeChoice(sessionKey) { change(it).also { c -> next = c } } // connected.repo throws once the connection is closed
                    next?.let(onStored)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Sends one answer at a time. Notes are kept by the answered request (the one drawn when tapped). CHANGED records
     * the request waiting once the answer was refused, so it shows with that block only, never with a later one.
     */
    private fun answer(requestId: String, executionGeneration: Long, post: suspend () -> AnswerResult) {
        if (local.value.answering) return
        if (follower.live(sessionKey).value == null) return
        local.update { it.copy(answering = true) }
        request {
            try {
                val answered = RequestRef(requestId, executionGeneration)
                val note = when (post()) {
                    AnswerResult.SENT -> null
                    AnswerResult.GONE -> AnswerNote(AnswerResult.GONE)
                    // Recorded once, now: the request displayed right after the refusal, never a later one.
                    AnswerResult.CHANGED -> follower.live(sessionKey).value?.pending
                        ?.let { RequestRef(it.requestId, it.executionGeneration) }
                        ?.takeIf { it != answered }
                        ?.let { AnswerNote(AnswerResult.CHANGED, it) }
                }
                if (note != null) local.update { it.copy(notes = it.notes + (answered to note)) }
            } finally {
                local.update { it.copy(answering = false) }
            }
        }
    }

    /** Runs [block]; every API failure shows on the status line, a success clears it. */
    private fun request(block: suspend () -> Unit) {
        launchRequest(block)
    }

    private fun launchRequest(block: suspend () -> Unit): Job =
        viewModelScope.launch {
            val failure = try {
                block()
                null
            } catch (e: ApiError) {
                when (e) {
                    is ApiError.Network -> Failure.NETWORK
                    ApiError.Unauthorized -> Failure.UNAUTHORIZED
                    ApiError.NotFound -> Failure.GONE
                    ApiError.Protocol, is ApiError.Http, is ApiError.Conflict, is ApiError.Unavailable -> Failure.SERVER
                }
            } catch (e: IllegalStateException) {
                null // the connection was closed by a server switch; this screen is about to go away
            }
            local.update { it.copy(failure = failure) }
        }
}

// The run states that end a run; mirrors TERMINAL in run/RunFollower.kt.
private val ENDED = setOf("completed", "failed", "cancelled", "interrupted")

/**
 * Puts the live reply into the transcript. The run's turn starts at the user message that started it (the one whose
 * [UserMessage.runId] is the run's). While streaming, stored replies and notices after that message are the stored
 * part of the same reply and are not shown; messages after it stay; one live item built only from [LiveReply] comes
 * last, keyed like the first stored part so nothing moves when the run ends. Without a known turn start every stored
 * item stays. While polling, the stored items are shown exactly as they are, with a "catching up" item after them.
 * The part up to the turn start is reused as the same list object while the transcript is unchanged.
 */
internal class LiveMerger(private val now: () -> Double = { System.currentTimeMillis() / 1000.0 }) {
    private var transcript: List<ChatItem>? = null
    private var runId: String? = null
    private var stored: List<ChatItem> = emptyList() // stage 1: the stored items shown while streaming
    private var liveKey = "live"
    private var since = 0.0
    private var synced = false // stage 1: the run's turn start is known and a stored reply follows it

    fun merge(transcript: List<ChatItem>, live: LiveReply?): List<ChatItem> {
        if (live == null) return transcript
        // Stage 1, only when the transcript or the run changes; a streamed token reads no stored item.
        if (transcript !== this.transcript || live.runId != runId) {
            if (live.runId != runId) since = now()
            this.transcript = transcript
            runId = live.runId
            val start = transcript.indexOfLast { it is UserMessage && it.runId == live.runId }
            if (start < 0) {
                stored = transcript
                liveKey = "live"
                synced = false
            } else {
                val after = transcript.subList(start + 1, transcript.size)
                stored = transcript.subList(0, start + 1) + after.filterIsInstance<UserMessage>()
                liveKey = after.firstOrNull { it is AssistantReply }?.key ?: "live"
                synced = after.any { it is AssistantReply }
            }
        }
        // An ended run whose turn is stored: the stored turn is shown as it is (its reply keeps the live item's key).
        if (synced && live.status in ENDED) return transcript
        // Stage 2: the stored part as it is, then one item.
        if (live.connection == Connection.POLLING) return Joined(transcript, listOf(AssistantReply("live", emptyList(), "", since, live = true)))
        return Joined(stored, listOf(AssistantReply(liveKey, live.steps, live.text, since, live = true)))
    }
}

/** For previews and screenshots: [LiveMerger] without its cache; [now] stamps the live item (goldens pass a fixed time). */
internal fun withLive(transcript: List<ChatItem>, live: LiveReply?, now: () -> Double = { System.currentTimeMillis() / 1000.0 }): List<ChatItem> =
    LiveMerger(now).merge(transcript, live)

/**
 * A read-only view of [head] followed by [tail]. Two views with the same [head] are equal when their tails hold the
 * same item instances: this equality gates UI updates (state flows, Compose state), and comparing a live reply's
 * content would visit every one of its steps on each update. A rebuilt live item is a new instance, so it is a change.
 */
internal class Joined<T>(val head: List<T>, val tail: List<T>) : AbstractList<T>() {
    override val size get() = head.size + tail.size

    override fun get(index: Int): T = if (index < head.size) head[index] else tail[index - head.size]

    override fun equals(other: Any?): Boolean =
        if (other is Joined<*> && other.head === head) {
            other.tail.size == tail.size && tail.indices.all { other.tail[it] === tail[it] }
        } else {
            super.equals(other)
        }

    override fun hashCode() = super.hashCode()
}

/** True while the device has a default network; follows changes. */
fun Context.onlineFlow(): Flow<Boolean> = callbackFlow {
    val cm = getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trySend(true)
        }

        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    trySend(cm.activeNetwork != null)
    cm.registerDefaultNetworkCallback(callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
