package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity

private const val RUN_ID = "run_ef57074dc1694db39e37045230b1425c"

@RunWith(RobolectricTestRunner::class)
class SendQueueTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var api: HermesApi
    private lateinit var queue: SendQueue
    private val started = mutableListOf<Pair<String, String>>()
    @Volatile private var clock = 10_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = HermesApi(server.url("/").toString(), "secret-key")
        queue = SendQueue(api, db, { key, run -> started += key to run }, { clock })
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    private fun runStart(runId: String) = """{"run_id":"$runId","status":"started","replayed":false}"""

    // OkHttp retries a dropped connection, so one failed call may reach the server up to three times.
    private fun dropConnection() =
        repeat(3) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }

    private suspend fun recorded(): List<RecordedRequest> = List(server.requestCount) { take() }

    private suspend fun take(): RecordedRequest = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS)!! }

    private suspend fun addSession(key: String, existsOnServer: Boolean = true, hidden: Boolean = false) =
        db.sessionDao().upsert(
            SessionEntity(
                key = key, currentId = key, lineageIds = "", title = null, pinned = false, archived = false,
                startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false,
                existsOnServer = existsOnServer, hidden = hidden,
            ),
        )

    private suspend fun pending(key: String) = db.pendingSendDao().get(key)!!

    private suspend fun rows(sessionKey: String) = db.pendingSendDao().observe(sessionKey).first()

    private suspend fun running(sessionKey: String, runId: String = "run_1") =
        db.activeRunDao().upsert(ActiveRunEntity(runId, sessionKey, null, "running", 1L))

    @Test
    fun noRunStartsWhileTheChatIsBeingDeleted() = runTest {
        addSession("apofis-a")
        val repo = ChatRepository(api, db, { clock }, queue)
        val key = queue.enqueue("apofis-a", "Hello there")
        repo.beginDelete("apofis-a")
        try {
            queue.drain()
            assertEquals(0, server.requestCount)
            assertEquals("queued", pending(key).state) // left as it was, not failed
        } finally {
            repo.endDelete("apofis-a")
        }
        respond(fixture("run_start.json"))
        assertTrue(queue.drain())
        assertEquals("sending", pending(key).state)
    }

    @Test
    fun happyPathCreatesSessionThenRun() = runTest {
        addSession("apofis-a", existsOnServer = false)
        respond(fixture("session_create.json"))
        respond(fixture("run_start.json"))
        val key = queue.enqueue("apofis-a", "Hello there")

        assertTrue(queue.drain())

        val create = take()
        assertEquals("POST", create.method)
        assertEquals("/api/sessions", create.target)
        assertTrue(create.body!!.utf8().contains("\"apofis-a\""))
        val run = take()
        assertEquals("/v1/runs", run.target)
        assertEquals(buildRunBody("apofis-a", "Hello there"), run.body!!.utf8())
        assertEquals(key, run.headers["Idempotency-Key"])
        assertEquals(pending(key).requestBody, run.body!!.utf8())
        assertTrue(db.sessionDao().get("apofis-a")!!.existsOnServer)
        val active = db.activeRunDao().forSession("apofis-a")!!
        assertEquals(RUN_ID, active.runId)
        assertEquals("running", active.status)
        assertEquals("sending", pending(key).state)
        assertEquals(RUN_ID, pending(key).runId)
        assertEquals(listOf("apofis-a" to RUN_ID), started)
    }

    @Test
    fun lostResponseDoesNotDuplicate() = runTest {
        addSession("apofis-a")
        server.enqueue(
            MockResponse.Builder().code(202).body(fixture("run_start.json")).addHeader("Content-Type", "application/json")
                .headersDelay(2, TimeUnit.SECONDS).build(),
        )
        respond(fixture("run_start_replayed.json"), 202)
        val key = queue.enqueue("apofis-a", "once only")

        val first = launch(Dispatchers.Default) { queue.drain() }
        val sent = take() // the server has the request; the client is cancelled before the response is stored
        first.cancel()
        first.join()
        assertEquals("queued", pending(key).state)
        assertNull(db.activeRunDao().forSession("apofis-a"))

        assertTrue(queue.drain())
        val again = take()
        assertEquals(sent.body!!.utf8(), again.body!!.utf8())
        assertEquals(sent.headers["Idempotency-Key"], again.headers["Idempotency-Key"])
        assertEquals(key, again.headers["Idempotency-Key"])
        assertEquals(1, db.activeRunDao().all().size)
        assertEquals(listOf("apofis-a" to RUN_ID), started)
    }

    @Test
    fun sessionAlreadyExistsIsFine() = runTest {
        addSession("apofis-a", existsOnServer = false)
        respond(fixture("session_exists_409.json"), 409)
        respond(fixture("run_start.json"), 202)
        val key = queue.enqueue("apofis-a", "hi")
        assertTrue(queue.drain())
        assertEquals("sending", pending(key).state)
        assertTrue(db.sessionDao().get("apofis-a")!!.existsOnServer)
    }

    @Test
    fun deletedSessionIdFailsPermanently() = runTest {
        addSession("apofis-a", existsOnServer = false)
        respond(fixture("session_recreate_deleted_409.json"), 409)
        val key = queue.enqueue("apofis-a", "hi")
        assertTrue(queue.drain())
        assertEquals("failed", pending(key).state)
        assertEquals("session_deleted", pending(key).error)
        assertEquals(1, server.requestCount)
        assertFalse(db.sessionDao().get("apofis-a")!!.existsOnServer)
    }

    @Test
    fun offlineStaysQueuedAndStops() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        dropConnection()
        val a = queue.enqueue("apofis-a", "one")
        clock += 1
        val b = queue.enqueue("apofis-b", "two")
        assertFalse(queue.drain())
        assertEquals("queued", pending(a).state)
        assertEquals("queued", pending(b).state)
        val bodies = recorded().map { it.body!!.utf8() }
        assertTrue(bodies.isNotEmpty())
        assertTrue(bodies.all { it == pending(a).requestBody }) // stopped after the first failure
    }

    @Test
    fun expiresAfter24Hours() = runTest {
        addSession("apofis-a")
        val key = queue.enqueue("apofis-a", "old")
        clock += SEND_EXPIRY_MS + 1
        assertTrue(queue.drain())
        assertEquals("failed", pending(key).state)
        assertEquals("expired", pending(key).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun retryOfExpiredSendGetsNewKey() = runTest {
        addSession("apofis-a")
        val key = queue.enqueue("apofis-a", "old")
        clock += SEND_EXPIRY_MS + 1
        queue.drain()
        assertEquals("expired", pending(key).error)
        assertEquals(RetryResult.STARTED, queue.retry(key))
        assertNull(db.pendingSendDao().get(key))
        val row = db.pendingSendDao().observe("apofis-a").first().single()
        assertEquals("queued", row.state)
        assertEquals(clock, row.createdAt)
        assertNull(row.error)
    }

    @Test
    fun permissionDeniedFails() = runTest {
        addSession("apofis-a")
        respond(fixture("run_foreign_source.json"), 409)
        val key = queue.enqueue("apofis-a", "hi")
        assertTrue(queue.drain())
        assertEquals("failed", pending(key).state)
        assertEquals("permission_denied", pending(key).error)
        assertTrue(started.isEmpty())
    }

    @Test
    fun unavailableStaysQueued() = runTest {
        addSession("apofis-a")
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "7").build())
        val key = queue.enqueue("apofis-a", "hi")
        assertFalse(queue.drain())
        assertEquals("queued", pending(key).state)
        assertNull(pending(key).error)
        assertFalse(pending(key).attempted) // a 503 is a definite refusal
    }

    @Test
    fun hiddenSessionFailsWithoutRequest() = runTest {
        addSession("apofis-a")
        val key = queue.enqueue("apofis-a", "hi")
        addSession("apofis-a", hidden = true) // hidden by a refresh after the send was queued
        assertTrue(queue.drain())
        assertEquals("session_unavailable", pending(key).error)
        assertEquals(0, server.requestCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun reservedSessionIsRefused() = runTest {
        addSession("apofis-push-1")
        queue.enqueue("apofis-push-1", "hi")
    }

    @Test
    fun cancelRemovesQueuedSend() = runTest {
        addSession("apofis-a")
        val key = queue.enqueue("apofis-a", "hi")
        queue.cancel(key)
        assertNull(db.pendingSendDao().get(key))
        assertTrue(queue.drain())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sendSteersWhenRunActive() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        respond(fixture("steer_response.json"))
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.send("apofis-a", "also this")
        val steer = take()
        assertEquals("/v1/runs/run_1/steer", steer.target)
        assertTrue(steer.body!!.utf8().contains("also this"))
        val row = rows("apofis-a").single()
        assertEquals("steered", row.state)
        assertEquals("run_1", row.runId)
        assertEquals(buildRunBody("apofis-a", "also this"), row.requestBody)
        val item = repo.transcript("apofis-a").first().filterIsInstance<UserMessage>().single()
        assertTrue(item.steered)
        assertEquals(SendState.SENT, item.state)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun failedSteerBecomesFailedMessageThatRetryQueues() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        respond("{}", 400)
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.send("apofis-a", "also this")
        val item = repo.transcript("apofis-a").first().filterIsInstance<UserMessage>().single()
        assertEquals(SendState.FAILED, item.state)
        assertEquals("http_error", pending(item.idempotencyKey!!).error)
        assertFalse(pending(item.idempotencyKey).attempted)
        assertNull(pending(item.idempotencyKey).runId)
        assertEquals("also this", item.text)

        db.activeRunDao().delete("run_1") // the run ended
        respond(fixture("run_start.json"), 202)
        assertEquals(RetryResult.STARTED, repo.retrySend(item.idempotencyKey))
        val run = recorded().last()
        assertEquals("/v1/runs", run.target)
        assertEquals(buildRunBody("apofis-a", "also this"), run.body!!.utf8())
        assertEquals("sending", pending(item.idempotencyKey).state)
    }

    @Test
    fun sendQueuesWhenNoRunIsActive() = runTest {
        addSession("apofis-a", existsOnServer = false)
        respond(fixture("session_create.json"))
        respond(fixture("run_start.json"), 202)
        ChatRepository(api, db, { clock }, queue).send("apofis-a", "first")
        assertEquals(2, server.requestCount)
        assertNotNull(db.activeRunDao().forSession("apofis-a"))
    }

    @Test
    fun ordersPerSession() = runTest {
        addSession("apofis-a")
        respond(runStart("run_1"), 202)
        respond(runStart("run_2"), 202)
        val first = queue.enqueue("apofis-a", "first")
        clock += 1
        val second = queue.enqueue("apofis-a", "second")

        assertFalse(queue.drain())
        assertEquals("sending", pending(first).state)
        assertEquals("queued", pending(second).state)
        assertEquals(1, server.requestCount)
        assertEquals(buildRunBody("apofis-a", "first"), take().body!!.utf8())

        assertFalse(queue.drain()) // the first is still sending
        assertEquals(1, server.requestCount)

        // The run ended and its transcript was synced (Task 8).
        db.pendingSendDao().delete(first)
        db.activeRunDao().delete("run_1")
        assertTrue(queue.drain())
        assertEquals(buildRunBody("apofis-a", "second"), take().body!!.utf8())
        assertEquals("sending", pending(second).state)
        assertEquals(listOf("apofis-a" to "run_1", "apofis-a" to "run_2"), started)
    }

    @Test
    fun attemptedExpiryNeedsConfirmation() = runTest {
        addSession("apofis-a")
        dropConnection()
        val key = queue.enqueue("apofis-a", "maybe sent")
        assertFalse(queue.drain())
        assertTrue(pending(key).attempted)
        clock += SEND_EXPIRY_MS + 1
        queue.drain()
        assertEquals("failed", pending(key).state)
        assertEquals("expired_uncertain", pending(key).error)

        assertEquals(RetryResult.NEEDS_CONFIRMATION, queue.retry(key))
        assertEquals("expired_uncertain", pending(key).error)
        assertEquals(RetryResult.STARTED, queue.retry(key, confirmedDuplicateRisk = true))
        assertNull(db.pendingSendDao().get(key))
        val row = rows("apofis-a").single()
        assertEquals("queued", row.state)
        assertFalse(row.attempted)
        assertEquals(clock, row.createdAt)
        assertEquals(RetryResult.NOT_FOUND, queue.retry(key))
    }

    @Test
    fun steerIsStoredBeforeTheRequest() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        server.enqueue(
            MockResponse.Builder().code(200).body(fixture("steer_response.json")).addHeader("Content-Type", "application/json")
                .headersDelay(500, TimeUnit.MILLISECONDS).build(),
        )
        val repo = ChatRepository(api, db, { clock }, queue)
        val job = launch(Dispatchers.Default) { repo.send("apofis-a", "steer me") }
        take()
        val row = rows("apofis-a").single()
        assertEquals("steering", row.state)
        assertTrue(row.attempted)
        assertEquals("run_1", row.runId)
        assertEquals(SendState.SENDING, repo.transcript("apofis-a").first().filterIsInstance<UserMessage>().single().state)
        job.join()
        assertEquals("steered", rows("apofis-a").single().state)
    }

    @Test
    fun steerNotFoundBecomesQueuedRunSend() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        respond("{}", 404)
        ChatRepository(api, db, { clock }, queue).send("apofis-a", "late steer")
        val row = rows("apofis-a").single()
        assertEquals("queued", row.state)
        assertNull(row.runId)
        assertFalse(row.attempted)
        assertEquals(1, server.requestCount) // the session is still busy until the follower clears the run
    }

    @Test
    fun steerNetworkFailureIsUncertain() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        dropConnection()
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.send("apofis-a", "lost ack")
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertEquals("steer_uncertain", row.error)
        assertNull(row.runId) // run-end cleanup by run id must not delete an undelivered steer
        assertTrue(row.attempted)
        assertEquals(RetryResult.NEEDS_CONFIRMATION, repo.retrySend(row.idempotencyKey))
        assertEquals("steer_uncertain", pending(row.idempotencyKey).error)
        assertEquals(RetryResult.STARTED, repo.retrySend(row.idempotencyKey, confirmedDuplicateRisk = true))
        val again = rows("apofis-a").single()
        assertEquals("queued", again.state)
        assertNull(again.runId)
        assertFalse(again.idempotencyKey == row.idempotencyKey)
    }

    @Test
    fun unconfirmedRetryNeedsConfirmation() = runTest {
        addSession("apofis-a")
        db.pendingSendDao().upsert(
            PendingSendEntity(
                idempotencyKey = "k", sessionKey = "apofis-a", text = "t", requestBody = buildRunBody("apofis-a", "t"),
                createdAt = clock, state = "failed", runId = null, error = SendError.UNCONFIRMED, attempted = true,
            ),
        )
        assertEquals(RetryResult.NEEDS_CONFIRMATION, queue.retry("k"))
        assertEquals("unconfirmed", pending("k").error)

        assertEquals(RetryResult.STARTED, queue.retry("k", confirmedDuplicateRisk = true))
        assertNull(db.pendingSendDao().get("k"))
        val again = rows("apofis-a").single()
        assertEquals("queued", again.state)
        assertFalse(again.attempted)
    }

    @Test
    fun interruptedSteerIsUncertainOnDrain() = runTest {
        addSession("apofis-a")
        db.pendingSendDao().upsert(
            PendingSendEntity(
                idempotencyKey = "k", sessionKey = "apofis-a", text = "t", requestBody = buildRunBody("apofis-a", "t"),
                createdAt = clock, state = "steering", runId = "run_1", error = null, attempted = true,
            ),
        )
        assertTrue(queue.drain())
        assertEquals("failed", pending("k").state)
        assertEquals("steer_uncertain", pending("k").error)
        assertNull(pending("k").runId)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun hiddenSessionFailsLocallyOnBothPaths() = runTest {
        addSession("apofis-a", hidden = true)
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.send("apofis-a", "run path")
        running("apofis-a")
        repo.send("apofis-a", "steer path")
        val all = rows("apofis-a")
        assertEquals(2, all.size)
        assertTrue(all.all { it.state == "failed" && it.error == "session_unavailable" })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun sendToMissingSessionInsertsNothing() = runTest {
        val repo = ChatRepository(api, db, { clock }, queue)
        try {
            repo.send("apofis-gone", "hi")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(rows("apofis-gone").isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun expiryIsCheckedAgainAfterSlowSessionCreate() = runTest {
        addSession("apofis-a", existsOnServer = false)
        server.enqueue(
            MockResponse.Builder().code(200).body(fixture("session_create.json")).addHeader("Content-Type", "application/json")
                .headersDelay(500, TimeUnit.MILLISECONDS).build(),
        )
        val key = queue.enqueue("apofis-a", "slow")
        val job = launch(Dispatchers.Default) { queue.drain() }
        take()
        clock += SEND_EXPIRY_MS + 1 // the deadline passes while the session is being created
        job.join()
        assertEquals("failed", pending(key).state)
        assertEquals("expired", pending(key).error)
        assertFalse(pending(key).attempted) // never sent
        assertEquals(1, server.requestCount)
    }

    @Test
    fun throwingRunStarterKeepsTheStartedSend() = runTest {
        val throwing = SendQueue(api, db, { _, _ -> throw IllegalStateException("follower broke") }, { clock })
        addSession("apofis-a")
        addSession("apofis-b")
        respond(runStart("run_1"), 202)
        respond(runStart("run_2"), 202)
        val a = throwing.enqueue("apofis-a", "one")
        clock += 1
        val b = throwing.enqueue("apofis-b", "two")
        assertTrue(throwing.drain())
        assertEquals("sending", pending(a).state)
        assertEquals("run_1", pending(a).runId)
        assertEquals("sending", pending(b).state)
        assertEquals("run_2", pending(b).runId)
    }

    @Test
    fun serverErrorStoresFixedCode() = runTest {
        addSession("apofis-a")
        respond("""{"error":{"code":"weird_thing"}}""", 500)
        val key = queue.enqueue("apofis-a", "hi")
        assertTrue(queue.drain())
        assertEquals("failed", pending(key).state)
        assertEquals("http_error", pending(key).error)
    }

    @Test
    fun drainStopsAtFirstUnauthorized() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        respond(fixture("error_401.json"), 401)
        val a = queue.enqueue("apofis-a", "one")
        clock += 1
        val b = queue.enqueue("apofis-b", "two")
        assertFalse(queue.drain())
        assertEquals("unauthorized", pending(a).error)
        assertEquals("queued", pending(b).state)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unreadableRunAckThenLateRetryNeedsConfirmation() = runTest {
        addSession("apofis-a")
        respond("not json", 202)
        val key = queue.enqueue("apofis-a", "hi")
        assertTrue(queue.drain())
        assertEquals("protocol", pending(key).error)
        assertTrue(pending(key).attempted)
        clock += SEND_EXPIRY_MS + 1
        assertEquals(RetryResult.NEEDS_CONFIRMATION, queue.retry(key))
        assertEquals("failed", pending(key).state)
        assertEquals(RetryResult.STARTED, queue.retry(key, confirmedDuplicateRisk = true))
        assertNull(db.pendingSendDao().get(key))
    }

    @Test
    fun unreadableRunAckRetriedEarlyReusesKeyAndBody() = runTest {
        addSession("apofis-a")
        respond("not json", 202)
        respond(fixture("run_start_replayed.json"), 202)
        val key = queue.enqueue("apofis-a", "hi")
        queue.drain()
        clock += SEND_EXPIRY_MS - 1
        assertEquals(RetryResult.STARTED, queue.retry(key))
        assertTrue(queue.drain())
        val (first, second) = recorded()
        assertEquals(first.body!!.utf8(), second.body!!.utf8())
        assertEquals(key, first.headers["Idempotency-Key"])
        assertEquals(key, second.headers["Idempotency-Key"])
        assertEquals("sending", pending(key).state)
    }

    @Test
    fun steerGatewayTimeoutNeedsConfirmation() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        respond("{}", 504)
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.send("apofis-a", "maybe steered")
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertTrue(row.attempted)
        assertNull(row.runId)
        assertEquals(RetryResult.NEEDS_CONFIRMATION, repo.retrySend(row.idempotencyKey))
    }

    @Test
    fun refreshCannotHideSessionDuringSteer() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        server.enqueue(
            MockResponse.Builder().code(200).body(fixture("steer_response.json")).addHeader("Content-Type", "application/json")
                .headersDelay(500, TimeUnit.MILLISECONDS).build(),
        )
        val hidden = """{"id":"apofis-a","source":"api_server","title":"t","pinned":false,"archived":false,"hidden":true,""" +
            """"started_at":1.0,"last_active":2.0,"message_count":1,"preview":"p","_lineage_ids":[]}"""
        respond("""{"object":"list","data":[$hidden],"has_more":false}""")
        val repo = ChatRepository(api, db, { clock }, queue)
        val send = launch(Dispatchers.Default) { repo.send("apofis-a", "steer me") }
        assertEquals("/v1/runs/run_1/steer", take().target)
        val refresh = launch(Dispatchers.Default) { repo.refreshSessions() }
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(200) }
        assertEquals(1, server.requestCount) // the refresh waits for the steer
        assertFalse(db.sessionDao().get("apofis-a")!!.hidden)
        send.join()
        refresh.join()
        assertEquals("steered", rows("apofis-a").single().state)
        assertTrue(db.sessionDao().get("apofis-a")!!.hidden)
    }

    @Test
    fun failedSteerAfterDeleteLeavesNoRow() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        server.enqueue(MockResponse.Builder().code(400).body("{}").headersDelay(500, TimeUnit.MILLISECONDS).build())
        respond(fixture("session_delete.json"))
        val repo = ChatRepository(api, db, { clock }, queue)
        val send = launch(Dispatchers.Default) { repo.send("apofis-a", "steer me") }
        take()
        val delete = launch(Dispatchers.Default) { repo.delete("apofis-a") }
        send.join()
        delete.join()
        assertNull(db.sessionDao().get("apofis-a"))
        assertTrue(rows("apofis-a").isEmpty())
    }

    @Test
    fun failedSessionCreateIsNotAnAttempt() = runTest {
        addSession("apofis-a", existsOnServer = false)
        dropConnection()
        val key = queue.enqueue("apofis-a", "hi")
        assertFalse(queue.drain())
        assertFalse(pending(key).attempted)
        assertTrue(recorded().all { it.target == "/api/sessions" })
        clock += SEND_EXPIRY_MS + 1
        queue.drain()
        assertEquals("expired", pending(key).error)
        assertEquals(RetryResult.STARTED, queue.retry(key))
    }

    @Volatile private var armed = false

    /**
     * A database that, once [armed], deletes the chat's pending rows on the query thread just before the next
     * statement whose SQL contains [match]: the user deleting the chat between retry's read and its write.
     */
    private fun racingDb(match: String): AppDatabase {
        lateinit var racing: AppDatabase
        racing = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(Dispatchers.Unconfined) { sql, _ ->
                if (armed && sql.contains(match)) {
                    armed = false
                    racing.openHelper.writableDatabase.execSQL("DELETE FROM PendingSendEntity WHERE sessionKey = 'apofis-a'")
                }
            }
            .build()
        racing.openHelper.writableDatabase.execSQL(
            "INSERT INTO SessionEntity (`key`, currentId, lineageIds, pinned, archived, startedAt, messageCount, stale, existsOnServer, hidden) " +
                "VALUES ('apofis-a', 'apofis-a', '', 0, 0, 1.0, 0, 0, 1, 0)",
        )
        return racing
    }

    @Test
    fun retryAfterConcurrentDeleteIsNotFound() = runTest {
        val racing = racingDb("`PendingSendEntity` SET")
        val q = SendQueue(api, racing, { _, _ -> }, { clock })
        respond("{}", 400)
        val key = q.enqueue("apofis-a", "hi")
        q.drain()
        assertEquals("http_error", racing.pendingSendDao().get(key)!!.error)
        armed = true
        assertEquals(RetryResult.NOT_FOUND, q.retry(key))
        assertFalse(armed) // the delete happened between the read and the write
        assertTrue(racing.pendingSendDao().observe("apofis-a").first().isEmpty())
        racing.close()
    }

    @Test
    fun retryReplacingKeyAfterConcurrentDeleteIsNotFound() = runTest {
        val racing = racingDb("DELETE FROM PendingSendEntity WHERE idempotencyKey")
        val q = SendQueue(api, racing, { _, _ -> }, { clock })
        val key = q.enqueue("apofis-a", "old")
        clock += SEND_EXPIRY_MS + 1
        q.drain()
        armed = true
        assertEquals(RetryResult.NOT_FOUND, q.retry(key))
        assertFalse(armed)
        assertTrue(racing.pendingSendDao().observe("apofis-a").first().isEmpty())
        racing.close()
    }

    @Test
    fun workerWithoutConnectionSucceeds() = runTest {
        val worker = TestListenableWorkerBuilder<SendWorker>(ApplicationProvider.getApplicationContext<Context>()).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test
    fun closedQueueTouchesNothing() = runTest {
        var open = true
        val closing = SendQueue(api, db, { _, _ -> started += "x" to "x" }, { clock }, active = { open })
        addSession("apofis-a")
        addSession("apofis-b")
        running("apofis-b")
        open = false
        val key = queue.enqueue("apofis-a", "written after the switch")
        val before = pending(key)

        assertFalse(closing.drain())
        assertClosed { closing.submit("apofis-b", "steer after the switch") }

        assertEquals(0, server.requestCount)
        assertEquals(before, pending(key))
        assertTrue(rows("apofis-b").isEmpty())
        assertTrue(started.isEmpty())
    }

    @Test
    fun drainStartedBeforeTheSwitchCannotSendRowsWrittenAfterIt() = runTest {
        var open = true
        val old = SendQueue(api, db, { key, run -> started += key to run }, { clock }, active = { open })
        addSession("apofis-a")
        addSession("apofis-b")
        server.enqueue(
            MockResponse.Builder().code(200).body(fixture("run_start.json")).addHeader("Content-Type", "application/json")
                .headersDelay(500, TimeUnit.MILLISECONDS).build(),
        )
        old.enqueue("apofis-a", "before the switch")
        val drain = launch(Dispatchers.Default) { old.drain() }
        take() // the first send is on the wire when the old instance is closed
        open = false
        val after = queue.enqueue("apofis-b", "after the switch")
        val written = pending(after)
        drain.join()

        assertEquals(1, server.requestCount)
        assertEquals(written, pending(after))
        assertTrue(started.isEmpty()) // no stream is attached for the old server either
    }

    private suspend fun assertClosed(block: suspend () -> Unit) {
        try {
            block()
            fail("a closed queue must refuse writes")
        } catch (e: IllegalStateException) {
            assertEquals("closed", e.message)
        }
    }

    @Test
    fun closedQueueRefusesEveryWrite() = runTest {
        var open = true
        val old = SendQueue(api, db, { _, _ -> }, { clock }, active = { open })
        addSession("apofis-a")
        val failed = old.enqueue("apofis-a", "failed before the switch")
        db.pendingSendDao().update(pending(failed).copy(state = "failed", error = "protocol"))
        val before = rows("apofis-a")
        open = false

        assertClosed { old.enqueue("apofis-a", "the same session key on the new server") }
        assertClosed { old.cancel(failed) }
        assertClosed { old.retry(failed) }

        assertEquals(before, rows("apofis-a"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun submitNeverThrowsOnceTheRowIsStored() = runTest {
        var checks = 0
        val closing = SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }) // closes right after the insert
        addSession("apofis-a")
        running("apofis-a")
        closing.submit("apofis-a", "kept") // must not throw: the caller would offer the text again
        assertEquals("kept", rows("apofis-a").single().text)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancelReportsWhatHappened() = runTest {
        addSession("apofis-a")
        val fresh = queue.enqueue("apofis-a", "fresh")
        val tried = queue.enqueue("apofis-a", "tried")
        db.pendingSendDao().update(pending(tried).copy(attempted = true))
        val sending = queue.enqueue("apofis-a", "sending")
        db.pendingSendDao().update(pending(sending).copy(state = "sending", runId = "run_1"))

        assertEquals(CancelResult.REMOVED, queue.cancel(fresh))
        assertEquals(CancelResult.REMOVED_MAY_HAVE_SENT, queue.cancel(tried))
        assertEquals(CancelResult.STARTED, queue.cancel(sending))
        assertEquals(CancelResult.NOT_FOUND, queue.cancel("missing"))
        assertEquals(listOf("sending"), rows("apofis-a").map { it.text })
    }

    @Test
    fun cancelKeepsARowAttemptedSinceTheWeakerWarning() = runTest {
        addSession("apofis-a")
        val key = queue.enqueue("apofis-a", "later")
        db.pendingSendDao().update(pending(key).copy(attempted = true)) // a drain tried it while the dialog was open

        assertEquals(CancelResult.NOW_ATTEMPTED, queue.cancel(key, mayHaveSentShown = false))
        assertEquals(listOf("later"), rows("apofis-a").map { it.text })
        assertEquals(CancelResult.REMOVED_MAY_HAVE_SENT, queue.cancel(key, mayHaveSentShown = true))
    }
}
