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
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.shadows.ShadowLog
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

/** The workers' application context: hands out the wake target of the container under test. */
private class WakeCtx(base: Context, private val target: () -> WakeTarget?) : ContextWrapper(base), WakeProvider {
    override val wakeTarget get() = target()
    override fun getApplicationContext(): Context = this
}

/**
 * The wake against a real container, notifier and follower, a fake server that records every request, and the test
 * WorkManager running the real enqueued work (its factory supplies the test graph; [TestDriver] releases delays and
 * constraints). Direct worker runs are used only for single outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class WakeWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = CredentialStore(app) { key }
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<String>()
    private val arrived = Channel<String>(Channel.UNLIMITED)
    private val auth = CopyOnWriteArrayList<String>()
    private val containers = CopyOnWriteArrayList<AppContainer>()
    @Volatile private lateinit var container: AppContainer
    @Volatile private var disconnected = false
    private val ctx = WakeCtx(app) {
        if (disconnected) null else container.let { c -> c.connected()?.let { WakeTarget(it, it.generation, c.notifier) } }
    }
    private val wm get() = WorkManager.getInstance(app)
    private val driver get() = WorkManagerTestInitHelper.getTestDriver(app)!!

    @Before
    fun setUp() = runTest {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == WakeWorker::class.java.name) WakeWorker(ctx, workerParameters) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        wakeStep = {}
        ShadowLog.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ensureChannels(app)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.substringBefore('?')
                requests += path
                request.headers["Authorization"]?.let { auth += it }
                arrived.trySend(path)
                return routes[path]?.invoke(request) ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        container = newContainer()
        container.connect(Credentials(url(), "key"))
    }

    @After
    fun tearDown() {
        wakeStep = {}
        wm.cancelAllWork().result.get()
        containers.forEach { it.scope.cancel() } // before the shared database closes
        db.close()
        server.close()
    }

    private fun url() = server.url("/").toString()
    private fun newContainer() = AppContainer(app, store) { db }.also { containers += it }
    private fun epoch() = connectionEpoch(app)
    private fun target() = container.connected()!!.let { WakeTarget(it, it.generation, container.notifier) }

    /** A delivered push as the receiver hands it off: the mark under the push lock, then the request. */
    private fun push() {
        assertTrue(PushStore.locked { Wake.onPush(app, epoch()) })
        Wake.recover(app)
    }

    /** Waits in real time (a watchdog only) for a state change made on other threads. */
    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private fun jobs(name: String = WAKE_WORK) = wm.getWorkInfosForUniqueWork(name).get()
    private fun live(name: String = WAKE_WORK) = jobs(name).filter { !it.state.isFinished }
    private suspend fun awaitWork(name: String = WAKE_WORK, until: (List<WorkInfo>) -> Boolean) = real { wm.getWorkInfosForUniqueWorkFlow(name).first(until) }

    /**
     * Releases the one queued wake job (its delay and its network constraint) and waits until WorkManager recorded its
     * end: finished, replaced, or back in the queue for a retry.
     */
    private suspend fun runJob(): WorkInfo {
        val job = live().single()
        if (job.initialDelayMillis > 0 || job.runAttemptCount > 0) driver.setInitialDelayMet(job.id)
        driver.setAllConstraintsMet(job.id)
        return real { wm.getWorkInfoByIdFlow(job.id).first { it == null || it.state.isFinished || (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > job.runAttemptCount) } } ?: job
    }

    private suspend fun runWake(cause: String = CAUSE_PUSH, attempt: Int = 0, epoch: Long = epoch(), followUp: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder.from(ctx, WakeWorker::class.java)
            .setInputData(workDataOf(EPOCH to epoch, CAUSE to cause, FOLLOW_UP to followUp)).setRunAttemptCount(attempt).build().doWork()

    /** The next [n] requests to arrive, as receipts. */
    private suspend fun awaitRequests(n: Int) = real { repeat(n) { arrived.receive() } }

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

    private suspend fun shown(key: String) = container.notifier.idle().let { nm.activeNotifications.filter { it.tag == key } }
    private suspend fun text(key: String) = shown(key).single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun statusReads() = requests.filter { it.startsWith("/v1/runs/") }.map { it.removePrefix("/v1/runs/") }

    // Single outcomes, run directly.

    @Test
    fun pushNeverReadsBody() {
        val onPush = Wake::class.java.methods.filter { it.name == "onPush" }
        assertEquals(listOf(listOf(Context::class.java, Long::class.javaPrimitiveType)), onPush.map { it.parameterTypes.toList() })
    }

    @Test
    fun wakeForUnknownSessionRunsOnlyOwnCheck() = runTest {
        // (a) Nothing stored: a wake (whatever chat it named) makes no request and posts nothing.
        runWake()
        assertTrue(requests.isEmpty())
        assertTrue(nm.activeNotifications.isEmpty())
        // (b) One stored run of chat A, finished; the push would have named chat B, which this phone never stored.
        stored("run_a")
        on("run_a", A, "completed")
        runWake()
        awaitRequests(3) // status, transcript, and the list refresh after the finish
        assertEquals(listOf("/api/sessions", "/api/sessions/$A/messages", "/v1/runs/run_a"), requests.sorted())
        assertEquals(1, shown(A).size)
        assertTrue(shown(B).isEmpty())
        // (c) No stored run, a pending outbox row of A: the replay posts it silently, nothing else.
        nm.cancelAll()
        requests.clear()
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", A, "run_old", 5L, "FINISHED", epoch(), 0))
        runWake()
        val n = shown(A).single().notification
        assertTrue("silent replay", n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && n.group != null)
        assertTrue(requests.isEmpty())
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
        assertTrue("no follow-up after a refused key", live().all { it.initialDelayMillis == 0L })
    }

    @Test
    fun disconnectedKeepsTheMark() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        push()
        disconnected = true
        assertEquals(ListenableWorker.Result.success(), runWake())
        assertTrue(requests.isEmpty())
        assertEquals("the mark stays for the next connection", epoch(), pendingWake(app))
    }

    @Test
    fun cancellationRethrown() = runTest {
        stored("run_a")
        stored("run_b", B, startedAt = 2)
        on("run_a", A, "completed")
        on("run_b", B, "running")
        // Inside a settle, while the job itself is not cancelled: the check must not swallow it and go on.
        target().connected.follower.afterOutboxWrite = { throw CancellationException("stopped") }
        try {
            runWake()
            fail("a stopped worker does not report a result")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf("run_a"), statusReads())
    }

    @Test
    fun networkAndUnavailableRetryForThreeExecutions() = runTest {
        val ids = (1..8).map { "run_$it" }
        ids.forEachIndexed { i, id ->
            stored(id, startedAt = i + 1L)
            on(id, A, "running")
        }
        routes["/v1/runs/run_1"] = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        routes["/v1/runs/run_2"] = { MockResponse.Builder().code(503).build() }
        assertEquals(ListenableWorker.Result.retry(), runWake(attempt = 0))
        assertEquals("the other runs are read", ids.drop(2), statusReads().filter { it != "run_1" && it != "run_2" })
        assertEquals(ListenableWorker.Result.retry(), runWake(attempt = 1))
        assertEquals("the third execution ends", ListenableWorker.Result.success(), runWake(attempt = 2))
        assertEquals("and asks for a follow-up", 10_000L, live().single().initialDelayMillis)
        on("run_1", A, "running") // only the 503 left: it alone retries
        assertEquals(ListenableWorker.Result.retry(), runWake(attempt = 0))
    }

    @Test
    fun aFailedCheckRetries() = runTest {
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", A, "run_old", 5L, "FINISHED", epoch(), 0))
        stored("run_x") // a session row for the replay to name
        db.activeRunDao().delete("run_x")
        container.notifier.beforePost = { throw IllegalStateException("tray") }
        assertEquals(ListenableWorker.Result.retry(), runWake())
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
            check(ctx, target())
            batches += statusReads()
        }
        assertEquals(listOf(ids.subList(0, 8), ids.subList(8, 16), ids.subList(16, 20) + ids.subList(0, 4), ids.subList(4, 12)), batches)
    }

    @Test
    fun anonymousApprovalPath() = runTest {
        stored("run_a")
        val approval = """{"kind":"approval","prompt_id":"p1","execution_generation":2,"command":"rm x","question":null}"""
        routes["/v1/runs/run_a"] = { status("run_a", A, "waiting_for_approval", approval) }
        runWake()
        assertEquals(app.getString(R.string.notif_needs_answer), text(A))
        assertEquals("a follow-up is due", 10_000L, live().single().initialDelayMillis)
    }

    @Test
    fun foregroundCheckLeavesTheFollowersAlone() = runTest {
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", A, "run_old", 5L, "FINISHED", epoch(), 0))
        target().connected.follower.resumeAll() // shown: the followers own runs and notices
        runWake()
        assertEquals("not replayed silently under the followers", 1, db.noticeOutboxDao().all(epoch()).size)
    }

    @Test
    fun periodicDeniedVersusAllowed() = runTest {
        stored("run_a", startedAt = 1)
        on("run_a", A, "completed")
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runWake(CAUSE_PERIODIC)
        container.notifier.idle()
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(db.noticeOutboxDao().all(epoch()).isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        stored("run_b", B, startedAt = 2)
        on("run_b", B, "completed")
        runWake(CAUSE_PERIODIC)
        assertEquals(1, shown(B).size)
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun noForegroundService() {
        val config = Properties().apply { ClassLoader.getSystemClassLoader().getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load) }
        val manifest = File(config.getProperty("android_merged_manifest")).readText()
        assertTrue("xyz.rycerz.apofis.push.PushReceiver" in manifest || ".push.PushReceiver" in manifest)
        for (absent in listOf("FOREGROUND_SERVICE", "SystemForegroundService", "RaiseToForegroundService")) assertFalse(absent, absent in manifest)
    }

    // The mark.

    @Test
    fun markReadersWaitForAFailingCommit() = runTest {
        val inCommit = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failing = FakePrefs(onCommit = { keys -> if (WAKE_PENDING in keys) { inCommit.countDown(); release.await(10, TimeUnit.SECONDS); false } else true })
        val failingCtx = object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getSharedPreferences(name: String, mode: Int) = if (name == WAKE_PREFS) failing else super.getSharedPreferences(name, mode)
        }
        val pushing = Thread { PushStore.locked { Wake.onPush(failingCtx, epoch()) } }
        pushing.start()
        assertTrue(inCommit.await(10, TimeUnit.SECONDS))
        // The failing value is in memory now; a reader must not see it.
        var seen: Long? = -1
        val reader = Thread { seen = pendingWake(failingCtx) }
        reader.start()
        val watchdog = System.nanoTime() + 10_000_000_000
        while (reader.state != Thread.State.BLOCKED && System.nanoTime() < watchdog) Thread.yield()
        assertEquals("the reader waits for the push lock", Thread.State.BLOCKED, reader.state)
        release.countDown()
        pushing.join(10_000)
        reader.join(10_000)
        assertNull("rolled back before it could be read", seen)
        assertNull(failing.disk[WAKE_PENDING])
        failing.onCommit = { true }
        assertTrue(PushStore.locked { Wake.onPush(failingCtx, epoch()) })
        assertEquals(epoch(), pendingWake(failingCtx))
    }

    @Test
    fun aMarkIsClearedOnlyForTheCurrentEpoch() = runTest {
        push()
        assertEquals(Cleared.STALE, clearPendingWake(app, epoch() - 1))
        assertEquals(epoch(), pendingWake(app))
        assertEquals(Cleared.CLEARED, clearPendingWake(app, epoch()))
        assertNull(pendingWake(app))
    }

    // The status store across a switch.

    @Test
    fun anOldReadStoresNothingAfterTheSwitch() = runTest {
        stored("run_a")
        val approval = """{"kind":"approval","prompt_id":"p1","execution_generation":2,"command":"rm x","question":null}"""
        routes["/v1/runs/run_a"] = { status("run_a", A, "waiting_for_approval", approval) }
        val old = container.connected()!!
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        old.follower.beforeStatusStore = { if (!paused.isCompleted) { paused.complete(Unit); release.await() } }
        val checking = async(Dispatchers.Default) { check(ctx, WakeTarget(old, old.generation, container.notifier)) }
        real { paused.await() }
        container.connect(Credentials(url(), "key-2")) // same server, new key: run_a is kept
        val switchedAt = auth.size
        release.complete(Unit)
        real { checking.await() }
        assertEquals("the old read is not stored into the kept run", "running", db.activeRunDao().get("run_a")?.status)
        assertTrue("no approval of the old read", shown(A).isEmpty())
        routes["/v1/runs/run_a"] = { status("run_a", A, "running") }
        check(ctx, target())
        assertEquals(listOf("Bearer key-2"), auth.drop(switchedAt).distinct())
    }

    // The real enqueued work.

    @Test
    fun finishedAfterProcessDeath() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) } // the process dies right after the hand-off
        // A new process: a fresh container on the same disk; its start hands the mark on.
        container = newContainer()
        recoverPush(app, Wake)
        assertEquals(1, live().size)
        assertEquals(WorkInfo.State.SUCCEEDED, runJob().state)
        awaitRequests(3)
        assertEquals(listOf("/api/sessions", "/api/sessions/$A/messages", "/v1/runs/run_a"), requests.sorted())
        assertEquals(app.getString(R.string.notif_reply_finished), text(A))
        assertNull(pendingWake(app))
        assertTrue("nothing left to do", live().isEmpty())
        val lines = ShadowLog.getLogsForTag("ApofisWake").map { it.msg }
        assertEquals(listOf("cause=push runs=1"), lines)
    }

    @Test
    fun deathWithNoLaterPush() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) } // dies before the request
        assertTrue(jobs().isEmpty())
        recoverPush(app, Wake) // the next process start, with no further push
        runJob()
        assertEquals(1, shown(A).size)
    }

    @Test
    fun repeatedColdStartsLeaveOneJob() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        push() // the network is away: the job waits
        repeat(3) { recoverPush(app, Wake) } // three process starts before it returns
        assertEquals(1, live().size)
        runJob()
        assertEquals(listOf("run_a"), statusReads())
        assertTrue(live().isEmpty())
    }

    @Test
    fun aPushWhileAJobWaitsInBackoffRunsPromptly() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        var n = 0
        routes["/v1/runs/run_a"] = { if (n++ == 0) MockResponse.Builder().code(503).build() else status("run_a", A, "completed") }
        push()
        val failed = runJob()
        assertEquals("in backoff", WorkInfo.State.ENQUEUED, failed.state)
        push()
        val next = live().single()
        assertNotEquals("replaced by a fresh job", failed.id, next.id)
        assertEquals(0, next.runAttemptCount)
        runJob()
        assertEquals(1, shown(A).size)
    }

    @Test
    fun aPushDuringTheCheckReplacesTheJob() = runTest {
        stored("run_a", startedAt = 1)
        stored("run_b", B, startedAt = 2)
        on("run_a", A, "running", "completed")
        on("run_b", B, "completed")
        push()
        val first = live().single().id
        var pushed = false
        wakeStep = { if (it == "settled run_a" && !pushed) { pushed = true; push() } }
        driver.setAllConstraintsMet(first)
        val replaced = real { wm.getWorkInfoByIdFlow(first).first { it == null || it.state.isFinished } }
        assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
        assertEquals("the replaced check went no further", listOf("run_a"), statusReads())
        runJob()
        assertEquals(listOf("run_a", "run_a", "run_b"), statusReads())
        assertEquals(1, shown(A).size)
        assertEquals(1, shown(B).size)
        assertNull(pendingWake(app))
        assertTrue(live().isEmpty())
    }

    @Test
    fun aPushAfterTheLastScanGetsItsOwnCheck() = runTest {
        stored("run_a")
        on("run_a", A, "running", "completed")
        push()
        val first = live().single().id
        var pushed = false
        wakeStep = { if (it == "checked" && !pushed) { pushed = true; push() } } // after the last scan, before the return
        driver.setAllConstraintsMet(first)
        real { wm.getWorkInfoByIdFlow(first).first { it == null || it.state.isFinished } }
        assertEquals(epoch(), pendingWake(app))
        val next = live().single()
        assertNotEquals(first, next.id)
        assertEquals("a push job, not a delayed follow-up", 0L, next.initialDelayMillis)
        runJob()
        assertEquals(listOf("run_a", "run_a"), statusReads())
        assertEquals(1, shown(A).size)
    }

    @Test
    fun aPushDuringPublicationIsServedAfterIt() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        container.afterSwitchMarker = {
            // The new epoch exists, its connection does not yet: the job runs and leaves the mark.
            push()
            runBlocking { assertEquals(WorkInfo.State.SUCCEEDED, runJob().state) }
            assertEquals(epoch(), pendingWake(app))
        }
        container.connect(Credentials(url(), "key-2")) // same server: run_a is kept
        container.afterSwitchMarker = {}
        assertTrue("requested at publication, with no further push", live().isNotEmpty())
        runJob()
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(listOf("Bearer key-2"), auth.distinct())
        assertEquals(1, shown(A).size)
    }

    @Test
    fun followUpsAt10And30And90AndAPushRestartsThem() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        push()
        val delays = mutableListOf<Long>()
        repeat(4) {
            runJob()
            delays += live().singleOrNull()?.initialDelayMillis ?: -1
        }
        assertEquals(listOf(10_000L, 30_000L, 90_000L, -1L), delays)
        assertEquals(4, statusReads().size)
        push()
        runJob()
        runJob()
        assertEquals("30 s follow-up pending", 30_000L, live().single().initialDelayMillis)
        push() // replaces the waiting follow-up
        assertEquals(0L, live().single().initialDelayMillis)
        runJob()
        assertEquals("the sequence starts again", 10_000L, live().single().initialDelayMillis)
    }

    @Test
    fun uncommittedFinishFollowsUp() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        var failures = 1
        target().connected.follower.afterOutboxWrite = { if (failures-- > 0) throw SQLiteException("disk I/O") }
        push()
        runJob()
        assertNotNull("the finish did not commit: the run stays", db.activeRunDao().get("run_a"))
        assertTrue(shown(A).isEmpty())
        assertEquals(10_000L, live().single().initialDelayMillis)
        runJob()
        assertEquals(1, shown(A).size)
        assertTrue(live().isEmpty())
    }

    @Test
    fun periodicTickSettles() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        val periodic = awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }.single { !it.state.isFinished }
        driver.setInitialDelayMet(periodic.id)
        driver.setAllConstraintsMet(periodic.id)
        driver.setPeriodDelayMet(periodic.id)
        awaitWork { it.any { w -> !w.state.isFinished } }
        runJob()
        assertEquals(app.getString(R.string.notif_reply_finished), text(A))
    }

    @Test
    fun periodicLifecycle() = runTest {
        stored("run_a")
        val w = awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }.single { !it.state.isFinished }
        assertEquals(TimeUnit.MINUTES.toMillis(15), w.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(15), w.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, w.constraints.requiredNetworkType)
        db.activeRunDao().delete("run_a")
        awaitWork(CHECK_WORK) { it.none { w -> !w.state.isFinished } }
        stored("run_b", B)
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        // Cold start: a run is stored, no check is scheduled (lost with the last process).
        Wake.reconcilePeriodic(app, epoch(), false)
        container = newContainer()
        container.connected()
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        // A switch to another server clears the stored runs, and with them the check.
        container.connect(Credentials("https://b.example/", "key-b"))
        assertTrue(live(CHECK_WORK).isEmpty())
    }

    @Test
    fun oldWorkAfterAKeyChangeDoesNothingAndTheNewEpochKeepsItsCheck() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        val before = awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }.single { !it.state.isFinished }
        val old = epoch()
        Wake.request(app, old, CAUSE_RECHECK, 1) // a follow-up of the old connection, waiting
        container.connect(Credentials(url(), "key-2")) // same server: run_a is kept
        assertTrue("the old wake work is cancelled", live().isEmpty())
        val after = live(CHECK_WORK).single()
        assertNotEquals("a periodic check of the new epoch, from the kept run, with no push", before.id, after.id)
        // The old connection's tick, follow-up and push job: no effect.
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_TICK, epoch = old))
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_RECHECK, epoch = old, followUp = 1))
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_PUSH, epoch = old))
        assertTrue(requests.isEmpty())
        assertTrue(live().isEmpty())
        // The new epoch's tick checks the kept run with the new key.
        driver.setInitialDelayMet(after.id)
        driver.setAllConstraintsMet(after.id)
        driver.setPeriodDelayMet(after.id)
        awaitWork { it.any { w -> !w.state.isFinished } }
        runJob()
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(listOf("Bearer key-2"), auth.distinct())
    }

    @Test
    fun coldRestoreKeepsEpoch() = runTest {
        val e = epoch()
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) }
        container = newContainer() // process death; the cold load reads the epoch and keeps it
        assertEquals(e, container.connected()!!.generation)
        assertEquals(e, epoch())
        assertEquals("a job of the previous epoch: nothing", ListenableWorker.Result.success(), runWake(epoch = e - 1))
        assertTrue(requests.isEmpty())
        assertEquals(e, pendingWake(app))
        recoverPush(app, Wake) // the start of that process hands the mark on
        runJob()
        assertEquals(1, shown(A).size)
    }
}
