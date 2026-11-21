package xyz.rycerz.apofis.push

import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi

/** The push registration records of one server, through one connection's client. */
interface RecordApi {
    val server: String

    /** False: `session_exists`. A retired id throws [ApiError.Conflict] `session_deleted`. */
    suspend fun create(id: String, title: String, prompt: String): Boolean

    /** (title, source), or null when the record does not exist. */
    suspend fun read(id: String): Pair<String?, String>?
    suspend fun isHiddenUnpinned(id: String): Boolean
    suspend fun hide(id: String)

    /** A missing record counts as deleted. */
    suspend fun delete(id: String)
}

class HermesRecordApi(override val server: String, private val api: HermesApi) : RecordApi {
    override suspend fun create(id: String, title: String, prompt: String) = api.createSession(id, title, prompt) != null

    override suspend fun read(id: String) = try {
        api.getSession(id).let { it.title to it.source }
    } catch (_: ApiError.NotFound) {
        null
    }

    override suspend fun isHiddenUnpinned(id: String) = api.getSession(id).let { it.hidden && !it.pinned }

    // Pinning clears hidden on the server, so unpin in the same request.
    override suspend fun hide(id: String) {
        api.patchSession(id, pinned = false, hidden = true)
    }

    override suspend fun delete(id: String) = api.deleteSession(id)
}
