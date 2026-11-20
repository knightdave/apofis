package xyz.rycerz.apofis.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.UnsentText
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.sync.CancelResult
import xyz.rycerz.apofis.sync.RetryResult

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
    val composerNote: ComposerNote? = null, // one line above the composer about the last send or cancel
    val sending: Boolean = false, // a send is being stored; the send button is disabled meanwhile
    val confirmCancel: String? = null, // the idempotency key of the unsent message the user asked to remove
    val unsent: UnsentText? = null, // text a send could not store, in its own notice above the composer
    val confirmDiscard: UnsentText? = null, // the unsent entry the user asked to discard, while it is still the one shown
    val draftRevision: Long = 0, // the draft's revision: an edit is applied only on the revision it was typed on
)

/** A request Hermes waits on, by id and execution generation: a new generation is a different request. */
data class RequestRef(val requestId: String, val executionGeneration: Long)

/**
 * What came of an answer that was not sent. CHANGED names the request displayed right after the refusal
 * ([successor]); the note shows only with that request's block.
 */
data class AnswerNote(val result: AnswerResult, val successor: RequestRef? = null)

enum class StatusLine { IDLE, WORKING, QUEUED, OFFLINE, UNREACHABLE, KEY_REJECTED, SERVER_ERROR, CHAT_GONE, LOAD_FAILED }

enum class ComposerMode { SEND_DISABLED, SEND, STEER, STOP }

enum class ComposerNote {
    REMOVED_MAY_HAVE_SENT, // cancelled on this phone, but it may already have reached the server
    CANCEL_TOO_LATE, // it started sending before the cancel: nothing was removed
}

/** Everything the chat screen can ask for; no-ops by default so previews and screenshots need no view model. */
interface ChatActions {
    /** [basedOnRevision]: [ChatUiState.draftRevision] of the state the field showed. */
    fun onDraftChange(text: String, basedOnRevision: Long) {}

    /** Sends the current draft; ignored while a send is being stored. */
    fun onSendClick() {}
    fun onStopClick() {}
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
}

private enum class Failure { NETWORK, UNAUTHORIZED, SERVER, GONE }

private data class Local(
    val failure: Failure? = null,
    val notes: Map<RequestRef, AnswerNote> = emptyMap(),
    val answering: Boolean = false,
    val confirmRetry: UserMessage? = null,
    val composerNote: ComposerNote? = null,
    val confirmCancel: String? = null,
    val confirmDiscard: Long? = null,
)

private data class Env(val title: String, val online: Boolean, val rejected: Boolean)

/** One chat. [online] is the device's connectivity (see [onlineFlow]); it defaults to always online for tests. */
class ChatViewModel(
    private val sessionKey: String,
    private val connected: Connected,
    online: Flow<Boolean> = flowOf(true),
) : ViewModel(), ChatActions {
    private val repo = connected.repo
    private val follower = connected.follower
    private val composer = connected.composer // draft, send in flight and send failure: outlive this view model
    private val local = MutableStateFlow(Local())
    private val host = connected.credentials.baseUrl.toHttpUrlOrNull()?.host.orEmpty()
    private val merger = LiveMerger()

    private val content = combine(repo.transcript(sessionKey), follower.live(sessionKey)) { t, l -> merger.merge(t, l) to l }

    private val env = combine(repo.session(sessionKey), online.distinctUntilChanged(), follower.credentialsRejected) { s, o, r ->
        Env(s?.title.orEmpty(), o, r)
    }

    val state: StateFlow<ChatUiState> = combine(content, composer.state(sessionKey), env, local) { (items, live), c, e, l ->
        val d = c.draft
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
                live == null && d.isBlank() -> ComposerMode.SEND_DISABLED
                live == null -> ComposerMode.SEND
                d.isBlank() -> ComposerMode.STOP
                else -> ComposerMode.STEER
            },
            draft = d,
            host = host,
            live = live,
            notes = l.notes,
            answering = l.answering,
            confirmRetry = l.confirmRetry,
            composerNote = l.composerNote,
            sending = c.sending,
            confirmCancel = l.confirmCancel,
            unsent = c.unsent,
            confirmDiscard = c.unsent?.takeIf { it.id == l.confirmDiscard },
            draftRevision = c.revision,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        composer.current(sessionKey).let {
            ChatUiState(sessionKey, "", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, it.draft, host, sending = it.sending, unsent = it.unsent, draftRevision = it.revision)
        },
    )

    init {
        onOpened()
    }

    /**
     * The chat was opened, now or again (a tap on its row while it is shown): fetches its history when it needs it.
     * Decided by the session row alone; unsent bubbles say nothing about the cache. A local-only chat makes no request.
     * A failure shows on the status line.
     */
    fun onOpened() = request {
        val session = repo.session(sessionKey).first()
        if (session != null && session.existsOnServer && (session.stale || !repo.hasCachedMessages(sessionKey))) {
            repo.syncMessages(sessionKey)
        }
    }

    override fun onDraftChange(text: String, basedOnRevision: Long) {
        composer.edit(sessionKey, text, basedOnRevision)
        if (local.value.composerNote != null) local.update { it.copy(composerNote = null) }
    }

    override fun onSendClick() {
        // Runs in the composer's scope: leaving the chat right after the tap must not cancel storing the message.
        val started = composer.send(sessionKey) { text ->
            repo.send(sessionKey, text) // stored before any request; delivery problems show on the bubble
            local.update { it.copy(notes = emptyMap()) } // harmless once this view model was cleared
        }
        if (started != null) local.update { it.copy(composerNote = null) }
    }

    override fun onStopClick() = request { follower.stop(sessionKey) }

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
            CancelResult.REMOVED_MAY_HAVE_SENT -> ComposerNote.REMOVED_MAY_HAVE_SENT
            CancelResult.STARTED -> ComposerNote.CANCEL_TOO_LATE
            CancelResult.REMOVED, CancelResult.NOT_FOUND -> null
        }
        local.update { it.copy(composerNote = note, confirmCancel = it.confirmCancel.takeIf { k -> k != idempotencyKey }) }
    }

    override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) =
        answer(requestId, executionGeneration) { follower.answerApproval(sessionKey, requestId, executionGeneration, choice) }

    override fun onClarify(requestId: String, executionGeneration: Long, answer: String) {
        if (answer.isBlank()) return
        answer(requestId, executionGeneration) { follower.answerClarify(sessionKey, requestId, executionGeneration, answer) }
    }

    override fun onResolveUnknown() = request { follower.resolveUnknown(sessionKey) }

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
}

