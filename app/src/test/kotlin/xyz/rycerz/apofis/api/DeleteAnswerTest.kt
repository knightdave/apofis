package xyz.rycerz.apofis.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** R10: a DELETE returns only for the two answers that mean the session is gone, and throws for everything else. */
class DeleteAnswerTest {
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

    private fun respond(code: Int, body: String, type: String = "application/json") =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", type).build())

    private suspend fun deleteError(id: String = "s"): ApiError {
        try {
            api.deleteSession(id)
        } catch (e: ApiError) {
            return e
        }
        fail("expected ApiError")
        error("unreachable")
    }

    private val html = "<html><body>Not here</body></html>"

    @Test
    fun wrongIdThrows() = runTest {
        respond(200, """{"object":"hermes.session.deleted","id":"other","deleted":true}""")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun wrongObjectThrows() = runTest {
        respond(200, """{"object":"hermes.session","id":"s","deleted":true}""")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun numericIdThrows() = runTest {
        respond(200, """{"object":"hermes.session.deleted","id":123,"deleted":true}""")
        assertEquals(ApiError.Protocol, deleteError("123"))
    }

    @Test
    fun genuineBodyWithOtherSuccessStatusThrows() = runTest {
        for (code in listOf(201, 202)) {
            respond(code, """{"object":"hermes.session.deleted","id":"s","deleted":true}""")
            assertEquals("status $code", ApiError.Protocol, deleteError())
        }
    }

    @Test
    fun notFoundCodeIsFiltered() = runTest {
        // The delete path uses the shared filter: a code outside the machine-code shape, or one that echoes the key, is dropped.
        respond(404, """{"error":{"code":"secret-key"}}""")
        assertEquals(ApiError.Http(404, null), deleteError())
        api = HermesApi(server.url("/").toString(), "key_0123456789abcdef")
        respond(404, """{"error":{"code":"key_0123456789abcdef"}}""")
        assertEquals(ApiError.Http(404, null), deleteError())
    }

    @Test
    fun missingFlagThrows() = runTest {
        respond(200, """{"object":"hermes.session.deleted","id":"s"}""")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun stringFlagThrows() = runTest {
        respond(200, """{"object":"hermes.session.deleted","id":"s","deleted":"true"}""")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun deletedFalseThrows() = runTest {
        respond(200, """{"object":"hermes.session.deleted","id":"s","deleted":false}""")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun html200Throws() = runTest {
        respond(200, html, "text/html")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun html404Throws() = runTest {
        respond(404, html, "text/html")
        assertEquals(ApiError.Http(404, null), deleteError())
    }

    @Test
    fun genericJson404Throws() = runTest {
        respond(404, """{"error":{"code":"not_found"}}""")
        assertEquals(ApiError.Http(404, "not_found"), deleteError())
    }

    @Test
    fun empty200Throws() = runTest {
        respond(200, "")
        assertEquals(ApiError.Protocol, deleteError())
    }

    @Test
    fun genuineSuccessReturns() = runTest {
        respond(200, """{"object":"hermes.session.deleted","id":"s","deleted":true}""")
        api.deleteSession("s")
        assertEquals(1, server.requestCount) // a Delete that sent nothing fails here instead of waiting for a request
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/sessions/s", req.url.encodedPath)
    }

    @Test
    fun genuineNotFoundReturns() = runTest {
        respond(
            404,
            """{"error":{"message":"Session not found: s","type":"invalid_request_error","param":null,"code":"session_not_found"}}""",
        )
        api.deleteSession("s")
    }

    @Test
    fun conflictThrows() = runTest {
        respond(409, """{"error":{"message":"busy","type":"invalid_request_error","param":null,"code":"session_active_turn"}}""")
        assertEquals(ApiError.Conflict("session_active_turn"), deleteError())
    }

    @Test
    fun proxyErrorsThrow() = runTest {
        respond(502, html, "text/html")
        assertEquals(ApiError.Http(502, null), deleteError())
        respond(503, html, "text/html")
        assertEquals(ApiError.Unavailable(null), deleteError())
        respond(504, html, "text/html")
        assertEquals(ApiError.Http(504, null), deleteError())
    }

    @Test
    fun otherCallsUnchanged() = runTest {
        respond(404, fixture("error_404.json"))
        try {
            api.getSession("s")
            fail("expected ApiError.NotFound")
        } catch (e: ApiError) {
            assertEquals(ApiError.NotFound, e)
        }
    }
}
