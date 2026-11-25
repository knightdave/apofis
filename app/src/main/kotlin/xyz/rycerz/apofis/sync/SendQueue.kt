package xyz.rycerz.apofis.sync

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.MAX_BODY_BYTES
import xyz.rycerz.apofis.api.MAX_EXPANDED_LENGTH
import xyz.rycerz.apofis.api.MAX_IMAGES
import xyz.rycerz.apofis.api.SessionDto
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.api.expandImageRefs
import xyz.rycerz.apofis.api.expandedSize
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.db.choice
import xyz.rycerz.apofis.notify.NoticeNotDelivered

/** Implemented by Task 8: told about every run this queue started. */
fun interface RunStarter {
    fun onRunStarted(sessionKey: String, runId: String)
}

const val SEND_EXPIRY_MS = 24L * 60 * 60 * 1000
private const val TITLE_TRIES = 9 // titles tried when the first line is already another chat's title

/**
 * [PendingSendEntity.state] values. In the transcript: [QUEUED] is QUEUED, [SENDING] is SENT, [FAILED] is FAILED,
 * and the steer states are a `UserMessage` with `steered = true`: [STEERING] is SENDING, [STEERED] is SENT.
 */
object PendingState {
    const val QUEUED = "queued"
    const val SENDING = "sending" // the run started; the run follower deletes the row when the run ends
    const val FAILED = "failed"
    const val STEERING = "steering" // the steer request is in flight
    const val STEERED = "steered" // delivered to the running run; deleted with the run's other rows by runId
}

/** Every [PendingSendEntity.error] value. Chosen here, never taken from the server or an exception. */
object SendError {
    const val EXPIRED = "expired"
    const val EXPIRED_UNCERTAIN = "expired_uncertain" // expired after a request the server may have accepted
    const val STEER_UNCERTAIN = "steer_uncertain" // the only error of a failed steer the server may have delivered
    const val UNCONFIRMED = "unconfirmed" // its run ended but the stored transcript does not show the message
    const val PERMISSION_DENIED = "permission_denied"
    const val SESSION_DELETED = "session_deleted"
    const val SESSION_UNAVAILABLE = "session_unavailable"
    const val CONFLICT = "conflict"
    const val UNAUTHORIZED = "unauthorized"
    const val NOT_FOUND = "not_found"
    const val PROTOCOL = "protocol"
    const val HTTP_ERROR = "http_error"
    const val IMAGE_MISSING = "image_missing" // a file of its images is gone or changed: nothing was sent for it
    const val STEER_NOT_DELIVERED = "steer_not_delivered" // a /steer whose run ended or refused it: not sent, never a run send
}

/** Thrown by [SendQueue.submit] for a send over [MAX_ROW_BYTES] or, expanded, over MAX_BODY_BYTES: nothing was stored. */
class SendTooLarge : Exception("too large")

// Text and request body are stored in one row, and a row must fit Android's 2 MiB cursor window or the queue cannot be
// read; the session id and model choice ride in the margin.
const val MAX_ROW_BYTES = 1_500_000

enum class RetryResult { STARTED, NEEDS_CONFIRMATION, NOT_FOUND }

/** What [SendQueue.cancel] did, read under the drain lock, so the UI can say it truthfully. */
enum class CancelResult {
    REMOVED,
    REMOVED_MAY_HAVE_SENT, // removed from this phone, but a request for it may already have reached the server
    STARTED, // already being sent or delivered: nothing was removed
    NOT_FOUND,
    NOW_ATTEMPTED, // a request for it may have gone out since the user was told it had not: nothing was removed
}

// Process-wide like the repository lock: the app foreground and SendWorker must never start the same send twice.
// Lock order: drainLock, then the repository lock.
private val drainLock = Mutex()

// Held only while a send is armed and stored; never while a request is in flight. Taken after drainLock, never before.
private val handoff = Mutex()

private enum class Attempt { STARTED, FAILED, STOP }

/** How a drain ended: nothing left to send; work left (behind busy chats, or a steer deferred); or stopped at a failure that stops all. */
private enum class Drained { EMPTY, BLOCKED, STOPPED }