/**
 * Puts the live reply into the transcript. The run's turn starts at the user message that started it (the one whose
 * [UserMessage.runId] is the run's). While streaming, stored replies and notices after that message are the stored
 * part of the same reply and are not shown; messages after it stay; one live item built only from [LiveReply] comes
 * last, keyed like the first stored part so nothing moves when the run ends. Without a known turn start every stored
 * item stays. While polling, the stored items are shown exactly as they are, with a "catching up" item after them.
 * The part up to the turn start is reused as the same list object while the transcript is unchanged.
 */
internal class LiveMerger {
    private var transcript: List<ChatItem>? = null
    private var runId: String? = null
    private var stored: List<ChatItem> = emptyList() // stage 1: the stored items shown while streaming
    private var liveKey = "live"
    private var since = 0.0

    fun merge(transcript: List<ChatItem>, live: LiveReply?): List<ChatItem> {
        if (live == null) return transcript
        // Stage 1, only when the transcript or the run changes; a streamed token reads no stored item.
        if (transcript !== this.transcript || live.runId != runId) {
            if (live.runId != runId) since = System.currentTimeMillis() / 1000.0
            this.transcript = transcript
            runId = live.runId
            val start = transcript.indexOfLast { it is UserMessage && it.runId == live.runId }
            if (start < 0) {
                stored = transcript
                liveKey = "live"
            } else {
                val after = transcript.subList(start + 1, transcript.size)
                stored = transcript.subList(0, start + 1) + after.filterIsInstance<UserMessage>()
                liveKey = after.firstOrNull { it is AssistantReply }?.key ?: "live"
            }
        }
        // Stage 2: the stored part as it is, then one item.
        if (live.connection == Connection.POLLING) return Joined(transcript, listOf(AssistantReply("live", emptyList(), "", since, live = true)))
        return Joined(stored, listOf(AssistantReply(liveKey, live.steps, live.text, since, live = true)))
    }
}

/** For previews and screenshots: [LiveMerger] without its cache. */
internal fun withLive(transcript: List<ChatItem>, live: LiveReply?): List<ChatItem> = LiveMerger().merge(transcript, live)

/** A read-only view of [head] followed by [tail]. Equal views with the same [head] compare only their tails. */
internal class Joined<T>(val head: List<T>, val tail: List<T>) : AbstractList<T>() {
    override val size get() = head.size + tail.size

    override fun get(index: Int): T = if (index < head.size) head[index] else tail[index - head.size]

    override fun equals(other: Any?): Boolean =
        if (other is Joined<*> && other.head === head) other.tail == tail else super.equals(other)

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
