package xyz.rycerz.apofis.api

import java.io.IOException
import java.io.InterruptedIOException
import java.lang.management.ManagementFactory
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
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.GzipSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xyz.rycerz.apofis.images.ImageFormat
import xyz.rycerz.apofis.images.REPLY_FORMATS
import xyz.rycerz.apofis.media.APP_PROTOCOL
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

    /** The next recorded request, waiting at most 10 s. */
    private fun taken() = server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("no request reached the server")

    /** Raw bytes as a body, so a test can send what a string cannot hold. */
    private fun respondBytes(code: Int, body: ByteArray, type: String, vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).addHeader("Content-Type", type).body(Buffer().write(body))
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun gzip(bytes: ByteArray) = Buffer().also { out -> GzipSink(out).buffer().use { it.write(bytes) } }.readByteArray()

    /**
     * A client with a short test-only deadline that records how the call failed, so a cancellation test ends within
     * 10 s and can tell a cancel (a closed socket) from that deadline (an InterruptedIOException).
     */
    private class Cancelling {
        @Volatile var failure: IOException? = null
        val headers = CountDownLatch(1)
        val client = HermesApi.defaultClient().newBuilder().callTimeout(5, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain -> chain.proceed(chain.request()).also { headers.countDown() } }
            .eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) {
                    failure = ioe
                }
            }).build()

        fun assertCancelled() {
            assertNotNull("the call did not fail", failure)
            assertFalse("the deadline, not the cancel, ended the call: $failure", failure is InterruptedIOException)
        }
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
        protocol: String? = "$APP_PROTOCOL",
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

        private val fill = ByteArray(8192) { filler }

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pulled >= total) return -1
            val n = minOf(byteCount, total - pulled)
            val end = pulled + n
            while (pulled < end) {
                if (pulled < prefix.size) {
                    sink.writeByte(prefix[pulled.toInt()].toInt())
                    pulled++
                } else {
                    val k = minOf(end - pulled, fill.size.toLong()).toInt()
                    sink.write(fill, 0, k)
                    pulled += k
                }
            }
            return n
        }

        override fun timeout() = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    /**
     * An API whose every answer is built in-process from [source]: the interceptor never proceeds, so no socket is
     * opened. The base URL is still the test's own server. [onCall] runs on the thread that will read the body.
     */
    private fun recorded(code: Int, type: String?, source: RecordingSource, declared: Long = -1, onCall: () -> Unit = {}): HermesApi {
        val client = HermesApi.defaultClient().newBuilder().addInterceptor { chain ->
            onCall()
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(source.buffer().asResponseBody(type?.toMediaType(), declared))
            if (type != null) builder.header("Content-Type", type)
            if (declared >= 0) builder.header("Content-Length", "$declared")
            builder.build()
        }.build()
        return HermesApi(server.url("/").toString(), "secret-key", client)
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
        assertEquals(AddonLimits(10485760, setOf(ImageFormat.JPEG)), pingOf(pingBody(types = """["jpg","PNG"]""")).limits)
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
            assertEquals("$name", MediaPing(AddonStatus.ERROR, null), pingOf(pingBody(name = name, protocol = "${APP_PROTOCOL + 1}")))
        }
    }

    @Test
    fun pingNewerProtocolIsAppOld() = runTest {
        assertEquals(MediaPing(AddonStatus.APP_OLD, null), pingOf(pingBody(protocol = "${APP_PROTOCOL + 1}", ready = "false")))
        assertEquals(MediaPing(AddonStatus.APP_OLD, null), pingOf(pingBody(protocol = "9".repeat(25))))
    }

    @Test
    fun pingNotReady() = runTest {
        for (protocol in listOf(null, "\"$APP_PROTOCOL\"", "$APP_PROTOCOL.0", "${APP_PROTOCOL}e0", "0", "true")) {
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
        val cancelling = Cancelling()
        val stalled = HermesApi(server.url("/").toString(), "secret-key", cancelling.client)
        var caught: Throwable? = null
        var result: MediaPing? = null
        val call = launch(Dispatchers.Default) {
            try { result = stalled.mediaPing() } catch (e: Throwable) { caught = e }
        }
        taken()
        call.cancelAndJoin()
        assertNull(result)
        assertTrue("$caught", caught is CancellationException)
        cancelling.assertCancelled()
    }

    @Test
    fun pingKeepsBasePrefix() = runTest {
        respond(200, READY_BODY)
        HermesApi(server.url("/prefix/").toString(), "secret-key").mediaPing()
        val req = taken()
        assertEquals("identity", req.headers["Accept-Encoding"])
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
        val req = taken()
        assertEquals("identity", req.headers["Accept-Encoding"])
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
        val body = taken().body!!.utf8()
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
        for ((bytes, type, format) in cases + Triple(PNG, "Image/PNG; foo=bar", ImageFormat.PNG)) {
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
        assertTrue(source.closed)
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
        val cancelling = Cancelling()
        val watched = HermesApi(server.url("/").toString(), "secret-key", cancelling.client)
        var caught: Throwable? = null
        var result: MediaFile? = null
        val call = launch(Dispatchers.Default) {
            try { result = watched.mediaFile(PATH, small) } catch (e: Throwable) { caught = e }
        }
        assertTrue(cancelling.headers.await(10, TimeUnit.SECONDS))
        call.cancelAndJoin()
        assertNull(result)
        assertTrue("$caught", caught is CancellationException)
        cancelling.assertCancelled()
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

    // ---- fix round 1 ----

    @Test
    fun pingEncodedIsError() = runTest {
        respond(200, READY_BODY, "application/json", "Content-Encoding" to "br")
        assertEquals(MediaPing(AddonStatus.ERROR, null), api.mediaPing())
        respondBytes(200, gzip(READY_BODY.encodeToByteArray()), "application/json", "Content-Encoding" to "gzip")
        assertEquals(MediaPing(AddonStatus.ERROR, null), api.mediaPing())
        assertEquals("identity", taken().headers["Accept-Encoding"])
    }

    @Test
    fun fileEncodedOverCapIs413() = runTest {
        // A small picture whose gzip representation is over the cap: the declared (encoded) length decides.
        val picture = PNG + kotlin.random.Random(7).nextBytes(40)
        val encoded = gzip(picture)
        assertTrue(picture.size <= 64 && encoded.size > 64)
        respondBytes(200, encoded, "image/png", "Content-Encoding" to "gzip")
        assertEquals(ApiError.Http(413, null), fileError())
        assertEquals("identity", taken().headers["Accept-Encoding"])
    }

    @Test
    fun fileEncodedAnswerIsRefused() = runTest {
        respondBytes(200, PNG, "image/png", "Content-Encoding" to "br")
        assertEquals(ApiError.Protocol, fileError())
        respondBytes(200, gzip(PNG), "image/png", "Content-Encoding" to "gzip")
        assertEquals(ApiError.Protocol, fileError())
        respondBytes(200, PNG, "image/png", "Content-Encoding" to "identity")
        assertArrayEquals(PNG, api.mediaFile(PATH, small).bytes)
    }

    @Test
    fun fileEncodedErrorBodyHasNoCode() = runTest {
        val body = """{"error":{"code":"file_unavailable"}}"""
        respond(404, body, "application/json", "Content-Encoding" to "br")
        assertEquals(ApiError.Http(404, null), fileError())
        respondBytes(404, gzip(body.encodeToByteArray()), "application/json", "Content-Encoding" to "gzip")
        assertEquals(ApiError.Http(404, null), fileError())
    }

    @Test
    fun fileSniffGetsOnlyTheHead() = runTest {
        // An ISO box header with a huge size: sniff walks brands across the whole box when it gets the whole body.
        val cap = 4 shl 20
        val head = bytes(0x7F, 0xFF, 0xFF, 0xFF) + "ftypisom".encodeToByteArray()
        val source = RecordingSource(head, total = cap - 16L, filler = 0)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        var thread = 0L
        var before = 0L
        val on = recorded(200, "image/png", source) {
            thread = Thread.currentThread().id
            before = bean.getThreadAllocatedBytes(thread)
        }
        assertEquals(ApiError.Protocol, fileError(AddonLimits(cap, REPLY_FORMATS), on))
        // Reading the body allocates about twice its size; a brand walk over it allocates about twenty times.
        val used = bean.getThreadAllocatedBytes(thread) - before
        assertTrue("allocated $used for a body of $cap", used < 4L * cap)
    }

    @Test
    fun pingRefusesMalformedJson() = runTest {
        assertEquals(MediaPing(AddonStatus.ERROR, null), pingOf(pingBody(protocol = "0$APP_PROTOCOL")))
        assertEquals(MediaPing(AddonStatus.ERROR, null), pingOf(pingBody(maxBytes = "0010")))
        assertEquals(MediaPing(AddonStatus.ERROR, null), pingOf(READY_BODY.dropLast(1) + ""","extra":bogus}"""))
        assertEquals(MediaPing(AddonStatus.ERROR, null), pingOf(READY_BODY.dropLast(1) + ""","extra":[1,{"a":007}]}"""))
        val valid = READY_BODY.dropLast(1) + ""","extra":{"s":"text","n":-0.5e+3,"o":{"a":[true,false,null,0]}}}"""
        assertEquals(MediaPing(AddonStatus.READY, AddonLimits(10485760, REPLY_FORMATS)), pingOf(valid))
    }

    @Test
    fun pingRefusesInvalidUtf8() = runTest {
        val body = READY_BODY.dropLast(1).encodeToByteArray() + ""","note":"""".encodeToByteArray() + bytes(0xFF) + """"}""".encodeToByteArray()
        respondBytes(200, body, "application/json")
        assertEquals(MediaPing(AddonStatus.ERROR, null), api.mediaPing())
    }

    @Test
    fun fileErrorBodyInvalidUtf8HasNoCode() = runTest {
        val body = """{"error":{"code":"file_unavailable","message":"""".encodeToByteArray() + bytes(0xC3, 0x28) + """"}}""".encodeToByteArray()
        respondBytes(404, body, "application/json")
        assertEquals(ApiError.Http(404, null), fileError())
    }

    private companion object {
        const val PATH = "/srv/out/chart.png"
        val READY_BODY = """{"name":"apofis-media","protocol":$APP_PROTOCOL,"ready":true,"max_bytes":10485760,"types":["png","jpeg","gif","webp"]}"""
        val small = AddonLimits(64, REPLY_FORMATS)

        fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
        val PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "data".encodeToByteArray()
        val JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0) + "data".encodeToByteArray()
        val GIF = "GIF89a-data".encodeToByteArray()
        val WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPdata".encodeToByteArray()
    }
}
