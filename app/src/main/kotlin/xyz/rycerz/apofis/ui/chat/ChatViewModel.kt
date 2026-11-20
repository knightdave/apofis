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
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
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
    val answerNote: AnswerResult? = null, // CHANGED or GONE after the last answer; never SENT
    val answering: Boolean = false,
    val confirmRetry: String? = null, // idempotency key of a failed send whose retry needs the user's consent
)

enum class StatusLine { IDLE, WORKING, QUEUED, OFFLINE, UNREACHABLE, KEY_REJECTED }

enum class ComposerMode { SEND_DISABLED, SEND, STEER, STOP }

/** Everything the chat screen can ask for; no-ops by default so previews and screenshots need no view model. */
interface ChatActions {
    fun onDraftChange(text: String) {}
    fun onSendClick() {}
    fun onStopClick() {}
    fun onRetry(idempotencyKey: String) {}
    fun onConfirmRetry() {}
    fun onDismissRetry() {}
    fun onCancel(idempotencyKey: String) {}
    fun onApproval(choice: ApprovalChoice) {}
    fun onClarify(answer: String) {}
    fun onResolveUnknown() {}
}

private enum class Failure { NETWORK, UNAUTHORIZED }

private data class Local(
    val failure: Failure? = null,
    val answerNote: AnswerResult? = null,
    val answering: Boolean = false,
    val confirmRetry: String? = null,
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

    private val content = combine(repo.transcript(sessionKey), follower.live(sessionKey)) { t, l -> withLive(t, l) to l }

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
            answerNote = l.answerNote,
            answering = l.answering,
            confirmRetry = l.confirmRetry,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(sessionKey, "", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, "", host),
    )

    init {
        // A stale chat or one without cached rows is fetched on open; a local-only chat makes no request.
        request {
            val session = repo.session(sessionKey).first()
            if (session != null && session.existsOnServer && (session.stale || repo.transcript(sessionKey).first().isEmpty())) {
                repo.syncMessages(sessionKey)
            }
        }
    }

    override fun onDraftChange(text: String) {
        draft.value = text
    }

    override fun onSendClick() {
        val text = draft.value
        if (text.isBlank()) return
        draft.value = ""
        viewModelScope.launch {
            try {
                repo.send(sessionKey, text) // stored before any request; delivery problems show on the bubble
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                draft.update { it.ifEmpty { text } } // nothing was stored: give the text back
            }
        }
    }

    override fun onStopClick() = request { follower.stop(sessionKey) }

    override fun onRetry(idempotencyKey: String) = request {
        // NOT_FOUND needs nothing: the transcript already follows the stored rows.
        if (repo.retrySend(idempotencyKey) == RetryResult.NEEDS_CONFIRMATION) local.update { it.copy(confirmRetry = idempotencyKey) }
    }

    override fun onConfirmRetry() {
        val key = local.value.confirmRetry ?: return
        local.update { it.copy(confirmRetry = null) }
        request { repo.retrySend(key, confirmedDuplicateRisk = true) }
    }

    override fun onDismissRetry() = local.update { it.copy(confirmRetry = null) }

    override fun onCancel(idempotencyKey: String) = request { repo.cancelSend(idempotencyKey) }

    override fun onApproval(choice: ApprovalChoice) = answer { follower.answerApproval(sessionKey, it.requestId, it.executionGeneration, choice) }

    override fun onClarify(answer: String) {
        if (answer.isBlank()) return
        answer { follower.answerClarify(sessionKey, it.requestId, it.executionGeneration, answer) }
    }

    override fun onResolveUnknown() = request { follower.resolveUnknown(sessionKey) }

    /** Answers the request on screen now, by its own ids, so a request replaced in between is never answered. */
    private fun answer(post: suspend (PendingControl) -> AnswerResult) {
        val shown = state.value.live?.pending ?: return
        if (local.value.answering) return
        local.update { it.copy(answering = true, answerNote = null) }
        request {
            try {
                val result = post(shown)
                local.update { it.copy(answerNote = result.takeIf { result != AnswerResult.SENT }) }
            } finally {
                local.update { it.copy(answering = false) }
            }
        }
    }

    /** Runs [block]; a network failure or a refused key shows on the status line, a success clears it. */
    private fun request(block: suspend () -> Unit) {
        viewModelScope.launch { perform(block) }
    }

    private suspend fun perform(block: suspend () -> Unit) {
        val failure = try {
            block()
            null
        } catch (e: ApiError) {
            when (e) {
                is ApiError.Network -> Failure.NETWORK
                ApiError.Unauthorized -> Failure.UNAUTHORIZED
                else -> null
            }
        } catch (e: IllegalStateException) {
            null // the connection was closed by a server switch; this screen is about to go away
        }
        local.update { it.copy(failure = failure) }
    }
}

/**
 * The transcript with the live reply as its last item. When the stored transcript already ends with a reply (the
 * run's rows arrived before the live state was cleared), the live state is merged into it and keeps its key, so the
 * swap at run end does not move the list; otherwise the live reply is keyed "live". O(1): no copy of the transcript.
 */
internal fun withLive(transcript: List<ChatItem>, live: LiveReply?): List<ChatItem> {
    if (live == null) return transcript
    val last = transcript.lastOrNull()
    if (last is AssistantReply) return Joined(transcript.subList(0, transcript.size - 1), listOf(last.copy(live = true)))
    // While polling, the synced rows already show the stored part, so the streamed text is not repeated.
    val text = if (live.connection == Connection.POLLING) "" else live.text
    return Joined(transcript, listOf(AssistantReply("live", live.steps, text, System.currentTimeMillis() / 1000.0, live = true)))
}

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
