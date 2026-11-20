package xyz.rycerz.apofis.run

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.sync.ChatRepository

private const val KEY = "apofis-a"
private const val RUN = "run_1"
private const val EVENTS = "/v1/runs/$RUN/events"
private const val STATUS = "/v1/runs/$RUN"
private const val MESSAGES = "/api/sessions/$KEY/messages"

@RunWith(RobolectricTestRunner::class)
class RunFollowerTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var api: HermesApi
    private lateinit var repo: ChatRepository

    /** Path (without query) to a handler; requests to unknown paths get 500 so a test never passes by accident. */
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val delays = CopyOnWriteArrayList<Long>()
    private val seenAtDelay = CopyOnWriteArrayList<LiveReply?>()
    private val finished = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val handler = routes[request.target.substringBefore('?')] ?: return MockResponse.Builder().code(500).build()
                return handler(request)
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = HermesApi(server.url("/").toString(), "secret-key")
        repo = ChatRepository(api, db)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun TestScope.follower(done: CompletableDeferred<Unit>? = null): RunFollower {
        lateinit var f: RunFollower
        f = RunFollower(
            api, db, repo, backgroundScope,
            delayMs = {
                delays += it
                seenAtDelay += f.live(KEY).value
            },
            onRunFinished = {
                finished += it
                done?.complete(Unit)
            },
        )
        return f
    }

    // ---- server helpers ----

    /** Answers in order; the last response repeats. */
    private fun on(path: String, vararg responses: () -> MockResponse) {
        val n = AtomicInteger()
        routes[path] = { responses[minOf(n.getAndIncrement(), responses.size - 1)]() }
    }

    private fun json(body: String, code: Int = 200) = { MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    private fun sse(vararg frames: String) = { sseResponse(*frames) }

    private fun sseResponse(vararg frames: String) =
        MockResponse.Builder().body(": open\n\n" + frames.joinToString("")).addHeader("Content-Type", "text/event-stream").build()

    private fun status(status: String, pending: String = "", admission: String = "adm_1", generation: Long = 3) = json(
        """{"object":"hermes.run","run_id":"$RUN","status":"$status","session_id":"$KEY","admission_id":"$admission",""" +
            """"execution_generation":$generation,"output":"","pending_controls":[$pending]}""",
    )

    private fun notFound() = json("""{"error":{"code":"run_not_found"}}""", 404)

    private fun frame(seq: Long?, json: String) = (if (seq != null) "id: $seq\n" else "") + "data: $json\n\n"
    private fun delta(seq: Long, text: String) = frame(seq, """{"event":"message.delta","delta":"$text","seq":$seq}""")
    private fun terminal(seq: Long, status: String = "completed") = frame(seq, """{"event":"run.$status","seq":$seq}""")

    private val messagesBody =
        """{"session_id":"$KEY","data":[{"id":10,"role":"user","content":"hi","timestamp":1.0},""" +
            """{"id":11,"role":"assistant","content":"Hello","timestamp":2.0}]}"""

    private fun messages() = on(MESSAGES, json(messagesBody))

    /** Messages that answer only after [gate] opens, so the live state before Finish can be read. */
    private fun gatedMessages(gate: CountDownLatch) {
        routes[MESSAGES] = {
            gate.await(10, TimeUnit.SECONDS)
            json(messagesBody)()
        }
    }

    private fun requestsTo(path: String) = requests.filter { it.target.substringBefore('?') == path }

    // ---- db helpers ----

    private suspend fun addSession(key: String = KEY) = db.sessionDao().upsert(
        SessionEntity(
            key = key, currentId = key, lineageIds = "", title = null, pinned = false, archived = false,
            startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true,
        ),
    )

    private suspend fun addRun(runId: String = RUN, key: String = KEY, lastSeq: Long? = null, status: String = "running") =
        db.activeRunDao().upsert(ActiveRunEntity(runId, key, lastSeq, status, 1L))

    private suspend fun addSend(id: String, state: String, runId: String?, key: String = KEY) =
        db.pendingSendDao().upsert(PendingSendEntity(id, key, "text $id", "{}", 1L, state, runId, null))

    /** The usual start: a session, its active run and the send that started it, then onRunStarted. */
    private suspend fun started(f: RunFollower) {
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        f.onRunStarted(KEY, RUN)
    }

    private suspend fun outcomes() = db.replyOutcomeDao().observe(KEY).first()

    // ---- tests ----

    @Test
    fun streamsThenReplacesWithServerRows() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = CountDownLatch(1)
        gatedMessages(gate)
        on(EVENTS, sse(delta(0, "Hel"), delta(1, "lo"), terminal(2)))
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        addSend("p2", "steered", RUN)
        addSend("p3", "failed", null)
        f.onRunStarted(KEY, RUN)

        assertEquals("Hello", f.live(KEY).first { it?.text == "Hello" }!!.text)
        gate.countDown()
        done.await()

        assertNull(f.live(KEY).value)
        assertEquals(11L, db.messageDao().maxId(KEY))
        assertEquals(listOf("p3"), db.pendingSendDao().observe(KEY).first().map { it.idempotencyKey })
        assertTrue(db.activeRunDao().all().isEmpty())
        assertTrue(outcomes().isEmpty())
        assertEquals(listOf(KEY), finished)
    }

    @Test
    fun firstAttachSendsNoLastEventId() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        done.await()

        assertNull(requestsTo(EVENTS).single().headers["Last-Event-ID"])
    }

    @Test
    fun resumesFromStoredSeq() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done) // a fresh process: the row survived, the live text did not
        messages()
        on(STATUS, status("running"))
        on(EVENTS, sse(delta(6, "b"), terminal(7)))
        addSession()
        addRun(lastSeq = 5)
        addSend("p1", "sending", RUN)

        f.resumeAll()
        done.await()

        assertEquals("5", requestsTo(EVENTS).single().headers["Last-Event-ID"])
        // The stored rows are synced before attaching, so earlier text of the reply is visible.
        assertTrue(requests.first().target.startsWith(MESSAGES))
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun silentCloseReconnects() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = CountDownLatch(1)
        gatedMessages(gate)
        on(STATUS, status("running"))
        // The second connection replays seq 2 once more: it must not be appended twice.
        on(EVENTS, sse(delta(0, "a"), delta(1, "b"), delta(2, "c")), sse(delta(2, "c"), delta(3, "d"), terminal(4)))
        started(f)

        assertEquals("abcd", f.live(KEY).first { it?.text?.endsWith("d") == true }!!.text)
        gate.countDown()
        done.await()

        val attaches = requestsTo(EVENTS)
        assertEquals(2, attaches.size)
        assertEquals("2", attaches[1].headers["Last-Event-ID"])
        assertEquals(listOf(1000L), delays)
        assertEquals(Connection.RECONNECTING, seenAtDelay.single()!!.connection)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun truncatedFallsBackToPolling() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("running"), status("completed"))
        on(EVENTS, sse(delta(0, "a"), frame(null, """{"event":"replay.truncated","oldest_retained_seq":40}""")))
        started(f)
        done.await()

        assertEquals(1, requestsTo(EVENTS).size)
        assertEquals(2, requestsTo(STATUS).size)
        assertEquals(listOf(3000L, 3000L), delays)
        assertTrue(seenAtDelay.all { it!!.connection == Connection.POLLING })
        assertEquals(3, requestsTo(MESSAGES).size) // one per poll, then Finish
        assertTrue(db.activeRunDao().all().isEmpty())
        assertNull(f.live(KEY).value)
    }

    @Test
    fun eventsNotFoundFallsBackToPolling() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("completed"))
        on(EVENTS, notFound())
        started(f)
        done.await()

        assertEquals(1, requestsTo(EVENTS).size)
        assertEquals(listOf(3000L), delays)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun statusNotFoundFinishesQuietly() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, notFound())
        on(EVENTS, sse())
        started(f)
        done.await()

        assertTrue(outcomes().isEmpty())
        assertTrue(db.activeRunDao().all().isEmpty())
        assertTrue(db.pendingSendDao().observe(KEY).first().isEmpty())
    }

    @Test
    fun queuedThenRunning() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("queued"), status("running"))
        on(EVENTS, sse(), sse(delta(0, "a"), terminal(1)))
        started(f)
        done.await()

        assertEquals(listOf(1000L, 3000L), delays)
        assertEquals("queued", seenAtDelay[1]!!.status)
        assertEquals(2, requestsTo(EVENTS).size)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun unknownWaitsForResolve() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("unknown", admission = "adm_7", generation = 4))
        on(EVENTS, sse())
        on("/v1/runs/$RUN/resolve-unknown", json("""{"run_id":"$RUN","status":"resolved"}"""))
        started(f)

        f.live(KEY).first { it?.status == "unknown" }
        assertNotNull(db.activeRunDao().forSession(KEY))
        assertTrue(finished.isEmpty())

        f.resolveUnknown(KEY)
        done.await()

        val body = requestsTo("/v1/runs/$RUN/resolve-unknown").single().body!!.utf8()
        assertTrue(body, body.contains("\"admission_id\":\"adm_7\""))
        assertTrue(body, body.contains("\"execution_generation\":4"))
        assertEquals(listOf("INTERRUPTED"), outcomes().map { it.outcome })
        assertEquals(11L, outcomes().single().afterMessageId)
        assertNull(outcomes().single().detail)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun stopRecordsStoppedOutcome() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val stopped = CountDownLatch(1)
        routes["/v1/runs/$RUN/stop"] = {
            stopped.countDown()
            json("""{"run_id":"$RUN","status":"stopping"}""")()
        }
        routes[EVENTS] = {
            stopped.await(10, TimeUnit.SECONDS)
            sseResponse(delta(0, "a"), terminal(1, "cancelled"))
        }
        started(f)

        f.stop(KEY)
        done.await()

        assertEquals(1, requestsTo("/v1/runs/$RUN/stop").size)
        assertEquals(listOf("STOPPED"), outcomes().map { it.outcome })
    }

    @Test
    fun cancelledWithoutStopIsInterrupted() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(EVENTS, sse(terminal(0, "cancelled")))
        started(f)
        done.await()

        assertEquals(listOf("INTERRUPTED"), outcomes().map { it.outcome })
    }

    @Test
    fun failedRunRecordsFailed() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(EVENTS, sse(terminal(0, "failed")))
        started(f)
        done.await()

        assertEquals(listOf("FAILED"), outcomes().map { it.outcome })
    }

    private val pendingControl =
        """{"kind":"approval","prompt_id":"ctl_prompt","execution_generation":2,"command":"rm x","question":null}"""
    private val approvalEvent =
        """{"event":"approval.request","kind":"approval","prompt_id":"evt_prompt","execution_generation":9,"seq":1}"""

    @Test
    fun approvalUsesPendingControlIds() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl))
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1) {
                sseResponse(delta(0, "a"), frame(1, approvalEvent))
            } else {
                release.await(10, TimeUnit.SECONDS)
                sseResponse(terminal(2))
            }
        }
        on("/v1/runs/$RUN/approval", json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}"""))
        started(f)

        val pending = f.live(KEY).first { it?.pending != null }!!.pending!!
        assertEquals("ctl_prompt", pending.requestId)
        f.answerApproval(KEY, ApprovalChoice.ONCE)

        val body = requestsTo("/v1/runs/$RUN/approval").single().body!!.utf8()
        assertTrue(body, body.contains("\"request_id\":\"ctl_prompt\""))
        assertTrue(body, body.contains("\"execution_generation\":2"))
        assertTrue(body, body.contains("\"choice\":\"once\""))
        assertNull(f.live(KEY).value!!.pending)
        release.countDown()
        done.await()
    }

    @Test
    fun approvalConflictClearsPending() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val release = CountDownLatch(1)
        val current = java.util.concurrent.atomic.AtomicReference(status("waiting_for_approval", pendingControl))
        routes[STATUS] = { current.get()() }
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1) {
                sseResponse(frame(1, approvalEvent))
            } else {
                release.await(10, TimeUnit.SECONDS)
                sseResponse(terminal(2))
            }
        }
        routes["/v1/runs/$RUN/approval"] = {
            current.set(status("running"))
            json("""{"error":{"code":"approval_not_pending"}}""", 409)()
        }
        started(f)

        f.live(KEY).first { it?.pending != null }
        f.answerApproval(KEY, ApprovalChoice.DENY)

        val live = f.live(KEY).value!!
        assertNull(live.pending)
        assertEquals("running", live.status)
        release.countDown()
        done.await()
    }

    @Test
    fun resumeAllFollowsEveryStoredRun() = runTest {
        val f = follower()
        addSession("apofis-a")
        addSession("apofis-b")
        addRun("run_a", "apofis-a")
        addRun("run_b", "apofis-b")
        on("/api/sessions/apofis-a/messages", json(messagesBody))
        on("/api/sessions/apofis-b/messages", json(messagesBody.replace(KEY, "apofis-b")))
        on("/v1/runs/run_a", json("""{"run_id":"run_a","status":"running","session_id":"apofis-a"}"""))
        on("/v1/runs/run_a/events", sse(delta(0, "a"), terminal(1)))
        on("/v1/runs/run_b", json("""{"run_id":"run_b","status":"completed","session_id":"apofis-b"}"""))

        f.resumeAll()
        db.activeRunDao().observeAll().first { it.isEmpty() }
        f.live("apofis-a").first { it == null }
        f.live("apofis-b").first { it == null }

        assertEquals(setOf("apofis-a", "apofis-b"), finished.toSet())
        assertEquals(1, requestsTo("/v1/runs/run_a/events").size)
        assertTrue(requestsTo("/v1/runs/run_b/events").isEmpty())
    }

    @Test
    fun pauseAllKeepsRows() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val first = delta(0, "Hi")
        val body = ": open\n\n" + first + ": " + "x".repeat(4000) + "\n\n" + delta(1, " later") + terminal(2)
        on(
            EVENTS,
            {
                MockResponse.Builder().body(body).addHeader("Content-Type", "text/event-stream")
                    .throttleBody((": open\n\n" + first).length.toLong(), 30, TimeUnit.SECONDS).build()
            },
        )
        on(STATUS, status("completed"))
        started(f)

        f.live(KEY).first { it?.text == "Hi" }
        f.pauseAll()

        assertEquals(0L, db.activeRunDao().observeAll().first { it.singleOrNull()?.lastSeq == 0L }.single().lastSeq)
        assertEquals("Hi", f.live(KEY).value!!.text)
        assertTrue(finished.isEmpty())

        f.resumeAll()
        done.await()
        assertEquals(1, requestsTo(EVENTS).size)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun unknownEventTypesIgnored() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = CountDownLatch(1)
        gatedMessages(gate)
        on(
            EVENTS,
            sse(
                ": a comment\n\n",
                frame(0, """{"event":"something.new","seq":0,"weird":[1,2]}"""),
                frame(null, """{"event":"other.unsequenced"}"""),
                "data: not json\n\n",
                "data: {\"no_event\":true}\n\n",
                delta(1, "ok"),
                terminal(2),
            ),
        )
        started(f)

        assertEquals("ok", f.live(KEY).first { it?.text == "ok" }!!.text)
        gate.countDown()
        done.await()
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun toolStepsCarryDuration() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = CountDownLatch(1)
        gatedMessages(gate)
        on(
            EVENTS,
            sse(
                frame(0, """{"event":"tool.started","tool":"terminal","preview":"ls","tool_call_id":"c1","timestamp":100.0,"seq":0}"""),
                frame(1, """{"event":"tool.started","tool":"web","preview":"q","tool_call_id":"c2","timestamp":101.0,"seq":1}"""),
                frame(2, """{"event":"tool.completed","tool":"terminal","duration":0,"error":false,"tool_call_id":"c1","preview":"out","timestamp":102.5,"seq":2}"""),
                frame(3, """{"event":"tool.completed","tool":"orphan","duration":0,"error":true,"tool_call_id":"c3","preview":"bad","timestamp":103.0,"seq":3}"""),
                terminal(4),
            ),
        )
        started(f)

        val steps = f.live(KEY).first { it?.steps?.size == 3 }!!.steps
        assertEquals(ToolStep("c1", "terminal", "ls", "out", false, false, false, 2.5), steps[0])
        assertEquals(ToolStep("c2", "web", "q", null, false, false, true, null), steps[1])
        assertEquals(ToolStep("c3", "orphan", "", "bad", false, true, false, null), steps[2])
        gate.countDown()
        done.await()
    }

    @Test
    fun unauthorizedStopsWithoutDeletingRow() = runTest {
        val f = follower()
        on(EVENTS, json("""{"error":{"code":"invalid_api_key"}}""", 401))
        started(f)

        f.credentialsRejected.first { it }
        assertNotNull(db.activeRunDao().forSession(KEY))
        assertTrue(requestsTo(STATUS).isEmpty())
        assertTrue(finished.isEmpty())
    }

    @Test
    fun finishRetriesWhenSyncFails() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val rowsDuringRetry = CopyOnWriteArrayList<Boolean>()
        val calls = AtomicInteger()
        routes[MESSAGES] = {
            if (calls.incrementAndGet() == 1) {
                MockResponse.Builder().code(500).build()
            } else {
                rowsDuringRetry += runBlocking { db.pendingSendDao().get("p1") != null && db.activeRunDao().forSession(KEY) != null }
                json(messagesBody)()
            }
        }
        on(EVENTS, sse(terminal(0)))
        started(f)
        done.await()

        assertEquals(listOf(true), rowsDuringRetry) // nothing was deleted after the failed sync
        assertEquals(listOf(1000L), delays)
        assertTrue(db.activeRunDao().all().isEmpty())
        assertFalse(db.pendingSendDao().observe(KEY).first().any { it.idempotencyKey == "p1" })
    }
}
