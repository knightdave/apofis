package xyz.rycerz.apofis.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HermesApiTest {
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

    private fun respond(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        server.enqueue(builder.build())
    }

    private fun serve(name: String, code: Int = 200) = respond(code, fixture(name))

    private suspend fun expectError(block: suspend () -> Unit): ApiError {
        try {
            block()
        } catch (e: ApiError) {
            return e
        }
        fail("expected ApiError")
        error("unreachable")
    }

    @Test
    fun requestUrls() = runTest {
        val bases = mapOf(
            "http://h:8642" to "http://h:8642/api/sessions?limit=200&offset=0",
            "http://h:8642/" to "http://h:8642/api/sessions?limit=200&offset=0",
            "http://h:8642/p/work" to "http://h:8642/p/work/api/sessions?limit=200&offset=0",
            "http://[200:1234::1]:8642" to "http://[200:1234::1]:8642/api/sessions?limit=200&offset=0",
        )
        for ((base, expected) in bases) {
            var seen: Request? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                seen = chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(fixture("sessions_list.json").toResponseBody()).build()
            }.build()
            HermesApi(base, "k3y", client).listSessions()
            assertEquals(expected, seen!!.url.toString())
            assertEquals("Bearer k3y", seen.header("Authorization"))
        }
    }

    @Test
    fun parsesEveryFixture() = runTest {
        val covered = mutableSetOf<String>()
        fun serveOnce(name: String, code: Int = 200) { covered += name; serve(name, code) }

        serveOnce("capabilities.json")
        assertEquals("hermes-agent", api.capabilities().platform)

        serveOnce("sessions_list.json")
        val list = api.listSessions()
        assertTrue(list.data.isNotEmpty() && list.data.all { it.id.startsWith("apofis-probe-") })
        assertFalse(list.hasMore)
        assertTrue(list.data[0].pinned)
        assertEquals(1795134090.0827117, list.data[0].lastActive!!, 0.0)
        assertEquals("", list.data[0].preview)
        assertNull(list.data[0].lineageRootId)
        assertEquals(emptyList<String>(), list.data[0].lineageIds)

        serveOnce("sessions_list_pinned_archived.json")
        api.listSessions().data.single().let { assertTrue(it.pinned && it.archived) }

        serveOnce("session_get_archived.json")
        api.getSession("x").let {
            assertTrue(it.archived)
            assertEquals("api_server", it.source)
            assertNull(it.lastActive)
        }
        serveOnce("session_get_model_locked.json")
        api.getSession("x")
        // Create receipts carry the same envelope as GET; createSession itself only returns a Boolean.
        serveOnce("session_create.json")
        api.getSession("x")
        serveOnce("session_create_foreign_source.json")
        assertEquals("cli", api.getSession("x").source)

        for (name in listOf("session_patch.json", "session_patch_archived.json", "session_patch_unarchived.json", "session_patch_foreign_source.json")) {
            serveOnce(name)
            api.patchSession("x", title = "t")
        }

        serveOnce("messages_tool_run.json")
        val page = api.messages("apofis-probe-1795131284-toolrun-86f6")
        assertEquals("apofis-probe-1795131284-toolrun-86f6", page.sessionId)
        assertEquals(7, page.messages.size)
        page.messages[0].let {
            assertEquals(16393L, it.id)
            assertEquals("user", it.role)
            assertEquals("internal_notification", it.displayKind)
            assertNull(it.toolCallsJson)
        }
        page.messages[1].let {
            assertEquals("tool_calls", it.finishReason)
            val calls = Json.parseToJsonElement(it.toolCallsJson!!)
            assertTrue(calls.toString().contains("call_bcfa62f8a89f42adb149a971df2bb7cf"))
        }
        page.messages[2].let {
            assertEquals("{\"output\": \"apofis-marker\", \"exit_code\": 0, \"error\": null}", it.content)
            assertEquals("terminal", it.toolName)
            assertEquals("call_bcfa62f8a89f42adb149a971df2bb7cf", it.toolCallId)
        }
        assertEquals("", page.messages[4].content) // session_meta row has content null
        serveOnce("messages_steer.json")
        assertTrue(api.messages("x").messages.any { it.displayKind == "steer" && it.content.contains("\u2014") })
        serveOnce("messages_after_stop.json")
        assertTrue(api.messages("x").messages.any { !it.reasoning.isNullOrEmpty() })

        serveOnce("run_start.json", 202)
        assertEquals(RunStart("run_ef57074dc1694db39e37045230b1425c", false), api.startRun("{}", "k"))
        serveOnce("run_start_replayed.json", 202)
        assertEquals(RunStart("run_ef57074dc1694db39e37045230b1425c", true), api.startRun("{}", "k"))

        serveOnce("run_status_queued.json")
        api.runStatus("r").let {
            assertEquals("queued", it.status)
            assertNull(it.executionGeneration)
            assertEquals("8d62dd82f819401d88ba8c58e89fe70c", it.admissionId)
            assertEquals(emptyList<PendingControl>(), it.pendingControls)
        }
        serveOnce("run_status_running.json")
        assertEquals(1L, api.runStatus("r").executionGeneration)
        serveOnce("run_status_completed.json")
        api.runStatus("r").let {
            assertEquals("completed", it.status)
            assertEquals("The command completed successfully: apofis-marker.", it.output)
            assertEquals("apofis-probe-1795131284-toolrun-86f6", it.sessionId)
            assertNull(it.error)
        }
        serveOnce("run_status_completed_queued.json")
        assertEquals(2L, api.runStatus("r").executionGeneration)
        serveOnce("run_status_cancelled.json")
        assertEquals("cancelled", api.runStatus("r").status)

        // Bodies the client reads only for success: the calls must accept them.
        serveOnce("steer_response.json")
        api.steer("r", "x")
        serveOnce("run_stop.json")
        api.stop("r")
        serveOnce("session_delete.json")
        api.deleteSession("x")
        serveOnce("session_delete_foreign_source.json")
        api.deleteSession("x")

        serveOnce("session_exists_409.json", 409)
        assertFalse(api.createSession("x", "t"))

        val errors = listOf(
            Triple("error_401.json", 401, ApiError.Unauthorized),
            Triple("error_401_wrong_key.json", 401, ApiError.Unauthorized),
            Triple("error_404.json", 404, ApiError.NotFound),
            Triple("error_404_run.json", 404, ApiError.NotFound),
            Triple("run_foreign_source.json", 409, ApiError.Conflict("permission_denied")),
            Triple("run_idempotency_conflict_409.json", 409, ApiError.Conflict("idempotency_key_conflict")),
            Triple("session_recreate_deleted_409.json", 409, ApiError.Conflict("session_deleted")),
            Triple("session_patch_unsupported_400.json", 400, ApiError.Http(400, "unsupported_session_field")),
        )
        for ((name, code, expected) in errors) {
            serveOnce(name, code)
            assertEquals(name, expected, expectError { api.getSession("x") })
        }

        // SSE fixtures are parsed in SseReaderTest. session_model_lock.json has no endpoint in this task.
        val notHere = fixtureNames().filter { it.endsWith(".sse") } + "session_model_lock.json"
        assertEquals(fixtureNames() - notHere.toSet(), covered)
    }

    @Test
    fun createSessionTwice() = runTest {
        serve("session_create.json", 201)
        assertTrue(api.createSession("apofis-1", "Hello"))
        serve("session_exists_409.json", 409)
        assertFalse(api.createSession("apofis-1", "Hello"))
        serve("session_recreate_deleted_409.json", 409)
        assertEquals(ApiError.Conflict("session_deleted"), expectError { api.createSession("apofis-1", null) })

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/api/sessions", first.url.encodedPath)
        val body = Json.parseToJsonElement(first.body!!.utf8()).jsonObject
        assertEquals("apofis-1", body["id"]!!.jsonPrimitive.content)
        assertEquals("Hello", body["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorMapping() = runTest {
        respond(401, fixture("error_401.json"))
        assertEquals(ApiError.Unauthorized, expectError { api.capabilities() })
        respond(404, fixture("error_404.json"))
        assertEquals(ApiError.NotFound, expectError { api.getSession("x") })
        respond(409, fixture("run_foreign_source.json"))
        assertEquals(ApiError.Conflict("permission_denied"), expectError { api.startRun("{}", "k") })
        respond(503, "", "Retry-After" to "7")
        assertEquals(ApiError.Unavailable(7), expectError { api.runStatus("r") })
        respond(500, "<html>oops</html>")
        assertEquals(ApiError.Http(500, null), expectError { api.runStatus("r") })
        repeat(3) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }
        assertTrue(expectError { api.runStatus("r") } is ApiError.Network)
    }

    @Test
    fun apiKeyNeverInErrors() = runTest {
        respond(401, fixture("error_401.json"))
        val e = expectError { api.capabilities() }
        assertFalse(e.toString().contains("secret-key"))
        assertFalse(e.message.orEmpty().contains("secret-key"))
    }

    @Test
    fun deleteMissingSessionSucceeds() = runTest {
        respond(404, fixture("error_404.json"))
        api.deleteSession("apofis-gone")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/sessions/apofis-gone", req.url.encodedPath)
    }

    @Test
    fun startRunSendsIdempotencyKeyAndExactBody() = runTest {
        serve("run_start.json", 202)
        val body = buildRunBody("apofis-1", "say \"hi\"\nplease")
        api.startRun(body, "idem-123")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/runs", req.url.encodedPath)
        assertEquals("idem-123", req.headers["Idempotency-Key"])
        assertEquals("Bearer secret-key", req.headers["Authorization"])
        assertEquals(body, req.body!!.utf8())
    }

    @Test
    fun controlRequests() = runTest {
        serve("session_patch.json")
        repeat(4) { respond(200, "{}") }
        serve("messages_tool_run.json")
        api.patchSession("a b", pinned = true)
        api.approve("r1", "p1", 3, ApprovalChoice.SESSION)
        api.clarify("r1", "p2", 3, "blue")
        api.resolveUnknown("r1", "adm", 4)
        api.steer("r1", "more")
        api.messages("s1")

        fun next(): Pair<String, String> = server.takeRequest().let { "${it.method} ${it.target}" to it.body?.utf8().orEmpty() }
        assertEquals("PATCH /api/sessions/a%20b" to """{"pinned":true}""", next())
        assertEquals("POST /v1/runs/r1/approval" to """{"request_id":"p1","execution_generation":3,"choice":"session"}""", next())
        assertEquals("POST /v1/runs/r1/clarify" to """{"request_id":"p2","execution_generation":3,"answer":"blue"}""", next())
        assertEquals("POST /v1/runs/r1/resolve-unknown" to """{"admission_id":"adm","execution_generation":4}""", next())
        assertEquals("POST /v1/runs/r1/steer" to """{"text":"more"}""", next())
        assertEquals("GET /api/sessions/s1/messages?limit=500&offset=0&order=oldest&include_compacted=true" to "", next())
    }
}
