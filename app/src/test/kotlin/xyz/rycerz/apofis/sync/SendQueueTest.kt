package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import xyz.rycerz.apofis.api.HoldingQueue
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
    private val holding = HoldingQueue()
    private val started = mutableListOf<Pair<String, String>>()
    @Volatile private var clock = 10_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = holding
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
        holding.closeAndVerify(server)
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

    private fun created(title: String) =
        """{"object":"hermes.session","session":{"id":"apofis-a","source":"api_server","title":"$title","pinned":false,""" +
            """"archived":false,"hidden":false,"started_at":1.0,"message_count":0}}"""

    private suspend fun local(title: String? = null, pinned: Boolean = false, archived: Boolean = false) {
        addSession("apofis-a", existsOnServer = false)
        db.sessionDao().upsert(db.sessionDao().get("apofis-a")!!.copy(title = title, pinned = pinned, archived = archived))
    }

    @Test
    fun firstSendStoresTheTitleTheServerAccepted() = runTest {
        local()
        respond(created("Hello there, cleaned by the server"), 201)
        respond(fixture("run_start.json"), 202)
        queue.enqueue("apofis-a", "Hello there\nsecond line")
        assertTrue(queue.drain())
        assertTrue(take().body!!.utf8().contains("\"title\":\"Hello there\""))
        assertEquals("Hello there, cleaned by the server", db.sessionDao().get("apofis-a")!!.title)
    }

    @Test
    fun firstLineAnotherChatHoldsGetsADisambiguatedTitle() = runTest {
        local()
        respond("""{"error":{"code":"invalid_title","message":"Title 'Hello' is already in use"}}""", 400)
        respond(created("Hello (2)"), 201)
        respond(fixture("run_start.json"), 202)
        val key = queue.enqueue("apofis-a", "Hello")
        assertTrue(queue.drain())
        assertTrue(take().body!!.utf8().contains("\"title\":\"Hello\""))
        assertTrue(take().body!!.utf8().contains("\"title\":\"Hello (2)\""))
        assertEquals("/v1/runs", take().target)
        assertEquals("sending", pending(key).state)
        assertEquals("Hello (2)", db.sessionDao().get("apofis-a")!!.title)
    }

    @Test
    fun localRenameIsTheTitleOfTheCreate() = runTest {
        local(title = "My own name")
        respond(created("My own name"), 201)
        respond(fixture("run_start.json"), 202)
        queue.enqueue("apofis-a", "Hello")
        assertTrue(queue.drain())
        assertTrue(take().body!!.utf8().contains("\"title\":\"My own name\""))
        assertEquals("My own name", db.sessionDao().get("apofis-a")!!.title)
    }

    @Test
    fun createWhoseAnswerWasLostReadsTheTitleItHolds() = runTest {
        local()
        respond(fixture("session_exists_409.json"), 409)
        respond(created("Hello"))
        respond(fixture("run_start.json"), 202)
        queue.enqueue("apofis-a", "Hello")
        assertTrue(queue.drain())
        take()
        assertEquals("/api/sessions/apofis-a", take().target)
        assertEquals("Hello", db.sessionDao().get("apofis-a")!!.title)
    }

    @Test
    fun offlinePinnedAndArchivedChatKeepsItsFlagsOnCreate() = runTest {
        local(pinned = true, archived = true)
        respond(created("Hello"), 201)
        respond(fixture("session_patch.json"))
        respond(fixture("run_start.json"), 202)
        queue.enqueue("apofis-a", "Hello")
        assertTrue(queue.drain())
        take()
        val patch = take()
        assertEquals("PATCH", patch.method)
        assertEquals("""{"pinned":true,"archived":true}""", patch.body!!.utf8())
        val row = db.sessionDao().get("apofis-a")!!
        assertTrue(row.existsOnServer && row.pinned && row.archived)
    }

    @Test
    fun lostResponseDoesNotDuplicate() = runTest {
        addSession("apofis-a")
        val lost = holding.hold(
            MockResponse.Builder().code(202).body(fixture("run_start.json")).addHeader("Content-Type", "application/json").build(),
        )
        respond(fixture("run_start_replayed.json"), 202)
        val key = queue.enqueue("apofis-a", "once only")

        val first = launch(Dispatchers.Default) { queue.drain() }
        val sent = take() // the server has the request; the client is cancelled before the response is stored
        first.cancel()
        first.join()
        lost.countDown() // the response now goes to a closed call
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
        respond(created("hi")) // its title is read, since the create that made it was not answered here
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
    fun sendIsStoredWhileARequestHoldsTheRepositoryLock() = runTest {
        addSession("apofis-a")
        val dto = """{"id":"apofis-a","source":"api_server","title":"t","pinned":false,"archived":false,"hidden":false,""" +
            """"started_at":1.0,"last_active":2.0,"message_count":1,"preview":"p","_lineage_ids":[]}"""
        val list = holding.hold(
            MockResponse.Builder().code(200).body("""{"object":"list","data":[$dto],"has_more":false}""")
                .addHeader("Content-Type", "application/json").build(),
        )
        respond(fixture("run_start.json"), 202)
        val repo = ChatRepository(api, db, { clock }, queue)
        val refresh = launch(Dispatchers.Default) { repo.refreshSessions() }
        take() // the drawer's list request is in flight and holds the repository lock
        val send = launch(Dispatchers.Default) { repo.send("apofis-a", "kept") }

        // Bounded only so a regression fails instead of hanging: the row must commit while the request is still held.
        val stored = withContext(Dispatchers.Default) { withTimeout(5_000) { db.pendingSendDao().observe("apofis-a").first { it.isNotEmpty() } } }
        assertEquals("kept", stored.single().text)
        assertEquals(1, server.requestCount)
        list.countDown()
        refresh.join()
        send.join()
        assertEquals("sending", rows("apofis-a").single().state)
    }

    @Test
    fun wakeupIsArmedBeforeTheRowAndAWorkerStartingAtOnceSeesIt() = runTest {
        addSession("apofis-a")
        respond(fixture("run_start.json"), 202)
        val scope = this
        lateinit var q: SendQueue
        var rowsWhenArmed = -1
        var worker: Deferred<String>? = null
        q = SendQueue(api, db, { _, _ -> }, { clock }, schedule = {
            rowsWhenArmed = rows("apofis-a").size
            // A worker WorkManager starts at once: undispatched, so its drain begins while the send is being handed off.
            worker = scope.async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
                q.drain()
                rows("apofis-a").single().state
            }
        })

        q.submit("apofis-a", "durable")

        assertEquals(0, rowsWhenArmed) // armed before the row exists: a death after the commit still has a worker
        assertEquals("sending", worker!!.await()) // the worker's drain did not end on an empty queue
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refusedRetryKeepsTheUncertaintyOfAnEarlierAttempt() = runTest {
        addSession("apofis-a")
        val lost = holding.hold(
            MockResponse.Builder().code(202).body(fixture("run_start.json")).addHeader("Content-Type", "application/json").build(),
        )
        val key = queue.enqueue("apofis-a", "maybe sent")
        val first = launch(Dispatchers.Default) { queue.drain() }
        take() // the server accepted the first POST; its response is lost
        first.cancel()
        first.join()
        lost.countDown()
        respond("{}", 503) // the retry is refused before it reaches Hermes: that settles only this request
        assertFalse(queue.drain())
        assertEquals(2, server.requestCount)
        assertTrue(pending(key).attempted)
        clock += SEND_EXPIRY_MS + 1
        queue.drain()
        assertEquals("expired_uncertain", pending(key).error)
        val requests = server.requestCount

        assertEquals(RetryResult.NEEDS_CONFIRMATION, queue.retry(key))
        assertEquals(requests, server.requestCount)
    }

    @Test
    fun expiredUncertainNeedsConfirmationEvenIfTheClockMovedBack() = runTest {
        addSession("apofis-a")
        db.pendingSendDao().upsert(
            PendingSendEntity(
                idempotencyKey = "k", sessionKey = "apofis-a", text = "t", requestBody = buildRunBody("apofis-a", "t"),
                createdAt = clock, state = "failed", runId = null, error = SendError.EXPIRED_UNCERTAIN, attempted = true,
            ),
        )
        assertEquals(RetryResult.NEEDS_CONFIRMATION, queue.retry("k"))
    }

    @Test
    fun steerStoredRightBeforeADeathStillSteersTheRunningReply() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        var checks = 0
        val dying = SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }) // gone right after the commit
        dying.submit("apofis-a", "also do this")
        val stored = rows("apofis-a").single()
        assertEquals("steering", stored.state) // the intent: this run, not a new turn
        assertEquals("run_1", stored.runId)
        assertFalse(stored.attempted)
        assertEquals(0, server.requestCount)
        respond(fixture("steer_response.json"))

        assertTrue(queue.drain()) // the restarted app (or its worker)

        assertEquals("/v1/runs/run_1/steer", take().target)
        assertEquals("steered", rows("apofis-a").single().state)
        assertEquals(1, server.requestCount) // never POSTed as a new run
    }

    @Test
    fun storedSteerOfARunThatEndedBecomesTheNextTurn() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }).submit("apofis-a", "also do this")
        db.activeRunDao().delete("run_1") // the run ended before the restart
        respond(runStart("run_2"), 202)

        assertTrue(queue.drain())

        assertEquals("/v1/runs", take().target)
        assertEquals("sending", rows("apofis-a").single().state)
    }

    @Test
    fun chatHiddenBetweenStoringAndSteeringIsNotSteered() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val hidden = """{"id":"apofis-a","source":"api_server","title":"t","pinned":false,"archived":false,"hidden":true,""" +
            """"started_at":1.0,"last_active":2.0,"message_count":1,"preview":"p","_lineage_ids":[]}"""
        val list = holding.hold(
            MockResponse.Builder().code(200).body("""{"object":"list","data":[$hidden],"has_more":false}""")
                .addHeader("Content-Type", "application/json").build(),
        )
        val repo = ChatRepository(api, db, { clock }, queue)
        val refresh = launch(Dispatchers.Default) { repo.refreshSessions() }
        take() // the refresh holds the repository lock while its list request is in flight
        val send = launch(Dispatchers.Default) { repo.send("apofis-a", "steer me") }
        withContext(Dispatchers.Default) { withTimeout(5_000) { db.pendingSendDao().observe("apofis-a").first { it.isNotEmpty() } } }
        list.countDown() // the refresh now hides the chat, before the send can take the lock
        refresh.join()
        send.join()

        assertEquals(1, server.requestCount) // no steer
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertEquals("session_unavailable", row.error)
    }

    @Test
    fun workerStoppedByARefusedKeyDoesNotSettleOrDrainAgain() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        addSession("apofis-c")
        running("apofis-c") // an unrelated chat whose next send waits for its run
        var settled = 0
        val worker = SendQueue(api, db, { _, _ -> }, { clock }, settle = { settled++ })
        worker.enqueue("apofis-a", "refused")
        clock += 1
        val waiting = worker.enqueue("apofis-b", "waits for a fixed key")
        clock += 1
        worker.enqueue("apofis-c", "behind the run")
        respond("""{"error":{"code":"invalid_api_key"}}""", 401)

        assertFalse(worker.drainSettlingRuns())

        assertEquals(1, server.requestCount)
        assertEquals(0, settled)
        val b = pending(waiting)
        assertEquals("queued", b.state) // still waiting for credentials, never tried under the refused key
        assertFalse(b.attempted)
    }

    @Test
    fun steerIsStoredBeforeTheRequest() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val response = holding.hold(
            MockResponse.Builder().code(200).body(fixture("steer_response.json")).addHeader("Content-Type", "application/json").build(),
        )
        val repo = ChatRepository(api, db, { clock }, queue)
        val job = launch(Dispatchers.Default) { repo.send("apofis-a", "steer me") }
        take()
        val row = rows("apofis-a").single()
        assertEquals("steering", row.state)
        assertTrue(row.attempted)
        assertEquals("run_1", row.runId)
        assertEquals(SendState.SENDING, repo.transcript("apofis-a").first().filterIsInstance<UserMessage>().single().state)
        response.countDown()
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
        val response = holding.hold(
            MockResponse.Builder().code(200).body(fixture("session_create.json")).addHeader("Content-Type", "application/json").build(),
        )
        val key = queue.enqueue("apofis-a", "slow")
        val job = launch(Dispatchers.Default) { queue.drain() }
        take()
        clock += SEND_EXPIRY_MS + 1 // the deadline passes while the session is being created
        response.countDown()
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
        val steer = holding.hold(
            MockResponse.Builder().code(200).body(fixture("steer_response.json")).addHeader("Content-Type", "application/json").build(),
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
        steer.countDown()
        send.join()
        refresh.join()
        assertEquals("steered", rows("apofis-a").single().state)
        assertTrue(db.sessionDao().get("apofis-a")!!.hidden)
    }

    @Test
    fun failedSteerAfterDeleteLeavesNoRow() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val refused = holding.hold(MockResponse.Builder().code(400).body("{}").build())
        respond(fixture("session_delete.json"))
        val repo = ChatRepository(api, db, { clock }, queue)
        val send = launch(Dispatchers.Default) { repo.send("apofis-a", "steer me") }
        take()
        // Undispatched: it runs to its first suspension, the lock the steer holds, before launch returns.
        val delete = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { repo.delete("apofis-a") }
        refused.countDown()
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
        val response = holding.hold(
            MockResponse.Builder().code(200).body(fixture("run_start.json")).addHeader("Content-Type", "application/json").build(),
        )
        old.enqueue("apofis-a", "before the switch")
        val drain = launch(Dispatchers.Default) { old.drain() }
        take() // the first send is on the wire when the old instance is closed
        open = false
        response.countDown() // the send completes only after the switch
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
