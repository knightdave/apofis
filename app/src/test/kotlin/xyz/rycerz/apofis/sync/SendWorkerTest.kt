package xyz.rycerz.apofis.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.HoldingQueue
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.notify.NoticeNotDelivered

/** The worker's application context: hands out the queue under test. */
private class QueueCtx(base: Context, private val queue: () -> SendQueue?) : ContextWrapper(base), SendQueueProvider {
    override val sendQueue get() = queue()
    override fun getApplicationContext(): Context = this
}

/** [SendWorker] run directly (no WorkManager) against a real queue, an in-memory database and a fake server. */
@RunWith(RobolectricTestRunner::class)
class SendWorkerTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var api: HermesApi
    private lateinit var queue: SendQueue
    private val holding = HoldingQueue()
    private val clock = 10_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = holding
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = HermesApi(server.url("/").toString(), "secret-key")
    }

    @After
    fun tearDown() {
        db.close()
        holding.closeAndVerify(server)
    }

    private fun worker() =
        TestListenableWorkerBuilder.from(QueueCtx(ApplicationProvider.getApplicationContext()) { queue }, SendWorker::class.java).build()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    private fun refusedKey() = respond("""{"error":{"code":"invalid_api_key"}}""", 401)

    private suspend fun take() = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS)!! }

    private suspend fun addSession(key: String) = db.sessionDao().upsert(
        SessionEntity(
            key = key, currentId = key, lineageIds = "", title = null, pinned = false, archived = false,
            startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false,
            existsOnServer = true, hidden = false,
        ),
    )

    private suspend fun running(sessionKey: String, runId: String = "run_1") =
        db.activeRunDao().upsert(ActiveRunEntity(runId, sessionKey, null, "running", 1L))

    private suspend fun rows(sessionKey: String) = db.pendingSendDao().rows(sessionKey)

    /** A steer of [sessionKey]'s running run, stored and never sent, as a process death right after the commit leaves it. */
    private suspend fun storedSteer(sessionKey: String) {
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { clock }, active = { checks++ < 1 }).submit(sessionKey, "also do this")
        val row = rows(sessionKey).single()
        assertEquals("steering", row.state)
        assertFalse(row.attempted)
    }

    // Issue 16 row 7: a new send replaces the worker (REPLACE cancels its coroutine); a steer in flight still completes.
    @Test
    fun storeDoesNotCancelSteer() = runTest {
        addSession("apofis-a")
        addSession("apofis-b")
        running("apofis-a")
        storedSteer("apofis-a")
        val gate = holding.hold(
            MockResponse.Builder().code(200).body(fixture("steer_response.json")).addHeader("Content-Type", "application/json").build(),
        )
        lateinit var workerJob: Job
        queue = SendQueue(api, db, { _, _ -> }, { clock }, schedule = { workerJob.cancel() })
        val w = worker()
        workerJob = launch(Dispatchers.Default) { w.doWork() }
        assertEquals("/v1/runs/run_1/steer", take().target) // the steer is in flight

        queue.enqueue("apofis-b", "a new message") // arms the worker again: REPLACE cancels the running one
        gate.countDown()
        workerJob.join()

        assertEquals("steered", rows("apofis-a").single().state)
        val b = rows("apofis-b").single()
        assertEquals("queued", b.state) // the cancelled drain went no further
        assertFalse(b.attempted)
        assertEquals(1, server.requestCount)
    }

    // Issue 16 row 8: a steer deferred by a delete in progress is work left, so the worker retries.
    @Test
    fun deferredSteerRetries() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        storedSteer("apofis-a")
        queue = SendQueue(api, db, { _, _ -> }, { clock })
        val repo = ChatRepository(api, db, { clock }, queue)
        repo.beginDelete("apofis-a")
        try {
            assertEquals(ListenableWorker.Result.retry(), worker().doWork())
        } finally {
            repo.endDelete("apofis-a")
        }
        assertEquals(0, server.requestCount)
        val row = rows("apofis-a").single()
        assertEquals("steering", row.state)
        assertEquals("run_1", row.runId)
        assertFalse(row.attempted)
    }

    // Control (issue 16 row 8): passes before the change. A settled run whose notice was not posted makes the worker
    // retry even though its drain then leaves nothing queued.
    @Test
    fun undeliveredNoticeRetriesWithEmptyQueue() = runTest {
        addSession("apofis-a")
        running("apofis-a")
        queue = SendQueue(api, db, { _, _ -> }, { clock }, settle = {
            db.activeRunDao().delete(it)
            queue.drain()
            throw NoticeNotDelivered()
        })
        queue.enqueue("apofis-a", "next turn")
        respond("""{"run_id":"run_2","status":"started","replayed":false}""", 202)

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        assertEquals(1, server.requestCount)
        assertEquals("sending", rows("apofis-a").single().state)
    }

    // Issue 16 row 9: a refused key met by the drain nested in a background settle stops the worker's own drain.
    @Test
    fun nested401StopsOuterDrain() = runTest {
        addSession("apofis-a")
        addSession("apofis-c")
        running("apofis-a", "run_a")
        running("apofis-c", "run_c")
        queue = SendQueue(api, db, { _, _ -> }, { clock }, settle = {
            db.activeRunDao().delete(it) // the follower saw the run end; its finish drains
            queue.drain()
        })
        queue.enqueue("apofis-a", "behind run a")
        queue.enqueue("apofis-c", "behind run c")
        repeat(2) { refusedKey() } // two, so a second request fails the count

        assertEquals(ListenableWorker.Result.retry(), worker().doWork())

        assertEquals(1, server.requestCount)
        // blockingQueued has no order: either chat may be settled first.
        val sends = rows("apofis-a") + rows("apofis-c")
        assertEquals(1, sends.count { it.state == "failed" && it.error == SendError.UNAUTHORIZED })
        val left = sends.single { it.state == "queued" }
        assertFalse(left.attempted)
    }
}
