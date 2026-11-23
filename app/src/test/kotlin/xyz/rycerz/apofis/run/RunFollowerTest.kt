package xyz.rycerz.apofis.run

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
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
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.withRepositoryLock

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

    /** Runs on the database thread just before each statement, so a test can act in the middle of a write. */
    private val gates = CopyOnWriteArrayList<CountDownLatch>()
    private val gateTimeouts = AtomicInteger()

    /** A gate the test opens; tearDown fails the test if one was never opened or a [pass] timed out. */
    private fun gate() = CountDownLatch(1).also { gates += it }

    /** Waits (on a server thread) for the gate to open; a timeout is recorded and fails the test in tearDown. */
    private fun CountDownLatch.pass() {
        if (!await(10, TimeUnit.SECONDS)) gateTimeouts.incrementAndGet()
    }

    /** The scope of every follower a test builds; tearDown fails the test if one of their coroutines is still unfinished. */
    private val followerScopes = CopyOnWriteArrayList<CoroutineScope>()

    private fun followers(scope: CoroutineScope) = scope.also { followerScopes += it }

    /**
     * [runTest] that ends every follower coroutine after the body, also a body that failed, was cancelled or timed out,
     * while the test scheduler still runs, so none is left unfinished to run into the next test (tearDown checks it).
     * runTest alone only cancels them. The body's own failure stays the one reported.
     */
    private fun runFollowerTest(body: suspend TestScope.() -> Unit) = runTest {
        val outcome = runCatching { body() }
        val stuck = endFollowers()
        outcome.exceptionOrNull()?.let { e ->
            stuck?.let(e::addSuppressed)
            throw e
        }
        stuck?.let { throw it }
    }

    /**
     * Cancels every follower coroutine and waits for all of them, at most 10 s in real time. The wait runs off the test
     * dispatcher and cannot be cancelled, so a cancelled or timed-out caller still waits. Returns the failure, if any.
     */
    private suspend fun endFollowers(): AssertionError? {
        followerScopes.forEach { it.coroutineContext.job.cancelChildren() }
        val children = followerScopes.flatMap { it.coroutineContext.job.children }
        val ended = withContext(NonCancellable + Dispatchers.Default) { withTimeoutOrNull(10_000) { children.joinAll() } } != null
        return if (ended) null else AssertionError("a follower did not end within 10 s after its test")
    }

    @Volatile private var beforeQuery: (sql: String, args: List<Any?>) -> Unit = { _, _ -> }

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
            .setQueryCallback(Dispatchers.Unconfined) { sql, args -> beforeQuery(sql, args) }
            .build()
        api = HermesApi(server.url("/").toString(), "secret-key")
        repo = ChatRepository(api, db)
    }

    @After
    fun tearDown() {
        val unopened = gates.count { it.count > 0 }
        gates.forEach { it.countDown() } // no server thread stays blocked
        // A follower coroutine that outlived its test can never end (its test scheduler no longer runs); one that holds
        // the process-wide repository lock would block every later test, so the lock is probed too (FLAKY cause 6).
        val followersEnded = runBlocking { withTimeoutOrNull(2_000) { followerScopes.flatMap { it.coroutineContext.job.children }.joinAll() } } != null
        val lockFree = runBlocking { withTimeoutOrNull(2_000) { withRepositoryLock { } } } != null
        db.close()
        server.close()
        assertTrue("a follower outlived its test", followersEnded)
        assertTrue("the repository lock is still held", lockFree)
        assertEquals("gates never opened", 0, unopened)
        assertEquals("gate waits that timed out", 0, gateTimeouts.get())
    }

    private fun TestScope.follower(
        done: CompletableDeferred<Unit>? = null,
        beforeFinished: suspend RunFollower.() -> Unit = {},
        onDelay: suspend RunFollower.() -> Unit = {},
    ): RunFollower {
        lateinit var f: RunFollower
        f = RunFollower(
            api, db, repo, followers(backgroundScope),
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
            gate.pass()
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
    fun finishAfterConfirmedDeleteRecreatesNothing() = runFollowerTest {
        val f = follower()
        val gate = gate()
        messages()
        routes[EVENTS] = {
            gate.pass() // the cancellation frame is delivered only after the chat was deleted here
            sseResponse(terminal(1, "cancelled"))
        }
        started(f)
        awaitUntil { requestsTo(EVENTS).isNotEmpty() }
        db.sessionDao().delete(KEY) // the server accepted DELETE; the local cascade ran
        gate.countDown()
        awaitUntil { requestsTo(EVENTS).isNotEmpty() && f.following.isEmpty() } // the frame was handled and the job ended

        assertTrue(outcomes().isEmpty())
        assertTrue(finished.isEmpty()) // nothing was settled, so nothing is handed on
        assertNull(db.sessionDao().get(KEY))
        assertTrue(db.pendingSendDao().observe(KEY).first().isEmpty())
        assertNull(f.live(KEY).value)
    }

    @Test
    fun stopDrawnForAFinishedRunNeverStopsItsSuccessor() = runFollowerTest {
        val f = follower()
        f.resumeAll()
        addSession()
        addRun("run_2") // the run the user saw ended and the hand-off admitted the next message's run
        on("/v1/runs/run_2/stop", json(fixture("run_stop.json")))

        f.stop(KEY, RUN) // the tap on the Stop button drawn for the first run

        assertTrue(requestsTo("/v1/runs/run_2/stop").isEmpty())
        assertFalse(db.activeRunDao().get("run_2")!!.stopRequested)
    }

    @Test
    fun earlierTranscriptFetchDoesNotClearALaterRejection() = runFollowerTest {
        val f = follower()
        messages()
        on(STATUS, json("{}", 500)) // the follower's next read fails without saying anything about the key
        addSession()
        addSession("apofis-b")
        addRun("run_b", "apofis-b")
        on("/v1/runs/run_b/stop", json("""{"error":{"code":"invalid_api_key"}}""", 401))
        addRun(lastSeq = 3) // after a restart: the resume syncs the transcript first
        repo.beforeSyncWrite = {
            // The transcript GET has returned; only then does another request learn the key was refused.
            assertTrue(runCatching { f.stop("apofis-b", "run_b") }.exceptionOrNull() is ApiError.Unauthorized)
        }

        f.resumeAll()
        awaitUntil { requestsTo(STATUS).isNotEmpty() } // the sync and its write are done

        assertTrue(f.credentialsRejected.value)
    }

    @Test
    fun finishOfAClosedConnectionLeavesTheRunToTheNewOne() = runFollowerTest {
        val open = AtomicBoolean(true)
        val f = RunFollower(api, db, repo, followers(backgroundScope), delayMs = {}, onRunFinished = { finished += it }, active = open::get)
        val wrote = CompletableDeferred<Unit>()
        f.beforeFinishWrite = {
            open.set(false) // a switch to a new key at the same server closes this connection; its rows stay
            wrote.complete(Unit)
        }
        on(MESSAGES, notFound()) // no transcript: the fallback finish without one
        on(EVENTS, sse(terminal(0, "cancelled")))
        started(f)
        wrote.await()
        awaitUntil { f.following.isEmpty() }

        assertNotNull(db.activeRunDao().get(RUN)) // the new connection's follower still needs it
        assertEquals("sending", db.pendingSendDao().get("p1")!!.state) // not failed as unconfirmed
        assertTrue(outcomes().isEmpty())
        assertTrue(finished.isEmpty()) // no drain for a run this follower did not settle
    }

    @Test
    fun backgroundWorkerFinishesAnEndedRunThenSendsTheNextMessage() = runFollowerTest {
        val f = follower() // never resumed: the app is in the background, so no follower runs
        messages()
        on(STATUS, status("completed")) // the run finished on the server meanwhile
        on("/v1/runs", json("""{"run_id":"run_2","status":"started","replayed":false}""", 202))
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        val queue = SendQueue(api, db, f, System::currentTimeMillis, settle = { f.settle(it) })
        val second = queue.enqueue(KEY, "second")

        assertFalse(queue.drain()) // a plain drain only sees its own rows: the session looks busy for good
        assertTrue(queue.drainSettlingRuns())

        assertNull(db.activeRunDao().get(RUN))
        assertNull(db.pendingSendDao().get("p1")) // confirmed by the synced transcript
        assertEquals("sending", db.pendingSendDao().get(second)!!.state)
        assertEquals("run_2", db.activeRunDao().forSession(KEY)!!.runId)
        assertEquals(1, requestsTo("/v1/runs").size)
        assertTrue(requestsTo(EVENTS).isEmpty()) // no stream in the background
    }

    @Test
    fun backgroundWorkerLeavesARunningRunAlone() = runFollowerTest {
        val f = follower()
        on(STATUS, status("running"))
        addSession()
        addRun()
        addSend("p1", "sending", RUN)
        val queue = SendQueue(api, db, f, System::currentTimeMillis, settle = { f.settle(it) })
        val second = queue.enqueue(KEY, "second")

        assertFalse(queue.drainSettlingRuns())

        assertNotNull(db.activeRunDao().get(RUN))
        assertEquals("queued", db.pendingSendDao().get(second)!!.state)
        assertTrue(requestsTo(MESSAGES).isEmpty())
        assertTrue(requestsTo(EVENTS).isEmpty())
    }

    @Test
    fun streamsThenReplacesWithServerRows() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = gate()
        val steer = "[OUT-OF-BAND USER MESSAGE - sent while you were working]\\ntext p2\\n[/OUT-OF-BAND USER MESSAGE]"
        routes[MESSAGES] = {
            gate.pass()
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
    fun firstAttachSendsNoLastEventId() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        done.await()

        assertNull(requestsTo(EVENTS).single().headers["Last-Event-ID"])
    }

    @Test
    fun resumesFromStoredSeq() = runFollowerTest {
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
    fun silentCloseReconnects() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = gate()
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
    fun truncatedFallsBackToPolling() = runFollowerTest {
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
    fun eventsNotFoundFallsBackToPolling() = runFollowerTest {
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
    fun statusNotFoundFinishesQuietly() = runFollowerTest {
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
    fun queuedThenRunning() = runFollowerTest {
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
    fun unknownWaitsForResolve() = runFollowerTest {
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
    fun stopRecordsStoppedOutcome() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val stopped = gate()
        routes["/v1/runs/$RUN/stop"] = {
            stopped.countDown()
            json("""{"run_id":"$RUN","status":"stopping"}""")()
        }
        routes[EVENTS] = {
            stopped.pass()
            sseResponse(delta(0, "a"), terminal(1, "cancelled"))
        }
        started(f)

        f.stop(KEY, RUN)
        done.await()

        assertEquals(1, requestsTo("/v1/runs/$RUN/stop").size)
        assertEquals(listOf("STOPPED"), outcomes().map { it.outcome })
    }

    @Test
    fun cancelledWithoutStopIsInterrupted() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(EVENTS, sse(terminal(0, "cancelled")))
        started(f)
        done.await()

        assertEquals(listOf("INTERRUPTED"), outcomes().map { it.outcome })
    }

    @Test
    fun failedRunRecordsFailed() = runFollowerTest {
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
    fun approvalUsesPendingControlIds() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl))
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1) {
                sseResponse(delta(0, "a"), frame(1, approvalEvent))
            } else {
                release.pass()
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
        assertEquals("waiting_for_approval", db.activeRunDao().forSession(KEY)!!.status) // stored before it is shown
        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, pending.requestId, pending.executionGeneration, ApprovalChoice.ONCE))

        val body = requestsTo("/v1/runs/$RUN/approval").single().body!!.utf8()
        assertTrue(body, body.contains("\"request_id\":\"ctl_prompt\""))
        assertTrue(body, body.contains("\"execution_generation\":2"))
        assertTrue(body, body.contains("\"choice\":\"once\""))
        awaitUntil { f.live(KEY).value!!.pending == null } // the follow-up read runs after SENT is returned
        release.countDown()
        done.await()
    }

    @Test
    fun approvalConflictClearsPending() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        val release = gate()
        val current = java.util.concurrent.atomic.AtomicReference(status("waiting_for_approval", pendingControl))
        routes[STATUS] = { current.get()() }
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1) {
                sseResponse(frame(1, approvalEvent))
            } else {
                release.pass()
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
    fun resumeAllFollowsEveryStoredRun() = runFollowerTest {
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
    fun pauseAllKeepsRows() = runFollowerTest {
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
    fun unknownEventTypesIgnored() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = gate()
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
    fun toolStepsCarryDuration() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = gate()
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
    fun unauthorizedStopsWithoutDeletingRow() = runFollowerTest {
        val f = follower()
        on(EVENTS, json("""{"error":{"code":"invalid_api_key"}}""", 401))
        started(f)

        f.credentialsRejected.first { it }
        assertNotNull(db.activeRunDao().forSession(KEY))
        assertTrue(requestsTo(STATUS).isEmpty())
        assertTrue(finished.isEmpty())
    }

    @Test
    fun finishRetriesWhenSyncFails() = runFollowerTest {
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
            release.pass()
            sseResponse(terminal(5))
        }
        started(f)
    }

    @Test
    fun approvalReplacedReturnsChanged() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun approvalGoneReturnsGone() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("running"))
        heldOpen(f, release)

        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertTrue(requestsTo("/v1/runs/$RUN/approval").isEmpty())
        assertNull(f.live(KEY).value!!.pending)
        release.countDown()
    }

    @Test
    fun clarifyUsesSameRule() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun finishTailSurvivesCancellation() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        // Leaving the foreground while onRunFinished runs cancels the follower job; the handoff must still complete.
        val f = follower(done, beforeFinished = {
            pauseAll()
            yield()
        })
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        withContext(Dispatchers.Default) { withTimeout(10_000) { done.await() } } // real time: a lost hand-off names this line

        assertEquals(listOf(KEY), finished)
        assertNull(f.live(KEY).value)
        assertTrue(db.activeRunDao().all().isEmpty())
    }

    @Test
    fun onRunStartedWhilePausedWaitsForResume() = runFollowerTest {
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
    fun approvalRefreshFailureIsRetried() = runFollowerTest {
        val f = follower()
        messages()
        val release = gate()
        on(STATUS, json("{}", 500), status("waiting_for_approval", pendingControl))
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1) {
                sseResponse(delta(0, "a"), frame(1, approvalEvent), delta(2, "never applied"))
            } else {
                release.pass()
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
    fun unauthorizedStopCancelsFollower() = runFollowerTest {
        val f = follower()
        val release = gate()
        on("/v1/runs/$RUN/stop", json("""{"error":{"code":"invalid_api_key"}}""", 401))
        heldOpen(f, release)
        awaitUntil { requestsTo(EVENTS).isNotEmpty() }

        try {
            f.stop(KEY, RUN)
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
    fun unknownSettledEventKeepsApproval() = runFollowerTest {
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
    fun approvalRespondedClearsOnlyWhenStatusAgrees() = runFollowerTest {
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
    fun pollingSurvivesPauseResumeAndRestart() = runFollowerTest {
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
    fun permanentSyncFailureUnblocksSession() = runFollowerTest {
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
    fun finishKeepsRetryingInTheForegroundUntilTheSyncSucceeds() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val calls = AtomicInteger()
        routes[MESSAGES] = { if (calls.incrementAndGet() <= 5) MockResponse.Builder().code(503).build() else json(messagesBody)() }
        on(EVENTS, sse(terminal(0)))
        started(f)
        done.await() // five transient failures, then connectivity is back: no resumeAll needed

        assertEquals(6, requestsTo(MESSAGES).size)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L), delays)
        assertTrue(db.activeRunDao().all().isEmpty())
        assertNull(f.live(KEY).value)
    }

    @Test
    fun malformedRunIdEndsAsFailure() = runFollowerTest {
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
    fun hiddenUserRowDoesNotConfirm() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        on(MESSAGES, messagesWith("""{"id":10,"role":"user","display_kind":"hidden","content":"text p1","timestamp":1.0}"""))
        on(EVENTS, sse(terminal(0)))
        started(f)
        done.await()

        assertEquals("unconfirmed", db.pendingSendDao().get("p1")!!.error)
    }

    @Test
    fun mergedSteersAreAllConfirmed() = runFollowerTest {
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
    fun transcriptNeverLosesUserTextDuringFinish() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = gate()
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
    fun cancellationDuringFinishWriteStillCompletes() = runFollowerTest {
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
    fun newerApprovalDuringPostIsShown() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl))
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("waiting_for_approval", otherControl)) // published while the POST was in flight
            json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}""")()
        }
        heldOpen(f, release)

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        awaitUntil { f.live(KEY).value!!.pending?.requestId == "ctl_other" } // the follow-up read runs after SENT
        release.countDown()
    }

    @Test
    fun alreadyResolvedReportsGone() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun conflictWithReplacementReportsChanged() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun pauseAfterTruncationNeverReattaches() = runFollowerTest {
        val f = follower()
        messages()
        on(STATUS, status("running"))
        on(EVENTS, sse(delta(0, "a"), frame(null, """{"event":"replay.truncated","oldest_retained_seq":40}""")))
        val paused = AtomicBoolean(false)
        beforeQuery = { sql, _ ->
            if ("SET polling = 1" in sql) {
                f.pauseAll() // lands while the truncation is stored
                paused.set(true)
            }
        }
        started(f)
        db.activeRunDao().observeAll().first { it.singleOrNull()?.polling == true } // the old process's write commits
        assertTrue(paused.get())

        val done = CompletableDeferred<Unit>()
        val g = follower(done) // a later resume, here in a new process
        on(STATUS, status("completed"))
        g.resumeAll()
        done.await()
        assertEquals(1, requestsTo(EVENTS).size)
    }

    @Test
    fun serverStoppingWithoutLocalIntentIsInterrupted() = runFollowerTest {
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
    fun stopAllStopsEveryRunOfTheChat() = runFollowerTest {
        val f = follower()
        addSession()
        addRun(RUN)
        db.activeRunDao().upsert(ActiveRunEntity("run_2", KEY, null, "queued", 2L))
        addRun("run_other", key = "apofis-other")
        on("/v1/runs/$RUN/stop", json("""{"run_id":"$RUN","status":"stopping"}"""))
        on("/v1/runs/run_2/stop", json("""{"run_id":"run_2","status":"stopping"}"""))

        f.stopAll(KEY)
        assertEquals(1, requestsTo("/v1/runs/$RUN/stop").size)
        assertEquals(1, requestsTo("/v1/runs/run_2/stop").size)
        assertTrue(requestsTo("/v1/runs/run_other/stop").isEmpty())
        assertTrue(db.activeRunDao().get(RUN)!!.stopRequested && db.activeRunDao().get("run_2")!!.stopRequested)
    }

    @Test
    fun stopAllThrowsWhenAStopFails() = runFollowerTest {
        val f = follower()
        addSession()
        addRun(RUN)
        on("/v1/runs/$RUN/stop", json("""{"error":{"code":"boom"}}""", 500))
        try {
            f.stopAll(KEY)
            fail("a failed stop must throw")
        } catch (_: ApiError) {
        }
    }

    @Test
    fun stopIntentIsStoredApartFromStatus() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("running"))
        on("/v1/runs/$RUN/stop", json("""{"run_id":"$RUN","status":"stopping"}"""))
        heldOpen(f, release)

        f.stop(KEY, RUN)
        val row = db.activeRunDao().forSession(KEY)!!
        assertTrue(row.stopRequested)
        assertEquals("running", row.status) // no status read has said otherwise yet
        release.countDown()
    }

    @Test
    fun recoveryFromStoppingIsStoredAsRunning() = runFollowerTest {
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
    fun unrecognizedStatusIsStoredAsRunning() = runFollowerTest {
        val f = follower()
        messages()
        on(STATUS, status("brand_new_state"))
        on(EVENTS, heldStream(delta(0, "a")))
        f.resumeAll()
        addSession()
        addRun()
        f.resumeAll()

        f.live(KEY).first { it?.text == "a" }
        assertEquals("running", f.live(KEY).value!!.status) // shown as stored: the screen and the send queue agree
        assertEquals("running", db.activeRunDao().forSession(KEY)!!.status)
    }

    @Test
    fun terminalStatusIsStoredBeforeFinish() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val gate = gate()
        gatedMessages(gate)
        on(EVENTS, sse(terminal(0)))
        started(f)

        db.activeRunDao().observeAll().first { it.singleOrNull()?.status == "completed" }
        gate.countDown()
        done.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pauseAllSurvivesInlineCompletion() = runFollowerTest {
        // Unconfined: a cancelled job completes inside cancel(), so its completion handler runs during pauseAll.
        val f = RunFollower(api, db, repo, followers(CoroutineScope(UnconfinedTestDispatcher(testScheduler))), delayMs = { awaitCancellation() })
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
    fun syncWithoutRequestKeepsCredentialsRejected() = runFollowerTest {
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
            f.stop(KEY, RUN)
            fail("expected Unauthorized")
        } catch (_: ApiError.Unauthorized) {
        }
        f.resumeAll()
        awaitUntil { rejectedAtDelay.isNotEmpty() }

        assertEquals(listOf(true), rejectedAtDelay)
    }

    // ---- fix round 3 ----

    @Test
    fun failedReadAfterPostKeepsApprovalAndRetries() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun malformedAnswerWithRequestStillPendingIsAnError() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun malformedAnswerWithRequestGoneIsGone() = runFollowerTest {
        val f = follower()
        val release = gate()
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
    fun resumeRightAfterPauseWaitsForTheOldJob() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("running"), status("completed"))
        on(EVENTS, sse(delta(0, "a"), frame(null, """{"event":"replay.truncated","oldest_retained_seq":40}""")))
        val paused = gate()
        beforeQuery = { sql, _ ->
            if ("SET polling = 1" in sql) {
                f.pauseAll() // the old job is still writing polling = true
                paused.countDown()
            }
        }
        started(f)
        awaitUntil { paused.count == 0L }
        f.resumeAll()
        done.await()

        assertEquals(1, requestsTo(EVENTS).size)
    }

    @Test
    fun pauseDuringTerminalStatusWriteStillStoresIt() = runFollowerTest {
        val f = follower()
        messages()
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        routes[MESSAGES] = { MockResponse.Builder().code(503).build() } // Finish never completes here
        val paused = gate()
        f.beforeStatusStore = { status ->
            if (status == "completed" && paused.count > 0) {
                pauseAll() // lands as the terminal status starts being stored, before anything is written
                paused.countDown()
                yield()
            }
        }
        started(f)

        awaitUntil { paused.count == 0L } // the pause itself happened
        awaitUntil { runBlocking { db.activeRunDao().forSession(KEY)?.status } == "completed" }
    }

    @Test
    fun failedFinishTransactionLeavesLiveAndQueue() = runFollowerTest {
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
    fun successfulTranscriptFetchClearsCredentialsRejected() = runFollowerTest {
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
            f.stop(KEY, RUN)
            fail("expected Unauthorized")
        } catch (_: ApiError.Unauthorized) {
        }
        assertTrue(f.credentialsRejected.value)
        f.resumeAll()
        awaitUntil { rejectedAtDelay.isNotEmpty() }

        assertEquals(listOf(false), rejectedAtDelay)
    }

    // ---- fix round 4 ----

    private val unauthorized = json("""{"error":{"code":"invalid_api_key"}}""", 401)

    @Test
    fun acceptedAnswerStaysSentWhenTheReadIsRejected() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl), unauthorized)
        on("/v1/runs/$RUN/approval", json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}"""))
        heldOpen(f, release)

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        awaitUntil { f.credentialsRejected.value }
        assertEquals("ctl_prompt", f.live(KEY).value!!.pending!!.requestId)
        release.countDown()
    }

    @Test
    fun alreadyResolvedWithFailedReadRethrowsAndRetries() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl), json("{}", 500), status("running"))
        on("/v1/runs/$RUN/approval", json("""{"run_id":"$RUN","status":"already_resolved","prompt_id":"ctl_prompt"}"""))
        heldOpen(f, release)

        try {
            f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE)
            fail("expected the read's error")
        } catch (e: ApiError.Http) {
            assertEquals(500, e.status)
        }
        assertEquals("ctl_prompt", f.live(KEY).value!!.pending!!.requestId)
        awaitUntil { f.live(KEY).value?.pending == null }
        assertEquals(listOf(1000L), delays)
        release.countDown()
    }

    @Test
    fun olderReadNeverOverwritesANewerApproval() = runFollowerTest {
        val f = follower()
        messages()
        val slow = gate()
        val emit = gate()
        val reads = AtomicInteger()
        routes[STATUS] = {
            when (reads.incrementAndGet()) {
                1 -> status("waiting_for_approval", pendingControl)()
                2 -> {
                    slow.pass() // started first, finishes last, and shows nothing pending
                    status("running")()
                }
                else -> status("waiting_for_approval", otherControl)()
            }
        }
        on("/v1/runs/$RUN/approval", json("""{"run_id":"$RUN","status":"already_resolved","prompt_id":"ctl_prompt"}"""))
        routes[EVENTS] = {
            emit.pass()
            heldStream(frame(1, approvalEvent))()
        }
        started(f)

        val answer = backgroundScope.async { f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE) }
        awaitUntil { requestsTo(STATUS).size == 2 }
        emit.countDown()
        f.live(KEY).first { it?.pending?.requestId == "ctl_other" }
        slow.countDown()

        assertEquals(AnswerResult.GONE, answer.await()) // that read saw nothing pending
        assertEquals("ctl_other", f.live(KEY).value!!.pending!!.requestId)
        assertEquals("waiting_for_approval", db.activeRunDao().forSession(KEY)!!.status)
    }

    @Test
    fun truncatedAnswerResponseIsReconciled() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl))
        routes["/v1/runs/$RUN/approval"] = {
            on(STATUS, status("running"))
            MockResponse.Builder().body("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}""")
                .addHeader("Content-Type", "application/json").onResponseBody(SocketEffect.CloseSocket()).build()
        }
        heldOpen(f, release)

        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertNull(f.live(KEY).value!!.pending)
        release.countDown()
    }

    @Test
    fun restartAfterPollingFallbackNeverRequestsEvents() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val g = follower(done) // a second follower whose gate is open before the run exists
        g.resumeAll()
        val f = follower()
        messages()
        on(STATUS, status("running"))
        on(EVENTS, notFound())
        started(f)
        db.activeRunDao().observeAll().first { it.singleOrNull()?.polling == true }
        f.pauseAll()

        on(STATUS, status("completed"))
        g.onRunStarted(KEY, RUN) // the callback carries no polling flag: only the stored row knows
        done.await()

        assertEquals(listOf(EVENTS), requests.map { it.target.substringBefore('?') }.filter { it.endsWith("/events") })
    }

    @Test
    fun rejectionDuringFinishDrainStaysShown() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done, beforeFinished = {
            try {
                stop("apofis-b", "run_b") // another run's request is refused while the drain runs
                fail("expected Unauthorized")
            } catch (_: ApiError.Unauthorized) {
            }
        })
        messages()
        addSession("apofis-b")
        addRun("run_b", "apofis-b")
        on("/v1/runs/run_b/stop", unauthorized)
        on(EVENTS, sse(delta(0, "a"), terminal(1)))
        started(f)
        done.await()
        awaitUntil { f.following.isEmpty() }

        assertTrue(f.credentialsRejected.value)
    }

    // ---- fix round 5 ----

    private val resolvedBody = json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_prompt"}""")

    @Test
    fun refreshKeepsRetryingUntilAReadSucceeds() = runFollowerTest {
        val f = follower()
        val release = gate()
        val failed = json("{}", 500)
        on(STATUS, status("waiting_for_approval", pendingControl), failed, failed, failed, failed, failed, failed, status("running"))
        on("/v1/runs/$RUN/approval", resolvedBody)
        heldOpen(f, release)

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        awaitUntil { f.live(KEY).value?.pending == null }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L), delays)
        release.countDown()
    }

    @Test
    fun refreshPendingAtPauseResumesAfterResumeAll() = runFollowerTest {
        val paused = AtomicBoolean(false)
        val resumed = AtomicBoolean(false)
        val readsWhilePaused = AtomicInteger()
        val f = follower(onDelay = { if (paused.compareAndSet(false, true)) pauseAll() }) // the gate closes during the backoff
        val release = gate()
        val reads = AtomicInteger()
        routes[STATUS] = {
            if (paused.get() && !resumed.get()) readsWhilePaused.incrementAndGet()
            when (reads.incrementAndGet()) {
                1 -> status("waiting_for_approval", pendingControl)()
                2 -> json("{}", 500)()
                else -> status("running")()
            }
        }
        on("/v1/runs/$RUN/approval", resolvedBody)
        heldOpen(f, release)

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        awaitUntil { paused.get() }
        resumed.set(true)
        f.resumeAll()
        awaitUntil { f.live(KEY).value?.pending == null }
        assertEquals(0, readsWhilePaused.get())
        release.countDown()
    }

    @Test
    fun rejectionBetweenStatusReadAndItsApplyStaysShown() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl))
        heldOpen(f, release)
        addSession("apofis-b")
        addRun("run_b", "apofis-b")
        on("/v1/runs/run_b/stop", unauthorized)
        val once = AtomicBoolean(false)
        f.beforeStatusApply = {
            if (once.compareAndSet(false, true)) {
                try {
                    stop("apofis-b", "run_b") // completes after the status GET returned, before the follower is done with it
                    fail("expected Unauthorized")
                } catch (_: ApiError.Unauthorized) {
                }
            }
        }

        assertEquals(AnswerResult.CHANGED, f.answerApproval(KEY, "ctl_gone", 2, ApprovalChoice.ONCE))
        assertTrue(once.get()) // the rejection did land between the read and its apply
        assertTrue(f.credentialsRejected.value)
        release.countDown()
    }

    @Test
    fun rejectedRefreshAfterAnswerStopsTheStream() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl), unauthorized)
        on("/v1/runs/$RUN/approval", resolvedBody)
        heldOpen(f, release)
        awaitUntil { requestsTo(EVENTS).isNotEmpty() }

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        awaitUntil { f.following.isEmpty() }
        assertTrue(f.credentialsRejected.value)
        release.countDown()
        assertEquals(1, requestsTo(EVENTS).size)
        assertNotNull(db.activeRunDao().forSession(KEY))
        assertTrue(finished.isEmpty())
    }

    @Test
    fun pauseWhileApplyingTerminalStatusStillStoresIt() = runFollowerTest {
        val f = follower()
        messages()
        on(STATUS, status("completed"))
        val paused = gate()
        f.beforeStatusApply = {
            if (paused.count > 0) {
                pauseAll() // lands after the read returned, at the first point that can be cancelled
                paused.countDown()
                yield()
            }
        }
        f.resumeAll()
        addSession()
        addRun()
        f.resumeAll()

        awaitUntil { paused.count == 0L } // the pause itself happened
        awaitUntil { runBlocking { db.activeRunDao().forSession(KEY)?.status } == "completed" }
    }

    @Test
    fun cancelledRefreshStaysRequestedUntilApplied() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("waiting_for_approval", pendingControl), status("waiting_for_approval", otherControl))
        on("/v1/runs/$RUN/approval", resolvedBody)
        heldOpen(f, release)
        val cancelled = AtomicBoolean(false)
        f.beforeStatusApply = {
            if (requestsTo(STATUS).size == 2 && cancelled.compareAndSet(false, true)) {
                pauseAll() // cancels the refresh read after it returned the replacement, before it is applied
                yield()
            }
        }

        assertEquals(AnswerResult.SENT, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        awaitUntil { cancelled.get() }
        assertEquals("ctl_prompt", f.live(KEY).value!!.pending!!.requestId)
        assertTrue(f.refreshOwed(RUN))
        f.resumeAll()
        f.live(KEY).first { it?.pending?.requestId == "ctl_other" }
        release.countDown()
    }

    // ---- the stored status is what the user sees ----

    private val steerPath = "/v1/runs/$RUN/steer"

    /**
     * Sends "now" from inside the live update that first shows [status], before the follower takes another step, the
     * way a user tapping send on what the screen shows would. Completes the returned deferred once it has sent.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.sendWhenShown(f: RunFollower, status: String): CompletableDeferred<Unit> {
        val sent = CompletableDeferred<Unit>()
        val queue = SendQueue(api, db, { _, _ -> }, System::currentTimeMillis)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.live(KEY).collect {
                if (it?.status == status && !sent.isCompleted) {
                    runBlocking { queue.submit(KEY, "now") } // inline: the follower is still inside its update
                    sent.complete(Unit)
                }
            }
        }
        return sent
    }

    @Test
    fun sendRightAfterRunningIsShownIsSteered() = runFollowerTest {
        val f = follower()
        messages()
        on(STATUS, status("queued"), status("running"))
        on(EVENTS, sse(), heldStream())
        on(steerPath, json(fixture("steer_response.json")))
        f.resumeAll()
        addSession()
        addRun(status = "queued")
        val sent = sendWhenShown(f, "running")
        f.onRunStarted(KEY, RUN)

        sent.await()
        assertEquals(1, requestsTo(steerPath).size)
        assertEquals(listOf("steered"), db.pendingSendDao().observe(KEY).first().map { it.state })
    }

    @Test
    fun sendRightAfterApprovalIsShownIsNotSteered() = runFollowerTest {
        val f = follower()
        messages()
        on(STATUS, status("waiting_for_approval", pendingControl))
        on(EVENTS, heldStream(frame(1, approvalEvent)))
        on(steerPath, json(fixture("steer_response.json")))
        f.resumeAll()
        addSession()
        addRun()
        val sent = sendWhenShown(f, "waiting_for_approval")
        f.onRunStarted(KEY, RUN)

        sent.await()
        assertTrue(requestsTo(steerPath).isEmpty())
        assertTrue(db.pendingSendDao().observe(KEY).first().none { it.runId == RUN })
    }

    @Test
    fun sendRightAfterTerminalStatusIsShownIsNotSteered() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        messages()
        on(STATUS, status("completed"))
        on(EVENTS, sse())
        on(steerPath, json(fixture("steer_response.json")))
        f.resumeAll()
        addSession()
        addRun()
        val sent = sendWhenShown(f, "completed")
        f.onRunStarted(KEY, RUN)

        sent.await()
        done.await()
        assertTrue(requestsTo(steerPath).isEmpty())
    }

    @Test
    fun sendRightAfterStopIsShownIsNotSteered() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("running"))
        routes["/v1/runs/$RUN/stop"] = {
            on(STATUS, status("stopping")) // the server reports it from now on
            json("""{"run_id":"$RUN","status":"stopping"}""")()
        }
        on(steerPath, json(fixture("steer_response.json")))
        heldOpen(f, release)
        val sent = sendWhenShown(f, "stopping")

        f.stop(KEY, RUN)
        sent.await()
        assertTrue(requestsTo(steerPath).isEmpty())
        release.countDown()
    }

    @Test
    fun failedStopKeepsTheStatusAndTheNextSendSteers() = runFollowerTest {
        val f = follower()
        val release = gate()
        on(STATUS, status("running"))
        on("/v1/runs/$RUN/stop", json("{}", 500))
        on(steerPath, json(fixture("steer_response.json")))
        heldOpen(f, release)

        try {
            f.stop(KEY, RUN)
            fail("expected the stop's error")
        } catch (e: ApiError.Http) {
            assertEquals(500, e.status)
        }
        val row = db.activeRunDao().forSession(KEY)!!
        assertEquals("running", row.status)
        assertFalse(row.stopRequested) // a later cancel by someone else is not this user's stop
        SendQueue(api, db, { _, _ -> }, System::currentTimeMillis).submit(KEY, "after the failed stop")
        assertEquals(1, requestsTo(steerPath).size)
        release.countDown()
    }

    @Test
    fun statusReadReturningAfterTheTerminalEventNeverUndoesIt() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val finish = gate()
        gatedMessages(finish) // keeps the run (and its live state) until the end
        val emit = gate()
        val read = gate()
        routes[EVENTS] = {
            emit.pass()
            sseResponse(terminal(0))
        }
        routes[STATUS] = {
            read.pass() // started before the terminal event, returns after it
            status("running")()
        }
        started(f)

        val answer = backgroundScope.async { f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE) }
        awaitUntil { requestsTo(STATUS).size == 1 }
        emit.countDown()
        awaitUntil { f.live(KEY).value?.status == "completed" }
        assertEquals("completed", db.activeRunDao().forSession(KEY)!!.status) // stored before it was shown
        read.countDown()
        assertEquals(AnswerResult.GONE, answer.await())

        assertEquals("completed", f.live(KEY).value!!.status)
        assertEquals("completed", db.activeRunDao().forSession(KEY)!!.status)
        finish.countDown()
        done.await() // the run ends before the database closes
    }

    @Test
    fun statusReadBeforeTheTerminalEventEndsTerminal() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val finish = gate()
        gatedMessages(finish)
        val emit = gate()
        routes[EVENTS] = {
            emit.pass()
            sseResponse(terminal(0))
        }
        on(STATUS, status("running"))
        started(f)

        assertEquals(AnswerResult.GONE, f.answerApproval(KEY, "ctl_prompt", 2, ApprovalChoice.ONCE))
        assertEquals("running", f.live(KEY).value!!.status)
        emit.countDown()
        awaitUntil { f.live(KEY).value?.status == "completed" }

        assertEquals("completed", db.activeRunDao().forSession(KEY)!!.status)
        finish.countDown()
        done.await() // the run ends before the database closes
    }

    @Test
    fun lateRunningReadAfterRestartShowsTheStoredTerminalStatus() = runFollowerTest {
        val f = follower() // a new process over a database that already stored the terminal status
        on(STATUS, status("running"))
        on(EVENTS, heldStream())
        on(steerPath, json(fixture("steer_response.json")))
        addSession()
        addRun(status = "completed")
        f.resumeAll()

        awaitUntil { requestsTo(EVENTS).isNotEmpty() } // it attaches only once the read has been published
        assertEquals("completed", f.live(KEY).value!!.status)
        assertEquals("completed", db.activeRunDao().forSession(KEY)!!.status)
        SendQueue(api, db, { _, _ -> }, System::currentTimeMillis).submit(KEY, "late")
        assertTrue(requestsTo(steerPath).isEmpty())
    }

    @Test
    fun failedTerminalStoreIsRetriedByTheNextObservation() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val atRetry = CopyOnWriteArrayList<String>()
        val f = follower(done, onDelay = {
            atRetry += "live=${live(KEY).value?.status} row=${db.activeRunDao().forSession(KEY)?.status}"
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_terminal")
        })
        // The first terminal write itself is attempted and fails in SQLite; the trigger is dropped at the retry delay.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_terminal BEFORE UPDATE OF status ON ActiveRunEntity WHEN NEW.status = 'completed' " +
                "BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END",
        )
        val stores = AtomicInteger()
        f.beforeStatusStore = { if (it == "completed") stores.incrementAndGet() }
        val finish = gate()
        gatedMessages(finish)
        on(STATUS, status("completed"))
        on(EVENTS, sse(terminal(0)))
        started(f)

        awaitUntil { f.live(KEY).value?.status == "completed" }
        assertEquals("completed", db.activeRunDao().forSession(KEY)!!.status)
        assertEquals(listOf("live=running row=running"), atRetry) // nothing terminal stored or shown before the retry
        assertEquals(2, stores.get())
        finish.countDown()
        done.await()
    }

    private val stopPath = "/v1/runs/$RUN/stop"
    private val stopAccepted = json("""{"run_id":"$RUN","status":"stopping"}""")

    /** The position of [request] among the stop requests, in the order the server recorded them. */
    private fun stopIndex(request: RecordedRequest) = requestsTo(stopPath).indexOfFirst { it === request }

    /** A run whose stream ends in a cancellation only once [release] opens; its transcript sync answers at once. */
    private suspend fun cancelledOnRelease(f: RunFollower, release: CountDownLatch) {
        on(STATUS, status("stopping"))
        messages()
        routes[EVENTS] = {
            release.pass()
            sseResponse(terminal(5, "cancelled"))
        }
        started(f)
    }

    @Test
    fun overlappingStopsKeepTheIntentOfTheOneAccepted() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val release = gate()
        val firstAnswer = gate()
        val secondAnswer = gate()
        routes[stopPath] = { request ->
            if (stopIndex(request) == 0) {
                firstAnswer.pass()
                json("{}", 500)()
            } else {
                secondAnswer.pass()
                stopAccepted()
            }
        }
        cancelledOnRelease(f, release)

        val first = backgroundScope.async { runCatching { f.stop(KEY, RUN) } }
        awaitUntil { requestsTo(stopPath).size == 1 }
        val second = backgroundScope.async { f.stop(KEY, RUN) }
        awaitUntil { requestsTo(stopPath).size == 2 }
        firstAnswer.countDown()
        assertTrue(first.await().exceptionOrNull() is ApiError.Http) // failed while the second is in flight
        assertTrue(db.activeRunDao().forSession(KEY)!!.stopRequested)
        secondAnswer.countDown()
        second.await()
        release.countDown()
        done.await()

        assertEquals(listOf("STOPPED"), outcomes().map { it.outcome })
    }

    @Test
    fun overlappingStopsThatBothFailLeaveNoIntent() = runFollowerTest {
        val f = follower()
        val release = gate()
        val firstAnswer = gate()
        on(STATUS, status("running"))
        routes[stopPath] = { request ->
            if (stopIndex(request) == 0) firstAnswer.pass()
            json("{}", 500)()
        }
        heldOpen(f, release)

        val first = backgroundScope.async { runCatching { f.stop(KEY, RUN) } }
        awaitUntil { requestsTo(stopPath).size == 1 }
        assertTrue(runCatching { f.stop(KEY, RUN) }.exceptionOrNull() is ApiError.Http) // fails while the first is in flight
        assertTrue(db.activeRunDao().forSession(KEY)!!.stopRequested)
        firstAnswer.countDown()
        assertTrue(first.await().exceptionOrNull() is ApiError.Http)

        assertFalse(db.activeRunDao().forSession(KEY)!!.stopRequested)
        release.countDown()
    }

    @Test
    fun stopWhoseIntentCannotBeStoredSendsNothingAndALaterStopCounts() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val release = gate()
        val answer = gate()
        routes[stopPath] = {
            answer.pass()
            stopAccepted()
        }
        cancelledOnRelease(f, release)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_intent BEFORE UPDATE OF stopRequested ON ActiveRunEntity BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END",
        )

        assertTrue(runCatching { f.stop(KEY, RUN) }.exceptionOrNull() is SQLiteException) // the error surfaces
        assertTrue(requestsTo(stopPath).isEmpty()) // and nothing was sent without a stored intent
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_intent")
        val second = backgroundScope.async { f.stop(KEY, RUN) }
        awaitUntil { requestsTo(stopPath).size == 1 }
        release.countDown() // the run is cancelled before the stop's answer arrives
        done.await()

        assertEquals(listOf("STOPPED"), outcomes().map { it.outcome })
        answer.countDown()
        second.await()
    }

    @Test
    fun acceptedStopSurvivesItsCallerBeingCancelled() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val release = gate()
        val firstAnswer = gate()
        val secondAnswer = gate()
        routes[stopPath] = { request ->
            if (stopIndex(request) == 0) {
                firstAnswer.pass()
                stopAccepted()
            } else {
                secondAnswer.pass()
                json("{}", 500)()
            }
        }
        cancelledOnRelease(f, release)

        val first = backgroundScope.launch { f.stop(KEY, RUN) }
        awaitUntil { requestsTo(stopPath).size == 1 }
        val second = backgroundScope.async { runCatching { f.stop(KEY, RUN) } }
        awaitUntil { requestsTo(stopPath).size == 2 } // both stops are in flight
        first.cancel() // the caller goes away while the server takes the stop
        firstAnswer.countDown()
        first.join()
        // Only now the overlapping stop fails, as the last one in flight: it clears the intent unless the cancelled
        // caller's acceptance was recorded.
        secondAnswer.countDown()
        assertTrue(second.await().exceptionOrNull() is ApiError.Http)
        assertTrue(db.activeRunDao().forSession(KEY)!!.stopRequested)
        release.countDown()
        done.await()

        assertEquals(listOf("STOPPED"), outcomes().map { it.outcome })
    }

    @Test
    fun stopIntentFromBeforeARestartSurvivesTwoOverlappingFailedStops() = runFollowerTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        val release = gate()
        val firstAnswer = gate()
        val secondAnswer = gate()
        routes[stopPath] = { request ->
            if (stopIndex(request) == 0) firstAnswer.pass() else secondAnswer.pass()
            json("{}", 500)()
        }
        cancelledOnRelease(f, release)
        db.activeRunDao().setStopRequested(RUN) // accepted by the server before the process restarted

        val first = backgroundScope.async { runCatching { f.stop(KEY, RUN) } }
        awaitUntil { requestsTo(stopPath).size == 1 }
        val second = backgroundScope.async { runCatching { f.stop(KEY, RUN) } }
        awaitUntil { requestsTo(stopPath).size == 2 }
        firstAnswer.countDown()
        assertTrue(first.await().exceptionOrNull() is ApiError.Http)
        secondAnswer.countDown()
        assertTrue(second.await().exceptionOrNull() is ApiError.Http)

        assertTrue(db.activeRunDao().forSession(KEY)!!.stopRequested)
        release.countDown()
        done.await()
        assertEquals(listOf("STOPPED"), outcomes().map { it.outcome })
    }

    @Test
    fun stopIntentLoadAndRegisterInterleave() = runFollowerTest {
        val f = follower()
        addSession()
        addRun()
        db.activeRunDao().setStopRequested(RUN) // accepted by the server before the process restarted
        // Refresher.stop is called directly with its lock held here, so both stops queue on that lock before either
        // registers and get it in turn. A stop that counts itself and loads the stored intent in two lock steps lets
        // the other count itself in between: both then skip the load and the last failure erases the intent.
        val refresher = RunFollower::class.java.getDeclaredMethod("refresher", ActiveRunEntity::class.java)
            .apply { isAccessible = true }.invoke(f, db.activeRunDao().get(RUN))!!
        val lock = refresher.javaClass.getDeclaredField("stopping").apply { isAccessible = true }.get(refresher) as Mutex
        val stop = refresher.javaClass.getDeclaredMethod("stop", Function1::class.java, Continuation::class.java)
            .apply { isAccessible = true }
        val failingPost: suspend () -> Unit = { throw ApiError.Http(500, null) }
        fun failingStop() = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { suspendCoroutineUninterceptedOrReturn<Unit> { stop.invoke(refresher, failingPost, it) } }
        }

        lock.lock()
        val first = failingStop() // each runs up to the held lock before the next line
        val second = failingStop()
        lock.unlock()

        assertTrue(first.await().exceptionOrNull() is ApiError.Http)
        assertTrue(second.await().exceptionOrNull() is ApiError.Http)
        assertTrue(db.activeRunDao().get(RUN)!!.stopRequested)
    }

    @Test
    fun followerCleanupOutlastsACancelledCaller() = runFollowerTest {
        follower() // registers the scope the cleanup ends
        val holding = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        lateinit var cleanup: Job
        var cleanupEndedFirst = true
        backgroundScope.launch {
            // A follower in a non-cancellable step under the repository lock that resumes from a real thread.
            withContext(NonCancellable) {
                withRepositoryLock {
                    holding.complete(Unit)
                    withContext(Dispatchers.IO) { release.await(10, TimeUnit.SECONDS) }
                }
                cleanupEndedFirst = cleanup.isCompleted
            }
        }
        holding.await()

        cleanup = launch(start = CoroutineStart.UNDISPATCHED) { endFollowers() }
        cleanup.cancel() // as runTest cancels a timed-out body during its cleanup
        release.countDown()
        cleanup.join()

        assertFalse("the cleanup ended before the follower", cleanupEndedFirst)
        withRepositoryLock { } // free again before Room closes
    }
}
