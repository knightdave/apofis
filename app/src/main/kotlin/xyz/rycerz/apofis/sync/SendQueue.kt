package xyz.rycerz.apofis.sync

import androidx.room.withTransaction
import java.util.UUID
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

// Process-wide like the repository lock: the app foreground and SendWorker must never start the same send twice.
// Lock order: drainLock, then the repository lock.
private val drainLock = Mutex()

private sealed interface Attempt {
    data object Started : Attempt
    data object Failed : Attempt
    data object Offline : Attempt
}

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
    suspend fun enqueue(sessionKey: String, text: String): String = insert(sessionKey, text, error = null).also { schedule() }

    /** A steer that failed: shown as a failed message that [retry] sends as a normal queued one. */
    internal suspend fun enqueueFailed(sessionKey: String, text: String, error: String): String = insert(sessionKey, text, error)

    /**
     * Starts queued sends oldest first. A session with a send in progress or an active run is skipped; its next
     * send starts on a later drain, as the server runs one turn per session at a time. True when nothing is left queued.
     */
    suspend fun drain(): Boolean = drainLock.withLock {
        val busy = pendingDao.busySessionKeys().toHashSet()
        for (send in pendingDao.allQueued()) {
            if (now() - send.createdAt > SEND_EXPIRY_MS) {
                fail(send, "expired")
                continue
            }
            if (send.sessionKey in busy) continue
            when (attempt(send)) {
                Attempt.Started -> busy += send.sessionKey
                Attempt.Failed -> Unit
                Attempt.Offline -> return@withLock false
            }
        }
        pendingDao.allQueued().isEmpty()
    }

    /** Removes a queued or failed send; one already started cannot be taken back. */
    suspend fun cancel(idempotencyKey: String) = drainLock.withLock {
        val send = pendingDao.get(idempotencyKey) ?: return@withLock
        if (send.state != "sending") pendingDao.delete(idempotencyKey)
    }

    /** Queues a failed send again. An expired one gets a new key and creation time, since the old key may be forgotten. */
    suspend fun retry(idempotencyKey: String) {
        drainLock.withLock {
            val send = pendingDao.get(idempotencyKey)?.takeIf { it.state == "failed" } ?: return
            val queued = send.copy(state = "queued", error = null)
            if (send.error == "expired" || now() - send.createdAt > SEND_EXPIRY_MS) {
                db.withTransaction {
                    pendingDao.delete(idempotencyKey)
                    pendingDao.upsert(queued.copy(idempotencyKey = UUID.randomUUID().toString(), createdAt = now()))
                }
            } else {
                pendingDao.upsert(queued)
            }
        }
        schedule()
    }

    private suspend fun insert(sessionKey: String, text: String, error: String?): String {
        require(!isReservedId(sessionKey)) { "reserved session id" }
        val session = requireNotNull(db.sessionDao().get(sessionKey)) { "unknown session" }
        val key = UUID.randomUUID().toString()
        pendingDao.upsert(
            PendingSendEntity(
                idempotencyKey = key, sessionKey = sessionKey, text = text,
                requestBody = buildRunBody(session.currentId, text), createdAt = now(),
                state = if (error == null) "queued" else "failed", runId = null, error = error,
            ),
        )
        return key
    }

    // Under the repository lock, so a user delete or a refresh cannot interleave with creating the chat and starting its run.
    private suspend fun attempt(send: PendingSendEntity): Attempt = withRepositoryLock {
        val sessionDao = db.sessionDao()
        val session = sessionDao.get(send.sessionKey)
        if (session == null || session.hidden || isReservedId(session.key) || isReservedId(session.currentId)) {
            fail(send, "session_unavailable")
            return@withRepositoryLock Attempt.Failed
        }
        try {
            if (!session.existsOnServer) {
                api.createSession(session.currentId, titleFrom(send.text).ifEmpty { null }) // false: it already exists, fine
                sessionDao.upsert(session.copy(existsOnServer = true))
            }
            val run = api.startRun(send.requestBody, send.idempotencyKey)
            db.withTransaction {
                pendingDao.upsert(send.copy(state = "sending", runId = run.runId, error = null))
                db.activeRunDao().upsert(ActiveRunEntity(run.runId, send.sessionKey, lastSeq = null, status = "running", startedAt = now()))
            }
            starter.onRunStarted(send.sessionKey, run.runId)
            Attempt.Started
        } catch (e: ApiError) {
            val code = errorCode(e) ?: return@withRepositoryLock Attempt.Offline
            fail(send, code)
            Attempt.Failed
        } catch (_: IllegalArgumentException) {
            fail(send, "invalid_id") // the API refused the id before sending
            Attempt.Failed
        }
    }

    private suspend fun fail(send: PendingSendEntity, code: String) = pendingDao.upsert(send.copy(state = "failed", error = code))
}

/** The fixed code stored on a failed send, or null when the send should stay queued. Never server text. */
private fun errorCode(e: ApiError): String? = when (e) {
    is ApiError.Network, is ApiError.Unavailable -> null
    ApiError.Unauthorized -> "unauthorized"
    ApiError.NotFound -> "not_found"
    ApiError.Protocol -> "protocol"
    is ApiError.Http -> "http_${e.status}"
    is ApiError.Conflict -> when (e.code) {
        "session_deleted", "permission_denied", "idempotency_key_conflict" -> e.code
        else -> "conflict"
    }
}
