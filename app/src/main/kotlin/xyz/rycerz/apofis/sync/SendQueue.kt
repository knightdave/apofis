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
    const val EXPIRED_UNCERTAIN = "expired_uncertain" // expired after a request whose outcome is unknown
    const val STEER_UNCERTAIN = "steer_uncertain" // the steer may have been delivered
    const val PERMISSION_DENIED = "permission_denied"
    const val SESSION_DELETED = "session_deleted"
    const val SESSION_UNAVAILABLE = "session_unavailable"
    const val CONFLICT = "conflict"
    const val UNAUTHORIZED = "unauthorized"
    const val NOT_FOUND = "not_found"
    const val PROTOCOL = "protocol"
    const val HTTP_ERROR = "http_error"

    /** Errors after which the message may already have reached the server: a retry needs the user's confirmation. */
    val UNCERTAIN = setOf(EXPIRED_UNCERTAIN, STEER_UNCERTAIN)
}

enum class RetryResult { STARTED, NEEDS_CONFIRMATION, NOT_FOUND }

// Process-wide like the repository lock: the app foreground and SendWorker must never start the same send twice.
// Lock order: drainLock, then the repository lock.
private val drainLock = Mutex()

private enum class Attempt { STARTED, FAILED, STOP }

/**
 * The durable path from "send" to "run started". Each message is stored with its exact request body and
 * idempotency key before any request, so a retry is byte-identical and the server never runs it twice.
 * [schedule] is called whenever a send is left queued; the app passes `{ SendWorker.schedule(context) }`.
 */
