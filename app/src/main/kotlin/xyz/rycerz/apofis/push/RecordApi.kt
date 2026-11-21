package xyz.rycerz.apofis.push

import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi

/** What the app needs to know about a record before it hides, adopts or deletes it. */
data class RecordRow(
    val title: String?,
    val source: String,
    val hidden: Boolean,
    val pinned: Boolean,
    val messageCount: Int,
    val parent: String?,
    val endReason: String?,
)

/** The push registration records of one server, through one connection's client. */
interface RecordApi {
    val server: String

    /** False: `session_exists`. A retired id throws [ApiError.Conflict] `session_deleted`. */
    suspend fun create(id: String, title: String, prompt: String): Boolean

    /** Null when the record does not exist. */
    suspend fun read(id: String): RecordRow?
    suspend fun hide(id: String)

    /** A missing record counts as deleted. */
    suspend fun delete(id: String)
}

class HermesRecordApi(override val server: String, private val api: HermesApi) : RecordApi {
    override suspend fun create(id: String, title: String, prompt: String) = api.createSession(id, title, prompt) != null

    override suspend fun read(id: String) = try {
        api.getSession(id).let { RecordRow(it.title, it.source, it.hidden, it.pinned, it.messageCount, it.parentSessionId, it.endReason) }
    } catch (_: ApiError.NotFound) {
        null
    }

    // Pinning clears hidden on the server, so unpin in the same request.
    override suspend fun hide(id: String) {
        api.patchSession(id, pinned = false, hidden = true)
    }

    override suspend fun delete(id: String) = api.deleteSession(id)
}
