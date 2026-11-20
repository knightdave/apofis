package xyz.rycerz.apofis.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client for the Hermes Agent API server. [baseUrl] may carry a path prefix.
 * The constructor throws IllegalArgumentException for a malformed [baseUrl] (the connect screen validates it first).
 * Every call fails with [ApiError] or CancellationException; a malformed id throws IllegalArgumentException before any request.
 */
class HermesApi(baseUrl: String, private val apiKey: String, client: OkHttpClient = defaultClient()) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    // A redirect would resend private bodies and the Idempotency-Key elsewhere, so never follow one.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun capabilities(): Capabilities = get(url("v1/capabilities"))

    /** Archived rows are never returned by the server (unless also pinned); read those with [getSession]. */
    suspend fun listSessions(limit: Int = 200, offset: Int = 0): SessionPage =
        get(url("api/sessions") { addQueryParameter("limit", "$limit").addQueryParameter("offset", "$offset") })

    suspend fun getSession(id: String): SessionDto = get<SessionEnvelope>(url("api/sessions", id)).session

    /** Returns false when the id already exists. A deleted id gives [ApiError.Conflict] `session_deleted`. */
    suspend fun createSession(id: String, title: String?, systemPrompt: String? = null): Boolean {
        val body = jsonBody {
            put("id", validId(id))
            if (title != null) put("title", title)
            if (systemPrompt != null) put("system_prompt", systemPrompt)
        }
        return try {
            exchange(post(url("api/sessions"), body)) { }
            true
        } catch (e: ApiError.Conflict) {
            if (e.code == "session_exists") false else throw e
        }
    }

    suspend fun patchSession(id: String, title: String? = null, pinned: Boolean? = null, archived: Boolean? = null, hidden: Boolean? = null): SessionDto {
        val body = jsonBody {
            if (title != null) put("title", title)
            if (pinned != null) put("pinned", pinned)
            if (archived != null) put("archived", archived)
            if (hidden != null) put("hidden", hidden)
        }
        return exchange(request(url("api/sessions", id)).patch(body.toJsonRequestBody()).build()) { decode<SessionEnvelope>(it) }.session
    }

    /** A missing session counts as deleted. */
    suspend fun deleteSession(id: String) {
        try {
            exchange(request(url("api/sessions", id)).delete().build()) { }
        } catch (_: ApiError.NotFound) {
        }
    }

    suspend fun messages(sessionId: String, limit: Int = 500, offset: Int = 0): MessagePage = get(
        url("api/sessions", sessionId, "messages") {
            addQueryParameter("limit", "$limit").addQueryParameter("offset", "$offset")
                .addQueryParameter("order", "oldest").addQueryParameter("include_compacted", "true")
        },
    )

    /** Sends [bodyJson] unchanged, so a retry with the same [idempotencyKey] is byte-identical. */
    suspend fun startRun(bodyJson: String, idempotencyKey: String): RunStart {
        val req = request(url("v1/runs")).header("Idempotency-Key", idempotencyKey).post(bodyJson.toRequestBody(JSON)).build()
        return exchange(req) { decode(it) }
    }

    suspend fun runStatus(runId: String): RunStatus = get(url("v1/runs", runId))

    /** Completes when the server closes the stream, with or without a terminal event. */
    fun runEvents(runId: String, lastSeq: Long?): Flow<RunEvent> = channelFlow {
        val req = request(url("v1/runs", runId, "events")).header("Accept", "text/event-stream")
        // Last-Event-ID n replays seq > n, so the first attach must not send it.
        if (lastSeq != null) req.header("Last-Event-ID", lastSeq.toString())
        exchange(req.build(), stream = true) { response ->
            val type = response.body.contentType()
            if (type?.type != "text" || type.subtype != "event-stream") throw ApiError.Protocol
            SseReader.events(response.body.source()).collect { send(it) }
        }
    }

    suspend fun steer(runId: String, text: String) = control(url("v1/runs", runId, "steer")) { put("text", text) }

    suspend fun stop(runId: String) = control(url("v1/runs", runId, "stop")) { }

    suspend fun approve(runId: String, requestId: String, executionGeneration: Long, choice: ApprovalChoice): ControlResult =
        answer(runId, requestId, url("v1/runs", runId, "approval")) {
            put("request_id", requestId)
            put("execution_generation", executionGeneration)
            put("choice", choice.name.lowercase())
        }

    suspend fun clarify(runId: String, requestId: String, executionGeneration: Long, answer: String): ControlResult =
        answer(runId, requestId, url("v1/runs", runId, "clarify")) {
            put("request_id", requestId)
            put("execution_generation", executionGeneration)
            put("answer", answer)
        }

    suspend fun resolveUnknown(runId: String, admissionId: String, executionGeneration: Long) =
        control(url("v1/runs", runId, "resolve-unknown")) {
            put("admission_id", admissionId)
            put("execution_generation", executionGeneration)
        }

    private suspend fun control(url: HttpUrl, body: JsonObjectBuilder.() -> Unit) {
        exchange(post(url, jsonBody(body))) { }
    }

    /**
     * Only the documented bodies `{run_id, status, prompt_id}` naming this run and this request count; any other 2xx
     * body is [ApiError.Protocol], never an accepted answer. The server does not echo the generation (it refuses a
     * stale one with 409 instead), so it cannot be checked here.
     */
    private suspend fun answer(runId: String, requestId: String, url: HttpUrl, body: JsonObjectBuilder.() -> Unit): ControlResult =
        exchange(post(url, jsonBody(body))) { response ->
            val reply = decode<ControlReply>(response)
            if (reply.runId != runId || reply.promptId != requestId) throw ApiError.Protocol
            when (reply.status) {
                "resolved" -> ControlResult.RESOLVED
                "already_resolved" -> ControlResult.ALREADY_RESOLVED
                else -> throw ApiError.Protocol
            }
        }

    private fun url(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().apply {
            // The first part is a fixed path; the rest (ids and fixed names) are single encoded segments.
            // addPathSegments replaces a trailing empty segment, so "http://h/" and "http://h" give the same URL.
            addPathSegments(segments.first())
            segments.drop(1).forEach { addPathSegment(validId(it)) }
            query()
        }.build()

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("Authorization", "Bearer $apiKey")

    private fun post(url: HttpUrl, body: JsonObject) = request(url).post(body.toJsonRequestBody()).build()

    private suspend inline fun <reified T> get(url: HttpUrl): T = exchange(request(url).build()) { decode<T>(it) }

    private inline fun <reified T> decode(response: Response): T {
        val text = response.body.string()
        return try {
            json.decodeFromString<T>(text)
        } catch (_: SerializationException) {
            throw ApiError.Protocol
        }
    }

    /**
     * Runs [request] on the IO dispatcher; cancelling the caller cancels the HTTP call at once. Every call but an event
     * [stream] keeps the client's overall deadline.
     */
    private suspend fun <T> exchange(request: Request, stream: Boolean = false, onSuccess: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        if (stream) call.timeout().clearTimeout() // an event stream lasts as long as its run
        val work = async(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw errorOf(response)
                    onSuccess(response)
                }
            } catch (e: IOException) {
                // A cancelled caller makes call.cancel() below fail the read: that is cancellation. Timeouts are Network.
                ensureActive()
                throw ApiError.Network(e)
            }
        }
        try {
            work.await()
        } finally {
            call.cancel()
        }
    }

    private fun errorOf(response: Response): ApiError {
        // Statuses that need no error code are mapped before the body is read, so a truncated body cannot mask them.
        when {
            response.code in 300..399 -> return ApiError.Http(response.code, null)
            response.code == 401 -> return ApiError.Unauthorized
            response.code == 404 -> return ApiError.NotFound
            response.code == 503 -> return ApiError.Unavailable(response.header("Retry-After")?.trim()?.toLongOrNull())
        }
        val text = response.body.string() // the code is needed to classify, so an IOException here is Network
        // Only a short machine code survives, and never one that echoes the key.
        val code = runCatching { json.decodeFromString<ErrorEnvelope>(text).error.code }.getOrNull()
            ?.takeIf { codeFormat.matches(it) && apiKey !in it }
        return if (response.code == 409 && code != null) ApiError.Conflict(code) else ApiError.Http(response.code, code)
    }

    companion object {
        /** One id rule for every method: a single path segment that cannot change the target resource. */
        private fun validId(id: String): String = id.also {
            require(it.isNotEmpty() && '/' !in it && '\\' !in it && it != "." && it != "..") { "invalid id" }
        }

        private val JSON = "application/json".toMediaType()
        private val codeFormat = Regex("[a-z0-9_]{1,64}")
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /**
         * The server sends a keepalive every 10 s on event streams, so 60 s of silence means a dead connection. Other
         * calls also get an overall deadline: a response trickling in faster than the read timeout must still end.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

        private fun jsonBody(block: JsonObjectBuilder.() -> Unit) = buildJsonObject(block)
        private fun JsonObject.toJsonRequestBody() = toString().toRequestBody(JSON)
    }
}
