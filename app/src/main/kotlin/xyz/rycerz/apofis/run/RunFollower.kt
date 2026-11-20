package xyz.rycerz.apofis.run

import android.database.SQLException
import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.ControlResult
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.PendingControl
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
import xyz.rycerz.apofis.sync.deliveredIn
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
    private val jobs = HashMap<String, Job>() // by run id; it also guards [foreground] and [stopping]
    private val stopping = HashMap<String, Job>() // cancelled jobs still completing, by run id; waited for before a restart
    @Volatile private var foreground = false // opened by resumeAll, closed by pauseAll; no follower starts while closed
    private val seqs = ConcurrentHashMap<String, Long>() // last applied seq by run id; matches the live text across pauseAll
    private val rejected = MutableStateFlow(false)
    private val refreshers = ConcurrentHashMap<String, Refresher>() // by run id

    /** True once the server refused the API key; the runs stay stored. Cleared by the next request that succeeds. */
    val credentialsRejected: StateFlow<Boolean> = rejected.asStateFlow()

    /** Test-only: runs at the start of Finish's database phase, to cancel or fail it there. */
    internal var beforeFinishWrite: suspend RunFollower.() -> Unit = {}

    /** Test-only: runs after a status read has returned and been recorded, before its result is applied. */
    internal var beforeStatusApply: suspend RunFollower.() -> Unit = {}

    /** Test-only: runs with each observed status at the start of its (non-cancellable) store, to pause it there. */
    internal var beforeStatusStore: suspend RunFollower.(status: String) -> Unit = {}

    /** True while a status read for [runId] is requested and not yet applied; for tests. */
    internal fun refreshOwed(runId: String): Boolean = refreshers[runId]?.owed == true

    /** Run ids with a follower job; for tests. */
    internal val following: Set<String> get() = synchronized(jobs) { jobs.keys.toSet() }

    fun live(sessionKey: String): StateFlow<LiveReply?> = state(sessionKey).asStateFlow()

    // Called by the send queue under the repository lock: it only starts a coroutine. While the app is in the
    // background it does nothing; the stored row is followed on the next resumeAll.
    override fun onRunStarted(sessionKey: String, runId: String) = start(runId, Next.Attach)

    /** Call on app start and every return to the foreground. */
    suspend fun resumeAll() {
        val completing = synchronized(jobs) {
            foreground = true
            stopping.values.toList()
        }
        // A paused job may still be writing (lastSeq, polling): read the rows only once it has finished.
        completing.joinAll()
        db.activeRunDao().all().forEach { start(it.runId, Next.Check) } // start checks the gate again
        refreshers.values.forEach { it.resume() }
    }

    /** Call when the app leaves the foreground: cancels the streams and keeps every stored run. */
    fun pauseAll() {
        val running = synchronized(jobs) {
            foreground = false
            stopping.putAll(jobs)
            jobs.values.toList().also { jobs.clear() }
        }
        // Outside the lock and over a copy: a job may complete inside cancel() and run its completion handler.
        running.forEach { it.cancel() }
        refreshers.values.forEach { it.stopWorker() } // an unserved request survives for resumeAll
    }

    /**
     * For the send worker while the app is in the background, where no follower runs: one status read of [runId]. A run
     * that ended, or that the server no longer knows, is finished by the same transcript transaction as a follower's
     * Finish, whose hand-off drains the queue. No stream is opened; a run still going is left for a later check.
     */
    suspend fun settle(runId: String) {
        if (foreground) return // a follower owns it
        val run = db.activeRunDao().get(runId) ?: return
        val status = try {
            refresher(run).read().status
        } catch (_: ApiError.NotFound) {
            null
        }
        if (status == null || status in TERMINAL) Follow(run, Next.Finish(status)).finish(status)
    }

    suspend fun stop(sessionKey: String) {
        stop(db.activeRunDao().forSession(sessionKey) ?: return)
    }

    /** Stops every active run of the chat, one request each, in order; the first failure throws and stops the rest. */
    suspend fun stopAll(sessionKey: String) {
        db.activeRunDao().allForSession(sessionKey).forEach { stop(it) }
    }

    private suspend fun stop(run: ActiveRunEntity) {
        // The intent is stored apart from the server's status, so a cancel seen after a restart still counts as this
        // user's stop, and a stop by another client never does.
        // The status itself changes only when the server reports it, through the refresher.
        val refresher = refresher(run)
        refresher.stop { io(run.runId) { api.stop(run.runId) } }
        refresher.request()
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
        val s = refresher(run).read()
        if (s.status != "unknown") return start(run.runId, Next.Check)
        val admission = s.admissionId ?: throw ApiError.Protocol
        val generation = s.executionGeneration ?: throw ApiError.Protocol
        io(run.runId) { api.resolveUnknown(run.runId, admission, generation) }
        start(run.runId, Next.Finish("interrupted"), replace = true)
    }

    private suspend fun answer(sessionKey: String, requestId: String, generation: Long, post: suspend (runId: String) -> ControlResult): AnswerResult {
        val run = db.activeRunDao().forSession(sessionKey) ?: return AnswerResult.GONE
        val refresher = refresher(run)
        fun same(c: PendingControl) = c.requestId == requestId && c.executionGeneration == generation
        val before = refresher.read().pendingControls
        if (before.none(::same)) return changedOrGone(before)
        // The POST decides first: an accepted answer is SENT whatever its follow-up read does.
        val unclear: ApiError? = try {
            if (io(run.runId) { post(run.runId) } == ControlResult.RESOLVED) {
                refresher.request()
                return AnswerResult.SENT
            }
            null // already resolved
        } catch (_: ApiError.Conflict) {
            null // answered elsewhere, expired, or a newer generation
        } catch (e: ApiError.Protocol) {
            e // an undocumented body: the answer may or may not have been taken
        } catch (e: ApiError.Network) {
            e // the request or its response was cut off: the same
        }
        val after = refresher.readOrRetry().pendingControls
        if (after.any(::same)) throw unclear ?: ApiError.Protocol // said resolved, yet still pending
        return changedOrGone(after)
    }

    private fun changedOrGone(pending: List<*>) = if (pending.isEmpty()) AnswerResult.GONE else AnswerResult.CHANGED

    private fun state(sessionKey: String) = lives.getOrPut(sessionKey) { MutableStateFlow(null) }

    private fun edit(run: ActiveRunEntity, change: (LiveReply) -> LiveReply) =
        state(run.sessionKey).update { if (it?.runId == run.runId) change(it) else it }

    private fun refresher(run: ActiveRunEntity): Refresher = refreshers.computeIfAbsent(run.runId) { Refresher(run) }

    /**
     * The only writer of a run's displayed pending controls and stored status. Every status read goes through
     * [read]; it takes a ticket when it starts and is applied only if no read with a higher ticket has been applied,
     * so a slow or retried read never overwrites a newer one. A failed read changes nothing.
     */
    private inner class Refresher(private val run: ActiveRunEntity) {
        private val tickets = AtomicLong()
        private val applying = Mutex()
        private var applied = 0L // guarded by [applying]
        private val stopping = Mutex() // guards the two fields below and orders the stop intent's writes
        private var stopsInFlight = 0
        private var stopAccepted = false // by the server, for this run; also true for an intent stored before a restart
        private var owedAfter = -1L // a read with a higher ticket is still wanted (-1: none); guarded by this
        private var worker: Job? = null // guarded by this

        /** One status read; returns its own result even when a newer one has already been applied. */
        suspend fun read(): RunStatus {
            val ticket = tickets.incrementAndGet()
            val s = io(run.runId) { api.runStatus(run.runId) }
            val pending = s.pendingControls.firstOrNull()
            // A terminal status is published before anything that can be cancelled: the send queue must never steer into it.
            // A publish that fails to store is not lost: the next read tries again.
            if (s.status in TERMINAL && !publish(s.status, pending, ticket)) request(retry = true)
            beforeStatusApply()
            if (s.status !in TERMINAL && !publish(s.status, pending, ticket)) request(retry = true)
            // Only now: a read cancelled before its result was applied leaves the request standing.
            synchronized(this) { if (ticket > owedAfter) owedAfter = -1L }
            return s
        }

        /**
         * The one writer of the run's status, under [applying] and not cancellable: stores [status] (a conditional
         * update that never replaces a terminal status), reads the row back and shows exactly what it holds, so the
         * screen and the send queue always read the same row. A rejected store shows the row's status (a gone row
         * shows no reply). A non-terminal status from an older read ([ticket]) than one already applied is ignored; a
         * stream event passes no ticket. False when the database failed: nothing was shown and the caller retries.
         */
        suspend fun publish(status: String, pending: PendingControl?, ticket: Long? = null): Boolean = withContext(NonCancellable) {
            try {
                beforeStatusStore(status)
                applying.withLock {
                    val ordered = ticket != null && status !in TERMINAL
                    if (ordered && ticket!! <= applied) return@withLock
                    val held = db.activeRunDao().storeStatus(run.runId, stored(status))
                    if (ordered) applied = ticket!!
                    if (held == null) {
                        state(run.sessionKey).update { if (it?.runId == run.runId) null else it }
                    } else {
                        val accepted = held == stored(status)
                        edit(run) { it.copy(status = held, pending = if (accepted) pending else null) }
                    }
                }
                true
            } catch (_: SQLException) {
                false
            }
        }

        /**
         * One stop request ([post]), not cancellable as a whole, so a cancelled caller never loses an accepted stop.
         * The intent is stored before the request is sent (if it cannot be, nothing is sent and the error surfaces).
         * Accepted: the intent stays, whatever other requests do. Failed: it is cleared only if no other stop is in
         * flight and none was accepted. The in-flight count is kept in try/finally, so no exception can leak it.
         */
        suspend fun stop(post: suspend () -> Unit): Unit = withContext(NonCancellable) {
            stopping.withLock { stopsInFlight++ }
            var accepted = false
            var failed = false
            try {
                stopping.withLock {
                    if (stopsInFlight == 1 && !stopAccepted) {
                        stopAccepted = db.activeRunDao().get(run.runId)?.stopRequested == true // from before a restart
                    }
                    db.activeRunDao().setStopRequested(run.runId)
                }
                try {
                    post()
                } catch (e: ApiError) {
                    failed = true
                    throw e
                }
                accepted = true
            } finally {
                stopping.withLock {
                    stopsInFlight--
                    if (accepted) {
                        stopAccepted = true
                        db.activeRunDao().setStopRequested(run.runId)
                    } else if (failed && stopsInFlight == 0 && !stopAccepted) {
                        db.activeRunDao().clearStopRequested(run.runId)
                    }
                }
            }
        }

        /** [read]; a failure (other than a refused key) also schedules a retry. */
        suspend fun readOrRetry(): RunStatus = try {
            read()
        } catch (e: ApiError) {
            if (e != ApiError.Unauthorized) request(retry = true)
            throw e
        }

        /** Notes that a read starting from now is wanted, without asking for one yet. */
        fun owe(): Unit = synchronized(this) { owedAfter = tickets.get() }

        val owed: Boolean get() = synchronized(this) { owedAfter >= 0 }

        /**
         * Asks for a read (after the backoff when [retry]: one just failed); requests are coalesced, and any read
         * started after a request serves it. Failures retry with backoff until one succeeds, the app leaves the
         * foreground, the run finishes or the key is refused.
         */
        fun request(retry: Boolean = false): Unit = synchronized(this) {
            owe()
            if (worker?.isActive != true) worker = scope.launch { work(if (retry) FIRST_RETRY_MS else 0L) }
        }

        /** On return to the foreground: an unserved request, or a displayed control, is read again. */
        fun resume() {
            val shown = state(run.sessionKey).value?.let { it.runId == run.runId && it.pending != null } == true
            if (shown || owed) request()
        }

        fun stopWorker() {
            synchronized(this) { worker }?.cancel()
        }

        private suspend fun work(firstWait: Long) {
            var wait = firstWait
            while (true) {
                if (wait > 0) delayMs(wait)
                synchronized(this) {
                    // Under the lock, so a request arriving after this decision always launches a new worker.
                    if (owedAfter < 0 || !foreground || refreshers[run.runId] !== this) {
                        worker = null
                        return
                    }
                }
                wait = try {
                    read()
                    0L
                } catch (e: ApiError) {
                    if (e == ApiError.Unauthorized || e == ApiError.NotFound) return synchronized(this) { worker = null }
                    (wait * 2).coerceIn(FIRST_RETRY_MS, MAX_RETRY_MS)
                }
            }
        }
    }

    /**
     * Every direct HermesApi request for a run goes through here. Its outcome is recorded once, right as it returns
     * or throws; a refused key is handled by [rejectedFor].
     */
    private suspend fun <T> io(runId: String, block: suspend () -> T): T {
        val result = try {
            block()
        } catch (e: ApiError.Unauthorized) {
            rejectedFor(runId)
            throw e
        }
        recordKey(accepted = true)
        return result
    }

    /** The one handling of a refused key for a run: recorded, its stream job and refresher stopped, its row kept. */
    private fun rejectedFor(runId: String) {
        recordKey(accepted = false)
        synchronized(jobs) { jobs.remove(runId)?.also { stopping[runId] = it } }?.cancel()
        refreshers[runId]?.stopWorker()
    }

    /**
     * The only writer of [credentialsRejected], called with each HTTP outcome as that call returns. One atomic
     * write, so of two outcomes the later-completed one wins.
     */
    private fun recordKey(accepted: Boolean) {
        rejected.value = !accepted
    }

    private fun start(runId: String, first: Next, replace: Boolean = false): Unit = synchronized(jobs) {
        if (!foreground) return
        val old = jobs[runId]
        if (old?.isActive == true && !replace) return
        old?.cancel()
        val previous = old ?: stopping[runId]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Not cancellable, so a cancelled waiter still ends after its predecessor: joining the newest job is enough.
            withContext(NonCancellable) { previous?.join() }
            // Everything the job uses is read here, after the join: the earlier job may have set polling, moved
            // lastSeq, stored a status or the stop intent, or finished and deleted the run.
            val row = db.activeRunDao().get(runId) ?: return@launch
            Follow(row, first).run()
        }
        jobs[runId] = job
        job.invokeOnCompletion {
            synchronized(jobs) {
                if (jobs[runId] === job) jobs.remove(runId)
                if (stopping[runId] === job) stopping.remove(runId)
            }
        }
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
        private val toolStarts = HashMap<String, Double>()

        suspend fun run() {
            state(key).update { if (it?.runId == id) it else LiveReply(id, run.status, "", emptyList(), null, Connection.LIVE) }
            try {
                // shortcut: after a restart the stream resumes at lastSeq, so earlier text of this reply shows only
                // as far as the server already stored it, until the run ends and the transcript is synced.
                if (next == Next.Check && run.lastSeq != null && state(key).value?.text.isNullOrEmpty()) syncQuietly()
                if (polling && next !is Next.Finish) next = Next.Poll // a run that lost its stream never reattaches
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
                            syncQuietly()
                            when (val n = status()) {
                                is Next.Finish, Next.Unknown -> n
                                else -> Next.Poll
                            }
                        }
                        Next.Unknown -> return // status() stored it; waits for resolveUnknown
                        is Next.Finish -> {
                            if (finish(step.status)) return
                            // No cap while in the foreground: connectivity may come back without a resume. pauseAll ends it.
                            retryWait()
                            step
                        }
                    }
                }
            } catch (_: ApiError.Unauthorized) {
                // Already recorded where the request returned; the row stays for a later resumeAll.
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
            var answered = false
            try {
                api.runEvents(id, lastSeq).takeWhile { event ->
                    // The response was accepted when its first frame arrived, not when the stream ends much later.
                    if (!answered) {
                        answered = true
                        recordKey(accepted = true)
                    }
                    handle(event)?.also { next = it } == null
                }.collect()
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) {
                    rejectedFor(id)
                    throw e
                }
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
                    // Stored at once and not cancellable: the send queue must never steer into a finished run.
                    if (!refresher(run).publish(event.status, pending = null)) return Next.Check // not stored: read the status again
                    return Next.Finish(event.status)
                }
                is RunEvent.Reasoning, is RunEvent.Steered -> Unit
            }
            return null
        }

        /** Null when the status was read; Check (retry with backoff, event not stored) when it failed. */
        private suspend fun readControls(): Next? = try {
            refresher(run).read()
            null
        } catch (e: ApiError) {
            if (e == ApiError.Unauthorized) throw e
            refresher(run).owe() // served by the Check read, or after resumeAll
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
                refresher(run).read()
            } catch (e: ApiError) {
                return when (e) {
                    ApiError.Unauthorized -> throw e
                    ApiError.NotFound -> Next.Finish(null)
                    else -> Next.Check
                }
            }
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
            try {
                if (sync) {
                    var wrote = false
                    try {
                        // The hand-off runs inside the repository call, only after its transaction has committed.
                        // The fetch's success is recorded as it returns, before the database phase and the drain.
                        repo.syncMessages(key, onFetched = { recordKey(accepted = true) }, afterCommit = { handOff() }) { rows ->
                            write(status, buildTranscript(rows))
                            wrote = true
                        }
                    } catch (e: ApiError) {
                        when (e) {
                            ApiError.Unauthorized -> {
                                rejectedFor(id)
                                throw e
                            }
                            is ApiError.Network, is ApiError.Unavailable -> return false
                            else -> Unit // permanent: finish without a transcript to check against
                        }
                    } catch (_: IllegalArgumentException) {
                        // the same
                    }
                    if (wrote) return true
                }
                withContext(NonCancellable) {
                    withRepositoryLock { db.withTransaction { write(status, items = null) } }
                    handOff() // reached only when the transaction returned normally
                }
                return true
            } catch (_: SQLException) {
                return false // rolled back: nothing was cleared or handed off; retried like a transient failure
            }
        }

        private suspend fun syncQuietly() = quietly {
            val fetched = try {
                repo.syncMessages(key)
            } catch (e: ApiError.Unauthorized) {
                rejectedFor(id)
                throw e
            }
            if (fetched) recordKey(accepted = true)
        }

        /**
         * Inside the repository lock and a transaction. A pending send is removed only when the transcript the user
         * would see ([items]; null: unknown) shows it; otherwise it becomes failed `unconfirmed` and stays on screen.
         */
        private suspend fun write(status: String?, items: List<ChatItem>?) {
            beforeFinishWrite()
            // Still owned: a confirmed delete or a cache clear (both under this lock) removed the row, and nothing of the
            // run may come back. There is no foreign key to refuse an orphan outcome.
            val owned = db.activeRunDao().get(id)?.takeIf { db.sessionDao().get(key) != null } ?: return
            val stopped = owned.stopRequested
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
                if (deliveredIn(p, shown, used)) {
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
            refreshers -= id
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
