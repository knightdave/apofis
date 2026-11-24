package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.security.MessageDigest
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
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.api.expandedSize
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.db.choice

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
        queue = imageQueue(db)
    }

    // The image store as the queue sees it: bytes by digest, sizes that may differ from the bytes, and a hook in the read.
    private val files = HashMap<String, ByteArray>()
    private val sizes = HashMap<String, Long>()
    @Volatile private var onRead: () -> Unit = {}
    private var schedules = 0
    private val sizeAsked = mutableListOf<String>()

    private fun imageQueue(db: AppDatabase, active: () -> Boolean = { true }) = SendQueue(
        api, db, { key, run -> started += key to run }, { clock }, schedule = { schedules++ }, active = active,
        readImage = { onRead(); files[it] }, imageSize = { sizeAsked += it; sizes[it] ?: files[it]?.size?.toLong() },
    )

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
        val stored = withContext(Dispatchers.Default) { withTimeout(60_000) { db.pendingSendDao().observe("apofis-a").first { it.isNotEmpty() } } }
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
        withContext(Dispatchers.Default) { withTimeout(60_000) { db.pendingSendDao().observe("apofis-a").first { it.isNotEmpty() } } }
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
        respond("""{"object":"hermes.session.deleted","id":"apofis-a","deleted":true}""")
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

    // Images, the frozen model choice and createStarted (Milestone 4 Task 1a).

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun photo(vararg bytes: Byte): String = sha(bytes).also { files[it] = bytes }

    private val oneTwoThree = "data:image/jpeg;base64,AQID" // bytes 1, 2, 3

    @Test
    fun imagesNeverSteer() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val d = photo(1, 2, 3)
        var atCommit = ""
        ChatRepository(api, db, { clock }, queue).send("apofis-a", "look", listOf(d)) {
            db.openHelper.readableDatabase.query("SELECT state || ',' || IFNULL(runId, '') FROM PendingSendEntity").use { it.moveToFirst(); atCommit = it.getString(0) }
        }
        assertEquals("queued,", atCommit) // stored as a run send, not as a steer that delivery later repairs
        val row = rows("apofis-a").single()
        assertEquals("queued", row.state) // behind the running reply (decision 5)
        assertNull(row.runId)
        assertFalse(row.attempted)
        assertEquals(d, row.images)
        assertEquals(buildRunBody("apofis-a", "look", listOf(d)), row.requestBody)
        assertEquals(0, server.requestCount) // no steer
    }

    @Test
    fun storedImageSteerIsNeverDelivered() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val d = photo(1, 2, 3)
        val body = buildRunBody("apofis-a", "look", listOf(d))
        // A steering image row (never written by insert) found by the drain's recovery of stored steers.
        db.pendingSendDao().upsert(PendingSendEntity("k", "apofis-a", "look", body, clock, "steering", "run_1", null, images = d))
        assertFalse(queue.drain())
        assertEquals(0, server.requestCount)
        assertEquals("queued", pending("k").state)
        assertNull(pending("k").runId)

        db.activeRunDao().delete("run_1") // the reply ends: the row is the next turn
        respond(runStart("run_2"), 202)
        assertTrue(queue.drain())
        val run = take()
        assertEquals("/v1/runs", run.target)
        assertEquals(body.replace("apofis-image:$d", oneTwoThree), run.body!!.utf8())
    }

    @Test
    fun bodyAtCapStoredOverCapRefused() = runTest {
        addSession("apofis-a")
        running("apofis-a") // a stored row waits behind the reply: nothing is read or sent
        val d = sha(byteArrayOf(9))
        val (size, fits) = atWireCap(d)
        sizes[d] = size
        try {
            queue.submit("apofis-a", fits + "x", images = listOf(d))
            fail("stored a body one byte over the cap")
        } catch (_: SendTooLarge) {
        }
        assertTrue(rows("apofis-a").isEmpty())
        assertEquals(0, schedules) // nothing armed either

        queue.submit("apofis-a", fits, images = listOf(d))
        assertEquals(fits, rows("apofis-a").single().text)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun imageWithoutAFileIsNotStored() = runTest {
        addSession("apofis-a")
        try {
            queue.submit("apofis-a", "look", images = listOf(sha(byteArrayOf(1))))
            fail("stored a send whose image is not on this phone")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(rows("apofis-a").isEmpty())
        assertEquals(0, schedules)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun choiceReadAtInsert() = runTest {
        addSession("apofis-a")
        val repo = ChatRepository(api, db, { clock }, queue)
        val choice = ChatModel("m", "p", "high", true)
        repo.setChoice("apofis-a", choice)
        respond(runStart("run_1"), 202)
        repo.send("apofis-a", "hi")
        val expected = buildRunBody("apofis-a", "hi", choice = choice)
        assertEquals(expected, rows("apofis-a").single().requestBody)
        assertEquals(expected, take().body!!.utf8())
    }

    @Test
    fun retryResendsFrozenBody() = runTest {
        addSession("apofis-a")
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.setChoice("apofis-a", ChatModel(model = "first"))
        respond("""{"error":{"code":"invalid_params","message":"route conflict"}}""", 400) // a refused model (H7)
        repo.send("apofis-a", "hi")
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertFalse(row.attempted)
        repo.setChoice("apofis-a", ChatModel(model = "second", fast = true))
        respond(runStart("run_1"), 202)
        assertEquals(RetryResult.STARTED, repo.retrySend(row.idempotencyKey))
        val (first, second) = recorded()
        assertEquals(buildRunBody("apofis-a", "hi", choice = ChatModel(model = "first")), first.body!!.utf8())
        assertEquals(first.body!!.utf8(), second.body!!.utf8())
        assertEquals(row.idempotencyKey, second.headers["Idempotency-Key"])
    }

    @Test
    fun setChoiceMakesNoRequest() = runTest {
        addSession("apofis-a") // on the server: a rename here would PATCH it
        val repo = ChatRepository(api, db, { clock }, queue)
        val before = db.sessionDao().get("apofis-a")!!
        repo.setChoice("apofis-a", ChatModel("m", "p", "low", false))
        assertEquals(ChatModel("m", "p", "low", false), db.sessionDao().get("apofis-a")!!.choice())
        assertEquals(before, db.sessionDao().get("apofis-a")!!.copy(model = null, provider = null, reasoning = null, fast = null))
        repo.setChoice("apofis-a", ChatModel())
        assertEquals(before, db.sessionDao().get("apofis-a"))
        repo.setChoice("apofis-gone", ChatModel(model = "m")) // no row, nothing written
        assertNull(db.sessionDao().get("apofis-gone"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun attemptExpandsRefs() = runTest {
        addSession("apofis-a")
        val d = photo(1, 2, 3)
        respond(runStart("run_1"), 202)
        queue.submit("apofis-a", "look", images = listOf(d))
        val stored = buildRunBody("apofis-a", "look", listOf(d))
        val run = take()
        assertEquals(stored.replace("apofis-image:$d", oneTwoThree), run.body!!.utf8())
        val row = rows("apofis-a").single()
        assertEquals("sending", row.state)
        assertEquals(stored, row.requestBody) // the expanded text is never stored
        assertEquals(d, row.images)
    }

    @Test
    fun textOnlyRowIsSentAsStored() = runTest {
        addSession("apofis-a")
        val d = photo(1, 2, 3)
        onRead = { fail("a row without images reads no file") }
        respond(runStart("run_1"), 202)
        queue.submit("apofis-a", "apofis-image:$d")
        assertEquals(buildRunBody("apofis-a", "apofis-image:$d"), take().body!!.utf8())
    }

    private suspend fun assertImageMissing(attempted: Boolean = false) {
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertEquals(SendError.IMAGE_MISSING, row.error)
        assertEquals(attempted, row.attempted)
    }

    @Test
    fun missingImageOnCreatedChatSendsNothing() = runTest {
        addSession("apofis-a")
        val d = sha(byteArrayOf(1, 2, 3))
        sizes[d] = 3L // stored while the file was there; gone before the attempt
        queue.submit("apofis-a", "look", images = listOf(d))
        assertEquals(0, server.requestCount)
        assertImageMissing()
    }

    @Test
    fun missingImageOnLocalChatStartsNoRun() = runTest {
        addSession("apofis-a", existsOnServer = false)
        val d = sha(byteArrayOf(1, 2, 3))
        sizes[d] = 3L
        respond(created("look"), 201)
        queue.submit("apofis-a", "look", images = listOf(d))
        assertTrue(recorded().all { it.target == "/api/sessions" }) // at most the create, never a run
        assertImageMissing()
    }

    @Test
    fun changedFileOfflineNotMarkedAttempted() = runTest {
        addSession("apofis-a")
        val d = sha(byteArrayOf(1, 2, 3))
        files[d] = byteArrayOf(1, 2, 4) // the file under that name no longer has that digest
        queue.submit("apofis-a", "look", images = listOf(d))
        assertEquals(0, server.requestCount)
        assertImageMissing()
    }

    @Test
    fun attemptedRowKeepsItsUncertaintyWhenTheFileIsGone() = runTest {
        addSession("apofis-a")
        val d = photo(1, 2, 3)
        dropConnection()
        queue.submit("apofis-a", "look", images = listOf(d))
        val key = rows("apofis-a").single().idempotencyKey
        assertTrue(pending(key).attempted)
        val sent = server.requestCount
        files.clear()
        queue.drain()
        assertEquals(sent, server.requestCount)
        assertImageMissing(attempted = true)
        assertEquals(CancelResult.REMOVED_MAY_HAVE_SENT, queue.cancel(key))
    }

    @Test
    fun expiryRecheckedAfterExpansion() = runTest {
        addSession("apofis-a")
        val d = photo(1, 2, 3)
        onRead = { clock += SEND_EXPIRY_MS + 1 } // the deadline passes while the file is read
        queue.submit("apofis-a", "late", images = listOf(d))
        assertEquals(0, server.requestCount)
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertEquals(SendError.EXPIRED, row.error)
        assertFalse(row.attempted)
    }

    @Test
    fun expiryRecheckedAfterAttemptMark() = runTest { // control: true on main
        var mark: () -> Unit = {}
        val marking = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(Dispatchers.Unconfined) { sql, _ -> if (sql.startsWith("UPDATE") && sql.contains("PendingSendEntity")) mark() }
            .build()
        try {
            marking.sessionDao().upsert(SessionEntity("apofis-a", "apofis-a", "", null, false, false, 1.0, null, 0, null, false, true))
            val q = imageQueue(marking)
            val key = q.enqueue("apofis-a", "late")
            mark = { mark = {}; clock += SEND_EXPIRY_MS + 1 } // the first update of the row is the attempt mark
            q.drain()
            assertEquals(0, server.requestCount)
            val row = marking.pendingSendDao().get(key)!!
            assertEquals(SendError.EXPIRED, row.error)
            assertFalse(row.attempted)
        } finally {
            marking.close()
        }
    }

    @Test
    fun generationChangeDuringExpansionSendsNothing() = runTest {
        var open = true
        val q = imageQueue(db, active = { open })
        addSession("apofis-a")
        val d = photo(1, 2, 3)
        onRead = { open = false } // the server switch happens while the file is read
        q.submit("apofis-a", "look", images = listOf(d))
        assertEquals(0, server.requestCount)
        val row = rows("apofis-a").single()
        assertEquals("queued", row.state)
        assertFalse(row.attempted)
    }

    @Test
    fun identicalWireBytesOnRetry() = runTest {
        addSession("apofis-a")
        val d = photo(1, 2, 3)
        respond("""{"error":{"code":"boom"}}""", 500) // may have been accepted: attempted, same key on Retry
        respond(runStart("run_1"), 202)
        queue.submit("apofis-a", "look", images = listOf(d))
        val row = rows("apofis-a").single()
        assertEquals(SendError.HTTP_ERROR, row.error)
        assertTrue(row.attempted)
        assertEquals(RetryResult.STARTED, queue.retry(row.idempotencyKey))
        assertTrue(queue.drain())
        val (first, second) = recorded()
        assertEquals(buildRunBody("apofis-a", "look", listOf(d)).replace("apofis-image:$d", oneTwoThree), first.body!!.utf8())
        assertEquals(first.body!!.utf8(), second.body!!.utf8())
        assertEquals(row.idempotencyKey, first.headers["Idempotency-Key"])
        assertEquals(row.idempotencyKey, second.headers["Idempotency-Key"])
    }

    @Test
    fun createStartedWrittenBeforeCreateRequest() = runTest {
        addSession("apofis-a", existsOnServer = false)
        val create = holding.hold(MockResponse.Builder().code(201).body(created("hi")).addHeader("Content-Type", "application/json").build())
        respond(runStart("run_1"), 202)
        queue.enqueue("apofis-a", "hi")
        val job = launch(Dispatchers.Default) { queue.drain() }
        assertEquals("/api/sessions", take().target)
        db.sessionDao().get("apofis-a")!!.let {
            assertTrue(it.createStarted) // a Delete now knows the create may reach the server
            assertFalse(it.existsOnServer)
        }
        create.countDown()
        job.join()
        assertEquals("/v1/runs", take().target)
        db.sessionDao().get("apofis-a")!!.let {
            assertTrue(it.existsOnServer)
            assertTrue(it.createStarted) // not written back from the row read before the create
            assertEquals("hi", it.title)
        }
    }

    @Test
    fun createStartedStaysAfterALostCreateAnswer() = runTest {
        addSession("apofis-a", existsOnServer = false)
        dropConnection()
        queue.enqueue("apofis-a", "hi")
        assertFalse(queue.drain())
        assertTrue(recorded().all { it.target == "/api/sessions" })
        db.sessionDao().get("apofis-a")!!.let {
            assertTrue(it.createStarted)
            assertFalse(it.existsOnServer)
        }
    }

    // The admission budgets as ruled, measured on a body with an empty session id and the default choice.
    private fun rowSize(text: String, images: List<String>) = text.encodeToByteArray().size + buildRunBody("", text, images).encodeToByteArray().size

    /** A caption whose row (text plus body) is exactly [target] bytes: "x" adds 2, a control character 1 + 6. */
    private fun captionOfRow(target: Int, images: List<String>): String {
        val extra = target - rowSize("", images)
        val text = if (extra % 2 == 0) "x".repeat(extra / 2) else "\u0001" + "x".repeat((extra - 7) / 2)
        assertEquals(target, rowSize(text, images))
        return text
    }

    /** A file size and a caption whose expanded body, measured as admission does, is exactly [cap] bytes. */
    private fun atWireCap(d: String, cap: Long = 9_000_000): Pair<Long, String> {
        val base = expandedSize(buildRunBody("", "", listOf(d)), listOf(0L))
        val size = 3L * ((cap - base) / 4)
        val text = "x".repeat((cap - expandedSize(buildRunBody("", "", listOf(d)), listOf(size))).toInt())
        assertEquals(cap, expandedSize(buildRunBody("", text, listOf(d)), listOf(size)))
        return size to text
    }

    private suspend fun assertRefused(images: List<String>, text: String = "look", expected: Class<out Exception>) {
        try {
            queue.submit("apofis-a", text, images = images)
            fail("stored a send that admission refuses")
        } catch (e: Exception) {
            assertEquals(expected, e.javaClass)
        }
        assertTrue(rows("apofis-a").isEmpty())
        assertEquals(0, schedules)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tenImagesAcceptedElevenRefusedWithoutFileAccess() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val photos = (1..11).map { photo(it.toByte()) }
        assertRefused(photos, expected = IllegalArgumentException::class.java)
        val huge = object : AbstractList<String>() { // refused on its size alone: no element is ever read or copied
            override val size = 1_000_000
            override fun get(index: Int): String = throw AssertionError("element $index visited")
        }
        assertRefused(huge, expected = IllegalArgumentException::class.java)
        var asked = 0
        val growing = object : AbstractList<String>() { // ten when first asked, eleven by the time it is copied
            override val size get() = if (asked++ == 0) 10 else 11
            override fun get(index: Int): String = photos[index]
        }
        assertRefused(growing, expected = IllegalArgumentException::class.java)
        assertTrue(sizeAsked.isEmpty())
        queue.submit("apofis-a", "ten", images = photos.take(10))
        assertEquals(photos.take(10).joinToString(","), rows("apofis-a").single().images)
    }

    @Test
    fun badDigestRefusedWithoutFileAccess() = runTest {
        addSession("apofis-a")
        val good = photo(1)
        for (bad in listOf("", good.uppercase(), good.dropLast(1), good + "0", good.dropLast(1) + ",", "g" + good.drop(1))) {
            assertRefused(listOf(good, bad), expected = IllegalArgumentException::class.java)
        }
        assertTrue(sizeAsked.isEmpty())
    }

    @Test
    fun rowBudgetHoldsForTextAndImageSends() = runTest {
        addSession("apofis-a")
        // Busy but not steerable: a stored row waits, text included, and nothing is sent.
        db.activeRunDao().upsert(ActiveRunEntity("run_1", "apofis-a", null, "waiting_for_approval", 1L))
        for (images in listOf(emptyList(), listOf(photo(1, 2, 3)))) {
            assertRefused(images, captionOfRow(1_500_001, images), SendTooLarge::class.java)
            val fits = captionOfRow(1_500_000, images)
            queue.submit("apofis-a", fits, images = images)
            assertEquals(fits, rows("apofis-a").single().text)
            db.pendingSendDao().delete(rows("apofis-a").single().idempotencyKey)
            schedules = 0
        }
    }

    @Test
    fun hugeCaptionRefusedBeforeAnyWork() = runTest {
        addSession("apofis-a")
        assertRefused(listOf(photo(1)), "\u0001".repeat(1_500_001), SendTooLarge::class.java)
        assertTrue(sizeAsked.isEmpty()) // refused before any file or body
    }

    @Test
    fun choiceSetBetweenSendsDoesNotChangeAdmission() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        val d = sha(byteArrayOf(9))
        val (size, fits) = atWireCap(d, 9_000_000L - "apofis-a".length) // room for the session id: only the choice differs
        sizes[d] = size
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.send("apofis-a", fits, listOf(d))
        clock += 1
        repo.setChoice("apofis-a", ChatModel("m".repeat(256), "p".repeat(256), "r".repeat(256), true))
        repo.send("apofis-a", fits, listOf(d))
        val (first, second) = rows("apofis-a")
        assertEquals(buildRunBody("apofis-a", fits, listOf(d)), first.requestBody)
        assertEquals(buildRunBody("apofis-a", fits, listOf(d), ChatModel("m".repeat(256), "p".repeat(256), "r".repeat(256), true)), second.requestBody)
    }

    @Test
    fun setChoiceRefusesLongNames() = runTest {
        addSession("apofis-a")
        val repo = ChatRepository(api, db, { clock }, queue)
        for (choice in listOf(ChatModel(model = "m".repeat(257)), ChatModel(provider = "p".repeat(257)), ChatModel(reasoning = "r".repeat(257)))) {
            try {
                repo.setChoice("apofis-a", choice)
                fail("stored $choice")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(ChatModel(), db.sessionDao().get("apofis-a")!!.choice())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fileLargerThanItsReportedSizeSendsNothing() = runTest {
        addSession("apofis-a")
        val big = ByteArray(7_100_000) { (it % 251).toByte() } // hashes to its digest, but expands past 9,400,000 characters
        val d = sha(big)
        files[d] = big
        sizes[d] = 1L // measured while a truncated copy stood under that name
        respond(runStart("run_1"), 202) // only for a build that would post it
        queue.submit("apofis-a", "look", images = listOf(d))
        assertEquals(0, server.requestCount)
        assertImageMissing()
    }

    @Test
    fun throwingReaderFailsLikeMissingAndOtherChatsStillSend() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        val d = photo(1, 2, 3)
        db.pendingSendDao().upsert(PendingSendEntity("ka", "apofis-a", "look", buildRunBody("apofis-a", "look", listOf(d)), clock, "queued", null, null, images = d))
        db.pendingSendDao().upsert(PendingSendEntity("kb", "apofis-b", "hi", buildRunBody("apofis-b", "hi"), clock + 1, "queued", null, null))
        onRead = { throw java.io.IOException("unreadable") }
        respond(runStart("run_b"), 202)
        assertTrue(queue.drain())
        assertImageMissing()
        assertEquals("/v1/runs", take().target)
        assertEquals("sending", pending("kb").state)
    }

    @Test
    fun legacyTextBodyIsPostedByteForByte() = runTest {
        addSession("apofis-a")
        val legacy = """{ "input" : "hi",  "session_id":"apofis-a" }""" // not the builder's spacing or key order
        db.pendingSendDao().upsert(PendingSendEntity("k", "apofis-a", "hi", legacy, clock, "queued", null, null))
        respond(runStart("run_1"), 202)
        assertTrue(queue.drain())
        assertEquals(legacy, take().body!!.utf8())
    }

    @Test
    fun admittedAtTheWireCapIsPostedWithItsIdAndChoice() = runTest {
        addSession("apofis-a") // exists on the server, session id "apofis-a"
        val choice = ChatModel("m".repeat(256), "p".repeat(256), "r".repeat(256), true)
        ChatRepository(api, db, { clock }, queue).setChoice("apofis-a", choice)
        val (size, caption) = atWireCap("0".repeat(64)) // only the digest's length matters to the sizes
        val photo = ByteArray(size.toInt()) { (it % 253).toByte() }
        val d = sha(photo)
        files[d] = photo
        respond(runStart("run_1"), 202)
        queue.submit("apofis-a", caption, images = listOf(d))
        val run = take()
        assertEquals("/v1/runs", run.target)
        val dataUrl = "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(photo)
        assertEquals(buildRunBody("apofis-a", caption, listOf(d), choice).replace("apofis-image:$d", dataUrl), run.body!!.utf8())
        assertEquals("sending", rows("apofis-a").single().state)
    }

    @Test
    fun grownRepeatedFileStopsReadingAtTheCeiling() = runTest {
        addSession("apofis-a")
        val grown = ByteArray(4_000_000) { (it % 241).toByte() } // two expansions pass 9,400,000 characters
        val d = sha(grown)
        files[d] = grown
        sizes[d] = 1L // measured as a truncated copy
        var reads = 0
        onRead = { reads++ }
        queue.submit("apofis-a", "look", images = listOf(d, d, d))
        assertEquals(2, reads) // the third occurrence is never read
        assertEquals(0, server.requestCount)
        assertImageMissing()
    }

    @Test
    fun sizeNearLongMaxIsRefusedWithoutOverflow() = runTest {
        addSession("apofis-a")
        val d = sha(byteArrayOf(1))
        for (size in listOf(Long.MAX_VALUE - 1, 3L shl 61)) { // the second makes 4 * ceil(n / 3) wrap to Long.MIN_VALUE
            sizes[d] = size
            assertRefused(listOf(d), expected = SendTooLarge::class.java)
        }
    }

    // Issue 16 row 6: a stored steer is never delivered past the send expiry.
    @Test
    fun oldSteerNeverDelivered() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }).submit("apofis-a", "a day late")
        clock += SEND_EXPIRY_MS + 1
        respond(fixture("steer_response.json")) // served only if the steer is wrongly sent

        assertTrue(queue.drain())

        assertEquals(0, server.requestCount)
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertEquals(SendError.EXPIRED, row.error)
        assertFalse(row.attempted)
    }

    // Issue 16 row 9: a refused key on a recovered steer stops the drain before any other send is tried.
    @Test
    fun recoveredSteer401KeepsOthersQueued() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        running("apofis-a")
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }).submit("apofis-a", "steer under a refused key")
        clock += 1
        val waiting = queue.enqueue("apofis-b", "waits for a fixed key")
        repeat(2) { respond("""{"error":{"code":"invalid_api_key"}}""", 401) } // two, so a second request fails the count

        assertFalse(queue.drain())

        assertEquals(1, server.requestCount)
        val a = rows("apofis-a").single()
        assertEquals("failed", a.state)
        assertEquals(SendError.UNAUTHORIZED, a.error)
        assertFalse(a.attempted)
        val b = pending(waiting)
        assertEquals("queued", b.state)
        assertFalse(b.attempted)
    }

    // Issue 16 row 9: only a refused key stops the drain. A recovered steer answered 503 fails with its own error and the
    // other chat's send still starts.
    @Test
    fun recoveredSteer503DoesNotStopTheDrain() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        running("apofis-a")
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }).submit("apofis-a", "steer the server refuses")
        clock += 1
        val other = queue.enqueue("apofis-b", "starts anyway")
        respond("{}", 503)
        respond(runStart("run_2"), 202)

        assertTrue(queue.drain())

        assertEquals(2, server.requestCount)
        val a = rows("apofis-a").single()
        assertEquals("failed", a.state)
        assertEquals(SendError.HTTP_ERROR, a.error)
        assertFalse(a.attempted)
        assertEquals("sending", pending(other).state)
    }

    // Issue 16 row 9: a foreground steer refused for the key fails its own row; the stop never leaves submit, so the
    // caller never offers the stored text again.
    @Test
    fun foregroundSteer401StaysInsideSubmit() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        repeat(2) { respond("""{"error":{"code":"invalid_api_key"}}""", 401) } // two, so a second request fails the count

        queue.submit("apofis-a", "steer under a refused key")

        assertEquals(1, server.requestCount)
        val row = rows("apofis-a").single()
        assertEquals("failed", row.state)
        assertEquals(SendError.UNAUTHORIZED, row.error)
        assertFalse(row.attempted)
    }

    // Issue 16 row 7: the steer cannot be cancelled, so the open check is what keeps an answer that arrives after a
    // server switch from being written: the row stays as the request left it.
    @Test
    fun steerAnsweredAfterTheSwitchWritesNothing() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }).submit("apofis-a", "also do this")
        val gate = holding.hold(
            MockResponse.Builder().code(200).body(fixture("steer_response.json")).addHeader("Content-Type", "application/json").build(),
        )
        val open = java.util.concurrent.atomic.AtomicBoolean(true) // read on the drain's thread
        val closing = imageQueue(db, active = open::get)
        val drained = async(Dispatchers.Default) { closing.drain() }
        assertEquals("/v1/runs/run_1/steer", take().target)

        open.set(false) // the server switched while the steer was in flight
        gate.countDown()

        assertFalse(drained.await())
        val row = rows("apofis-a").single()
        assertEquals("steering", row.state)
        assertTrue(row.attempted)
    }
}
