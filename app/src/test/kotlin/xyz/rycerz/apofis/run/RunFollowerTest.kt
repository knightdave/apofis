package xyz.rycerz.apofis.run

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
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
    private val roomStatusAtDelay = CopyOnWriteArrayList<String?>()

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

    private fun TestScope.follower(
        done: CompletableDeferred<Unit>? = null,
        beforeFinished: suspend RunFollower.() -> Unit = {},
        onDelay: suspend RunFollower.() -> Unit = {},
    ): RunFollower {
        lateinit var f: RunFollower
        f = RunFollower(
            api, db, repo, backgroundScope,
            delayMs = {
                delays += it
                seenAtDelay += f.live(KEY).value
                roomStatusAtDelay += db.activeRunDao().forSession(KEY)?.status
                f.onDelay()
            },
            onRunFinished = {
                f.beforeFinished()
                finished += it
                done?.complete(Unit)
            },
        )
        return f
    }

    /** Waits (in real time, since requests run on real threads) for [condition]; fails the test after 10 s. */
    private suspend fun awaitUntil(condition: () -> Boolean) = withContext(Dispatchers.IO) {
        repeat(2000) {
            if (condition()) return@withContext
            Thread.sleep(5)
        }
        fail("condition not met within 10 s")
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
        """{"session_id":"$KEY","data":[{"id":10,"role":"user","content":"text p1","timestamp":1.0},""" +
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
        f.resumeAll() // opens the foreground gate; nothing is stored yet
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
        val steer = "[OUT-OF-BAND USER MESSAGE - sent while you were working]\\ntext p2\\n[/OUT-OF-BAND USER MESSAGE]"
        routes[MESSAGES] = {
            gate.await(10, TimeUnit.SECONDS)
            json(
                """{"session_id":"$KEY","data":[{"id":10,"role":"user","content":" text p1 ","timestamp":1.0},""" +
                    """{"id":11,"role":"user","display_kind":"steer","content":"$steer","timestamp":1.5},""" +
                    """{"id":12,"role":"assistant","content":"Hello","timestamp":2.0}]}""",
            )()
        }
        on(EVENTS, sse(delta(0, "Hel"), delta(1, "lo"), terminal(2)))
        f.resumeAll()
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        addSend("p2", "steered", RUN)
        addSend("p3", "failed", null)
        addSend("p4", "sending", RUN) // the server never stored these two
        addSend("p5", "steered", RUN)
        f.onRunStarted(KEY, RUN)

        assertEquals("Hello", f.live(KEY).first { it?.text == "Hello" }!!.text)
        gate.countDown()
        done.await()

        assertNull(f.live(KEY).value)
        assertEquals(12L, db.messageDao().maxId(KEY))
        val left = db.pendingSendDao().observe(KEY).first().associateBy { it.idempotencyKey }
        assertEquals(setOf("p3", "p4", "p5"), left.keys)
        for (k in listOf("p4", "p5")) {
            assertEquals("failed", left[k]!!.state)
            assertEquals("unconfirmed", left[k]!!.error)
            assertNull(left[k]!!.runId)
        }
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
        assertEquals("queued", roomStatusAtDelay[1]) // the send queue reads this to decide whether to steer
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
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("running"))
            json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}""")()
        }
        started(f)

        val pending = f.live(KEY).first { it?.pending != null }!!.pending!!
        assertEquals("ctl_prompt", pending.requestId)
        assertEquals("waiting_for_approval", db.activeRunDao().forSession(KEY)!!.status)
        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, pending.requestId, pending.executionGeneration, ApprovalChoice.ONCE))

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

        val pending = f.live(KEY).first { it?.pending != null }!!.pending!!
        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, pending.requestId, pending.executionGeneration, ApprovalChoice.DENY))

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
                MockResponse.Builder().code(503).build()
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

    // ---- fix round 1 ----

    private val otherControl =
        """{"kind":"approval","prompt_id":"ctl_other","execution_generation":2,"command":"rm y","question":null}"""

    /** A run held open (second attach blocks until [release]) so user actions can be tested while it is followed. */
    private suspend fun heldOpen(f: RunFollower, release: CountDownLatch) {
        messages()
        routes[EVENTS] = {
            release.await(10, TimeUnit.SECONDS)
            sseResponse(terminal(5))
        }
        started(f)
    }

    @Test
    fun approvalReplacedReturnsChanged() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", otherControl))
        heldOpen(f, release)

        assertEquals(AnswerResult.CHANGED, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertTrue(requestsTo("/v1/runs/$RUN/approval").isEmpty())
        assertEquals("ctl_other", f.live(KEY).value!!.pending!!.requestId)
        // Same id but another generation is a different request too.
        on(STATUS, status("waiting_for_approval", pendingControl.replace("\"execution_generation\":2", "\"execution_generation\":5")))
        assertEquals(AnswerResult.CHANGED, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertTrue(requestsTo("/v1/runs/$RUN/approval").isEmpty())
        release.countDown()
    }

    @Test
    fun approvalGoneReturnsGone() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("running"))
        heldOpen(f, release)

        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertTrue(requestsTo("/v1/runs/$RUN/approval").isEmpty())
        assertNull(f.live(KEY).value!!.pending)
        release.countDown()
    }

    @Test
    fun clarifyUsesSameRule() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        val clarify = """{"kind":"clarify","prompt_id":"cl_1","execution_generation":2,"command":null,"question":"Which?"}"""
        on(STATUS, status("waiting_for_approval", clarify))
        on("/v1/runs/$RUN/clarify", json("""{"run_id":"$RUN","status":"resolved","prompt_id":"cl_1"}"""))
        heldOpen(f, release)

        assertEquals(AnswerResult.CHANGED, f.answerClarify(KEY, "cl_old", 2, "this one"))
        assertEquals(AnswerResult.SENT, f.answerClarify(KEY, "cl_1", 2, "this one"))
        val body = requestsTo("/v1/runs/$RUN/clarify").single().body!!.utf8()
        assertTrue(body, body.contains("\"request_id\":\"cl_1\"") && body.contains("\"answer\":\"this one\""))
        release.countDown()
    }

    @Test
    fun finishTailSurvivesCancellation() = runTest {
        val done = CompletableDeferred<Unit>()
        // Leaving the foreground while onRunFinished runs cancels the follower job; the handoff must still complete.
        val f = follower(done) {
            pauseAll()
            yield()
        }
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        done.await()

        assertEquals(listOf(KEY), finished)
        assertNull(f.live(KEY).value)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun onRunStartedWhilePausedWaitsForResume() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("running"))
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        f.resumeAll()
        f.pauseAll()
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        f.onRunStarted(KEY, RUN)
        testScheduler.advanceUntilIdle()

        assertTrue(f.following.isEmpty())
        assertTrue(requests.isEmpty())
        assertNotNull(db.activeRunDao().forSession(KEY))

        f.resumeAll()
        done.await()
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun approvalRefreshFailureIsRetried() = runTest {
        val f = follower()
        messages()
        val release = CountDownLatch(1)
        on(STATUS, json("{}", 500), status("waiting_for_approval", pendingControl))
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1) {
                sseResponse(delta(0, "a"), frame(1, approvalEvent), delta(2, "never applied"))
            } else {
                release.await(10, TimeUnit.SECONDS)
                sseResponse(terminal(9))
            }
        }
        started(f)

        assertEquals("ctl_prompt", f.live(KEY).first { it?.pending != null }!!.pending!!.requestId)
        awaitUntil { requestsTo(EVENTS).size >= 2 }
        assertEquals("0", requestsTo(EVENTS)[1].headers["Last-Event-ID"]) // the approval event was not checkpointed
        assertEquals(listOf(1000L), delays)
        assertEquals("a", f.live(KEY).value!!.text)
        release.countDown()
    }

    @Test
    fun unauthorizedStopCancelsFollower() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on("/v1/runs/$RUN/stop", json("""{"error":{"code":"invalid_api_key"}}""", 401))
        heldOpen(f, release)
        awaitUntil { requestsTo(EVENTS).isNotEmpty() }

        try {
            f.stop(KEY)
            fail("expected Unauthorized")
        } catch (_: ApiError.Unauthorized) {
        }
        assertTrue(f.credentialsRejected.value)
        awaitUntil { f.following.isEmpty() }
        assertNotNull(db.activeRunDao().forSession(KEY))
        release.countDown()
        assertTrue(finished.isEmpty())
    }

    /** An event stream that delivers [frames] and then stays open, so no reconnect interferes. */
    private fun heldStream(vararg frames: String): () -> MockResponse = {
        val head = ": open\n\n" + frames.joinToString("")
        MockResponse.Builder().body(head + ": " + "x".repeat(4000) + "\n\n").addHeader("Content-Type", "text/event-stream")
            .throttleBody(head.length.toLong(), 30, TimeUnit.SECONDS).build()
    }

    @Test
    fun unknownSettledEventKeepsApproval() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("waiting_for_approval", pendingControl))
        on(EVENTS, heldStream(frame(1, approvalEvent), frame(2, """{"event":"foo.settled","prompt_id":"ctl_prompt","seq":2}"""), delta(3, "x")))
        started(f)

        f.live(KEY).first { it?.text == "x" }
        assertEquals("ctl_prompt", f.live(KEY).value!!.pending!!.requestId)
        assertEquals(1, requestsTo(STATUS).size) // only the approval request read the status
    }

    @Test
    fun approvalRespondedClearsOnlyWhenStatusAgrees() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("waiting_for_approval", pendingControl), status("waiting_for_approval", pendingControl), status("running"))
        val responded = """{"event":"approval.responded","choice":"once","resolved":true,"seq":%d}"""
        on(
            EVENTS,
            heldStream(
                frame(1, approvalEvent),
                frame(2, responded.format(2)), // the status still lists the request: it stays
                delta(3, "x"),
                frame(4, responded.format(4)), // now the status shows none
                delta(5, "y"),
            ),
        )
        started(f)

        f.live(KEY).first { it?.text == "xy" }
        assertNull(f.live(KEY).value!!.pending)
        assertEquals("running", f.live(KEY).value!!.status)
        assertEquals(3, requestsTo(STATUS).size)
    }

    @Test
    fun pollingSurvivesPauseResumeAndRestart() = runTest {
        val f = follower()
        messages()
        val statusCalls = AtomicInteger()
        routes[STATUS] = {
            statusCalls.incrementAndGet()
            status("running")()
        }
        on(EVENTS, notFound())
        started(f)

        db.activeRunDao().observeAll().first { it.singleOrNull()?.polling == true }
        f.pauseAll()
        val before = statusCalls.get()
        f.resumeAll()
        awaitUntil { statusCalls.get() > before }
        f.pauseAll()

        val done = CompletableDeferred<Unit>()
        val g = follower(done) // a new process over the same database
        on(STATUS, status("completed"))
        g.resumeAll()
        done.await()

        assertEquals(1, requestsTo(EVENTS).size)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun permanentSyncFailureUnblocksSession() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        on(MESSAGES, json("not json"))
        on(EVENTS, sse(terminal(0, "failed")))
        started(f)
        done.await()

        assertEquals(listOf(KEY), finished)
        assertTrue(db.activeRunDao().all().isEmpty())
        assertNull(f.live(KEY).value)
        assertEquals(listOf("FAILED"), outcomes().map { it.outcome })
        val p1 = db.pendingSendDao().get("p1")!!
        assertEquals("failed", p1.state)
        assertEquals("unconfirmed", p1.error)
        assertNull(p1.runId)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun retryableSyncFailureIsBoundedPerResume() = runTest {
        val f = follower()
        on(MESSAGES, json("{}", 503))
        on(EVENTS, sse(terminal(0)))
        started(f)
        testScheduler.advanceUntilIdle()
        awaitUntil { f.following.isEmpty() }

        assertEquals(5, requestsTo(MESSAGES).size)
        assertTrue(finished.isEmpty())
        assertNotNull(db.activeRunDao().forSession(KEY))
        assertEquals("sending", db.pendingSendDao().get("p1")!!.state)
    }

    @Test
    fun malformedRunIdEndsAsFailure() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        f.resumeAll()
        addSession()
        addRun(runId = "bad/id")
        addSend("p1", "sending", "bad/id")
        f.onRunStarted(KEY, "bad/id")
        done.await()

        assertEquals(listOf("FAILED"), outcomes().map { it.outcome })
        assertTrue(db.activeRunDao().all().isEmpty())
        assertEquals("unconfirmed", db.pendingSendDao().get("p1")!!.error)
        assertTrue(requests.isEmpty())
    }

    // ---- fix round 2 ----

    private fun steerContent(vararg texts: String) =
        "[OUT-OF-BAND USER MESSAGE - sent while you were working]\\n" + texts.joinToString("\\n\\n") + "\\n[/OUT-OF-BAND USER MESSAGE]"

    private fun messagesWith(vararg rows: String) =
        json("""{"session_id":"$KEY","data":[${rows.joinToString(",")}]}""")

    @Test
    fun hiddenUserRowDoesNotConfirm() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        on(MESSAGES, messagesWith("""{"id":10,"role":"user","display_kind":"hidden","content":"text p1","timestamp":1.0}"""))
        on(EVENTS, sse(terminal(0)))
        started(f)
        done.await()

        assertEquals("unconfirmed", db.pendingSendDao().get("p1")!!.error)
    }

    @Test
    fun mergedSteersAreAllConfirmed() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        on(
            MESSAGES,
            messagesWith(
                """{"id":10,"role":"user","content":"text p1","timestamp":1.0}""",
                """{"id":11,"role":"user","display_kind":"steer","content":"${steerContent("text p2", "text p3")}","timestamp":1.5}""",
            ),
        )
        on(EVENTS, sse(terminal(0)))
        f.resumeAll()
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        addSend("p2", "steered", RUN)
        addSend("p3", "steered", RUN)
        f.onRunStarted(KEY, RUN)
        done.await()

        assertTrue(db.pendingSendDao().observe(KEY).first().isEmpty())
    }

    @Test
    fun transcriptNeverLosesUserTextDuringFinish() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = CountDownLatch(1)
        gatedMessages(gate)
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        val emissions = CopyOnWriteArrayList<List<ChatItem>>()
        val first = CompletableDeferred<Unit>()
        backgroundScope.launch {
            repo.transcript(KEY).collect {
                emissions += it
                first.complete(Unit)
            }
        }
        first.await()
        gate.countDown()
        done.await()
        awaitUntil { emissions.last().any { it is UserMessage && it.key.startsWith("u") } }

        for (e in emissions) assertTrue(e.toString(), e.any { it is UserMessage && it.text == "text p1" })
    }

    @Test
    fun cancellationDuringFinishWriteStillCompletes() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        f.beforeFinishWrite = {
            pauseAll()
            yield()
        }
        messages()
        on(EVENTS, sse(terminal(0, "failed")))
        started(f)
        done.await()

        assertTrue(db.activeRunDao().all().isEmpty())
        assertNull(db.pendingSendDao().get("p1"))
        assertEquals(listOf("FAILED"), outcomes().map { it.outcome })
        assertNull(f.live(KEY).value)
    }

    @Test
    fun newerApprovalDuringPostIsShown() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl))
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("waiting_for_approval", otherControl)) // published while the POST was in flight
            json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}""")()
        }
        heldOpen(f, release)

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertEquals("ctl_other", f.live(KEY).value!!.pending!!.requestId)
        release.countDown()
    }

    @Test
    fun alreadyResolvedReportsGone() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl))
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("running"))
            json("""{"run_id":"$RUN","status":"already_resolved","prompt_id":"ctl_prompt"}""")()
        }
        heldOpen(f, release)

        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertNull(f.live(KEY).value!!.pending)
        release.countDown()
    }

    @Test
    fun conflictWithReplacementReportsChanged() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl))
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("waiting_for_approval", otherControl))
            json("""{"error":{"code":"stale_generation"}}""", 409)()
        }
        heldOpen(f, release)

        assertEquals(AnswerResult.CHANGED, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertEquals("ctl_other", f.live(KEY).value!!.pending!!.requestId)
        release.countDown()
    }

    @Test
    fun pauseAfterTruncationNeverReattaches() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("running"))
        on(EVENTS, sse(delta(0, "a"), frame(null, """{"event":"replay.truncated","oldest_retained_seq":40}""")))
        started(f)
        f.live(KEY).first { it?.text == "a" }
        f.pauseAll() // lands while the follower handles the truncation

        val done = CompletableDeferred<Unit>()
        val g = follower(done) // a later resume, here in a new process
        on(STATUS, status("completed"))
        g.resumeAll()
        done.await()
        assertEquals(1, requestsTo(EVENTS).size)
    }

    @Test
    fun serverStoppingWithoutLocalIntentIsInterrupted() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("stopping"))
        on(EVENTS, sse(), sse(terminal(0, "cancelled")))
        started(f)
        done.await()

        assertEquals(listOf("INTERRUPTED"), outcomes().map { it.outcome })
    }

    @Test
    fun stopIntentIsStoredApartFromStatus() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("running"))
        on("/v1/runs/$RUN/stop", json("""{"run_id":"$RUN","status":"stopping"}"""))
        heldOpen(f, release)

        f.stop(KEY)
        val row = db.activeRunDao().forSession(KEY)!!
        assertTrue(row.stopRequested)
        assertEquals("running", row.status) // no status read has said otherwise yet
        release.countDown()
    }

    @Test
    fun recoveryFromStoppingIsStoredAsRunning() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("running"))
        on(EVENTS, heldStream(delta(0, "a")))
        f.resumeAll()
        addSession()
        addRun(status = "stopping")
        f.resumeAll()

        f.live(KEY).first { it?.text == "a" }
        assertEquals("running", db.activeRunDao().forSession(KEY)!!.status)
    }

    @Test
    fun unrecognizedStatusIsStoredAsRunning() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("brand_new_state"))
        on(EVENTS, heldStream(delta(0, "a")))
        f.resumeAll()
        addSession()
        addRun()
        f.resumeAll()

        f.live(KEY).first { it?.text == "a" }
        assertEquals("brand_new_state", f.live(KEY).value!!.status)
        assertEquals("running", db.activeRunDao().forSession(KEY)!!.status)
    }

    @Test
    fun terminalStatusIsStoredBeforeFinish() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = CountDownLatch(1)
        gatedMessages(gate)
        on(EVENTS, sse(terminal(0)))
        started(f)

        db.activeRunDao().observeAll().first { it.singleOrNull()?.status == "completed" }
        gate.countDown()
        done.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pauseAllSurvivesInlineCompletion() = runTest {
        // Unconfined: a cancelled job completes inside cancel(), so its completion handler runs during pauseAll.
        val f = RunFollower(api, db, repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), delayMs = { awaitCancellation() })
        addSession("apofis-a")
        addSession("apofis-b")
        db.activeRunDao().upsert(ActiveRunEntity("run_a", "apofis-a", null, "running", 1L, polling = true))
        db.activeRunDao().upsert(ActiveRunEntity("run_b", "apofis-b", null, "running", 1L, polling = true))
        f.resumeAll()
        assertEquals(setOf("run_a", "run_b"), f.following)

        f.pauseAll()
        assertTrue(f.following.isEmpty())
    }

    @Test
    fun syncWithoutRequestKeepsCredentialsRejected() = runTest {
        val rejectedAtDelay = CopyOnWriteArrayList<Boolean>()
        val f = follower(onDelay = {
            rejectedAtDelay += credentialsRejected.value
            pauseAll()
        })
        on("/v1/runs/$RUN/stop", json("""{"error":{"code":"invalid_api_key"}}""", 401))
        on(STATUS, json("{}", 500))
        f.resumeAll()
        addRun(lastSeq = 3) // its chat is not stored, so the resume sync makes no request
        try {
            f.stop(KEY)
            fail("expected Unauthorized")
        } catch (_: ApiError.Unauthorized) {
        }
        f.resumeAll()
        awaitUntil { rejectedAtDelay.isNotEmpty() }

        assertEquals(listOf(true), rejectedAtDelay)
    }

    // ---- fix round 3 ----

    @Test
    fun failedReadAfterPostKeepsApprovalAndRetries() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl), json("{}", 500), status("waiting_for_approval", otherControl))
        on("/v1/runs/$RUN/approval", json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}"""))
        heldOpen(f, release)

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertEquals("ctl_prompt", f.live(KEY).value!!.pending!!.requestId) // never cleared on a failed read
        assertEquals("ctl_other", f.live(KEY).first { it?.pending?.requestId == "ctl_other" }!!.pending!!.requestId)
        assertEquals(listOf(1000L), delays)
        release.countDown()
    }

    @Test
    fun malformedAnswerWithRequestStillPendingIsAnError() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl))
        on("/v1/runs/$RUN/approval", json("not json"))
        heldOpen(f, release)

        try {
            f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE)
            fail("expected Protocol")
        } catch (_: ApiError.Protocol) {
        }
        assertEquals("ctl_prompt", f.live(KEY).value!!.pending!!.requestId)
        release.countDown()
    }

    @Test
    fun malformedAnswerWithRequestGoneIsGone() = runTest {
        val f = follower()
        val release = CountDownLatch(1)
        on(STATUS, status("waiting_for_approval", pendingControl))
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("running"))
            json("""{"run_id":"$RUN"}""")()
        }
        heldOpen(f, release)

        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        release.countDown()
    }

    @Test
    fun resumeRightAfterPauseWaitsForTheOldJob() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("running"), status("completed"))
        on(EVENTS, sse(delta(0, "a"), frame(null, """{"event":"replay.truncated","oldest_retained_seq":40}""")))
        started(f)
        f.live(KEY).first { it?.text == "a" }
        f.pauseAll() // the old job is still writing polling = true
        f.resumeAll()
        done.await()

        assertEquals(1, requestsTo(EVENTS).size)
    }

    @Test
    fun pauseDuringTerminalStatusWriteStillStoresIt() = runTest {
        val f = follower()
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        routes[MESSAGES] = { MockResponse.Builder().code(503).build() } // Finish never completes here
        started(f)
        f.live(KEY).first { it?.text == "a" }
        f.pauseAll() // lands while the terminal status is being stored

        awaitUntil { runBlocking { db.activeRunDao().forSession(KEY)?.status } == "completed" }
    }

    @Test
    fun failedFinishTransactionLeavesLiveAndQueue() = runTest {
        val done = CompletableDeferred<Unit>()
        val atRetry = CopyOnWriteArrayList<String>()
        val f = follower(done, onDelay = {
            atRetry += "live=${live(KEY).value != null} finished=${finished.size} row=${db.activeRunDao().forSession(KEY) != null} " +
                "p1=${db.pendingSendDao().get("p1")?.state}"
        })
        val failures = AtomicInteger()
        f.beforeFinishWrite = { if (failures.getAndIncrement() == 0) throw SQLiteException("disk I/O error") }
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        done.await()

        assertEquals(listOf("live=true finished=0 row=true p1=sending"), atRetry)
        assertEquals(listOf(KEY), finished)
        assertNull(db.pendingSendDao().get("p1"))
    }

    @Test
    fun successfulTranscriptFetchClearsCredentialsRejected() = runTest {
        val rejectedAtDelay = CopyOnWriteArrayList<Boolean>()
        val f = follower(onDelay = {
            rejectedAtDelay += credentialsRejected.value
            pauseAll()
        })
        messages()
        on("/v1/runs/$RUN/stop", json("""{"error":{"code":"invalid_api_key"}}""", 401))
        on(STATUS, json("{}", 500))
        f.resumeAll()
        addSession()
        addRun(lastSeq = 3) // the resume syncs the transcript first, with a request that succeeds
        try {
            f.stop(KEY)
            fail("expected Unauthorized")
        } catch (_: ApiError.Unauthorized) {
        }
        assertTrue(f.credentialsRejected.value)
        f.resumeAll()
        awaitUntil { rejectedAtDelay.isNotEmpty() }

        assertEquals(listOf(false), rejectedAtDelay)
    }
}
