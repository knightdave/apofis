package xyz.rycerz.apofis.run

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.fail
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.notify.NoticeReceipts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeOutboxEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.notify.Notice
import xyz.rycerz.apofis.notify.NoticeNotDelivered
import xyz.rycerz.apofis.notify.NoticeSink
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.ChatRepository

private const val KEY = "apofis-a"
private const val RUN = "run_1"
private const val STATUS = "/v1/runs/$RUN"
private const val MESSAGES = "/api/sessions/$KEY/messages"
private const val GEN = 7L

private data class Sent(val key: String, val runId: String, val startedAt: Long, val notice: Notice, val eventId: String, val generation: Long, val replay: Boolean)

/** The follower's side of notifications: what it writes to the outbox and hands to the notifier, and when. */
@RunWith(RobolectricTestRunner::class)
class FollowerNoticeTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var api: HermesApi
    private lateinit var repo: ChatRepository
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val sent = CopyOnWriteArrayList<Sent>()
    private val cleared = CopyOnWriteArrayList<List<Any?>>() // key, run, retired request, ended
    private val lockedAtHandOver = CopyOnWriteArrayList<Boolean>()
    private var follower: RunFollower? = null
    private val sink = NoticeSink { k, r, s, n, e, g, replay ->
        lockedAtHandOver += follower!!.writerLocked(r)
        sent += Sent(k, r, s, n, e, g, replay)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.target.substringBefore('?')]?.invoke(request) ?: MockResponse.Builder().code(500).build()
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        api = HermesApi(server.url("/").toString(), "secret-key")
        repo = ChatRepository(api, db)
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun TestScope.follower(done: CompletableDeferred<Unit>? = null) = RunFollower(
        api, db, repo, backgroundScope, delayMs = {}, onRunFinished = { done?.complete(Unit) },
        onNotice = sink, onControlsCleared = { k, r, q, ended -> cleared += listOf(k, r, q, ended) }, generation = { GEN },
    ).also { follower = it }

    private fun on(path: String, vararg responses: () -> MockResponse) {
        val n = AtomicInteger()
        routes[path] = { responses[minOf(n.getAndIncrement(), responses.size - 1)]() }
    }

    private fun json(body: String, code: Int = 200) = { MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    private fun status(status: String, pending: String = "") = json(
        """{"object":"hermes.run","run_id":"$RUN","status":"$status","session_id":"$KEY","admission_id":"adm_1",""" +
            """"execution_generation":3,"output":"","pending_controls":[$pending]}""",
    )

    private fun control(kind: String, id: String) = """{"kind":"$kind","prompt_id":"$id","execution_generation":2,"command":"rm x","question":null}"""

    private fun messages() = on(MESSAGES, json("""{"session_id":"$KEY","data":[{"id":10,"role":"user","content":"secret text","timestamp":1.0}]}"""))

    private suspend fun stored() {
        db.sessionDao().upsert(
            SessionEntity(KEY, KEY, "", "Chat", pinned = false, archived = false, startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true),
        )
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, "running", startedAt = 42L))
    }

    private suspend fun outbox() = db.noticeOutboxDao().all(GEN)

    @Test
    fun completedFinishWritesOutboxInTransaction() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("completed"))
        stored()
        var failures = 1
        f.afterOutboxWrite = { if (failures-- > 0) throw SQLiteException("disk I/O") }

        f.settle(RUN) // the failure after the insert rolls back the run deletion and the row
        assertNotNull(db.activeRunDao().get(RUN))
        assertTrue(outbox().isEmpty())
        assertTrue(sent.isEmpty())

        f.settle(RUN)
        assertNull(db.activeRunDao().get(RUN))
        assertEquals(listOf(NoticeOutboxEntity("run:$RUN", KEY, RUN, 42L, "FINISHED", GEN, outbox().single().createdAt)), outbox())
        assertEquals(listOf(Sent(KEY, RUN, 42L, Notice.FINISHED, "run:$RUN", GEN, replay = false)), sent)
    }

    @Test
    fun losingSettlementWritesNothing() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("completed"))
        stored()
        f.beforeFinishWrite = { db.activeRunDao().delete(RUN) } // another settlement won meanwhile

        f.settle(RUN)

        assertTrue(outbox().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun cancelledNoRow() = runTest {
        val f = follower()
        messages()
        on(STATUS, status("cancelled"))
        stored()

        f.settle(RUN)

        assertNull(db.activeRunDao().get(RUN))
        assertTrue(outbox().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun failedAndUnknownRows() = runTest {
        val f = follower()
        messages()
        stored()
        on(STATUS, status("failed"))
        f.settle(RUN)
        assertEquals(listOf("FAILED"), outbox().map { it.notice })

        db.noticeOutboxDao().clear()
        stored()
        on(STATUS, status("interrupted"))
        f.settle(RUN)
        assertEquals(listOf("FAILED"), outbox().map { it.notice })

        // A run the server reports as unknown, resolved by the user: it ends as interrupted.
        db.noticeOutboxDao().clear()
        sent.clear()
        stored()
        val done = CompletableDeferred<Unit>()
        val g = follower(done)
        on(STATUS, status("unknown"))
        on("/v1/runs/$RUN/events", { MockResponse.Builder().body(": open\n\n").addHeader("Content-Type", "text/event-stream").build() })
        on("/v1/runs/$RUN/resolve-unknown", json("""{"run_id":"$RUN","status":"resolved"}"""))
        g.resumeAll()
        g.resolveUnknown(KEY)
        done.await()
        assertEquals(listOf("FAILED"), outbox().map { it.notice })
        assertEquals(listOf(Notice.FAILED), sent.map { it.notice })
    }

    @Test
    fun approvalHandedOverWithItsRequest() = runTest {
        val f = follower()
        stored()
        on(STATUS, status("waiting_for_approval", control("approval", "p1")))
        f.settle(RUN)
        f.settle(RUN) // read again: handed over again, in order; the notifier drops the repeat

        assertEquals(Sent(KEY, RUN, 42L, Notice.NEEDS_ANSWER, "req:$RUN:p1:$GEN", GEN, replay = false), sent.first())
        assertTrue(outbox().isEmpty()) // never stored: the next accepted read derives it again

        on(STATUS, status("waiting_for_approval", control("clarify", "p2")))
        f.settle(RUN)
        on(STATUS, status("waiting_for_approval", control("something_new", "p3")))
        f.settle(RUN)
        assertEquals(listOf("req:$RUN:p1:$GEN", "req:$RUN:p1:$GEN", "req:$RUN:p2:$GEN"), sent.map { it.eventId })
        assertTrue(cleared.isEmpty()) // an unsupported control still waits: nothing is cleared
    }

    @Test
    fun handOverUnderWriterLockInAcceptedOrder() = runTest {
        val f = follower()
        stored()
        on(STATUS, status("waiting_for_approval", control("approval", "p1")), status("running"))
        f.settle(RUN)
        f.settle(RUN)

        assertEquals(listOf(true), lockedAtHandOver) // a newer read cannot overtake: it waits for the lock
        assertEquals(listOf(listOf(KEY, RUN, "p1", false)), cleared) // the read began after p1 was handed over
    }

    @Test
    fun lostTicketReadNeverNotifies() = runTest {
        val f = follower()
        stored()
        on(STATUS, status("waiting_for_approval", control("approval", "p1")), status("running"))
        var nested = false
        f.beforeStatusApply = {
            if (!nested) {
                nested = true
                settle(RUN) // a newer read starts, returns and is applied first
            }
        }

        f.settle(RUN) // the older read, showing the approval, is not applied

        assertTrue(sent.isEmpty())
        assertEquals(listOf(listOf(KEY, RUN, null, false)), cleared) // only the newer read's (no control)
    }

    /** A sink that hands each notice's receipt ([outcome]) to the check it belongs to, as the notifier does. */
    private fun TestScope.receiptFollower(outcome: () -> CompletableDeferred<Unit>) = RunFollower(
        api, db, repo, backgroundScope, delayMs = {},
        onNotice = { _, _, _, _, _, _, _ -> currentCoroutineContext()[NoticeReceipts]!!.add(outcome().also { handed += it }) },
        generation = { GEN },
    )

    @Test
    fun settleWaitsForItsOwnReceipt() = runTest {
        val receipt = CompletableDeferred<Unit>()
        val f = receiptFollower { receipt }
        stored()
        on(STATUS, status("waiting_for_approval", control("approval", "p1")))
        val settle = async(Dispatchers.IO) { f.settle(RUN) }
        spinUntil { receipt in handed }
        assertTrue(settle.isActive) // not done while its own notice is not

        receipt.complete(Unit)
        assertEquals("waiting_for_approval", settle.await())
    }

    private val handed = CopyOnWriteArrayList<CompletableDeferred<Unit>>()

    /** Waits in real time for another thread; a 10 s watchdog only. */
    private suspend fun spinUntil(condition: () -> Boolean) = withContext(Dispatchers.IO) {
        repeat(2000) {
            if (condition()) return@withContext
            Thread.sleep(5)
        }
        fail("condition not met within 10 s")
    }

    @Test
    fun undeliveredNoticeMakesTheWorkerRetry() = runTest {
        val f = receiptFollower { CompletableDeferred<Unit>().apply { completeExceptionally(NoticeNotDelivered()) } }
        messages()
        on(STATUS, status("completed"))
        on("/v1/runs", json("""{"run_id":"run_2","status":"started","replayed":false}""", 202))
        stored()
        val queue = SendQueue(api, db, f, System::currentTimeMillis, settle = { f.settle(it) })
        queue.enqueue(KEY, "second")

        assertFalse(queue.drainSettlingRuns()) // the run settled and the next message went out, yet a notice did not

        assertNull(db.activeRunDao().get(RUN))
        assertEquals("run_2", db.activeRunDao().forSession(KEY)!!.runId)
    }

    @Test
    fun clearRetiresOnlyTheRequestPendingWhenItsReadBegan() = runTest {
        val f = follower()
        stored()
        on(STATUS, status("waiting_for_approval", control("approval", "pA")))
        f.settle(RUN) // A handed over
        // Read L starts, then read H; L answers B and is applied first; H's older, empty snapshot is applied last.
        on(STATUS, status("waiting_for_approval", control("approval", "pB")), status("running"))
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        var h: kotlinx.coroutines.Job? = null
        f.beforeStatusApply = {
            when (calls.incrementAndGet()) {
                1 -> { // L, before its publish: H takes its ticket and reads now
                    h = backgroundScope.launch(Dispatchers.IO) { settle(RUN) }
                    spinUntil { calls.get() >= 2 }
                }
                2 -> release.await() // H waits until L is applied
            }
        }
        f.settle(RUN)
        release.complete(Unit)
        h!!.join()

        assertEquals(listOf("req:$RUN:pA:$GEN", "req:$RUN:pB:$GEN"), sent.map { it.eventId })
        assertEquals(listOf(listOf(KEY, RUN, "pA", false)), cleared) // it retires A, the request current when it began, never B
    }

    @Test
    fun controlsClearedByAFreshWriter() = runTest {
        val f = follower() // a new process: this writer never handed over the request shown before
        stored()
        on(STATUS, status("running"))
        f.settle(RUN)

        assertEquals(listOf(listOf(KEY, RUN, null, false)), cleared)
    }

    @Test
    fun silentEndsRetireTheRequest() = runTest {
        val f = follower()
        messages()
        stored()
        on(STATUS, status("cancelled"))
        f.settle(RUN)
        stored()
        on(STATUS, json("""{"error":{"code":"run_not_found"}}""", 404))
        f.settle(RUN)

        assertEquals(listOf(listOf(KEY, RUN, null, true), listOf(KEY, RUN, null, true)), cleared)
        assertTrue(sent.isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun malformedRunIdIsNoVerifiedOutcome() = runTest {
        val done = CompletableDeferred<Unit>()
        val f = follower(done)
        f.resumeAll()
        stored()
        db.activeRunDao().upsert(ActiveRunEntity("bad/id", KEY, null, "running", 1L))
        f.onRunStarted(KEY, "bad/id")
        done.await()

        assertNull(db.activeRunDao().get("bad/id")) // still cleaned up
        assertTrue(outbox().none { it.runId == "bad/id" })
        assertTrue(sent.none { it.runId == "bad/id" })
        assertTrue(listOf(KEY, "bad/id", null, true) in cleared)
    }

    @Test
    fun settleReturnsStatus() = runTest {
        val f = follower()
        messages()
        stored()
        on(STATUS, status("running"))
        assertEquals("running", f.settle(RUN))
        on(STATUS, json("""{"error":{"code":"run_not_found"}}""", 404))
        assertNull(f.settle(RUN))
        assertNull(db.activeRunDao().get(RUN)) // gone on the server: finished without an outcome or notice
        assertTrue(sent.isEmpty())

        stored()
        on(STATUS, status("completed"))
        assertEquals("completed", f.settle(RUN))
        assertNull(f.settle("run_missing"))
        f.resumeAll()
        stored()
        assertNull(f.settle(RUN)) // a follower owns it in the foreground
    }
}