private val UNCERTAIN_ERRORS = setOf(SendError.STEER_UNCERTAIN, SendError.UNCONFIRMED, SendError.EXPIRED_UNCERTAIN)

/**
 * For a switch to another API key at the same server, after the old graph is closed: quarantines attempted sends
 * (see [xyz.rycerz.apofis.db.PendingSendDao.quarantineAttempted]) under drainLock and the repository lock, so no
 * drain, retry or cancel of the old graph can be between its read and its write.
 */
internal suspend fun quarantineAttemptedSends(db: AppDatabase) =
    drainLock.withLock { withRepositoryLock { db.pendingSendDao().quarantineAttempted() } }

/** Thrown inside the locks once the owning connection is closed. Writes pass it on; [SendQueue.drain] returns false. */
private class QueueClosed : IllegalStateException("closed")

/** Thrown by a steer the server refused for the key: like a queued send's 401, it stops the drain. */
private class KeyRefused : Exception()

/**
 * The durable path from "send" to "run started". Each message is stored with its exact request body and
 * idempotency key before any request, so a retry is byte-identical and the server never runs it twice.
 * [schedule] is called whenever a send is left queued; the app passes `{ SendWorker.schedule(context) }`.
 * [active] turns false when the app switches servers. From then on every write ([enqueue], [submit], [cancel], [retry])
 * throws IllegalStateException("closed") and [drain] returns false, each checked inside the lock of the write and again
 * before every request, so a queue bound to the old server never sends or changes rows that belong to the new one.
 * [readImage] and [imageSize] give an image file's bytes and size by digest, null when it is missing.
 */
