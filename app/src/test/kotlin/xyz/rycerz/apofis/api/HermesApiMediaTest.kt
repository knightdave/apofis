package xyz.rycerz.apofis.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xyz.rycerz.apofis.images.ImageFormat
import xyz.rycerz.apofis.images.REPLY_FORMATS
import xyz.rycerz.apofis.media.AddonLimits
import xyz.rycerz.apofis.media.AddonStatus

class HermesApiMediaTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HermesApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HermesApi(server.url("/").toString(), "secret-key")
    }

    @After
    fun tearDown() = server.close()

    // ---- helpers ----

    private fun respond(code: Int, body: String, type: String? = "application/json", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        if (type != null) builder.addHeader("Content-Type", type)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun picture(bytes: ByteArray, type: String?, chunked: Boolean = false, code: Int = 200) {
        val builder = MockResponse.Builder().code(code)
        if (type != null) builder.addHeader("Content-Type", type)
        if (chunked) builder.chunkedBody(Buffer().write(bytes), 16) else builder.body(Buffer().write(bytes))
        server.enqueue(builder.build())
    }

    /** A JSON object from raw JSON field values; a null value leaves the field out. */
    private fun pingBody(
        name: String? = "\"apofis-media\"",
        protocol: String? = "1",
        ready: String? = "true",
        maxBytes: String? = "10485760",
        types: String? = """["png","jpeg","gif","webp"]""",
    ): String = listOf("name" to name, "protocol" to protocol, "ready" to ready, "max_bytes" to maxBytes, "types" to types)
        .filter { it.second != null }.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }

    private suspend fun pingOf(body: String): MediaPing {
        respond(200, body)
        return api.mediaPing()
    }

    private suspend fun fileError(limits: AddonLimits = small, on: HermesApi = api): ApiError {
        try {
            on.mediaFile(PATH, limits)
        } catch (e: ApiError) {
            return e
        }
        fail("expected an ApiError")
        throw AssertionError()
    }

    /** A client that never retries, so one enqueued socket failure is one request. */
    private fun noRetry() = HermesApi(server.url("/").toString(), "secret-key", HermesApi.defaultClient().newBuilder().retryOnConnectionFailure(false).build())

    /** Offers [prefix], then [filler] up to [total] bytes; counts what is pulled and whether it was closed. */
    private class RecordingSource(private val prefix: ByteArray, private val total: Long = 1L shl 20, private val filler: Byte = ' '.code.toByte()) : Source {
        var pulled = 0L
        var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pulled >= total) return -1
            val start = pulled
            val n = minOf(byteCount, total - pulled).toInt()
            sink.write(ByteArray(n) { i -> (start + i).let { at -> if (at < prefix.size) prefix[at.toInt()] else filler } })
            pulled += n
            return n.toLong()
        }

        override fun timeout() = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    /** An API whose every answer is built in-process from [source]: no network, no socket, no name lookup. */
    private fun recorded(code: Int, type: String?, source: RecordingSource, declared: Long = -1): HermesApi {
        val client = HermesApi.defaultClient().newBuilder().addInterceptor { chain ->
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(source.buffer().asResponseBody(type?.toMediaType(), declared))
            if (type != null) builder.header("Content-Type", type)
            if (declared >= 0) builder.header("Content-Length", "$declared")
            builder.build()
        }.build()
        return HermesApi("http://media.test/", "secret-key", client)
    }

    // ---- ping ----

    @Test
    fun pingReadyGivesLimits() = runTest {
        assertEquals(MediaPing(AddonStatus.READY, AddonLimits(10485760, REPLY_FORMATS)), pingOf(READY_BODY))
    }

    @Test
    fun pingLimitsAreClamped() = runTest {
        val clamped = pingOf(pingBody(maxBytes = "52428800", types = """["png","jpg","bmp","PNG"]"""))
        assertEquals(MediaPing(AddonStatus.READY, AddonLimits(10485760, setOf(ImageFormat.PNG, ImageFormat.JPEG))), clamped)
        assertEquals(AddonLimits(10485760, REPLY_FORMATS), pingOf(pingBody(maxBytes = "1".repeat(25))).limits)
        assertEquals(AddonLimits(1000, REPLY_FORMATS), pingOf(pingBody(maxBytes = "1000")).limits)
    }

    @Test
    fun pingRouteMissingIsNeeded() = runTest {
        respond(404, "404: Not Found", "text/plain")
        respond(404, """{"error":{"code":"file_unavailable"}}""")
        respond(405, "")
        repeat(3) { assertEquals("case $it", MediaPing(AddonStatus.NEEDED, null), api.mediaPing()) }
    }

    @Test
    fun pingFailuresAreError() = runTest {
        respond(401, fixture("error_401.json"))
        respond(500, """{"error":{"code":"internal"}}""")
        respond(503, """{"error":{"code":"busy"}}""")
        respond(302, "", null, "Location" to server.url("/elsewhere").toString())
        respond(200, "<html>login</html>", "text/html")
        respond(200, "[1]")
        repeat(6) { assertEquals("case $it", MediaPing(AddonStatus.ERROR, null), api.mediaPing()) }
        assertEquals(6, server.requestCount)
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertEquals(MediaPing(AddonStatus.ERROR, null), noRetry().mediaPing())
    }

    @Test
    fun pingOverLimitIsError() = runTest {
        assertEquals(MediaPing(AddonStatus.ERROR, null), pingOf(READY_BODY.padEnd(4097)))
        assertEquals(AddonStatus.READY, pingOf(READY_BODY.padEnd(4096)).status)

        val source = RecordingSource(READY_BODY.encodeToByteArray())
        assertEquals(MediaPing(AddonStatus.ERROR, null), recorded(200, "application/json", source).mediaPing())
        assertTrue("pulled ${source.pulled}", source.pulled <= 4097 + 8192)
        assertTrue(source.closed)
    }

    @Test
    fun pingNameIsCheckedFirst() = runTest {
        for (name in listOf("\"other\"", null, "5")) {
            assertEquals("$name", MediaPing(AddonStatus.ERROR, null), pingOf(pingBody(name = name, protocol = "2")))
        }
    }

    @Test
    fun pingNewerProtocolIsAppOld() = runTest {
        assertEquals(MediaPing(AddonStatus.APP_OLD, null), pingOf(pingBody(protocol = "2", ready = "false")))
        assertEquals(MediaPing(AddonStatus.APP_OLD, null), pingOf(pingBody(protocol = "9".repeat(25))))
    }

    @Test
    fun pingNotReady() = runTest {
        for (protocol in listOf(null, "\"1\"", "1.0", "1e0", "0", "true")) {
            assertEquals("protocol $protocol", MediaPing(AddonStatus.NOT_READY, null), pingOf(pingBody(protocol = protocol)))
        }
        for (ready in listOf("false", "\"true\"", "1", null)) {
            assertEquals("ready $ready", MediaPing(AddonStatus.NOT_READY, null), pingOf(pingBody(ready = ready)))
        }
    }

    @Test
    fun pingReadyNeedsValidLimits() = runTest {
        for (max in listOf("0", "-1", "true", "\"10\"", "1.5", null)) {
            assertEquals("max_bytes $max", MediaPing(AddonStatus.ERROR, null), pingOf(pingBody(maxBytes = max)))
        }
        for (types in listOf(null, "\"png\"", "[1]", "[true]", """["png", null]""")) {
            assertEquals("types $types", MediaPing(AddonStatus.ERROR, null), pingOf(pingBody(types = types)))
        }
        assertEquals(MediaPing(AddonStatus.READY, AddonLimits(10485760, emptySet())), pingOf(pingBody(types = "[]")))
    }

    @Test
    fun pingCancellationPropagates() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        var caught: Throwable? = null
        var result: MediaPing? = null
        val call = launch(Dispatchers.Default) {
            try { result = api.mediaPing() } catch (e: Throwable) { caught = e }
        }
        assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertNull(result)
        assertTrue("$caught", caught is CancellationException)
    }

    @Test
    fun pingKeepsBasePrefix() = runTest {
        respond(200, READY_BODY)
        HermesApi(server.url("/prefix/").toString(), "secret-key").mediaPing()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/prefix/apofis-media/v1/ping", req.url.encodedPath)
        assertEquals("Bearer secret-key", req.headers["Authorization"])
        assertEquals(1, server.requestCount)
    }

    // ---- file ----

    @Test
    fun fileRequestShape() = runTest {
        val prefixed = HermesApi(server.url("/prefix/").toString(), "secret-key")
        repeat(2) { picture(PNG, "image/png") }
        prefixed.mediaFile(PATH, small)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/prefix/apofis-media/v1/file", req.url.encodedPath)
        assertEquals("Bearer secret-key", req.headers["Authorization"])
        val type = req.headers["Content-Type"]!!.toMediaType()
        assertEquals("application/json", "${type.type}/${type.subtype}")
        assertTrue("$type", type.charset() == null || type.charset() == Charsets.UTF_8)
        assertNotNull(req.headers["Content-Length"])
        assertNull(req.headers["Transfer-Encoding"])
        assertNull(req.headers["Content-Encoding"])
        assertEquals("""{"path":"/srv/out/chart.png"}""", req.body!!.utf8())

        val odd = "/srv/out/a\"bé.png"
        prefixed.mediaFile(odd, small)
        val body = server.takeRequest().body!!.utf8()
        assertEquals("{\"path\":\"/srv/out/a\\\"bé.png\"}", body)
        assertEquals(odd, Json.parseToJsonElement(body).jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun fileAcceptsEachFormat() = runTest {
        val cases = listOf(
            Triple(PNG, "image/png", ImageFormat.PNG),
            Triple(JPEG, "image/jpeg", ImageFormat.JPEG),
            Triple(GIF, "image/gif", ImageFormat.GIF),
            Triple(WEBP, "image/webp", ImageFormat.WEBP),
        )
        for ((bytes, type, format) in cases) {
            picture(bytes, type)
            val file = api.mediaFile(PATH, small)
            assertArrayEquals(type, bytes, file.bytes)
            assertEquals(type, format, file.format)
        }
    }

    @Test
    fun fileExactlyAtCapIsAccepted() = runTest {
        val atCap = PNG + ByteArray(64 - PNG.size)
        picture(atCap, "image/png")
        picture(atCap, "image/png", chunked = true)
        repeat(2) { assertArrayEquals("case $it", atCap, api.mediaFile(PATH, small).bytes) }
    }

    @Test
    fun fileMismatchedTypeIsProtocol() = runTest {
        picture(JPEG, "image/png")
        picture(PNG, null)
        picture(PNG, "text/plain")
        picture(JPEG, "image/jpg")
        repeat(4) { assertEquals("case $it", ApiError.Protocol, fileError()) }
    }

    @Test
    fun fileFormatOutsideTypesIsProtocol() = runTest {
        picture(JPEG, "image/jpeg")
        assertEquals(ApiError.Protocol, fileError(AddonLimits(64, setOf(ImageFormat.PNG))))
    }

    @Test
    fun fileUnknownSignatureIsProtocol() = runTest {
        picture("hello, not a picture".encodeToByteArray(), "image/png")
        picture(ByteArray(0), "image/png")
        repeat(2) { assertEquals("case $it", ApiError.Protocol, fileError()) }
    }

    @Test
    fun fileDeclaredLengthOverCapIs413() = runTest {
        val source = RecordingSource(PNG)
        assertEquals(ApiError.Http(413, null), fileError(on = recorded(200, "image/png", source, declared = 65)))
        assertEquals(0L, source.pulled)
    }

    @Test
    fun fileChunkedOverCapIs413() = runTest {
        picture(PNG + ByteArray(65 - PNG.size), "image/png", chunked = true)
        assertEquals(ApiError.Http(413, null), fileError())

        val source = RecordingSource(PNG)
        assertEquals(ApiError.Http(413, null), fileError(on = recorded(200, "image/png", source)))
        assertTrue("pulled ${source.pulled}", source.pulled <= 65 + 8192)
        assertTrue(source.closed)
    }

    @Test
    fun fileOversizeWinsOverWrongType() = runTest {
        picture(PNG + ByteArray(65 - PNG.size), "text/plain", chunked = true)
        picture(ByteArray(65) { 'x'.code.toByte() }, "image/png", chunked = true)
        repeat(2) { assertEquals("case $it", ApiError.Http(413, null), fileError()) }
    }

    @Test
    fun fileCancellationPropagates() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(PNG)).onResponseBody(SocketEffect.Stall).build())
        val headers = CountDownLatch(1)
        val client = HermesApi.defaultClient().newBuilder().addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { headers.countDown() } }.build()
        val watched = HermesApi(server.url("/").toString(), "secret-key", client)
        var caught: Throwable? = null
        var result: MediaFile? = null
        val call = launch(Dispatchers.Default) {
            try { result = watched.mediaFile(PATH, small) } catch (e: Throwable) { caught = e }
        }
        assertTrue(headers.await(10, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertNull(result)
        assertTrue("$caught", caught is CancellationException)
    }

    @Test
    fun fileErrorStatuses() = runTest {
        val cases = listOf(
            Triple(404, """{"error":{"code":"file_unavailable","message":"..."}}""", ApiError.Http(404, "file_unavailable")),
            Triple(404, "", ApiError.Http(404, null)),
            Triple(404, """{"error":{"code":"other_code"}}""", ApiError.Http(404, "other_code")),
            Triple(413, """{"error":{"code":"too_large"}}""", ApiError.Http(413, "too_large")),
            Triple(415, """{"error":{"code":"not_a_picture"}}""", ApiError.Http(415, "not_a_picture")),
            Triple(503, """{"error":{"code":"busy"}}""", ApiError.Http(503, "busy")),
            Triple(500, """{"error":{"code":"internal"}}""", ApiError.Http(500, "internal")),
            Triple(400, """{"error":{"code":"bad_request"}}""", ApiError.Http(400, "bad_request")),
            Triple(401, fixture("error_401.json"), ApiError.Unauthorized),
        )
        for ((code, body, expected) in cases) {
            respond(code, body)
            assertEquals("$code $body", expected, fileError())
        }
    }

    @Test
    fun fileErrorBodyReadAtMost4KiB() = runTest {
        val body = """{"error":{"code":"file_unavailable"}}"""
        respond(404, body.padEnd(4096))
        assertEquals(ApiError.Http(404, "file_unavailable"), fileError())
        respond(404, body.padEnd(4097))
        assertEquals(ApiError.Http(404, null), fileError())

        val source = RecordingSource(body.encodeToByteArray())
        assertEquals(ApiError.Http(404, null), fileError(on = recorded(404, "application/json", source)))
        assertTrue("pulled ${source.pulled}", source.pulled <= 4097 + 8192)
        assertTrue(source.closed)
    }

    @Test
    fun fileRedirectIsNotFollowed() = runTest {
        respond(302, "", null, "Location" to server.url("/elsewhere").toString())
        respond(200, "should not be reached", "text/plain")
        assertEquals(ApiError.Http(302, null), fileError())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fileNetworkFailureIsNetwork() = runTest {
        val api = noRetry()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        assertTrue(fileError(on = api) is ApiError.Network)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "image/png").body(Buffer().write(PNG)).onResponseBody(SocketEffect.CloseSocket()).build())
        assertTrue(fileError(on = api) is ApiError.Network)
        assertEquals(2, server.requestCount)
    }

    private companion object {
        const val PATH = "/srv/out/chart.png"
        const val READY_BODY = """{"name":"apofis-media","protocol":1,"ready":true,"max_bytes":10485760,"types":["png","jpeg","gif","webp"]}"""
        val small = AddonLimits(64, REPLY_FORMATS)

        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        val PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "data".encodeToByteArray()
        val JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0) + "data".encodeToByteArray()
        val GIF = "GIF89a-data".encodeToByteArray()
        val WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPdata".encodeToByteArray()
    }
}