class SendQueue(
    private val api: HermesApi,
    private val db: AppDatabase,
    private val starter: RunStarter,
    private val now: () -> Long,
    private val schedule: () -> Unit = {},
) {
    private val pendingDao = db.pendingSendDao()

    /** Stores [text] as a queued send for [sessionKey]; returns its idempotency key. */
    suspend fun enqueue(sessionKey: String, text: String): String =
        insert(sessionKey, text, steer = false).idempotencyKey.also { schedule() }

    /**
     * For ChatRepository.send: steers the session's running run, else queues [text] and drains. The row is stored
     * before any request; a hidden session fails locally; an unknown session throws IllegalArgumentException.
     */
    internal suspend fun submit(sessionKey: String, text: String) {
        val queued = drainLock.withLock {
            val row = insert(sessionKey, text, steer = true)
            if (row.state == PendingState.STEERING) steer(row) else row.state == PendingState.QUEUED
        }
        if (queued) {
            schedule()
            drain()
        }
    }

    /**
     * Starts queued sends oldest first. A session with a send in progress or an active run is skipped; its next
     * send starts on a later drain, as the server runs one turn per session at a time. True when nothing is left queued.
     */
    suspend fun drain(): Boolean = drainLock.withLock {
        // Steers run under drainLock, so a row still steering here was cut off by process death.
        pendingDao.withState(PendingState.STEERING).forEach { fail(it, SendError.STEER_UNCERTAIN) }
        val busy = pendingDao.busySessionKeys().toHashSet()
        for (send in pendingDao.allQueued()) {
            if (failIfExpired(send)) continue
            if (send.sessionKey in busy) continue
            when (attempt(send)) {
                Attempt.STARTED -> busy += send.sessionKey
                Attempt.FAILED -> Unit
                Attempt.STOP -> return@withLock false
            }
        }
        pendingDao.allQueued().isEmpty()
    }

    /** Removes a queued or failed send; one started or steered cannot be taken back. */
    suspend fun cancel(idempotencyKey: String) = drainLock.withLock {
        val send = pendingDao.get(idempotencyKey) ?: return@withLock
        if (send.state == PendingState.QUEUED || send.state == PendingState.FAILED) pendingDao.delete(idempotencyKey)
    }

    /**
     * Queues a failed send again as a normal run send. After an uncertain failure the message may already have run,
     * so nothing changes until the user confirms ([confirmedDuplicateRisk]). A confirmed or expired retry gets a new
     * key and creation time, since the server must treat it as a new message.
     */
    suspend fun retry(idempotencyKey: String, confirmedDuplicateRisk: Boolean = false): RetryResult {
        drainLock.withLock {
            val send = pendingDao.get(idempotencyKey)?.takeIf { it.state == PendingState.FAILED } ?: return RetryResult.NOT_FOUND
            val uncertain = send.error in SendError.UNCERTAIN
            if (uncertain && !confirmedDuplicateRisk) return RetryResult.NEEDS_CONFIRMATION
            val queued = send.copy(state = PendingState.QUEUED, error = null, runId = null)
            if (uncertain || send.error == SendError.EXPIRED || now() - send.createdAt > SEND_EXPIRY_MS) {
                db.withTransaction {
                    pendingDao.delete(idempotencyKey)
                    pendingDao.upsert(queued.copy(idempotencyKey = UUID.randomUUID().toString(), createdAt = now(), attempted = false))
                }
            } else {
                pendingDao.upsert(queued)
            }
        }
        schedule()
        return RetryResult.STARTED
    }

    /** Inserted under the lock that checked the session, so a concurrent delete cannot leave an orphan row. */
    private suspend fun insert(sessionKey: String, text: String, steer: Boolean): PendingSendEntity = withRepositoryLock {
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
        row
    }

    /** Under drainLock. Returns true when the steer was refused and the row became a queued run send. */
    private suspend fun steer(row: PendingSendEntity): Boolean {
        val sent = row.copy(attempted = true)
        pendingDao.upsert(sent)
        try {
            api.steer(checkNotNull(row.runId), row.text)
            pendingDao.upsert(sent.copy(state = PendingState.STEERED))
            return false
        } catch (e: ApiError) {
            when (e) {
                // The run is gone or no longer steerable: the text was not delivered.
                ApiError.NotFound, is ApiError.Conflict -> {
                    pendingDao.upsert(row.copy(state = PendingState.QUEUED, runId = null, attempted = false))
                    return true
                }
                is ApiError.Network, is ApiError.Unavailable -> fail(sent, SendError.STEER_UNCERTAIN)
                else -> fail(sent, errorCode(e))
            }
            return false
        }
    }

    // Under the repository lock, so a user delete or a refresh cannot interleave with creating the chat and starting its run.
    private suspend fun attempt(send: PendingSendEntity): Attempt = withRepositoryLock {
        val sessionDao = db.sessionDao()
        val session = sessionDao.get(send.sessionKey)
        if (session == null || session.hidden || isReservedId(session.key) || isReservedId(session.currentId)) {
            fail(send, SendError.SESSION_UNAVAILABLE)
            return@withRepositoryLock Attempt.FAILED
        }
        val run = try {
            if (!session.existsOnServer) {
                api.createSession(session.currentId, titleFrom(send.text).ifEmpty { null }) // false: it already exists, fine
                sessionDao.upsert(session.copy(existsOnServer = true))
            }
            // Checked again after any lock wait and session creation: a send never starts after its deadline.
            if (failIfExpired(send)) return@withRepositoryLock Attempt.FAILED
            if (!send.attempted) pendingDao.upsert(send.copy(attempted = true))
            api.startRun(send.requestBody, send.idempotencyKey)
        } catch (e: ApiError) {
            if (e is ApiError.Network || e is ApiError.Unavailable) return@withRepositoryLock Attempt.STOP
            fail(pendingDao.get(send.idempotencyKey) ?: send, errorCode(e))
            // Every later send would be refused too; they wait for fixed credentials.
            return@withRepositoryLock if (e == ApiError.Unauthorized) Attempt.STOP else Attempt.FAILED
        } catch (_: IllegalArgumentException) {
            fail(send, SendError.SESSION_UNAVAILABLE) // the API refused the session id before sending
            return@withRepositoryLock Attempt.FAILED
        }
        db.withTransaction {
            pendingDao.upsert(send.copy(state = PendingState.SENDING, runId = run.runId, error = null, attempted = true))
            db.activeRunDao().upsert(ActiveRunEntity(run.runId, send.sessionKey, lastSeq = null, status = "running", startedAt = now()))
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

    private suspend fun failIfExpired(send: PendingSendEntity): Boolean {
        if (now() - send.createdAt <= SEND_EXPIRY_MS) return false
        val current = pendingDao.get(send.idempotencyKey) ?: send
        fail(current, if (current.attempted) SendError.EXPIRED_UNCERTAIN else SendError.EXPIRED)
        return true
    }

    private suspend fun fail(send: PendingSendEntity, code: String) =
        pendingDao.upsert(send.copy(state = PendingState.FAILED, error = code))
}

/** The fixed code stored for a definite failure. Network and Unavailable are handled by the callers. */
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
