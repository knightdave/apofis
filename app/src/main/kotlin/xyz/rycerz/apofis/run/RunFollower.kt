package xyz.rycerz.apofis.run

import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.ControlResult
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.RunEvent
import xyz.rycerz.apofis.api.RunStatus
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.chat.buildTranscript
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.ReplyOutcomeEntity
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.PendingState
import xyz.rycerz.apofis.sync.RunStarter
import xyz.rycerz.apofis.sync.SendError
import xyz.rycerz.apofis.sync.withRepositoryLock

private val TERMINAL = setOf("completed", "failed", "cancelled", "interrupted")

// The only statuses ever stored: server text outside this list is stored as running (and followed like it).
private val KNOWN = TERMINAL + setOf("queued", "running", "waiting_for_approval", "stopping", "unknown")

private fun stored(status: String) = if (status in KNOWN) status else "running"

// Events after which the pending controls may have changed; each one is checked against a fresh status read.
private val CONTROL_EVENTS = setOf("approval.responded", "approval.settled", "clarify.request", "clarify.settled")
private const val FIRST_RETRY_MS = 1_000L
private const val MAX_RETRY_MS = 30_000L
private const val POLL_MS = 3_000L
private const val SAVE_EVERY_EVENTS = 20
private const val SAVE_EVERY_NS = 500_000_000L
private const val FINISH_ATTEMPTS = 5 // per resume, for a transcript sync that keeps failing transiently
private const val DELIVERY_WINDOW_S = 60.0

/** The result of answering an approval or clarify request. */
enum class AnswerResult {
    SENT,
    CHANGED, // another request is pending now; nothing was sent
    GONE, // no request is pending any more; nothing was sent, or the server no longer wanted the answer
}

/** The follower's state machine; one step per loop turn. */
private sealed interface Next {
    data object Attach : Next
    data object Check : Next // after a drop: wait (1 s doubling to 30 s), then read the status
    data object Queued : Next
    data object Poll : Next // no stream any more: read status and transcript every 3 s
    data object Unknown : Next
    data class Finish(val status: String?) : Next // null: the server no longer knows the run, so no outcome
}

/**
 * Follows every active run while the app is visible: streams the reply into [live], survives drops and restarts by
 * resuming or polling, and when the run ends replaces the streamed reply with the server transcript.
 * [onRunFinished] runs after each run is cleaned up; the app passes `{ sendQueue.drain() }`.
 */