class SendQueue(
    private val api: HermesApi,
    private val db: AppDatabase,
    private val starter: RunStarter,
    private val now: () -> Long,
    private val schedule: suspend () -> Unit = {},
    private val active: () -> Boolean = { true },
    private val settle: suspend (runId: String) -> Unit = {},
    private val readImage: (String) -> ByteArray? = { null },
    private val imageSize: (String) -> Long? = { null },
) {
    private val pendingDao = db.pendingSendDao()

    // Drains of this queue that stopped, counted so a worker learns of a stop inside a settle's nested drain.
    private val stops = java.util.concurrent.atomic.AtomicInteger()

    /** Test-only: runs in [retry] after its read and decision, before its write. */
    internal var beforeRetryWrite: suspend () -> Unit = {}

    /** Stores [text] as a queued send for [sessionKey]; returns its idempotency key. */
    suspend fun enqueue(sessionKey: String, text: String): String = checkNotNull(store(sessionKey, text, steer = false)).idempotencyKey

    /**
     * For ChatRepository.send: stores [text] and the digests [images] (see [store]), as a steer of the session's running
     * run when there is one and no images, then delivers it (see [deliverStored]), else drains. A hidden session fails
     * locally; an unknown session or a missing image file throws IllegalArgumentException; a body too large to send
     * throws [SendTooLarge]. [steerRun]: the run the Steer button was drawn for, steered only while it runs (else the
     * send is queued, never steering another run); null steers any running run of the session. [steerOnly] (/steer)
     * stores nothing and returns without calling [onStored] when there is no such run, and its row is never sent as a
     * run. Without [steer] the send is always queued.
     */
    internal suspend fun submit(
        sessionKey: String,
        text: String,
        onStored: (String) -> Unit = {},
        images: List<String> = emptyList(),
        steerRun: String? = null,
        steerOnly: Boolean = false,
        steer: Boolean = true,
    ) {
        // Contract: a throw here means nothing was stored, so the caller may offer the text again.
        val row = store(sessionKey, text, steer, images, steerRun, steerOnly) ?: return
        onStored(row.idempotencyKey) // committed with its worker armed: from here the row is the message
        if (row.state == PendingState.FAILED) return
        try {
            val queued = drainLock.withLock { withRepositoryLock { deliverStored(row.idempotencyKey) } }
            if (queued) drain()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Once the row exists it is the message: whatever fails later shows on its bubble or is retried by a later drain.
        }
    }

    /**
     * Accepts a send as a short local handoff that waits for no request in flight: the durable worker is armed first,
     * then the row is stored in one transaction, so the text is never held only in memory behind a slow request, and a
     * death right after the commit still leaves a worker to send it. A drain waits for [handoff], so a worker the arming
     * started at once cannot find the queue empty and end before the row is in it. With [steer], a session with a
     * running run gets a steer of that run, stored as such before any request (not yet attempted).
     */
    private suspend fun store(
        sessionKey: String,
        text: String,
        steer: Boolean,
        images: List<String> = emptyList(),
        steerRun: String? = null,
        steerOnly: Boolean = false,
    ): PendingSendEntity? {
        require(images.size <= MAX_IMAGES) { "too many images" } // before the copy: a huge list is never walked
        val digests = images.toList() // the caller's list may change while this suspends
        withContext(Dispatchers.IO) { admit(text, digests) }
        return handoff.withLock {
            schedule()
            db.withTransaction {
                ensureOpen()
                insert(sessionKey, text, steer, digests, steerRun, steerOnly) // null: a steer-only send with no run, nothing stored
            }
        }
    }

    /**
     * Decides once, before anything is armed, on the caller's inputs only: well-formed digests, each file's size known
     * (IllegalArgumentException), no file, the row and the expanded body over their bounds ([MAX_ROW_BYTES],
     * MAX_BODY_BYTES; [SendTooLarge]). Measured without the session id and choice, which ride in the margins of both.
     */
    private fun admit(text: String, images: List<String>) {
        require(images.size <= MAX_IMAGES) { "too many images" } // on the snapshot: the caller's list may have grown
        require(images.all { d -> d.length == 64 && d.all { it in '0'..'9' || it in 'a'..'f' } }) { "bad digest" }
        if (text.length > MAX_ROW_BYTES) throw SendTooLarge() // each character is at least one byte
        val sizes = images.map { d ->
            requireNotNull(imageSize(d)?.takeIf { it >= 0 }) { "image missing" }.also { if (it > MAX_BODY_BYTES) throw SendTooLarge() }
        }
        val body = buildRunBody("", text, images)
        if (text.encodeToByteArray().size + body.encodeToByteArray().size > MAX_ROW_BYTES) throw SendTooLarge()
        if (expandedSize(body, sizes) > MAX_BODY_BYTES) throw SendTooLarge()
    }

    /**
     * Starts queued sends oldest first. A session with a send in progress or an active run is skipped; its next
     * send starts on a later drain, as the server runs one turn per session at a time. True when nothing is left to send.
     */
    suspend fun drain(): Boolean = drainOnce() == Drained.EMPTY

    private suspend fun drainOnce(): Drained = drainLock.withLock {
        handoff.withLock { } // a send being stored now is in the queue read below
        try {
            ensureOpen()
            // Steers run under drainLock. A row steering here and attempted was cut off mid-request by process death; one
            // not attempted was stored and not yet sent (its submit died, or waits for this lock): it is delivered first.
            for (steer in pendingDao.withState(PendingState.STEERING)) {
                if (steer.attempted) fail(steer, SendError.STEER_UNCERTAIN) else withRepositoryLock { deliverStored(steer.idempotencyKey) }
            }
            val busy = pendingDao.busySessionKeys().toHashSet()
            for (send in pendingDao.allQueued()) {
                if (expired(send)) {
                    fail(send, if (send.attempted) SendError.EXPIRED_UNCERTAIN else SendError.EXPIRED)
                    continue
                }
                if (send.sessionKey in busy) continue
                when (attempt(send)) {
                    Attempt.STARTED -> busy += send.sessionKey
                    Attempt.FAILED -> Unit
                    Attempt.STOP -> return@withLock Drained.STOPPED
                }
            }
            if (pendingDao.needsDrain()) Drained.BLOCKED else Drained.EMPTY
        } catch (_: QueueClosed) {
            Drained.STOPPED
        } catch (_: KeyRefused) {
            Drained.STOPPED
        }
    }.also { if (it == Drained.STOPPED) stops.incrementAndGet() }

    /**
     * For SendWorker: [drain]; when queued sends still wait behind an active run (in the background no follower sees it
     * end), [settle] checks each such run once, outside the drain lock since its Finish drains on its own, then drains again.
     * A drain that stopped (refused key, no network, closed queue), also one nested in a settle, is left to the worker's
     * backoff: nothing more is settled or drained.
     */
    suspend fun drainSettlingRuns(): Boolean {
        when (drainOnce()) {
            Drained.EMPTY -> return true
            Drained.STOPPED -> return false
            Drained.BLOCKED -> Unit
        }
        val before = stops.get()
        val blocking = db.activeRunDao().blockingQueued()
        if (blocking.isEmpty()) return false
        var undelivered = false
        for (runId in blocking) {
            try {
                settle(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: NoticeNotDelivered) {
                undelivered = true // settled, but its notice is not posted: the worker retries
            } catch (_: Exception) {
                // checked again on the worker's next try
            }
            if (stops.get() != before) return false
        }
        return drain() && !undelivered
    }

    /**
     * Removes a queued or failed send; one started or steered cannot be taken back. [mayHaveSentShown] is what the
     * user was told; a row attempted since a "not sent yet" warning is kept ([CancelResult.NOW_ATTEMPTED]).
     */
    suspend fun cancel(idempotencyKey: String, mayHaveSentShown: Boolean = true): CancelResult = drainLock.withLock {
        ensureOpen()
        val send = pendingDao.get(idempotencyKey) ?: return@withLock CancelResult.NOT_FOUND
        if (send.state != PendingState.QUEUED && send.state != PendingState.FAILED) return@withLock CancelResult.STARTED
        if (send.attempted && !mayHaveSentShown) return@withLock CancelResult.NOW_ATTEMPTED
        if (pendingDao.delete(idempotencyKey) == 0) return@withLock CancelResult.NOT_FOUND
        if (send.attempted) CancelResult.REMOVED_MAY_HAVE_SENT else CancelResult.REMOVED
    }

    /**
     * Queues a failed send again as a normal run send. Within 24 hours a run send keeps its key and body, which is
     * always safe. Where the message may already have reached the server and the key cannot protect it (an attempted
     * steer, an unconfirmed send, or an attempted run send past 24 hours), nothing changes until the user confirms [confirmedDuplicateRisk].
     * A confirmed or expired retry gets a new key and creation time.
     */
    suspend fun retry(idempotencyKey: String, confirmedDuplicateRisk: Boolean = false): RetryResult {
        drainLock.withLock {
            ensureOpen()
            val send = pendingDao.get(idempotencyKey)?.takeIf { it.state == PendingState.FAILED } ?: return RetryResult.NOT_FOUND
            // A failed steer keeps attempted only with STEER_UNCERTAIN; an unconfirmed send may already be on the server;
            // an expired uncertain one needs consent whatever the clock says now.
            val uncertain = send.error in UNCERTAIN_ERRORS
            val old = expired(send)
            val unsafe = send.attempted && (uncertain || old)
            if (unsafe && !confirmedDuplicateRisk) return RetryResult.NEEDS_CONFIRMATION
            val queued = send.copy(state = PendingState.QUEUED, error = null, runId = null, steerOnly = false) // a /steer retried is a message
            beforeRetryWrite()
            // Armed before the write, under drainLock: a worker it starts waits for this lock and then sees the row.
            schedule()
            // The chat may have been deleted since the read: a write that changes no row means nothing was queued.
            val written = if (unsafe || old) {
                db.withTransaction {
                    if (pendingDao.delete(idempotencyKey) == 0) return@withTransaction false
                    pendingDao.upsert(queued.copy(idempotencyKey = UUID.randomUUID().toString(), createdAt = now(), attempted = false))
                    true
                }
            } else {
                pendingDao.update(queued) == 1
            }
            if (!written) return RetryResult.NOT_FOUND
        }
        return RetryResult.STARTED
    }

    /** In the transaction that checked the session, so a concurrent delete or refresh cannot leave an orphan row. */
    private suspend fun insert(
        sessionKey: String,
        text: String,
        steer: Boolean,
        images: List<String>,
        steerRun: String?,
        steerOnly: Boolean,
    ): PendingSendEntity? {
        require(!isReservedId(sessionKey)) { "reserved session id" }
        val session = requireNotNull(db.sessionDao().get(sessionKey)) { "unknown session" }
        val runs = db.activeRunDao()
        // A message with images never steers: it waits behind the running reply (decision 5). A named run that ended
        // falls back to the queue, not to another run of the chat.
        val run = if (steer && images.isEmpty()) {
            (if (steerRun != null) runs.get(steerRun)?.takeIf { it.sessionKey == sessionKey } else runs.forSession(sessionKey))?.takeIf { it.status == "running" }
        } else {
            null
        }
        if (steerOnly && run == null) return null
        val row = PendingSendEntity(
            idempotencyKey = UUID.randomUUID().toString(), sessionKey = sessionKey, text = text,
            requestBody = buildRunBody(session.currentId, text, images, session.choice()), createdAt = now(), state = PendingState.QUEUED,
            runId = null, error = null, images = images.joinToString(","),
        ).let {
            when {
                session.hidden -> it.copy(state = PendingState.FAILED, error = SendError.SESSION_UNAVAILABLE)
                run != null -> it.copy(state = PendingState.STEERING, runId = run.runId, steerOnly = steerOnly)
                else -> it
            }
        }
        pendingDao.upsert(row)
        return row
    }

    /**
     * Under both locks: delivers the stored send [idempotencyKey]. A queued one is left for a drain (true). A steer not
     * yet attempted goes to the run it was stored for, after the chat is checked again under this lock: a chat hidden
     * meanwhile fails locally; one being deleted waits (a later drain delivers it if the delete fails); a run that no
     * longer runs here makes it a queued run send, or fails a steer-only one ([SendError.STEER_NOT_DELIVERED]). Anything
     * else (gone, started, attempted) is left as it is.
     */
    private suspend fun deliverStored(idempotencyKey: String): Boolean {
        ensureOpen()
        val row = pendingDao.get(idempotencyKey) ?: return false
        if (row.state == PendingState.QUEUED) return true
        if (row.state != PendingState.STEERING || row.attempted) return false
        val session = db.sessionDao().get(row.sessionKey)
        if (session == null || session.hidden) {
            fail(row, SendError.SESSION_UNAVAILABLE)
            return false
        }
        if (isBeingDeleted(session.key)) return false
        // A row with images never steers (decision 5): like a steer whose run ended, it becomes a queued run send.
        val run = row.runId?.takeIf { row.images.isEmpty() }?.let { db.activeRunDao().get(it) }?.takeIf { it.sessionKey == row.sessionKey && it.status == "running" }
        if (run != null) return steer(row.copy(runId = run.runId))
        if (!row.steerOnly) return pendingDao.update(row.copy(state = PendingState.QUEUED, runId = null)) == 1
        fail(row, SendError.STEER_NOT_DELIVERED)
        return false
    }

    /**
     * Under both locks. Returns true when the steer was refused and the row became a queued run send. A steer past the
     * send expiry, checked before and after the attempted mark, fails unsent; a refused key throws [KeyRefused]. Not
     * cancellable: a new send's worker REPLACE must not cut the request off and leave its delivery uncertain.
     */
    private suspend fun steer(row: PendingSendEntity): Boolean = withContext(NonCancellable) {
        val sent = row.copy(attempted = true)
        ensureOpen()
        if (expired(row)) {
            fail(row, SendError.EXPIRED) // before the attempted mark: an expired steer is never written as attempted
            return@withContext false
        }
        if (pendingDao.update(sent) == 0) return@withContext false
        // Again after the mark, as attemptLocked does, with nothing suspending before the request: never sent past it.
        if (expired(row)) {
            fail(row, SendError.EXPIRED) // the unmarked row: not attempted
            return@withContext false
        }
        try {
            api.steer(checkNotNull(row.runId), row.text)
            ensureOpen()
            pendingDao.update(sent.copy(state = PendingState.STEERED))
            return@withContext false
        } catch (e: ApiError) {
            ensureOpen()
            // A failed steer drops its run id, so cleanup of the run's rows cannot delete the undelivered text.
            val failed = sent.copy(state = PendingState.FAILED, runId = null)
            when {
                // The run is gone or no longer steerable: the text was not delivered. A steer-only row fails unattempted.
                (e == ApiError.NotFound || e is ApiError.Conflict) && row.steerOnly ->
                    pendingDao.update(failed.copy(error = SendError.STEER_NOT_DELIVERED, attempted = false))
                // Any other steer is sent as a new turn.
                e == ApiError.NotFound || e is ApiError.Conflict ->
                    return@withContext pendingDao.update(row.copy(state = PendingState.QUEUED, runId = null, attempted = false)) == 1
                refused(e) -> {
                    pendingDao.update(failed.copy(error = errorCode(e), attempted = false))
                    if (e == ApiError.Unauthorized) throw KeyRefused() // every later send would be refused too
                }
                else -> pendingDao.update(failed.copy(error = SendError.STEER_UNCERTAIN))
            }
            false
        }
    }

    // Under the repository lock, so a user delete or a refresh cannot interleave with creating the chat and starting its run.
    // On IO as a whole: a Retry drains from the main thread, and the request (up to MAX_BODY_BYTES) is encoded in startRun.
    private suspend fun attempt(send: PendingSendEntity): Attempt = withContext(Dispatchers.IO) { attemptLocked(send) }

    private suspend fun attemptLocked(send: PendingSendEntity): Attempt = withRepositoryLock {
        ensureOpen()
        val sessionDao = db.sessionDao()
        val session = sessionDao.get(send.sessionKey)
        if (session == null || session.hidden || isReservedId(session.key) || isReservedId(session.currentId)) {
            fail(send, SendError.SESSION_UNAVAILABLE)
            return@withRepositoryLock Attempt.FAILED
        }
        if (isBeingDeleted(session.key)) return@withRepositoryLock Attempt.FAILED // stays queued; sent if the delete fails
        val sent = send.copy(attempted = true)
        var requested = false // creating the session is not an attempt to deliver the message
        val run = try {
            if (!session.existsOnServer) {
                // Written before the request: a Delete must know the create may reach the server even if its answer is lost.
                val starting = session.copy(createStarted = true)
                ensureOpen()
                sessionDao.upsert(starting)
                val title = create(session, send.text)
                ensureOpen()
                sessionDao.upsert(starting.copy(existsOnServer = true, title = title))
            }
            val body = wireBody(send) ?: run {
                fail(send, SendError.IMAGE_MISSING) // attempted as before: nothing was sent now, an earlier request may have been
                return@withRepositoryLock Attempt.FAILED
            }
            ensureOpen()
            if (pendingDao.update(sent) == 0) return@withRepositoryLock Attempt.FAILED
            // The deadline is read with nothing suspending before startRun: a send never starts after it.
            if (expired(send)) {
                fail(send, if (send.attempted) SendError.EXPIRED_UNCERTAIN else SendError.EXPIRED) // never sent: attempted as before
                return@withRepositoryLock Attempt.FAILED
            }
            ensureOpen()
            requested = true
            api.startRun(body, send.idempotencyKey)
        } catch (e: ApiError) {
            ensureOpen()
            // A refusal settles only this request: an earlier attempt may still have been accepted, so keep its flag.
            val row = if (!requested || refused(e)) send else sent
            return@withRepositoryLock when (e) {
                is ApiError.Network, is ApiError.Unavailable -> {
                    pendingDao.update(row)
                    Attempt.STOP
                }
                // Every later send would be refused too; they wait for fixed credentials.
                ApiError.Unauthorized -> fail(row, SendError.UNAUTHORIZED).let { Attempt.STOP }
                else -> fail(row, errorCode(e)).let { Attempt.FAILED }
            }
        } catch (_: IllegalArgumentException) {
            fail(send, SendError.SESSION_UNAVAILABLE) // the API refused the session id before sending
            return@withRepositoryLock Attempt.FAILED
        }
        ensureOpen() // the run started on the old server; its rows are about to be cleared, and no stream attaches
        db.withTransaction {
            if (pendingDao.update(sent.copy(state = PendingState.SENDING, runId = run.runId, error = null)) == 1) {
                db.activeRunDao().upsert(ActiveRunEntity(run.runId, send.sessionKey, lastSeq = null, status = "running", startedAt = now()))
            }
        }
        try {
            starter.onRunStarted(send.sessionKey, run.runId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The run started and is stored; a broken follower must not change that or stop the drain.
        }
        Attempt.STARTED
    }

    /**
     * Creates [session] on the server, titled by its local rename or else by the first line of [text], and returns the
     * title the server holds. Hermes keeps titles unique: a title another chat holds is retried as "title (2)", "(3)" and
     * so on. A pinned or archived chat gets those flags too (idempotent), so the next refresh does not undo them; until
     * this returns the chat counts as not created and the whole step is repeated.
     */
    private suspend fun create(session: SessionEntity, text: String): String? {
        val base = session.title ?: titleFrom(text).ifEmpty { null }
        var title = base
        var tries = 1
        var created: SessionDto? = null
        while (true) {
            try {
                created = api.createSession(session.currentId, title) ?: readExisting(session.currentId)
                break
            } catch (e: ApiError.Http) {
                if (e.code != "invalid_title" || base == null || tries++ >= TITLE_TRIES) throw e
                title = "$base ($tries)"
            }
        }
        if (session.pinned || session.archived) {
            api.patchSession(session.currentId, pinned = session.pinned.takeIf { it }, archived = session.archived.takeIf { it })
        }
        return created?.title ?: title
    }

    /** It already exists (an earlier create whose answer was lost): what the server holds, if it can be read now. */
    private suspend fun readExisting(id: String): SessionDto? = try {
        api.getSession(id)
    } catch (e: ApiError) {
        if (e == ApiError.Unauthorized) throw e
        null // the next session refresh brings the title
    }

    /**
     * What [attempt] posts: the stored body, or for a row with images that body with each reference replaced by its file,
     * re-hashed while read; null when one is missing, unreadable or changed, or the files have grown past
     * MAX_EXPANDED_LENGTH since they were measured. Never stored; built once per attempt.
     */
    private fun wireBody(send: PendingSendEntity): String? {
        if (send.images.isEmpty()) return send.requestBody
        val read = { d: String ->
            try {
                readImage(d)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        return expandImageRefs(send.requestBody, send.images.split(','), MAX_EXPANDED_LENGTH, read)
    }

    private fun expired(send: PendingSendEntity) = now() - send.createdAt > SEND_EXPIRY_MS

    private suspend fun fail(send: PendingSendEntity, code: String): Int {
        ensureOpen()
        return pendingDao.update(send.copy(state = PendingState.FAILED, error = code, runId = null))
    }

    private fun ensureOpen() {
        if (!active()) throw QueueClosed()
    }
}

/** A definite refusal: the server did not accept the request. Anything else may have been accepted. */
private fun refused(e: ApiError): Boolean = when (e) {
    ApiError.Unauthorized, ApiError.NotFound, is ApiError.Conflict -> true
    is ApiError.Unavailable -> true // the server keeps no reservation for a run it did not admit
    is ApiError.Http -> e.status in 400..499
    is ApiError.Network, ApiError.Protocol -> false
}

/** The fixed code stored for a failure. */
private fun errorCode(e: ApiError): String = when (e) {
    ApiError.Unauthorized -> SendError.UNAUTHORIZED
    ApiError.NotFound -> SendError.NOT_FOUND
    ApiError.Protocol -> SendError.PROTOCOL
    is ApiError.Conflict -> when (e.code) {
        SendError.SESSION_DELETED -> SendError.SESSION_DELETED
        SendError.PERMISSION_DENIED -> SendError.PERMISSION_DENIED
        else -> SendError.CONFLICT
    }
    is ApiError.Http, is ApiError.Network, is ApiError.Unavailable -> SendError.HTTP_ERROR
}
