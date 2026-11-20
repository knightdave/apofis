package xyz.rycerz.apofis.run

import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.RunEvent
import xyz.rycerz.apofis.api.RunStatus
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.ReplyOutcomeEntity
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.RunStarter
import xyz.rycerz.apofis.sync.withRepositoryLock

private val TERMINAL = setOf("completed", "failed", "cancelled", "interrupted")
private const val FIRST_RETRY_MS = 1_000L
private const val MAX_RETRY_MS = 30_000L
private const val POLL_MS = 3_000L
private const val SAVE_EVERY_EVENTS = 20
private const val SAVE_EVERY_NS = 500_000_000L

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
    private val jobs = HashMap<String, Job>() // by run id, guarded by itself
    private val seqs = ConcurrentHashMap<String, Long>() // last applied seq by run id; matches the live text across pauseAll
    private val stopRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val rejected = MutableStateFlow(false)

    /** True once the server refused the API key; the runs stay stored and [resumeAll] tries again. */
    val credentialsRejected: StateFlow<Boolean> = rejected.asStateFlow()

    fun live(sessionKey: String): StateFlow<LiveReply?> = state(sessionKey).asStateFlow()

    // Called by the send queue under the repository lock: it only starts a coroutine.
    override fun onRunStarted(sessionKey: String, runId: String) =
        start(ActiveRunEntity(runId, sessionKey, lastSeq = null, status = "running", startedAt = 0L), Next.Attach)

    /** Call on app start and every return to the foreground. */
    suspend fun resumeAll() {
        rejected.value = false
        db.activeRunDao().all().forEach { start(it, Next.Check) }
    }

    /** Call when the app leaves the foreground: cancels the streams and keeps every stored run. */
    fun pauseAll() = synchronized(jobs) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    suspend fun stop(sessionKey: String) {
        val run = db.activeRunDao().forSession(sessionKey) ?: return
        // Stored too, so a cancel seen after a restart still counts as this user's stop.
        stopRequested += run.runId
        db.activeRunDao().setStatus(run.runId, "stopping")
        edit(run) { it.copy(status = "stopping") }
        call { api.stop(run.runId) }
    }

    /** Answers with the ids the server lists in `pending_controls` now, never ids from an event. */
    suspend fun answerApproval(sessionKey: String, choice: ApprovalChoice) {
        val run = db.activeRunDao().forSession(sessionKey) ?: return
        val control = refresh(run).pendingControls.firstOrNull { it.kind == "approval" } ?: return
        try {
            call { api.approve(run.runId, control.requestId, control.executionGeneration, choice) }
            edit(run) { it.copy(pending = null) }
        } catch (_: ApiError.Conflict) {
            // Answered elsewhere, expired, or a newer generation: show what the server waits for now.
            edit(run) { it.copy(pending = null) }
            refresh(run)
        }
    }

    /** For a run whose status is `unknown`: resolves it with the ids from the status, then ends it as interrupted. */
    suspend fun resolveUnknown(sessionKey: String) {
        val run = db.activeRunDao().forSession(sessionKey) ?: return
        val s = refresh(run)
        if (s.status != "unknown") return start(run, Next.Check)
        val admission = s.admissionId ?: throw ApiError.Protocol
        val generation = s.executionGeneration ?: throw ApiError.Protocol
        call { api.resolveUnknown(run.runId, admission, generation) }
        start(run, Next.Finish("interrupted"), replace = true)
    }

    private fun state(sessionKey: String) = lives.getOrPut(sessionKey) { MutableStateFlow(null) }

    private fun edit(run: ActiveRunEntity, change: (LiveReply) -> LiveReply) =
        state(run.sessionKey).update { if (it?.runId == run.runId) change(it) else it }

    private suspend fun refresh(run: ActiveRunEntity): RunStatus =
        call { api.runStatus(run.runId) }.also { s -> edit(run) { it.with(s) } }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiError.Unauthorized) {
        rejected.value = true
        throw e
    }

    private fun start(run: ActiveRunEntity, first: Next, replace: Boolean = false) {
        state(run.sessionKey).update {
            if (it?.runId == run.runId) it else LiveReply(run.runId, run.status, "", emptyList(), null, Connection.LIVE)
        }
        synchronized(jobs) {
            val old = jobs[run.runId]
            if (old?.isActive == true && !replace) return
            old?.cancel()
            jobs[run.runId] = scope.launch { Follow(run, first).run() }
        }
    }

    private inner class Follow(private val run: ActiveRunEntity, private var next: Next) {
        private val id = run.runId
        private val key = run.sessionKey
        private var lastSeq = seqs[id] ?: run.lastSeq
        private var backoff = if (next == Next.Check) 0L else FIRST_RETRY_MS // a resume checks the status at once
        private var unsaved = 0
        private var savedAt = System.nanoTime()
        private val toolStarts = HashMap<String, Double>()

        suspend fun run() {
            try {
                // shortcut: after a restart the stream resumes at lastSeq, so earlier text of this reply shows only
                // as far as the server already stored it, until the run ends and the transcript is synced.
                if (next == Next.Check && run.lastSeq != null && state(key).value?.text.isNullOrEmpty()) quietly { repo.syncMessages(key) }
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
                        Next.Unknown -> {
                            db.activeRunDao().setStatus(id, "unknown") // also keeps the send queue from steering into it
                            edit(run) { it.copy(status = "unknown") }
                            return // waits for resolveUnknown
                        }
                        is Next.Finish -> if (finish(step.status)) return else step.also { retryWait() }
                    }
                }
            } catch (_: ApiError.Unauthorized) {
                rejected.value = true // the row stays; resumeAll tries again
            }
        }

        private suspend fun retryWait() {
            if (backoff > 0) delayMs(backoff)
            backoff = (backoff * 2).coerceIn(FIRST_RETRY_MS, MAX_RETRY_MS)
        }

        private suspend fun attach(): Next {
            var next: Next = Next.Check // a stream that ends without a terminal event is a drop, not the run's end
            try {
                api.runEvents(id, lastSeq).takeWhile { event -> handle(event)?.also { next = it } == null }.collect()
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) throw e
                if (e == ApiError.NotFound) next = Next.Poll
            } finally {
                withContext(NonCancellable) { save() }
            }
            return next
        }

        /** Applies [event]; returns the next step when the stream should be left. */
        private suspend fun handle(event: RunEvent): Next? {
            val seq = event.seq.takeIf { it >= 0 } // frames without a sequence number are applied but never stored
            if (seq != null && lastSeq.let { it != null && seq <= it }) return null // already applied
            backoff = FIRST_RETRY_MS
            if (state(key).value?.connection != Connection.LIVE) edit(run) { it.copy(connection = Connection.LIVE) }
            val next = apply(event)
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
                is RunEvent.ApprovalRequested -> quietly { refresh(run) }
                is RunEvent.Other -> if (event.name.endsWith(".settled")) edit(run) { it.copy(pending = null) }
                is RunEvent.Truncated -> return Next.Poll
                is RunEvent.Terminal -> return Next.Finish(event.status)
                is RunEvent.Reasoning, is RunEvent.Steered -> Unit
            }
            return null
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
                api.runStatus(id)
            } catch (e: ApiError) {
                return when (e) {
                    ApiError.Unauthorized -> throw e
                    ApiError.NotFound -> Next.Finish(null)
                    else -> Next.Check
                }
            }
            edit(run) { it.with(s) }
            return when (s.status) {
                in TERMINAL -> Next.Finish(s.status)
                "queued" -> Next.Queued
                "unknown" -> Next.Unknown
                else -> Next.Attach // running, stopping, waiting_for_approval and anything new
            }
        }

        /**
         * Syncs the transcript first, so the user's text is never off screen; false (rows kept) when that fails.
         * The cleanup holds the repository lock, so it never interleaves with a steer into this run.
         */
        private suspend fun finish(status: String?): Boolean {
            try {
                repo.syncMessages(key)
            } catch (e: ApiError) {
                if (e == ApiError.Unauthorized) throw e
                return false
            }
            withRepositoryLock {
                val stopped = id in stopRequested || db.activeRunDao().forSession(key)?.takeIf { it.runId == id }?.status == "stopping"
                val outcome = when (status) {
                    "failed" -> ReplyOutcome.FAILED
                    "interrupted" -> ReplyOutcome.INTERRUPTED
                    "cancelled" -> if (stopped) ReplyOutcome.STOPPED else ReplyOutcome.INTERRUPTED
                    else -> null
                }
                db.withTransaction {
                    db.pendingSendDao().deleteForRun(id)
                    if (outcome != null) {
                        db.replyOutcomeDao().upsert(ReplyOutcomeEntity(key, db.messageDao().maxId(key) ?: 0, outcome.name, detail = null))
                    }
                    db.activeRunDao().delete(id)
                }
            }
            stopRequested -= id
            seqs -= id
            state(key).update { if (it?.runId == id) null else it }
            try {
                onRunFinished(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The run is finished and cleaned up; a failing drain must not undo that.
            }
            return true
        }
    }
}

private fun LiveReply.with(s: RunStatus) = copy(status = s.status, pending = s.pendingControls.firstOrNull())

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