class RunFollower(
    private val api: HermesApi,
    private val db: AppDatabase,
    private val repo: ChatRepository,
    private val scope: CoroutineScope,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    private val onRunFinished: suspend (sessionKey: String) -> Unit = {},
) : RunStarter {
    private val lives = ConcurrentHashMap<String, MutableStateFlow<LiveReply?>>()
    private val jobs = HashMap<String, Job>() // by run id; it also guards [foreground]
    private var foreground = false // opened by resumeAll, closed by pauseAll; no follower starts while closed
    private val seqs = ConcurrentHashMap<String, Long>() // last applied seq by run id; matches the live text across pauseAll
    private val rejected = MutableStateFlow(false)

    /** True once the server refused the API key; the runs stay stored. Cleared by the next request that succeeds. */
    val credentialsRejected: StateFlow<Boolean> = rejected.asStateFlow()

    /** Test seam: runs at the start of Finish's database phase. */
    internal var beforeFinishWrite: suspend RunFollower.() -> Unit = {}

    /** Run ids with a follower job; for tests. */
    internal val following: Set<String> get() = synchronized(jobs) { jobs.keys.toSet() }

    fun live(sessionKey: String): StateFlow<LiveReply?> = state(sessionKey).asStateFlow()

    // Called by the send queue under the repository lock: it only starts a coroutine. While the app is in the
    // background it does nothing; the stored row is followed on the next resumeAll.
    override fun onRunStarted(sessionKey: String, runId: String) =
        start(ActiveRunEntity(runId, sessionKey, lastSeq = null, status = "running", startedAt = 0L), Next.Attach)

    /** Call on app start and every return to the foreground. */
    suspend fun resumeAll() {
        synchronized(jobs) { foreground = true }
        db.activeRunDao().all().forEach { start(it, Next.Check) } // start checks the gate again
    }

    /** Call when the app leaves the foreground: cancels the streams and keeps every stored run. */
    fun pauseAll() {
        val running = synchronized(jobs) {
            foreground = false
            jobs.values.toList().also { jobs.clear() }
        }
        // Outside the lock and over a copy: a job may complete inside cancel() and run its completion handler.
        running.forEach { it.cancel() }
    }

    suspend fun stop(sessionKey: String) {
        val run = db.activeRunDao().forSession(sessionKey) ?: return
        // The intent is stored apart from the server's status, so a cancel seen after a restart still counts as this
        // user's stop, and a stop by another client never does.
        db.activeRunDao().setStopRequested(run.runId)
        edit(run) { it.copy(status = "stopping") }
        call(run) { api.stop(run.runId) }
    }

    /**
     * Answers the approval the user saw ([requestId], [executionGeneration]) only if the server still waits for
     * exactly that request; otherwise nothing is sent and [live] shows what is pending now.
     */
    suspend fun answerApproval(sessionKey: String, requestId: String, executionGeneration: Long, choice: ApprovalChoice): AnswerResult =
        answer(sessionKey, requestId, executionGeneration) { api.approve(it, requestId, executionGeneration, choice) }

    /** Same rule as [answerApproval], for a clarify request shown in [LiveReply.pending]. */
    suspend fun answerClarify(sessionKey: String, requestId: String, executionGeneration: Long, answer: String): AnswerResult =
        answer(sessionKey, requestId, executionGeneration) { api.clarify(it, requestId, executionGeneration, answer) }

    /** For a run whose status is `unknown`: resolves it with the ids from the status, then ends it as interrupted. */
    suspend fun resolveUnknown(sessionKey: String) {
        val run = db.activeRunDao().forSession(sessionKey) ?: return
        val s = refresh(run)
        if (s.status != "unknown") return start(run, Next.Check)
        val admission = s.admissionId ?: throw ApiError.Protocol
        val generation = s.executionGeneration ?: throw ApiError.Protocol
        call(run) { api.resolveUnknown(run.runId, admission, generation) }
        start(run, Next.Finish("interrupted"), replace = true)
    }

    private suspend fun answer(sessionKey: String, requestId: String, generation: Long, post: suspend (runId: String) -> ControlResult): AnswerResult {
        val run = db.activeRunDao().forSession(sessionKey) ?: return AnswerResult.GONE
        val before = refresh(run).pendingControls
        if (before.none { it.requestId == requestId && it.executionGeneration == generation }) return changedOrGone(before)
        val accepted = try {
            call(run) { post(run.runId) } == ControlResult.RESOLVED
        } catch (_: ApiError.Conflict) {
            false // answered elsewhere, expired, or a newer generation
        }
        // What shows next comes from a read made after the POST: a new request may have been published meanwhile.
        val after = try {
            refresh(run).pendingControls
        } catch (e: ApiError) {
            if (!accepted || e == ApiError.Unauthorized) throw e
            edit(run) { it.copy(pending = null) } // the stream's next control event reads the status again
            return AnswerResult.SENT
        }
        return if (accepted) AnswerResult.SENT else changedOrGone(after)
    }

    private fun changedOrGone(pending: List<*>) = if (pending.isEmpty()) AnswerResult.GONE else AnswerResult.CHANGED

    private fun state(sessionKey: String) = lives.getOrPut(sessionKey) { MutableStateFlow(null) }

    private fun edit(run: ActiveRunEntity, change: (LiveReply) -> LiveReply) =
        state(run.sessionKey).update { if (it?.runId == run.runId) change(it) else it }

    /** Shows [s] and stores its status (terminal ones too), so the send queue's steer decision reads the truth. */
    private suspend fun applyStatus(run: ActiveRunEntity, s: RunStatus) {
        edit(run) { it.copy(status = s.status, pending = s.pendingControls.firstOrNull()) }
        db.activeRunDao().setStatus(run.runId, stored(s.status))
    }

    private suspend fun refresh(run: ActiveRunEntity): RunStatus = call(run) { api.runStatus(run.runId) }.also { applyStatus(run, it) }

    /** A user action's API call: a refused key also stops that run's follower until the next resumeAll. */
    private suspend fun <T> call(run: ActiveRunEntity, block: suspend () -> T): T = try {
        io(block)
    } catch (e: ApiError.Unauthorized) {
        synchronized(jobs) { jobs.remove(run.runId) }?.cancel()
        throw e
    }

    /** Every direct HermesApi request goes through here (never repository calls, which may make no request). */
    private suspend fun <T> io(block: suspend () -> T): T = try {
        block().also { accepted() }
    } catch (e: ApiError.Unauthorized) {
        rejected.value = true
        throw e
    }

    /** The one place that records a request the server accepted with this key. */
    private fun accepted() {
        rejected.value = false
    }

    private fun start(run: ActiveRunEntity, first: Next, replace: Boolean = false): Unit = synchronized(jobs) {
        if (!foreground) return
        val old = jobs[run.runId]
        if (old?.isActive == true && !replace) return
        old?.cancel()
        state(run.sessionKey).update {
            if (it?.runId == run.runId) it else LiveReply(run.runId, run.status, "", emptyList(), null, Connection.LIVE)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { Follow(run, first).run() }
        jobs[run.runId] = job
        job.invokeOnCompletion { synchronized(jobs) { if (jobs[run.runId] === job) jobs.remove(run.runId) } }
        job.start()
    }

    private inner class Follow(private val run: ActiveRunEntity, private var next: Next) {
        private val id = run.runId
        private val key = run.sessionKey
        private var lastSeq = seqs[id] ?: run.lastSeq
        private var polling = run.polling
        private var backoff = if (next == Next.Check) 0L else FIRST_RETRY_MS // a resume checks the status at once
        private var unsaved = 0
        private var savedAt = System.nanoTime()
        private var finishFailures = 0
        private val toolStarts = HashMap<String, Double>()

        suspend fun run() {
            try {
                // shortcut: after a restart the stream resumes at lastSeq, so earlier text of this reply shows only
                // as far as the server already stored it, until the run ends and the transcript is synced.
                if (next == Next.Check && run.lastSeq != null && state(key).value?.text.isNullOrEmpty()) quietly { repo.syncMessages(key) }
                if (next == Next.Check && polling) next = Next.Poll // a run that lost its stream never reattaches
                while (true) {
                    next = when (val step = next) {
                        Next.Attach -> attach()
                        Next.Check -> {
                            edit(run) { it.copy(connection = Connection.RECONNECTING) }
                            retryWait()
                            status()
                        }
                        Next.Queued -> {
                            delayMs(POLL_MS)
                            status()
                        }
                        Next.Poll -> {
                            edit(run) { it.copy(connection = Connection.POLLING) }
                            delayMs(POLL_MS)
                            quietly { repo.syncMessages(key) }
                            when (val n = status()) {
                                is Next.Finish, Next.Unknown -> n
                                else -> Next.Poll
                            }
                        }
                        Next.Unknown -> return // status() stored it; waits for resolveUnknown
                        is Next.Finish -> {
                            if (finish(step.status)) return
                            if (++finishFailures >= FINISH_ATTEMPTS) return // rows kept; the next resumeAll tries again
                            retryWait()
                            step
                        }
                    }
                }
            } catch (_: ApiError.Unauthorized) {
                rejected.value = true // the row stays for a later resumeAll
            } catch (_: IllegalArgumentException) {
                // HermesApi refuses a malformed run id before any request: the run can never be followed.
                finish("failed", sync = false)
            }
        }

        private suspend fun retryWait() {
            if (backoff > 0) delayMs(backoff)
            backoff = (backoff * 2).coerceIn(FIRST_RETRY_MS, MAX_RETRY_MS)
        }

        private suspend fun attach(): Next {
            var next: Next = Next.Check // a stream that ends without a terminal event is a drop, not the run's end
            try {
                io { api.runEvents(id, lastSeq).takeWhile { event -> handle(event)?.also { next = it } == null }.collect() }
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) throw e
                if (e == ApiError.NotFound) next = fallBackToPolling()
            } finally {
                withContext(NonCancellable) { save() }
            }
            return next
        }

        /** Applies [event]; returns the next step when the stream should be left. */
        private suspend fun handle(event: RunEvent): Next? {
            val seq = event.seq.takeIf { it >= 0 } // frames without a sequence number are applied but never stored
            if (seq != null && lastSeq.let { it != null && seq <= it }) return null // already applied
            accepted()
            if (state(key).value?.connection != Connection.LIVE) edit(run) { it.copy(connection = Connection.LIVE) }
            val next = apply(event)
            // Check means a control change could not be read: leave without storing this seq, so the event replays.
            if (next == Next.Check) return next
            backoff = FIRST_RETRY_MS
            if (seq != null) {
                lastSeq = seq
                seqs[id] = seq
                // A write per token would thrash the database: store at most every 20 events or 500 ms, and on stream end.
                if (++unsaved >= SAVE_EVERY_EVENTS || System.nanoTime() - savedAt >= SAVE_EVERY_NS) save()
            }
            return next
        }

        private suspend fun apply(event: RunEvent): Next? {
            when (event) {
                is RunEvent.MessageDelta -> edit(run) { it.copy(text = it.text + event.delta) }
                is RunEvent.MessageInterim -> if (!event.alreadyStreamed) edit(run) { it.copy(text = it.text + event.text) }
                is RunEvent.ToolStarted -> {
                    toolStarts[event.toolCallId] = event.timestamp
                    val step = ToolStep(event.toolCallId, event.tool, event.preview, null, resultTruncated = false, error = false, running = true)
                    edit(run) { it.copy(steps = it.steps + step) }
                }
                is RunEvent.ToolCompleted -> {
                    // The server's duration is always 0, so time the step from the two events.
                    val started = toolStarts.remove(event.toolCallId)
                    val duration = if (started != null && started > 0 && event.timestamp > 0) (event.timestamp - started).takeIf { it >= 0 } else null
                    edit(run) { it.copy(steps = it.steps.completed(event, duration)) }
                }
                // The event's ids are never used: pending controls come only from a status read.
                is RunEvent.ApprovalRequested -> return readControls()
                is RunEvent.Other -> if (event.name in CONTROL_EVENTS) return readControls()
                is RunEvent.Truncated -> return fallBackToPolling()
                is RunEvent.Terminal -> {
                    db.activeRunDao().setStatus(id, stored(event.status)) // before Finish: the queue must not steer into it
                    return Next.Finish(event.status)
                }
                is RunEvent.Reasoning, is RunEvent.Steered -> Unit
            }
            return null
        }

        /** Null when the status was read; Check (retry with backoff, event not stored) when it failed. */
        private suspend fun readControls(): Next? = try {
            applyStatus(run, io { api.runStatus(id) })
            null
        } catch (e: ApiError) {
            if (e == ApiError.Unauthorized) throw e
            Next.Check
        }

        /** Stored before anything else can suspend or be cancelled: once the stream is gone, it is never requested again. */
        private suspend fun fallBackToPolling(): Next {
            if (!polling) {
                polling = true
                withContext(NonCancellable) { db.activeRunDao().setPolling(id) }
            }
            return Next.Poll
        }

        private suspend fun save() {
            val seq = lastSeq ?: return
            if (unsaved == 0) return
            unsaved = 0
            savedAt = System.nanoTime()
            db.activeRunDao().updateLastSeq(id, seq)
        }

        private suspend fun status(): Next {
            val s = try {
                io { api.runStatus(id) }
            } catch (e: ApiError) {
                return when (e) {
                    ApiError.Unauthorized -> throw e
                    ApiError.NotFound -> Next.Finish(null)
                    else -> Next.Check
                }
            }
            applyStatus(run, s)
            return when (s.status) {
                in TERMINAL -> Next.Finish(s.status)
                "queued" -> Next.Queued
                "unknown" -> Next.Unknown
                else -> Next.Attach // running, stopping, waiting_for_approval and anything new
            }
        }

        /**
         * Syncs the transcript and, in the same transaction, settles the run's pending sends, records the outcome and
         * deletes the run. False (rows kept, retried) only for a transient failure; any other failure still ends the
         * run, so the session's queue is never blocked, but then nothing counts as delivered. Only the fetch is
         * cancellable: once the write has committed, the hand-off always completes.
         */
        suspend fun finish(status: String?, sync: Boolean = true): Boolean {
            var committed = false
            try {
                val replaced = sync && try {
                    repo.syncMessages(key) { rows ->
                        write(status, buildTranscript(rows))
                        committed = true
                    }
                } catch (e: ApiError) {
                    when (e) {
                        ApiError.Unauthorized -> throw e
                        is ApiError.Network, is ApiError.Unavailable -> return false
                        else -> false
                    }
                } catch (_: IllegalArgumentException) {
                    false
                }
                if (!replaced) {
                    withContext(NonCancellable) {
                        withRepositoryLock { db.withTransaction { write(status, items = null) } }
                        committed = true
                    }
                }
            } finally {
                if (committed) withContext(NonCancellable) { handOff() }
            }
            return true
        }

        /**
         * Inside the repository lock and a transaction. A pending send is removed only when the transcript the user
         * would see ([items]; null: unknown) shows it; otherwise it becomes failed `unconfirmed` and stays on screen.
         */
        private suspend fun write(status: String?, items: List<ChatItem>?) {
            beforeFinishWrite()
            val stopped = db.activeRunDao().get(id)?.stopRequested == true
            val outcome = when (status) {
                "failed" -> ReplyOutcome.FAILED
                "interrupted" -> ReplyOutcome.INTERRUPTED
                "cancelled" -> if (stopped) ReplyOutcome.STOPPED else ReplyOutcome.INTERRUPTED
                else -> null
            }
            val shown = items.orEmpty().filterIsInstance<UserMessage>()
            val used = HashSet<String>() // one bubble confirms one send; a merged steer bubble confirms every steer in it
            val pendingDao = db.pendingSendDao()
            for (p in pendingDao.forRun(id)) {
                val text = p.text.trim()
                val since = p.createdAt / 1000.0 - DELIVERY_WINDOW_S
                val confirmed = when (p.state) {
                    PendingState.SENDING -> shown.firstOrNull { !it.steered && it.key !in used && it.timestamp >= since && it.text.trim() == text }
                        ?.also { used += it.key } != null
                    PendingState.STEERED -> shown.any { it.steered && it.timestamp >= since && it.text.contains(text) }
                    else -> false
                }
                if (confirmed) {
                    pendingDao.delete(p.idempotencyKey)
                } else {
                    pendingDao.update(p.copy(state = PendingState.FAILED, error = SendError.UNCONFIRMED, runId = null))
                }
            }
            if (outcome != null) {
                db.replyOutcomeDao().upsert(ReplyOutcomeEntity(key, db.messageDao().maxId(key) ?: 0, outcome.name, detail = null))
            }
            db.activeRunDao().delete(id)
        }

        private suspend fun handOff() {
            seqs -= id
            state(key).update { if (it?.runId == id) null else it }
            try {
                onRunFinished(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The run is finished and cleaned up; a failing drain must not undo that.
            }
        }
    }
}

private fun List<ToolStep>.completed(e: RunEvent.ToolCompleted, duration: Double?): List<ToolStep> {
    val i = indexOfFirst { it.callId == e.toolCallId }
    val done = (getOrNull(i) ?: ToolStep(e.toolCallId, e.tool, "", null, resultTruncated = false, error = false, running = false))
        .copy(result = e.preview, error = e.error, running = false, durationSeconds = duration)
    return if (i < 0) this + done else toMutableList().also { it[i] = done }
}

/** Swallows transient API errors; a refused key still stops the follower. */
private suspend fun quietly(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ApiError) {
        if (e == ApiError.Unauthorized) throw e
    }
}
