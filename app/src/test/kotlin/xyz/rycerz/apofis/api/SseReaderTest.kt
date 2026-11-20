package xyz.rycerz.apofis.api

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SseReaderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private suspend fun read(text: String) = SseReader.events(Buffer().writeUtf8(text)).toList()

    private fun sse(body: String) = server.enqueue(
        MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).build(),
    )

    @Test
    fun parsesToolRun() = runTest {
        val events = read(fixture("events_tool_run.sse"))
        val started = events.first() as RunEvent.ToolStarted
        assertEquals(RunEvent.ToolStarted(0, "call_bcfa62f8a89f42adb149a971df2bb7cf", "terminal", "sleep 20 + 1 command", 1795131287.0361226), started)
        assertEquals(RunEvent.ToolCompleted(1, "call_bcfa62f8a89f42adb149a971df2bb7cf", "terminal", false, "", 0.0, 1795131307.3678918), events[1])
        val deltas = events.filterIsInstance<RunEvent.MessageDelta>()
        assertEquals("\n\nThe command completed successfully: apofis-marker.", deltas.joinToString("") { it.delta })
        assertEquals(RunEvent.Terminal(12, "completed", "The command completed successfully: apofis-marker."), events.last())
        assertEquals(events.map { it.seq }.sorted().distinct(), events.map { it.seq })
    }

    @Test
    fun parsesOtherFixtures() = runTest {
        val resumed = read(fixture("events_tool_run_resumed.sse"))
        assertEquals(1L, resumed.first().seq)
        assertTrue(resumed.last() is RunEvent.Terminal)

        val steer = read(fixture("events_steer.sse"))
        assertEquals(2, steer.count { it is RunEvent.Steered })
        assertEquals("completed", (steer.last() as RunEvent.Terminal).status)

        val stop = read(fixture("events_stop.sse"))
        assertEquals(true, (stop[1] as RunEvent.ToolCompleted).error)
        assertEquals(RunEvent.Terminal(2, "cancelled", null), stop.last())
    }

    @Test
    fun skipsCommentsAndUnknownEvents() = runTest {
        val text = """
            |: open
            |
            |: keepalive
            |
            |id: 5
            |data: {"event": "future.thing",
            |data:  "run_id": "r", "seq": 5}
            |
            |data: {"event": "replay.truncated", "run_id": "r", "oldest_retained_seq": 40, "requested_seq": 2}
            |
            |data: not json
            |
            |id: 41
            |data: {"event": "message.interim", "run_id": "r", "text": "Working", "already_streamed": true, "seq": 41}
            |
            |id: 42
            |data: {"event": "approval.request", "run_id": "r", "kind": "approval", "prompt_id": "p", "execution_generation": 1, "seq": 42}
            |
            |id: 43
            |data: {"event": "reasoning.available", "run_id": "r", "text": "hmm", "seq": 43}
            |
            |id: 44
            |data: {"event": "run.failed", "run_id": "r", "error": "boom", "seq": 44}
            |
            |: stream closed
            |
        """.trimMargin()
        assertEquals(
            listOf(
                RunEvent.Other(5, "future.thing"),
                RunEvent.Truncated(-1, 40),
                RunEvent.MessageInterim(41, "Working", true),
                RunEvent.ApprovalRequested(42),
                RunEvent.Reasoning(43, "hmm"),
                RunEvent.Terminal(44, "failed", null),
            ),
            read(text),
        )
    }

    @Test
    fun closesWithoutTerminal() = runTest {
        // Body ends mid-run: no terminal event and no closing comment.
        sse(fixture("events_tool_run.sse").substringBefore("id: 3\n"))
        val api = HermesApi(server.url("/").toString(), "k")
        val events = api.runEvents("run_1", null).toList()
        assertEquals(listOf(0L, 1L, 2L), events.map { it.seq })
        assertTrue(events.none { it is RunEvent.Terminal })
    }

    @Test
    fun missingTimestampIsZero() = runTest {
        val text = "id: 0\ndata: {\"event\": \"tool.started\", \"tool\": \"t\", \"tool_call_id\": \"c\", \"preview\": \"p\", \"seq\": 0}\n\n"
        assertEquals(listOf(RunEvent.ToolStarted(0, "c", "t", "p", 0.0)), read(text))
    }

    @Test
    fun nonEventStreamIsProtocol() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>login</html>").build())
        server.enqueue(MockResponse.Builder().code(404).body(fixture("error_404_run.json")).build())
        val api = HermesApi(server.url("/").toString(), "k")
        val first = runCatching { api.runEvents("run_1", null).toList() }.exceptionOrNull()
        assertEquals(ApiError.Protocol, first)
        val second = runCatching { api.runEvents("run_1", null).toList() }.exceptionOrNull()
        assertEquals(ApiError.NotFound, second)
    }

    @Test
    fun lastEventIdHeader() = runTest {
        val api = HermesApi(server.url("/").toString(), "k")
        sse(": open\n\n")
        sse(": open\n\n")
        api.runEvents("run_1", null).toList()
        api.runEvents("run_1", 4).toList()
        val first = server.takeRequest()
        assertEquals("/v1/runs/run_1/events", first.url.encodedPath)
        assertEquals("text/event-stream", first.headers["Accept"])
        assertNull(first.headers["Last-Event-ID"])
        assertEquals("4", server.takeRequest().headers["Last-Event-ID"])
    }
}
