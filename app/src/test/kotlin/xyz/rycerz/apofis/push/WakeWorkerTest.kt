package xyz.rycerz.apofis.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.connectionEpoch
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeOutboxEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.notify.ensureChannels
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials

private const val A = "apofis-a"
private const val B = "apofis-b"

/** The worker's application context: hands out the wake target the test chooses. */
private class WakeCtx(base: Context, private val target: () -> WakeTarget?) : ContextWrapper(base), WakeProvider {
    override val wakeTarget get() = target()
    override fun getApplicationContext(): Context = this
}

/**
 * The wake: a push only adds demand; a job checks this phone's own outbox and stored runs against a real container,
 * notifier and follower, with a fake server that records every request. Jobs are run directly, so each test decides
 * what WorkManager would run when; WorkManager itself (synchronous, constraints unmet) shows what is enqueued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class WakeWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = CredentialStore(app) { key }
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<String>()
    private val auth = CopyOnWriteArrayList<String>()
    private lateinit var container: AppContainer
    @Volatile private var target: WakeTarget? = null
    private val ctx = WakeCtx(app) { target }
    private var now = 1_000_000L

    @Before
    fun setUp() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        WakeScheduler.reading = null
        afterSettle = {}
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ensureChannels(app)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.substringBefore('?')
                requests += path
                request.headers["Authorization"]?.let { auth += it }
                return routes[path]?.invoke(request) ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        container = AppContainer(app, store) { db }
        connect()
    }

    @After
    fun tearDown() {
        afterSettle = {}
        WorkManager.getInstance(app).cancelAllWork().result.get()
        db.close()
        server.close()
    }

    private suspend fun connect(c: Credentials = Credentials(server.url("/").toString(), "key"), on: AppContainer = container) {
        on.connect(c)
        bind(on)
    }

    private fun bind(on: AppContainer) {
        val conn = on.connected()!!
        target = WakeTarget(conn, conn.generation, on.notifier) { now }
    }

    private fun epoch() = connectionEpoch(app)
    private fun wake() = wakePrefs(app)
    private fun demand() = counter(wake(), WAKE_DEMAND, epoch())
    private fun done() = counter(wake(), WAKE_DONE, epoch())
    private fun jobs(name: String = WAKE_WORK) = WorkManager.getInstance(app).getWorkInfosForUniqueWork(name).get()
    private fun live(name: String = WAKE_WORK) = jobs(name).filter { !it.state.isFinished }

    /** A delivered push as the receiver hands it off: the demand under the push lock, then the schedule. */
    private fun push() {
        assertTrue(PushStore.locked { Wake.onPush(app, epoch()) })
        Wake.recover(app)
    }

    private suspend fun runWake(cause: String = CAUSE_PUSH, attempt: Int = 0, epoch: Long = epoch()): ListenableWorker.Result =
        TestListenableWorkerBuilder.from(ctx, WakeWorker::class.java).setInputData(workDataOf(EPOCH to epoch, CAUSE to cause))
            .setRunAttemptCount(attempt).build().doWork()

    private suspend fun tick(cause: String): ListenableWorker.Result =
        TestListenableWorkerBuilder.from(app, WakeWorker::class.java).setInputData(workDataOf(CAUSE to cause)).build().doWork()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    private fun status(run: String, key: String, status: String, pending: String = "") = json(
        """{"object":"hermes.run","run_id":"$run","status":"$status","session_id":"$key","admission_id":"adm_1",""" +
            """"execution_generation":3,"output":"","pending_controls":[$pending]}""",
    )

    private fun on(run: String, key: String = A, vararg statuses: String) {
        var n = 0
        routes["/v1/runs/$run"] = { status(run, key, statuses[minOf(n++, statuses.size - 1)]) }
        routes["/api/sessions/$key/messages"] = { json("""{"session_id":"$key","data":[{"id":10,"role":"user","content":"hi","timestamp":1.0}]}""") }
    }

    private suspend fun stored(run: String, key: String = A, startedAt: Long = 1L, status: String = "running") {
        db.sessionDao().upsert(
            SessionEntity(key, key, "", "Chat $key", pinned = false, archived = false, startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true),
        )
        db.activeRunDao().upsert(ActiveRunEntity(run, key, null, status, startedAt))
    }

    private fun shown(key: String) = nm.activeNotifications.filter { it.tag == key }
    private fun text(key: String) = shown(key).single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun statusReads() = requests.filter { it.startsWith("/v1/runs/") }.map { it.removePrefix("/v1/runs/") }
    private fun due(run: String) = wake().getString(DUE + run, null)

    /** Real-time watchdog for work done on other threads (the container's collector, WorkManager's worker thread). */
    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!condition()) {
            if (System.nanoTime() > end) fail(what)
            Thread.sleep(5)
        }
    }

    // Step 1.

    @Test
    fun pushNeverReadsBody() {
        val onPush = Wake::class.java.methods.filter { it.name == "onPush" }
        assertEquals(listOf(listOf(Context::class.java, Long::class.javaPrimitiveType)), onPush.map { it.parameterTypes.toList() })
    }

    @Test
    fun finishedAfterProcessDeath() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) } // the process dies right after the hand-off
        // A new process: no scheduler state, a fresh container on the same disk.
        WakeScheduler.reading = null
        val fresh = AppContainer(app, store) { db }
        bind(fresh)
        Wake.recover(app)
        assertEquals(1, live().size)
        assertEquals(ListenableWorker.Result.success(), runWake())
        awaitUntil("the list refresh") { "/api/sessions" in requests }
        assertEquals(listOf("/api/sessions", "/api/sessions/$A/messages", "/v1/runs/run_a"), requests.sorted())
        assertEquals(app.getString(R.string.notif_reply_finished), text(A))
        assertTrue(db.noticeOutboxDao().all(epoch()).isEmpty())
        assertEquals(demand(), done())
    }

    @Test
    fun wakeForUnknownSessionRunsOnlyOwnCheck() = runTest {
        // (a) Nothing stored: a wake (whatever chat it named) makes no request and posts nothing.
        push()
        runWake()
        assertTrue(requests.isEmpty())
        assertTrue(nm.activeNotifications.isEmpty())
        // (b) One stored run of chat A, finished; the push would have named chat B, which this phone never stored.
        stored("run_a")
        on("run_a", A, "completed")
        push()
        runWake()
        assertTrue(requests.none { B in it })
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(1, shown(A).size)
        assertTrue(shown(B).isEmpty())
        // (c) No stored run, a pending outbox row of A: the replay posts it silently, nothing else.
        nm.cancelAll()
        requests.clear()
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", A, "run_old", 5L, "FINISHED", epoch(), 0))
        push()
        runWake()
        val n = shown(A).single().notification
        assertTrue("silent replay", n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && n.group != null)
        assertTrue(requests.none { it.startsWith("/v1/") || "messages" in it })
    }

    @Test
    fun concurrentArrivals() = runTest {
        stored("run_a", startedAt = 1)
        stored("run_b", B, startedAt = 2)
        on("run_a", A, "running")
        on("run_b", B, "running")
        push()
        var pass = 1
        afterSettle = { if (pass++ <= 2) push() } // after A's settle (before the last one), then after B's (the last)
        assertEquals(ListenableWorker.Result.success(), runWake())
        assertEquals("one repeat covers both arrivals", listOf("run_a", "run_b", "run_a", "run_b"), statusReads())
        assertEquals("no second job while one runs", 1, jobs().size)
        assertEquals(3L, done())
        assertEquals(demand(), done())
    }

    @Test
    fun killAfterClaimRetries() = runTest {
        stored("run_a")
        val inRequest = CountDownLatch(1)
        val release = CountDownLatch(1)
        routes["/v1/runs/run_a"] = {
            inRequest.countDown()
            release.await(10, TimeUnit.SECONDS)
            status("run_a", A, "running")
        }
        push()
        val job = async(Dispatchers.IO) { runWake() }
        assertTrue(inRequest.await(10, TimeUnit.SECONDS))
        job.cancel() // WorkManager stops the worker after its claim, in the middle of the check
        release.countDown()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertEquals("nothing counted as done", 0L, done())
        // WorkManager retries the same job; no new push.
        on("run_a", A, "completed")
        assertEquals(ListenableWorker.Result.success(), runWake(attempt = 1))
        assertEquals(1, shown(A).size)
        assertEquals(1L, done())
    }

    @Test
    fun cancellationRethrown() = runTest {
        stored("run_a")
        stored("run_b", B, startedAt = 2)
        on("run_a", A, "completed")
        on("run_b", B, "running")
        push()
        // Inside a settle, while the job itself is not cancelled: the check must not swallow it and go on.
        target!!.connected.follower.afterOutboxWrite = { throw CancellationException("stopped") }
        try {
            runWake()
            fail("a stopped worker does not report a result")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(0L, done())
    }

    @Test
    fun earlyStatusRecheck() = runTest {
        stored("run_a")
        on("run_a", A, "running", "completed")
        push()
        runWake()
        assertEquals("1:${now + 10_000}", due("run_a"))
        val recheck = live(RECHECK_WORK).single()
        assertEquals(10_000L, recheck.initialDelayMillis)
        assertTrue(shown(A).isEmpty())
        // At the due time WorkManager runs the recheck tick, which only adds demand and schedules the wake.
        now += 10_000
        WorkManagerTestInitHelper.getTestDriver(app)!!.setInitialDelayMet(recheck.id)
        awaitUntil("the tick scheduled a wake") { demand() == 2L && live().isNotEmpty() }
        runWake(CAUSE_RECHECK)
        assertEquals(app.getString(R.string.notif_reply_finished), text(A))
        assertNull("pruned with the run", due("run_a"))
    }

    @Test
    fun twoDisjointRechecks() = runTest {
        stored("run_a", startedAt = 1)
        on("run_a", A, "running", "running", "completed")
        push()
        runWake()
        val t0 = now
        now += 5_000
        stored("run_b", B, startedAt = 2)
        on("run_b", B, "running", "running", "completed")
        push()
        runWake()
        assertEquals("A's earlier due is kept", "1:${t0 + 10_000}", due("run_a"))
        assertEquals("1:${t0 + 15_000}", due("run_b"))
        assertEquals(5_000L, live(RECHECK_WORK).single().initialDelayMillis)
        now = t0 + 10_000
        tick(CAUSE_RECHECK)
        runWake(CAUSE_RECHECK)
        assertEquals(1, shown(A).size)
        assertTrue(shown(B).isEmpty())
        assertEquals("B's due survived the replacement", 5_000L, live(RECHECK_WORK).single().initialDelayMillis)
        now = t0 + 15_000
        tick(CAUSE_RECHECK)
        runWake(CAUSE_RECHECK)
        assertEquals(1, shown(B).size)
    }

    @Test
    fun recheckBudgetPerRun() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        val dues = mutableListOf<String?>()
        repeat(4) { push(); runWake(); dues += due("run_a") } // four pushes before the first due: one recheck
        assertEquals(1, dues.distinct().size)
        repeat(5) {
            now += 100_000
            tick(CAUSE_RECHECK)
            runWake(CAUSE_RECHECK)
            dues += due("run_a")
        }
        assertEquals("at most three rechecks", 3, dues.distinct().count { !it!!.endsWith(":0") })
        assertEquals("3:0", due("run_a"))
        push()
        runWake()
        assertEquals("a push never resets the budget", "3:0", due("run_a"))
    }

    @Test
    fun uncommittedFinishRechecks() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        var failures = 1
        target!!.connected.follower.afterOutboxWrite = { if (failures-- > 0) throw SQLiteException("disk I/O") }
        push()
        runWake()
        assertNotNull("the finish did not commit: the run stays", db.activeRunDao().get("run_a"))
        assertTrue(shown(A).isEmpty())
        assertEquals("1:${now + 10_000}", due("run_a"))
        now += 10_000
        tick(CAUSE_RECHECK)
        runWake(CAUSE_RECHECK)
        assertEquals(1, shown(A).size)
        nm.cancelAll()
        push()
        runWake()
        assertTrue("notified once", shown(A).isEmpty())
    }

    @Test
    fun anonymousApprovalPath() = runTest {
        stored("run_a")
        val approval = """{"kind":"approval","prompt_id":"p1","execution_generation":2,"command":"rm x","question":null}"""
        routes["/v1/runs/run_a"] = { status("run_a", A, "waiting_for_approval", approval) }
        push()
        runWake()
        assertEquals(app.getString(R.string.notif_needs_answer), text(A))
        assertEquals("1:${now + 10_000}", due("run_a"))
    }

    @Test
    fun fairCursor() = runTest {
        val ids = (1..20).map { "run_%02d".format(it) }
        ids.forEachIndexed { i, id ->
            stored(id, startedAt = i + 1L)
            on(id, A, "running")
        }
        val batches = mutableListOf<List<String>>()
        repeat(4) {
            requests.clear()
            check(ctx, target!!)
            batches += statusReads()
        }
        assertEquals(listOf(ids.subList(0, 8), ids.subList(8, 16), ids.subList(16, 20) + ids.subList(0, 4), ids.subList(4, 12)), batches)
    }

    @Test
    fun oneRunFailureContinues() = runTest {
        val ids = (1..8).map { "run_$it" }
        ids.forEachIndexed { i, id ->
            stored(id, startedAt = i + 1L)
            on(id, A, "running")
        }
        routes["/v1/runs/run_1"] = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        push()
        assertEquals(ListenableWorker.Result.retry(), runWake())
        assertEquals(ids.drop(1), statusReads().filter { it != "run_1" })
        assertEquals("not done: retried", 0L, done())
        assertEquals("the last attempt ends", ListenableWorker.Result.success(), runWake(attempt = 3))
        assertEquals(1L, done())
    }

    @Test
    fun generationAba() = runTest {
        val serverA = server.url("/").toString()
        val first = epoch()
        connect(Credentials("https://b.example/", "key-b")) // nothing is requested of B in this test
        connect(Credentials(serverA, "key"))
        assertEquals(first + 2, epoch())
        stored("run_a")
        on("run_a", A, "completed")
        Wake.onPush(app, epoch()).also { assertTrue(it) }
        assertEquals("a job of the first connection to this URL", ListenableWorker.Result.success(), runWake(epoch = first))
        assertTrue(requests.isEmpty())
        assertEquals(0L, done())
        // A same-URL key change: the queued job is cancelled, and a stale one that still runs changes nothing.
        push()
        val queued = epoch()
        connect(Credentials(serverA, "key-2"))
        assertTrue(jobs().all { it.state == WorkInfo.State.CANCELLED })
        runWake(epoch = queued)
        assertTrue(requests.isEmpty())
        assertEquals("demand of the old epoch does not count", 0L, demand())
    }

    @Test
    fun unauthorizedStops() = runTest {
        stored("run_a", startedAt = 1)
        stored("run_b", B, startedAt = 2)
        routes["/v1/runs/run_a"] = { json("""{"error":"unauthorized"}""", 401) }
        on("run_b", B, "completed")
        push()
        assertEquals(ListenableWorker.Result.success(), runWake())
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(demand(), done())
        assertNull(due("run_a"))
    }

    @Test
    fun disconnectedDoesNothing() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        push()
        target = null
        assertEquals(ListenableWorker.Result.success(), runWake())
        assertTrue(requests.isEmpty())
        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test
    fun periodicTickSettles() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        awaitUntil("the periodic check is scheduled") { live(CHECK_WORK).isNotEmpty() }
        val periodic = live(CHECK_WORK).single()
        WorkManagerTestInitHelper.getTestDriver(app)!!.run {
            setInitialDelayMet(periodic.id)
            setAllConstraintsMet(periodic.id)
            setPeriodDelayMet(periodic.id)
        }
        awaitUntil("the tick scheduled a wake") { live().isNotEmpty() }
        runWake(CAUSE_PERIODIC)
        assertEquals(app.getString(R.string.notif_reply_finished), text(A))
    }

    @Test
    fun periodicDeniedVersusAllowed() = runTest {
        stored("run_a", startedAt = 1)
        on("run_a", A, "completed")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        tick(CAUSE_PERIODIC)
        runWake(CAUSE_PERIODIC)
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(db.noticeOutboxDao().all(epoch()).isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        stored("run_b", B, startedAt = 2)
        on("run_b", B, "completed")
        tick(CAUSE_PERIODIC)
        runWake(CAUSE_PERIODIC)
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(1, shown(B).size)
    }

    @Test
    fun periodicLifecycle() = runTest {
        assertTrue(live(CHECK_WORK).isEmpty())
        stored("run_a")
        awaitUntil("scheduled on insert") { live(CHECK_WORK).isNotEmpty() }
        val w = live(CHECK_WORK).single()
        assertEquals(TimeUnit.MINUTES.toMillis(15), w.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(15), w.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, w.constraints.requiredNetworkType)
        db.activeRunDao().delete("run_a")
        awaitUntil("cancelled when empty") { live(CHECK_WORK).isEmpty() }
        // Cold start: a run is stored but no check is scheduled (say, lost with the last process).
        stored("run_b", B)
        awaitUntil("scheduled again") { live(CHECK_WORK).isNotEmpty() }
        Wake.cancelPeriodic(app)
        AppContainer(app, store) { db }
        awaitUntil("reconciled at cold start") { live(CHECK_WORK).isNotEmpty() }
        // A switch to another server clears the stored runs, and with them the check.
        connect(Credentials("https://b.example/", "key-b"))
        awaitUntil("cancelled on switch") { live(CHECK_WORK).isEmpty() }
    }

    @Test
    fun noForegroundService() {
        val config = Properties().apply { ClassLoader.getSystemClassLoader().getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load) }
        val manifest = File(config.getProperty("android_merged_manifest")).readText()
        assertTrue(".push.PushReceiver" in manifest || "xyz.rycerz.apofis.push.PushReceiver" in manifest)
        for (absent in listOf("FOREGROUND_SERVICE", "SystemForegroundService", "RaiseToForegroundService")) assertFalse(absent, absent in manifest)
    }

    // Step 1a (I7).

    @Test
    fun arrivalBeforeClaim() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        push()
        push() // between the enqueue and the job's claim
        assertEquals("no job replaced or added", 1, jobs().size)
        runWake()
        assertEquals("one execution saw both", listOf("run_a"), statusReads())
        assertEquals(2L, done())
    }

    @Test
    fun arrivalAfterLastScan() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        push()
        afterSettle = { push() } // after the final settle of every pass, before the job decides
        runWake()
        assertEquals("three passes, then it ends", 3, statusReads().size)
        assertEquals("one trailing job queued behind it", 2, jobs().size)
        assertTrue("the demand is above done only with a job queued", demand() > done())
        afterSettle = {}
        runWake()
        assertEquals(demand(), done())
    }

    @Test
    fun periodicAndPushRaceForRecheck() = runTest {
        stored("run_a")
        on("run_a", A, "running", "running", "completed")
        push()
        runWake()
        val dueAt = now + 10_000
        now += 5_000
        tick(CAUSE_PERIODIC)
        runWake(CAUSE_PERIODIC)
        assertEquals("the earlier due survives", "1:$dueAt", due("run_a"))
        assertEquals(5_000L, live(RECHECK_WORK).single().initialDelayMillis)
        now = dueAt
        tick(CAUSE_RECHECK)
        runWake(CAUSE_RECHECK)
        assertEquals(1, shown(A).size)
    }

    @Test
    fun deathWithNoLaterPush() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) } // dies before the schedule
        assertTrue(jobs().isEmpty())
        WakeScheduler.reading = null
        recoverPush(app, Wake) // the next process start, with no further push
        assertEquals(1, live().size)
        runWake()
        assertEquals(1, shown(A).size)
        assertEquals(demand(), done())
    }

    @Test
    fun coldRestoreKeepsEpoch() = runTest {
        val e = epoch()
        stored("run_a")
        on("run_a", A, "completed")
        push()
        // Process death; the cold load reads the epoch and keeps it.
        WakeScheduler.reading = null
        val restored = AppContainer(app, store) { db }
        assertEquals(e, restored.connected()!!.generation)
        assertEquals(e, epoch())
        bind(restored)
        assertEquals("a job of the previous epoch: nothing", ListenableWorker.Result.success(), runWake(epoch = e - 1))
        assertTrue(requests.isEmpty())
        assertEquals(0L, done())
        runWake(epoch = e) // the in-flight job of this epoch carries on
        assertEquals(1, shown(A).size)
        assertEquals(demand(), done())
    }

    @Test
    fun sameUrlKeyChangeNewEpoch() = runTest {
        stored("run_a")
        on("run_a", A, "running", "completed")
        push()
        val old = epoch()
        connect(Credentials(server.url("/").toString(), "key-2"))
        assertEquals(old + 1, epoch())
        assertTrue(live().isEmpty())
        assertEquals("the new epoch starts with no demand", 0L, demand())
        runWake(epoch = old)
        assertTrue(requests.isEmpty())
        // The run is still stored for the new connection; a new push checks it with the new key.
        push()
        assertEquals(1, live().size)
        runWake()
        assertEquals(listOf("Bearer key-2"), auth.distinct())
    }
}
