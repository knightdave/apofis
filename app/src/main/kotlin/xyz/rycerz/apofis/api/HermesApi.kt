package xyz.rycerz.apofis.api

import java.io.IOException
import java.math.BigInteger
import java.nio.charset.CharacterCodingException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import xyz.rycerz.apofis.images.ImageFormat
import xyz.rycerz.apofis.images.sniff
import xyz.rycerz.apofis.media.APP_PROTOCOL
import xyz.rycerz.apofis.media.AddonLimits
import xyz.rycerz.apofis.media.AddonStatus

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

    /**
     * The created session (its title as the server accepted it), or null when the id already exists. A deleted id gives
     * [ApiError.Conflict] `session_deleted`; a title another chat holds gives [ApiError.Http] 400 `invalid_title`.
     */
    suspend fun createSession(id: String, title: String?, systemPrompt: String? = null): SessionDto? {
        val body = jsonBody {
            put("id", validId(id))
            if (title != null) put("title", title)
            if (systemPrompt != null) put("system_prompt", systemPrompt)
        }
        return try {
            exchange(post(url("api/sessions"), body)) { decode<SessionEnvelope>(it) }.session
        } catch (e: ApiError.Conflict) {
            if (e.code == "session_exists") null else throw e
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

    /**
     * Returns only when [id] is gone: a 200 with the deleted object for exactly [id], or a 404 `session_not_found`.
     * Any other 2xx (201 and 202 too) is [ApiError.Protocol], any other 404 is [ApiError.Http], the rest maps as for every call.
     */
    suspend fun deleteSession(id: String) = exchange(request(url("api/sessions", id)).delete().build(), checked = false) { response ->
        when {
            response.code == 404 -> codeOf(response.body.string()).let { if (it != "session_not_found") throw ApiError.Http(404, it) }
            !response.isSuccessful -> throw errorOf(response)
            else -> {
                val o = decode<JsonObject>(response)
                fun field(key: String, quoted: Boolean) = (o[key] as? JsonPrimitive)?.takeIf { it.isString == quoted }?.content
                if (response.code != 200 || field("object", true) != "hermes.session.deleted" ||
                    field("id", true) != id || field("deleted", false) != "true"
                ) {
                    throw ApiError.Protocol
                }
            }
        }
    }

    /** The models of the server's configured providers, for the model sheet; never stored. */
    suspend fun modelOptions(): ModelOptions = withContext(Dispatchers.Default) { // a long list is mapped off the main thread
        get<ModelOptionsDto>(url("api/model/options") { addQueryParameter("include_unconfigured", "false") }).options()
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

    /** Whether the picture add-on is there, ready and compatible (spec 5.3). Never throws but for cancellation. */
    suspend fun mediaPing(): MediaPing = try {
        exchange(identity(url("apofis-media/v1/ping")).build(), checked = false) { response ->
            when {
                response.code == 404 || response.code == 405 -> MediaPing(AddonStatus.NEEDED, null)
                response.code != 200 || response.encoded -> MediaPing(AddonStatus.ERROR, null)
                else -> pingOf(response.bytesAtMost(BODY_MAX))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        MediaPing(AddonStatus.ERROR, null)
    }

    /**
     * One picture by its add-on [path], a request field only. A 200 over [limits] is `Http(413, null)`, decided before
     * the type; any other bad 200 is [ApiError.Protocol]; a non-200 is mapped here, never by [errorOf].
     */
    suspend fun mediaFile(path: String, limits: AddonLimits): MediaFile =
        exchange(identity(url("apofis-media/v1/file")).post(jsonBody { put("path", path) }.toJsonRequestBody()).build(), checked = false) { response ->
            when (response.code) {
                200 -> {
                    if (response.body.contentLength() > limits.maxBytes) throw ApiError.Http(413, null)
                    val bytes = response.bytesAtMost(limits.maxBytes.toLong())
                    if (bytes.size > limits.maxBytes) throw ApiError.Http(413, null)
                    val format = response.body.contentType()?.let { PICTURE_TYPES["${it.type}/${it.subtype}"] }
                    // The four formats differ within 12 bytes; a longer head only makes sniff walk unrelated boxes.
                    if (response.encoded || format == null || sniff(bytes.copyOf(minOf(bytes.size, 12))) != format || format !in limits.types) throw ApiError.Protocol
                    MediaFile(bytes, format)
                }
                401 -> throw ApiError.Unauthorized
                else -> throw ApiError.Http(
                    response.code,
                    if (response.encoded) null else response.bytesAtMost(BODY_MAX).takeIf { it.size <= BODY_MAX }?.utf8()?.let(::codeOf),
                )
            }
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

    /** These calls ask for the body as sent, so OkHttp neither negotiates nor transparently decodes gzip (no bound on that). */
    private fun identity(url: HttpUrl) = request(url).header("Accept-Encoding", "identity")

    /** A body in any encoding but identity is not one these calls read. */
    private val Response.encoded get() = headers("Content-Encoding").any { !it.trim().equals("identity", ignoreCase = true) }

    /** Strict UTF-8, or null. */
    private fun ByteArray.utf8(): String? = try {
        decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }

    /** At most [limit] + 1 bytes of the body, so "over the limit" is known without reading the rest. */
    private fun Response.bytesAtMost(limit: Long): ByteArray = body.source().run {
        request(limit + 1)
        buffer.readByteArray(minOf(buffer.size, limit + 1))
    }

    /** The ping mapping of spec 5.3 for a 200 body, in its order. */
    private fun pingOf(body: ByteArray): MediaPing {
        val error = MediaPing(AddonStatus.ERROR, null)
        if (body.size > BODY_MAX) return error
        val text = body.utf8()?.takeIf(::isJson) ?: return error
        val o = json.parseToJsonElement(text) as? JsonObject ?: return error
        fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        // A JSON integer: an unquoted literal without fraction or exponent, of any size; never a boolean or a string.
        fun integer(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString && integerFormat.matches(it.content) }?.content?.toBigInteger()
        if (string("name") != "apofis-media") return error
        val protocol = integer("protocol")
        val pin = APP_PROTOCOL.toBigInteger()
        if (protocol != null && protocol > pin) return MediaPing(AddonStatus.APP_OLD, null)
        if (protocol != pin || o["ready"] != JsonPrimitive(true)) return MediaPing(AddonStatus.NOT_READY, null)
        val maxBytes = integer("max_bytes")?.takeIf { it.signum() > 0 } ?: return error
        val types = (o["types"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return error } ?: return error
        return MediaPing(AddonStatus.READY, AddonLimits(maxBytes.min(MAX_PICTURE.toBigInteger()).toInt(), types.mapNotNull(OFFER_TYPES::get).toSet()))
    }

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
     * [stream] keeps the client's overall deadline. With [checked] false, [onSuccess] gets every status, not only 2xx.
     */
    private suspend fun <T> exchange(request: Request, stream: Boolean = false, checked: Boolean = true, onSuccess: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        if (stream) call.timeout().clearTimeout() // an event stream lasts as long as its run
        val work = async(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (checked && !response.isSuccessful) throw errorOf(response)
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
        val code = codeOf(text)
        return if (response.code == 409 && code != null) ApiError.Conflict(code) else ApiError.Http(response.code, code)
    }

    // Only a short machine code survives, and never one that echoes the key.
    private fun codeOf(text: String): String? = runCatching { json.decodeFromString<ErrorEnvelope>(text).error.code }.getOrNull()
        ?.takeIf { codeFormat.matches(it) && apiKey !in it }

    companion object {
        /** One id rule for every method: a single path segment that cannot change the target resource. */
        private fun validId(id: String): String = id.also {
            require(it.isNotEmpty() && '/' !in it && '\\' !in it && it != "." && it != "..") { "invalid id" }
        }

        private val JSON = "application/json".toMediaType()
        private val codeFormat = Regex("[a-z0-9_]{1,64}")
        private val integerFormat = Regex("-?[0-9]+")

        /** Ping bodies and error bodies are read to this many bytes plus one. */
        private const val BODY_MAX = 4096L

        /** The ping validator's nesting bound, the JSON library's own default (400 containers, the outer one counted). */
        private const val MAX_DEPTH = 400

        /**
         * True when [s] is exactly one RFC 8259 JSON value, at most [MAX_DEPTH] containers deep. The tree reader alone is not
         * enough: it keeps raw control characters in strings and names, and drops a bad value that a later member replaces.
         * Internal, not private: the library also refuses trailing text and deeper nesting, so only a direct test sees those rules.
         */
        internal fun isJson(s: String): Boolean {
            var i = 0
            fun at(c: Char) = i < s.length && s[i] == c
            fun eat(c: Char) = at(c).also { if (it) i++ }
            fun ws() {
                while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
            }
            fun digits(): Boolean {
                val start = i
                while (i < s.length && s[i] in '0'..'9') i++
                return i > start
            }
            fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            fun string(): Boolean {
                if (!eat('"')) return false
                while (i < s.length) {
                    val c = s[i++]
                    when {
                        c == '"' -> return true
                        c < ' ' -> return false
                        c == '\\' -> when {
                            eat('u') -> if (i + 4 <= s.length && (i until i + 4).all { hex(s[it]) }) i += 4 else return false
                            i < s.length && s[i] in "\"\\/bfnrt" -> i++
                            else -> return false
                        }
                    }
                }
                return false
            }
            fun number(): Boolean {
                eat('-')
                if (!eat('0') && !digits()) return false
                if (eat('.') && !digits()) return false
                if (eat('e') || eat('E')) {
                    if (!eat('+')) eat('-')
                    if (!digits()) return false
                }
                return true
            }
            fun literal(word: String) = s.startsWith(word, i).also { if (it) i += word.length }
            fun value(depth: Int): Boolean {
                ws()
                val ok = when {
                    at('{') || at('[') -> {
                        if (depth >= MAX_DEPTH) return false
                        val close = if (eat('{')) '}' else ']'.also { i++ }
                        ws()
                        if (eat(close)) return true.also { ws() }
                        do {
                            ws()
                            if (close == '}' && !(string() && run { ws(); eat(':') })) return false
                            if (!value(depth + 1)) return false
                        } while (eat(','))
                        eat(close)
                    }
                    at('"') -> string()
                    at('t') -> literal("true")
                    at('f') -> literal("false")
                    at('n') -> literal("null")
                    else -> number()
                }
                ws()
                return ok
            }
            return value(0) && i == s.length
        }
        private const val MAX_PICTURE = 10 * 1024 * 1024
        private val OFFER_TYPES = mapOf("png" to ImageFormat.PNG, "jpeg" to ImageFormat.JPEG, "jpg" to ImageFormat.JPEG, "gif" to ImageFormat.GIF, "webp" to ImageFormat.WEBP)
        private val PICTURE_TYPES = mapOf("image/png" to ImageFormat.PNG, "image/jpeg" to ImageFormat.JPEG, "image/gif" to ImageFormat.GIF, "image/webp" to ImageFormat.WEBP)
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

data class MediaPing(val status: AddonStatus, val limits: AddonLimits?)

class MediaFile(val bytes: ByteArray, val format: ImageFormat)
