package xyz.rycerz.apofis.sync

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity

/** Implemented by Task 8: told about every run this queue started. */
fun interface RunStarter {
    fun onRunStarted(sessionKey: String, runId: String)
}

const val SEND_EXPIRY_MS = 24L * 60 * 60 * 1000

/**
 * [PendingSendEntity.state] values. In the transcript: [QUEUED] is QUEUED, [SENDING] is SENDING, [FAILED] is FAILED,
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
}

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

private enum class Attempt { STARTED, FAILED, STOP }

/** Thrown inside the locks once the owning connection is closed. Writes pass it on; [SendQueue.drain] returns false. */
private class QueueClosed : IllegalStateException("closed")

/**
 * The durable path from "send" to "run started". Each message is stored with its exact request body and
 * idempotency key before any request, so a retry is byte-identical and the server never runs it twice.
 * [schedule] is called whenever a send is left queued; the app passes `{ SendWorker.schedule(context) }`.
 * [active] turns false when the app switches servers. From then on every write ([enqueue], [submit], [cancel], [retry])
 * throws IllegalStateException("closed") and [drain] returns false, each checked inside the lock of the write and again
 * before every request, so a queue bound to the old server never sends or changes rows that belong to the new one.
 */
class SendQueue(
    private val api: HermesApi,
    private val db: AppDatabase,
    private val starter: RunStarter,
    private val now: () -> Long,
    private val schedule: () -> Unit = {},
    private val active: () -> Boolean = { true },
) {
    private val pendingDao = db.pendingSendDao()

    /** Stores [text] as a queued send for [sessionKey]; returns its idempotency key. */
    suspend fun enqueue(sessionKey: String, text: String): String =
        withRepositoryLock {
            ensureOpen()
            insert(sessionKey, text, steer = false)
        }.idempotencyKey.also { schedule() }

    /**
     * For ChatRepository.send: steers the session's running run, else queues [text] and drains. The row is stored
     * before any request; a hidden session fails locally; an unknown session throws IllegalArgumentException.
     * The repository lock is held from the session check through the steer request, so a refresh cannot hide
     * the session and a delete cannot remove the row in between.
     */
    internal suspend fun submit(sessionKey: String, text: String) {
        var stored = false
        try {
            val queued = drainLock.withLock {
                withRepositoryLock {
                    ensureOpen()
                    val row = insert(sessionKey, text, steer = true)
                    stored = true
                    if (row.state == PendingState.STEERING) steer(row) else row.state == PendingState.QUEUED
                }
            }
            if (queued) {
                schedule()
                drain()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Contract: a throw means nothing was stored, so the caller may offer the text again. Once the row
            // exists it is the message: whatever fails later shows on its bubble or is retried by a later drain.
            if (!stored) throw e
        }
    }

    /**
     * Starts queued sends oldest first. A session with a send in progress or an active run is skipped; its next
     * send starts on a later drain, as the server runs one turn per session at a time. True when nothing is left queued.
     */
    suspend fun drain(): Boolean = drainLock.withLock {
        try {
            ensureOpen()
            // Steers run under drainLock, so a row still steering here was cut off by process death.
            pendingDao.withState(PendingState.STEERING).forEach { fail(it, SendError.STEER_UNCERTAIN) }
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
                    Attempt.STOP -> return@withLock false
                }
            }
            pendingDao.allQueued().isEmpty()
        } catch (_: QueueClosed) {
            false
        }
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
            // A failed steer keeps attempted only with STEER_UNCERTAIN; an unconfirmed send may already be on the server.
            val uncertain = send.error == SendError.STEER_UNCERTAIN || send.error == SendError.UNCONFIRMED
            val old = expired(send)
            val unsafe = send.attempted && (uncertain || old)
            if (unsafe && !confirmedDuplicateRisk) return RetryResult.NEEDS_CONFIRMATION
            val queued = send.copy(state = PendingState.QUEUED, error = null, runId = null)
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
        schedule()
        return RetryResult.STARTED
    }

    /** Called under the repository lock that checked the session, so a concurrent delete cannot leave an orphan row. */
    private suspend fun insert(sessionKey: String, text: String, steer: Boolean): PendingSendEntity {
        require(!isReservedId(sessionKey)) { "reserved session id" }
        val session = requireNotNull(db.sessionDao().get(sessionKey)) { "unknown session" }
        val run = if (steer) db.activeRunDao().forSession(sessionKey)?.takeIf { it.status == "running" } else null
        val row = PendingSendEntity(
            idempotencyKey = UUID.randomUUID().toString(), sessionKey = sessionKey, text = text,
            requestBody = buildRunBody(session.currentId, text), createdAt = now(), state = PendingState.QUEUED,
            runId = null, error = null,
        ).let {
            when {
                session.hidden -> it.copy(state = PendingState.FAILED, error = SendError.SESSION_UNAVAILABLE)
                run != null -> it.copy(state = PendingState.STEERING, runId = run.runId)
                else -> it
            }
        }
        pendingDao.upsert(row)
        return row
    }

    /** Under both locks. Returns true when the steer was refused and the row became a queued run send. */
    private suspend fun steer(row: PendingSendEntity): Boolean {
        val sent = row.copy(attempted = true)
        ensureOpen()
        if (pendingDao.update(sent) == 0) return false
        try {
            api.steer(checkNotNull(row.runId), row.text)
            ensureOpen()
            pendingDao.update(sent.copy(state = PendingState.STEERED))
            return false
        } catch (e: ApiError) {
            ensureOpen()
            // A failed steer drops its run id, so cleanup of the run's rows cannot delete the undelivered text.
            val failed = sent.copy(state = PendingState.FAILED, runId = null)
            when {
                // The run is gone or no longer steerable: the text was not delivered, so send it as a new turn.
                e == ApiError.NotFound || e is ApiError.Conflict ->
                    return pendingDao.update(row.copy(state = PendingState.QUEUED, runId = null, attempted = false)) == 1
                refused(e) -> pendingDao.update(failed.copy(error = errorCode(e), attempted = false))
                else -> pendingDao.update(failed.copy(error = SendError.STEER_UNCERTAIN))
            }
            return false
        }
    }

    // Under the repository lock, so a user delete or a refresh cannot interleave with creating the chat and starting its run.
    private suspend fun attempt(send: PendingSendEntity): Attempt = withRepositoryLock {
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
                api.createSession(session.currentId, titleFrom(send.text).ifEmpty { null }) // false: it already exists, fine
                ensureOpen()
                sessionDao.upsert(session.copy(existsOnServer = true))
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
            api.startRun(send.requestBody, send.idempotencyKey)
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
