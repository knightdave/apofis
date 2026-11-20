package xyz.rycerz.apofis.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
    val notes: Map<String, AnswerResult> = emptyMap(), // by request id, for the run in progress: CHANGED or GONE
    val answering: Boolean = false,
    val confirmRetry: UserMessage? = null, // the failed send whose retry waits for the user's consent
    val composerNote: ComposerNote? = null, // one line above the composer about the last send or cancel
)

enum class StatusLine { IDLE, WORKING, QUEUED, OFFLINE, UNREACHABLE, KEY_REJECTED, SERVER_ERROR, CHAT_GONE, LOAD_FAILED }

enum class ComposerMode { SEND_DISABLED, SEND, STEER, STOP }

enum class ComposerNote {
    SEND_FAILED, // nothing was stored; the text is back in the composer
    REMOVED_MAY_HAVE_SENT, // cancelled on this phone, but it may already have reached the server
    CANCEL_TOO_LATE, // it started sending before the cancel: nothing was removed
}

/** Everything the chat screen can ask for; no-ops by default so previews and screenshots need no view model. */
interface ChatActions {
    fun onDraftChange(text: String) {}
    fun onSendClick(text: String) {}
    fun onStopClick() {}
    fun onRetry(idempotencyKey: String) {}
    fun onConfirmRetry(idempotencyKey: String) {}
    fun onDismissRetry(idempotencyKey: String) {}
    fun onCancel(idempotencyKey: String) {}

    /** Answers the request the block was drawing, by its own ids. */
    fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {}
    fun onClarify(requestId: String, executionGeneration: Long, answer: String) {}
    fun onResolveUnknown() {}
}

private enum class Failure { NETWORK, UNAUTHORIZED, SERVER, GONE }

private data class Local(
    val failure: Failure? = null,
    val notesRun: String? = null, // the run [notes] belong to; they vanish with it
    val notes: Map<String, AnswerResult> = emptyMap(),
    val answering: Boolean = false,
    val confirmRetry: UserMessage? = null,
    val composerNote: ComposerNote? = null,
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
    private val draft = MutableStateFlow("")
    private val local = MutableStateFlow(Local())
    private val host = connected.credentials.baseUrl.toHttpUrlOrNull()?.host.orEmpty()
    private val merger = LiveMerger()

    private val content = combine(repo.transcript(sessionKey), follower.live(sessionKey)) { t, l -> merger.merge(t, l) to l }

    private val env = combine(repo.session(sessionKey), online.distinctUntilChanged(), follower.credentialsRejected) { s, o, r ->
        Env(s?.title.orEmpty(), o, r)
    }

    val state: StateFlow<ChatUiState> = combine(content, draft, env, local) { (items, live), d, e, l ->
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
            notes = if (live != null && live.runId == l.notesRun) l.notes else emptyMap(),
            answering = l.answering,
            confirmRetry = l.confirmRetry,
            composerNote = l.composerNote,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(sessionKey, "", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, "", host),
    )

    init {
        // Decided by the session row alone; unsent bubbles say nothing about the cache. A local-only chat makes no request.
        request {
            val session = repo.session(sessionKey).first()
            if (session != null && session.existsOnServer && (session.stale || !repo.hasCachedMessages(sessionKey))) {
                repo.syncMessages(sessionKey)
            }
        }
    }

    override fun onDraftChange(text: String) {
        draft.value = text
        if (local.value.composerNote != null) local.update { it.copy(composerNote = null) }
    }

    override fun onSendClick(text: String) {
        if (text.isBlank()) return
        draft.value = ""
        local.update { it.copy(composerNote = null) }
        viewModelScope.launch {
            try {
                repo.send(sessionKey, text) // stored before any request; delivery problems show on the bubble
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The repository's contract: a throw means nothing was stored. Never overwrite what was typed since.
                draft.update { if (it.isEmpty()) text else text + "\n" + it }
                local.update { it.copy(composerNote = ComposerNote.SEND_FAILED) }
            }
        }
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

    override fun onCancel(idempotencyKey: String) = request {
        val note = when (repo.cancelSend(idempotencyKey)) {
            CancelResult.REMOVED_MAY_HAVE_SENT -> ComposerNote.REMOVED_MAY_HAVE_SENT
            CancelResult.STARTED -> ComposerNote.CANCEL_TOO_LATE
            CancelResult.REMOVED, CancelResult.NOT_FOUND -> null
        }
        local.update { it.copy(composerNote = note) }
    }

    override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) =
        answer(requestId) { follower.answerApproval(sessionKey, requestId, executionGeneration, choice) }

    override fun onClarify(requestId: String, executionGeneration: Long, answer: String) {
        if (answer.isBlank()) return
        answer(requestId) { follower.answerClarify(sessionKey, requestId, executionGeneration, answer) }
    }

    override fun onResolveUnknown() = request { follower.resolveUnknown(sessionKey) }

    /**
     * Sends one answer at a time. GONE is noted against the answered request and stays as a notice in the reply;
     * CHANGED is noted against the request now waiting, so it shows with that block and never with a later one.
     */
    private fun answer(requestId: String, post: suspend () -> AnswerResult) {
        if (local.value.answering) return
        val run = follower.live(sessionKey).value?.runId ?: return
        local.update { it.copy(answering = true) }
        request {
            try {
                val note = when (post()) {
                    AnswerResult.SENT -> null
                    AnswerResult.GONE -> requestId to AnswerResult.GONE
                    AnswerResult.CHANGED -> follower.live(sessionKey).value?.pending?.let { it.requestId to AnswerResult.CHANGED }
                }
                if (note != null) {
                    local.update { l -> l.copy(notesRun = run, notes = (if (l.notesRun == run) l.notes else emptyMap()) + note) }
                }
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
 * Puts the live reply into the transcript as its own item. Everything after the last user message (stored or
 * unsent) belongs to the run in progress: stored replies there are replaced by the live item, which takes the
 * first one's key so the swap at run end does not move the list; with none it is appended as "live". A reply
 * before the latest user message is never touched. The part before that message is reused as the same list object
 * while the transcript is unchanged, so a streamed token costs only the items after it.
 */
internal class LiveMerger {
    private var transcript: List<ChatItem>? = null
    private var cut = -1
    private var head: List<ChatItem> = emptyList()

    fun merge(transcript: List<ChatItem>, live: LiveReply?): List<ChatItem> {
        if (live == null) return transcript
        var lastUser = transcript.size - 1
        while (lastUser >= 0 && transcript[lastUser] !is UserMessage) lastUser--
        if (transcript !== this.transcript || lastUser + 1 != cut) {
            this.transcript = transcript
            cut = lastUser + 1
            head = transcript.subList(0, cut)
        }
        val after = transcript.subList(cut, transcript.size)
        val stored = after.firstOrNull { it is AssistantReply } as AssistantReply?
        // While polling, the synced rows already hold the stored part, so the streamed text is not repeated.
        val polling = live.connection == Connection.POLLING
        val reply = AssistantReply(
            key = stored?.key ?: "live",
            steps = if (polling && stored != null) stored.steps else live.steps,
            text = if (polling) stored?.text.orEmpty() else live.text,
            timestamp = stored?.timestamp ?: (System.currentTimeMillis() / 1000.0),
            live = true,
        )
        return Joined(head, after.filter { it !is AssistantReply } + reply)
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
