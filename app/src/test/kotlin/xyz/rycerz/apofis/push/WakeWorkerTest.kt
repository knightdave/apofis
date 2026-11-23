package xyz.rycerz.apofis.push

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
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

/** A wake job's input as WorkManager handed it to the worker (or holds it for a queued job). */
private data class Input(val epoch: Long, val cause: String?, val followUp: Int)

/**
 * The wake against a real container, notifier and follower, a fake server that records every request, and the test
 * WorkManager running the real enqueued work: its factory supplies the test graph and records each worker's input;
 * [TestDriver] releases delays and constraints; receipts (`wakeStep`, request arrivals, WorkManager's flows) order the
 * steps. Direct worker runs are used only for single outcomes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@SuppressLint("RestrictedApi") // the queued WorkSpec's expedited flag and input, which WorkInfo does not expose
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
    private val started = CopyOnWriteArrayList<Input>()
    private val steps = CopyOnWriteArrayList<String>()
    private val stepReceipts = Channel<String>(Channel.UNLIMITED)
    @Volatile private var barrier: suspend (String) -> Unit = {}
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
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != WakeWorker::class.java.name) return null
                started += workerParameters.inputData.let { Input(it.getLong(EPOCH, -1), it.getString(CAUSE), it.getInt(FOLLOW_UP, 0)) }
                return WakeWorker(ctx, workerParameters)
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        Wake.newProcess()
        wakeStep = { step ->
            steps += step
            stepReceipts.trySend(step)
            barrier(step)
        }
        Wake.laneStep = {}
        Wake.beforeInstall = {}
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
        Wake.idle()
    }

    @After
    fun tearDown() {
        wakeStep = {}
        Wake.laneStep = {}
        Wake.beforeInstall = {}
        Wake.idle()
        wm.cancelAllWork().result.get()
        containers.forEach { it.scope.cancel() } // before the shared database closes
        db.close()
        server.close()
    }

    private fun url() = server.url("/").toString()
    private fun newContainer() = AppContainer(app, store) { db }.also { containers += it }

    /** A new process on the same disk: the old container's scope (its collector among it) ends first. */
    private fun restart() {
        Wake.newProcess()
        container.scope.cancel()
        container = newContainer()
    }

    private fun epoch() = connectionEpoch(app)
    private fun target() = container.connected()!!.let { WakeTarget(it, it.generation, container.notifier) }

    /** A delivered push as the receiver hands it off: the mark under the push lock, then the lane's request. */
    private fun push() {
        assertTrue(PushStore.locked { Wake.onPush(app, epoch()) })
        Wake.recover(app)
        Wake.idle()
    }

    /** Waits in real time (a watchdog only) for a state change made on other threads. */
    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private fun jobs(name: String = WAKE_WORK) = wm.getWorkInfosForUniqueWork(name).get()
    private fun live(name: String = WAKE_WORK) = jobs(name).filter { !it.state.isFinished }
    private suspend fun awaitWork(name: String = WAKE_WORK, until: (List<WorkInfo>) -> Boolean) = real { wm.getWorkInfosForUniqueWorkFlow(name).first(until) }
    private fun spec(id: UUID) = WorkManagerImpl.getInstance(app).workDatabase.workSpecDao().getWorkSpec(id.toString())!!
    private fun input(id: UUID) = spec(id).input.let { Input(it.getLong(EPOCH, -1), it.getString(CAUSE), it.getInt(FOLLOW_UP, 0)) }
    private fun expedited(id: UUID) = spec(id).let { it.expedited && it.outOfQuotaPolicy == OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST }

    /** Releases a queued job (its delay and its network constraint) without waiting. */
    private fun release(job: WorkInfo) {
        if (job.initialDelayMillis > 0 || job.runAttemptCount > 0) driver.setInitialDelayMet(job.id)
        driver.setAllConstraintsMet(job.id)
    }

    /** Waits until WorkManager recorded the end of [id]: finished, removed, or back in the queue for a retry. */
    private suspend fun awaitEnd(id: UUID, attempts: Int = 0): WorkInfo? =
        real { wm.getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished || (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > attempts) } }

    /** Runs the one queued wake job to its end, then lets the lane handle what it asked for. */
    private suspend fun runJob(): WorkInfo {
        val job = live().single()
        release(job)
        val end = awaitEnd(job.id, job.runAttemptCount) ?: job
        Wake.idle()
        return end
    }

    private suspend fun runWake(cause: String = CAUSE_PUSH, attempt: Int = 0, epoch: Long = epoch(), followUp: Int = 0): ListenableWorker.Result =
        TestListenableWorkerBuilder.from(ctx, WakeWorker::class.java)
            .setInputData(workDataOf(EPOCH to epoch, CAUSE to cause, FOLLOW_UP to followUp)).setRunAttemptCount(attempt).build().doWork()
            .also { Wake.idle() }

    /** The next [n] requests to arrive, as receipts. */
    private suspend fun awaitRequests(n: Int) = real { repeat(n) { arrived.receive() } }

    /** Receives step receipts until [step] has arrived [count] times in total. */
    private suspend fun awaitStep(step: String, count: Int = 1) = real { while (steps.count { it == step } < count) stepReceipts.receive() }

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

    private suspend fun session(key: String) = db.sessionDao().upsert(
        SessionEntity(key, key, "", "Chat $key", pinned = false, archived = false, startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true),
    )

    private suspend fun stored(run: String, key: String = A, startedAt: Long = 1L, status: String = "running") {
        session(key)
        db.activeRunDao().upsert(ActiveRunEntity(run, key, null, status, startedAt))
    }

    private suspend fun shown(key: String) = container.notifier.idle().let { nm.activeNotifications.filter { it.tag == key } }
    private suspend fun text(key: String) = shown(key).single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun silent(n: Notification) = n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && n.group != null
    private fun statusReads() = requests.filter { it.startsWith("/v1/runs/") }.map { it.removePrefix("/v1/runs/") }
    private fun summaries() = ShadowLog.getLogsForTag("ApofisWake").map { it.msg }

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
        assertTrue("silent replay", silent(shown(A).single().notification))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun unauthorizedStops() = runTest {
        stored("run_a", startedAt = 1)
        stored("run_b", B, startedAt = 2)
        routes["/v1/runs/run_a"] = { json("""{"error":"unauthorized"}""", 401) }
        on("run_b", B, "completed")
        push()
        assertEquals(WorkInfo.State.SUCCEEDED, runJob().state)
        assertEquals(listOf("run_a"), statusReads())
        assertTrue("no follow-up after a refused key, though runs are stored", live().isEmpty())
    }

    @Test
    fun oneSummaryLinePerCheck() = runTest {
        // A 401 after a partial batch.
        stored("run_1", startedAt = 1)
        stored("run_2", startedAt = 2)
        stored("run_3", startedAt = 3)
        on("run_1", A, "running")
        routes["/v1/runs/run_2"] = { json("""{"error":"unauthorized"}""", 401) }
        on("run_3", A, "running")
        check(ctx, target())
        assertEquals(listOf("cause=push runs=3 settled=1 failed=1 outcome=DONE"), summaries())
        // Several per-run failures.
        ShadowLog.clear()
        routes["/v1/runs/run_2"] = { MockResponse.Builder().code(503).build() }
        routes["/v1/runs/run_3"] = { MockResponse.Builder().code(503).build() }
        check(ctx, target(), CAUSE_PERIODIC)
        assertEquals(listOf("cause=periodic runs=3 settled=1 failed=2 outcome=RETRY"), summaries())
        // An early failure, before any run.
        ShadowLog.clear()
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", A, "run_old", 5L, "FINISHED", epoch(), 0))
        container.notifier.beforePost = { throw IllegalStateException("tray") }
        runCatching { check(ctx, target()) }
        assertEquals(listOf("cause=push runs=0 settled=0 failed=0 outcome=none"), summaries())
        assertTrue("no ids, no other lines", ShadowLog.getLogsForTag("ApofisWake").none { "run_" in it.msg || A in it.msg })
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
        on("run_1", A, "running") // only the 503 left: it alone retries
        assertEquals(ListenableWorker.Result.retry(), runWake(attempt = 0))
    }

    @Test
    fun aFailedCheckRetries() = runTest {
        session(A)
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", A, "run_old", 5L, "FINISHED", epoch(), 0))
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
        assertTrue("nothing of the cursor in the mark's file", wakePrefs(app).all.keys.none { it != WAKE_PENDING && it != WAKE_LOST })
    }

    @Test
    fun anonymousApprovalPath() = runTest {
        stored("run_a")
        val approval = """{"kind":"approval","prompt_id":"p1","execution_generation":2,"command":"rm x","question":null}"""
        routes["/v1/runs/run_a"] = { status("run_a", A, "waiting_for_approval", approval) }
        push()
        runJob()
        assertEquals(app.getString(R.string.notif_needs_answer), text(A))
        assertEquals("a follow-up is due", Input(epoch(), CAUSE_RECHECK, 1), input(live().single().id))
    }

    @Test
    fun foregroundReplaysAnOutboxRowOfAnotherChat() = runTest {
        session(B)
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_old", B, "run_old", 5L, "FINISHED", epoch(), 0))
        target().connected.follower.resumeAll() // the app is shown, another chat open
        runWake()
        assertTrue("replayed, not left behind", db.noticeOutboxDao().all(epoch()).isEmpty())
        assertTrue(silent(shown(B).single().notification))
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

    // The mark and the cursor.

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
        // Meanwhile a check advances its cursor: it writes its own file, not the mark's.
        stored("run_a")
        on("run_a", A, "running")
        check(failingCtx, target())
        release.countDown()
        pushing.join(10_000)
        reader.join(10_000)
        assertNull("rolled back before it could be read", seen)
        assertEquals(emptyMap<String, Any?>(), failing.disk)
        assertEquals("run_a", app.getSharedPreferences(CURSOR_PREFS, Context.MODE_PRIVATE).getString(CURSOR_ID, null))
        failing.onCommit = { true }
        assertTrue(PushStore.locked { Wake.onPush(failingCtx, epoch()) })
        assertEquals(epoch(), pendingWake(failingCtx))
    }

    @Test
    fun aMarkIsClearedOnlyForTheCurrentEpoch() = runTest {
        push()
        assertEquals(Cleared.STALE, clearPendingWake(app, epoch() - 1))
        assertEquals(epoch(), pendingWake(app))
        assertEquals(Cleared.REMOVED, clearPendingWake(app, epoch()))
        assertEquals(Cleared.NONE, clearPendingWake(app, epoch()))
        assertNull(pendingWake(app))
    }

    @Test
    fun aLateLastSettleAcrossASwitchLeavesTheNewCursor() = runTest {
        val ids = (1..10).map { "run_%02d".format(it) }
        ids.forEachIndexed { i, id ->
            stored(id, startedAt = i + 1L)
            on(id, A, "running")
        }
        val held = CompletableDeferred<Unit>()
        barrier = { if (it == "settled run_08") withTimeout(10_000) { held.await() } }
        val old = target()
        val checking = async(Dispatchers.Default) { check(ctx, old) }
        awaitStep("settled run_08")
        container.connect(Credentials(url(), "key-2")) // same server: the runs are kept
        held.complete(Unit) // the old check returns from its last settle and writes its cursor
        real { checking.await() }
        barrier = {}
        requests.clear()
        check(ctx, target())
        assertEquals("the new connection starts at its own beginning", ids.subList(0, 8), statusReads())
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
        old.follower.beforeStatusStore = { if (!paused.isCompleted) { paused.complete(Unit); withTimeout(10_000) { release.await() } } }
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
        restart()
        recoverPush(app, Wake)
        Wake.idle()
        val job = live().single()
        assertEquals(Input(epoch(), CAUSE_PUSH, 0), input(job.id))
        assertTrue(expedited(job.id))
        var atEntry: Pair<Long?, Int>? = null
        barrier = { if (it == "entered") atEntry = pendingWake(app) to requests.size }
        assertEquals(WorkInfo.State.SUCCEEDED, runJob().state)
        assertEquals("the mark is gone before the check's first request", null to 0, atEntry)
        awaitRequests(3)
        assertEquals(listOf("/api/sessions", "/api/sessions/$A/messages", "/v1/runs/run_a"), requests.sorted())
        assertEquals(app.getString(R.string.notif_reply_finished), text(A))
        assertTrue("nothing left to do", live().isEmpty())
        assertEquals(listOf(Input(epoch(), CAUSE_PUSH, 0)), started)
        assertEquals(listOf("cause=push runs=1 settled=1 failed=0 outcome=DONE"), summaries())
    }

    @Test
    fun deathWithNoLaterPush() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) } // dies before the request
        assertTrue(jobs().isEmpty())
        restart()
        recoverPush(app, Wake) // the next process start, with no further push
        Wake.idle()
        runJob()
        assertEquals(1, shown(A).size)
    }

    @Test
    fun aJobStoppedAfterClearingTheMarkRunsAgainAfterARestart() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        push()
        val held = CompletableDeferred<Unit>()
        barrier = { if (it == "cleared") withTimeout(10_000) { held.await() } }
        release(live().single())
        awaitStep("cleared")
        assertNull("cleared", pendingWake(app))
        // The process dies here. WorkManager reruns the persisted spec (the test WorkManager cannot reset a running
        // spec after a death, so the rerun gets that spec's input, as recorded by the factory).
        val persisted = started.single()
        barrier = {}
        restart()
        TestListenableWorkerBuilder.from(ctx, WakeWorker::class.java)
            .setInputData(workDataOf(EPOCH to persisted.epoch, CAUSE to persisted.cause, FOLLOW_UP to persisted.followUp)).build().doWork()
        assertEquals("checked with no mark left", listOf("run_a"), statusReads())
        assertEquals(1, shown(A).size)
        held.complete(Unit)
    }

    @Test
    fun repeatedColdStartsLeaveOneJob() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        push() // the network is away: the job waits
        repeat(3) {
            restart()
            recoverPush(app, Wake) // three process starts before it returns
            Wake.idle()
        }
        assertEquals(1, live().size)
        runJob()
        assertEquals(listOf("run_a"), statusReads())
        assertTrue(live().isEmpty())
    }

    @Test
    fun aFailedInstallIsTriedOnceMoreThenTheMarkStays() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        var failures = 1
        Wake.beforeInstall = { if (failures-- > 0) throw IllegalStateException("database full") }
        push()
        assertEquals("installed on the second try", 1, live().size)
        wm.cancelAllWork().result.get()
        failures = 2
        push()
        assertTrue("nothing installed", live().isEmpty())
        assertEquals("the mark stays for the next trigger", epoch(), pendingWake(app))
        Wake.recover(app) // say, the next process start
        Wake.idle()
        runJob()
        assertEquals(1, shown(A).size)
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
        assertEquals(1, failed.runAttemptCount)
        push()
        val next = live().single()
        assertNotEquals("replaced by a fresh job", failed.id, next.id)
        assertEquals(0, next.runAttemptCount)
        assertTrue("expedited", expedited(next.id))
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
        barrier = { if (it == "settled run_a" && !pushed) { pushed = true; push() } }
        driver.setAllConstraintsMet(first)
        val replaced = awaitEnd(first)
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
    fun aPushAfterTheLastScanGetsAnImmediateFreshJob() = runTest {
        stored("run_a")
        on("run_a", A, "running", "completed")
        push()
        val first = live().single().id
        var pushed = false
        // After the last scan: the receiver's commit only (its request comes later, from another thread).
        barrier = { if (it == "checked" && !pushed) { pushed = true; PushStore.locked { Wake.onPush(app, epoch()) } } }
        driver.setAllConstraintsMet(first)
        awaitEnd(first)
        Wake.idle() // the finishing job's follow-up request meets the pending mark
        assertEquals(epoch(), pendingWake(app))
        val next = live().single()
        assertEquals("the mark wins over the follow-up", Input(epoch(), CAUSE_PUSH, 0), input(next.id))
        assertEquals(0L, next.initialDelayMillis)
        assertTrue(expedited(next.id))
        Wake.recover(app) // the receiver's request
        Wake.idle()
        runJob()
        assertEquals(listOf("run_a", "run_a"), statusReads())
        assertEquals(1, shown(A).size)
    }

    @Test
    fun aFollowUpFromAReplacedJobChangesNothing() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        push()
        val j1 = live().single().id
        // J1 is replaced by a fresh push before it ran; its late request meets the push's pending mark.
        push()
        Wake.post(app, WakeRequest.FollowUp(1, j1))
        Wake.idle()
        val j2 = live().single()
        assertEquals(Input(epoch(), CAUSE_PUSH, 0), input(j2.id))
        assertTrue(expedited(j2.id))
        assertEquals(0L, j2.initialDelayMillis)
        // J2 runs and clears the mark; J1's late request arrives during its check and must not replace it.
        val held = CompletableDeferred<Unit>()
        barrier = { if (it == "entered") withTimeout(10_000) { held.await() } }
        release(j2)
        awaitStep("entered")
        Wake.post(app, WakeRequest.FollowUp(1, j1))
        Wake.idle()
        held.complete(Unit)
        assertEquals("J2 ran to its end", WorkInfo.State.SUCCEEDED, awaitEnd(j2.id)?.state ?: WorkInfo.State.SUCCEEDED)
        Wake.idle()
        assertEquals(listOf("run_a"), statusReads())
        assertEquals("J2's own follow-up", Input(epoch(), CAUSE_RECHECK, 1), input(live().single().id))
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
        Wake.idle()
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
        val queued = mutableListOf<Pair<Input, Long>?>()
        repeat(4) {
            runJob()
            queued += live().singleOrNull()?.let { input(it.id) to it.initialDelayMillis }
        }
        val e = epoch()
        assertEquals(listOf(Input(e, CAUSE_RECHECK, 1) to 10_000L, Input(e, CAUSE_RECHECK, 2) to 30_000L, Input(e, CAUSE_RECHECK, 3) to 90_000L, null), queued)
        assertEquals(listOf(Input(e, CAUSE_PUSH, 0), Input(e, CAUSE_RECHECK, 1), Input(e, CAUSE_RECHECK, 2), Input(e, CAUSE_RECHECK, 3)), started)
        assertEquals(4, statusReads().size)
        push()
        runJob()
        runJob()
        assertEquals("30 s follow-up pending", 30_000L, live().single().initialDelayMillis)
        assertFalse("a follow-up is not expedited", expedited(live().single().id))
        push() // replaces the waiting follow-up
        assertEquals(Input(e, CAUSE_PUSH, 0), input(live().single().id))
        runJob()
        assertEquals("the sequence starts again", Input(e, CAUSE_RECHECK, 1), input(live().single().id))
    }

    @Test
    fun aThirdFollowUpThatClearsAFreshMarkStartsAFreshSequence() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        push()
        repeat(3) { runJob() }
        val third = live().single()
        assertEquals(Input(epoch(), CAUSE_RECHECK, 3), input(third.id))
        PushStore.locked { Wake.onPush(app, epoch()) } // a push lands; its request has not come yet
        runJob()
        assertNull(pendingWake(app))
        assertEquals("a fresh budget: the sequence starts again", Input(epoch(), CAUSE_RECHECK, 1), input(live().single().id))
    }

    @Test
    fun checksNeverOverlapAndAFinishKeepsItsAlert() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        val inFinish = CompletableDeferred<Unit>()
        val held = CompletableDeferred<Unit>()
        target().connected.follower.afterOutboxWrite = { if (!inFinish.isCompleted) { inFinish.complete(Unit); withTimeout(10_000) { held.await() } } }
        push()
        val j1 = live().single().id
        release(live().single())
        real { inFinish.await() } // J1 is in its finish transaction, not cancellable
        push() // replaces J1
        val j2 = live().single()
        assertNotEquals(j1, j2.id)
        release(j2) // the successor's constraints are released at once
        awaitStep("cleared", 2)
        assertEquals("no second check enters while the first is in its finish", 1, steps.count { it == "entered" })
        held.complete(Unit)
        awaitEnd(j2.id)
        Wake.idle()
        assertEquals(2, steps.count { it == "entered" })
        val n = shown(A).single().notification
        assertFalse("the normal alert, not a silent replay", silent(n))
        assertTrue(db.noticeOutboxDao().all(epoch()).isEmpty())
        assertTrue(live().isEmpty())
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
        Wake.idle()
        val job = live().single()
        assertEquals(Input(epoch(), CAUSE_PERIODIC, 0), input(job.id))
        assertFalse("a tick spends no expedited quota", expedited(job.id))
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
        assertEquals(Input(epoch(), CAUSE_TICK, 0), input(w.id))
        db.activeRunDao().delete("run_a")
        awaitWork(CHECK_WORK) { it.none { w -> !w.state.isFinished } }
        stored("run_b", B)
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        // Cold start: a run is stored, no check is scheduled (lost with the last process).
        wm.cancelUniqueWork(CHECK_WORK).result.get()
        restart()
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        // A switch to another server clears the stored runs, and with them the check.
        container.connect(Credentials("https://b.example/", "key-b"))
        Wake.idle()
        assertTrue(live(CHECK_WORK).isEmpty())
    }

    @Test
    fun aFollowUpComesAfterTheLastFailedExecution() = runTest {
        stored("run_a")
        routes["/v1/runs/run_a"] = { MockResponse.Builder().code(503).build() }
        push()
        assertEquals(WorkInfo.State.ENQUEUED, runJob().state) // execution 1: retry
        assertEquals(WorkInfo.State.ENQUEUED, runJob().state) // execution 2: retry
        runJob() // execution 3 ends
        assertEquals("the run is still unfinished: follow-up 1", Input(epoch(), CAUSE_RECHECK, 1), input(live().single().id))
        assertEquals(10_000L, live().single().initialDelayMillis)
    }

    @Test
    fun aForegroundCheckWithAnUnfinishedRunFollowsUp() = runTest {
        target().connected.follower.resumeAll() // shown; no follower starts for a run stored after this
        stored("run_a")
        on("run_a", A, "running")
        push()
        runJob()
        assertEquals(Input(epoch(), CAUSE_RECHECK, 1), input(live().single().id))
    }

    @Test
    fun aRetriedJobKeepsTheFreshBudgetOfThePushItConsumed() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        push()
        repeat(3) { runJob() }
        assertEquals(Input(epoch(), CAUSE_RECHECK, 3), input(live().single().id))
        // A push lands; its request reaches the lane only after the job cleared the mark, and finds none.
        PushStore.locked { Wake.onPush(app, epoch()) }
        var n = 0
        routes["/v1/runs/run_a"] = { if (n++ == 0) MockResponse.Builder().code(503).build() else status("run_a", A, "running") }
        val retried = runJob()
        assertEquals("a 503: retried", WorkInfo.State.ENQUEUED, retried.state)
        assertEquals("the mark is back for the retry", epoch(), pendingWake(app))
        assertEquals(retried.id, live().single().id)
        runJob() // the retry succeeds with the run still unfinished
        assertEquals("a fresh sequence: follow-up 1 at 10 s", Input(epoch(), CAUSE_RECHECK, 1), input(live().single().id))
        assertEquals(10_000L, live().single().initialDelayMillis)
    }

    @Test
    fun aSwitchNeverCancelsTheNewConnectionsJob() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        push() // an old job, queued
        val old = live().single().id
        // The collector's request for the stored run is handled first, so the old recover is the gate's first arrival.
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        // Every lane step is held. An old recover is at the head while the key changes and the new push lands.
        val gate = LaneGate { true }
        Wake.recover(app)
        assertEquals(WakeRequest.Recover, gate.next())
        container.connect(Credentials(url(), "key-2")) // its own lane requests queue behind the old recover
        PushStore.locked { Wake.onPush(app, epoch()) }
        Wake.recover(app)
        gate.release() // the old recover reads the new epoch and mark: the new connection's job
        gate.next()
        val job = live().single()
        assertNotEquals(old, job.id)
        assertEquals(Input(epoch(), CAUSE_PUSH, 0), input(job.id))
        // The new job clears the mark; only then does everything else the switch queued run.
        val held = CompletableDeferred<Unit>()
        barrier = { if (it == "cleared") withTimeout(10_000) { held.await() } }
        release(job)
        awaitStep("cleared")
        repeat(10) { gate.release() }
        Wake.idle()
        Wake.laneStep = {}
        held.complete(Unit)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitEnd(job.id)?.state ?: WorkInfo.State.SUCCEEDED)
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(listOf("Bearer key-2"), auth.distinct())
        assertEquals(1, shown(A).size)
    }

    @Test
    fun anOldTickAfterASwitchDoesNothing() = runTest {
        stored("run_a")
        val old = epoch()
        container.connect(Credentials(url(), "key-2"))
        Wake.idle()
        val before = live().map { it.id }
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_TICK, epoch = old))
        assertEquals("no wake job asked for", before, live().map { it.id })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun requestsHeldInTheLaneReadTheStateWhenTheyRun() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        for (request in listOf(WakeRequest.Recover, WakeRequest.Tick)) {
            wm.cancelUniqueWork(WAKE_WORK).result.get()
            val gate = LaneGate { it == request }
            Wake.post(app, request)
            gate.next() // posted with no mark; held before the lane reads
            PushStore.locked { Wake.onPush(app, epoch()) }
            gate.release()
            Wake.idle()
            Wake.laneStep = {}
            assertEquals("$request reads the mark when it runs", Input(epoch(), CAUSE_PUSH, 0), input(live().single().id))
            assertEquals(Cleared.REMOVED, clearPendingWake(app, epoch()))
        }
        // A follow-up of a real asker, held while a mark lands.
        push()
        runJob()
        val followUp = live().single()
        assertEquals(Input(epoch(), CAUSE_RECHECK, 1), input(followUp.id))
        val gate = LaneGate { it is WakeRequest.FollowUp }
        release(followUp)
        awaitEnd(followUp.id)
        gate.next()
        PushStore.locked { Wake.onPush(app, epoch()) }
        gate.release()
        Wake.idle()
        Wake.laneStep = {}
        assertEquals("the follow-up meets the mark: an immediate push job", Input(epoch(), CAUSE_PUSH, 0), input(live().single().id))
        // The epoch moves on while a recover is held: the job is for the epoch read when it runs.
        wm.cancelUniqueWork(WAKE_WORK).result.get()
        val held = LaneGate { it == WakeRequest.Recover }
        Wake.recover(app)
        held.next()
        container.connect(Credentials(url(), "key-2"))
        PushStore.locked { Wake.onPush(app, epoch()) }
        repeat(2) { held.release() }
        Wake.idle()
        Wake.laneStep = {}
        assertEquals(Input(epoch(), CAUSE_PUSH, 0), input(live().single().id))
    }

    @Test
    fun aTransientEmptyReadIsCorrectedByTheNextEmission() = runTest {
        stored("run_a")
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        // The lane reads the table empty for an instant and cancels the timer; the observer never saw it empty.
        Wake.post(app, WakeRequest.Reconcile { false })
        Wake.idle()
        assertTrue(live(CHECK_WORK).isEmpty())
        // The run is written again: the observer's value is unchanged (true), its emission still reconciles.
        db.activeRunDao().upsert(ActiveRunEntity("run_a", A, 3L, "running", 1L))
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        assertEquals("the timer is back, with no push", Input(epoch(), CAUSE_TICK, 0), input(live(CHECK_WORK).single().id))
    }

    @Test
    fun anOlderMarkClearedByANewConnectionsJobIsNoConsumedPush() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        PushStore.locked { Wake.onPush(app, epoch()) } // a mark of connection A, never served
        container.connect(Credentials(url(), "key-2")) // same server: run_a is kept; no push under B
        Wake.idle()
        val periodic = live(CHECK_WORK).single()
        driver.setInitialDelayMet(periodic.id)
        driver.setAllConstraintsMet(periodic.id)
        driver.setPeriodDelayMet(periodic.id)
        awaitWork { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        val job = live().single()
        assertEquals(Input(epoch(), CAUSE_PERIODIC, 0), input(job.id))
        routes["/v1/runs/run_a"] = { MockResponse.Builder().code(503).build() }
        assertEquals("a 503: retried", WorkInfo.State.ENQUEUED, runJob().state)
        assertNull("A's old mark is gone and not written back as B's", pendingWake(app))
        Wake.recover(app)
        Wake.idle()
        val after = live().single()
        assertEquals("no push job for B: still the periodic job's retry", job.id, after.id)
        assertEquals(Input(epoch(), CAUSE_PERIODIC, 0), input(after.id))
        assertFalse(expedited(after.id))
    }

    @Test
    fun aFailedPeriodicInstallIsNotRememberedAsApplied() = runTest {
        stored("run_a")
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        // The timer is removed (through the lane, so its memory says "removed"; a removal behind the lane's back would
        // leave it remembering "installed" and skip the install this test is about).
        Wake.post(app, WakeRequest.Reconcile { false })
        Wake.idle()
        assertTrue(live(CHECK_WORK).isEmpty())
        // Its install fails twice: only this request installs while the hook throws.
        var failures = 2
        Wake.beforeInstall = { if (failures-- > 0) throw IllegalStateException("database full") }
        Wake.post(app, WakeRequest.Reconcile { true })
        Wake.idle()
        Wake.beforeInstall = {}
        assertTrue("not installed", live(CHECK_WORK).isEmpty())
        // An emission with the same decision arrives: the lane must not treat the failed install as applied.
        db.activeRunDao().upsert(ActiveRunEntity("run_a", A, 3L, "running", 1L))
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        assertEquals(Input(epoch(), CAUSE_TICK, 0), input(live(CHECK_WORK).single().id))
    }

    /** Holds the lane at each request [hold] selects, one at a time, with a receipt when it arrives there. */
    private inner class LaneGate(private val hold: (WakeRequest) -> Boolean) {
        val arrived = Channel<WakeRequest>(Channel.UNLIMITED)
        private val go = java.util.concurrent.LinkedBlockingQueue<Unit>()
        init {
            Wake.laneStep = { r -> if (hold(r)) { arrived.trySend(r); go.poll(10, TimeUnit.SECONDS) } }
        }
        suspend fun next(): WakeRequest = real { arrived.receive() }
        fun release() = go.put(Unit)
    }

    @Test
    fun aStaleCollectorDecisionLastStillLeavesTheNewEpochsCheck() = runTest {
        stored("run_a")
        awaitWork(CHECK_WORK) { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        // The collector's request is held at the lane while the key changes (the run is kept). The held request
        // decides from what is current when it runs, not when it was posted.
        val gate = LaneGate { it is WakeRequest.Reconcile }
        Wake.post(app, WakeRequest.Reconcile { db.activeRunDao().any() })
        gate.next()
        container.connect(Credentials(url(), "key-2"))
        gate.release()
        gate.next() // publication's: the held reconcile is done
        assertEquals("it installed the current epoch's timer", Input(epoch(), CAUSE_TICK, 0), input(live(CHECK_WORK).single().id))
        gate.release()
        Wake.idle()
        Wake.laneStep = {}
        val periodic = live(CHECK_WORK).single()
        assertEquals("the timer belongs to the current epoch, with no push", Input(epoch(), CAUSE_TICK, 0), input(periodic.id))
        on("run_a", A, "completed")
        driver.setInitialDelayMet(periodic.id)
        driver.setAllConstraintsMet(periodic.id)
        driver.setPeriodDelayMet(periodic.id)
        awaitWork { it.any { w -> !w.state.isFinished } }
        Wake.idle()
        runJob()
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(listOf("Bearer key-2"), auth.distinct())
    }

    @Test
    fun aStalePublicationCancelLastStillLeavesTheCheck() = runTest {
        assertTrue(live(CHECK_WORK).isEmpty())
        // Publication's reconcile is held at the lane while no run is stored; a run is inserted meanwhile. The held
        // request reads the stored runs when it runs, so it installs the timer itself (before the collector's own).
        val gate = LaneGate { it is WakeRequest.Reconcile }
        container.connect(Credentials(url(), "key-2"))
        gate.next()
        stored("run_a")
        gate.release()
        gate.next() // the collector's: the held one is done
        assertEquals(Input(epoch(), CAUSE_TICK, 0), input(live(CHECK_WORK).single().id))
        gate.release()
        Wake.idle()
        Wake.laneStep = {}
        assertEquals(Input(epoch(), CAUSE_TICK, 0), input(live(CHECK_WORK).single().id))
    }

    @Test
    fun oldWorkAfterAKeyChangeDoesNothingAndTheNewConnectionIsServed() = runTest {
        stored("run_a")
        on("run_a", A, "running")
        val epochA = epoch()
        push()
        val oldJob = live().single().id
        container.connect(Credentials(url(), "key-2")) // same server: run_a is kept; nothing is cancelled
        Wake.idle()
        assertEquals("the periodic check carries the new epoch", Input(epoch(), CAUSE_TICK, 0), input(live(CHECK_WORK).single().id))
        // The new connection's push, then the old connection's producers, each released only now.
        push()
        val pushJob = live().single()
        Wake.recover(app)
        Wake.post(app, WakeRequest.Tick)
        Wake.post(app, WakeRequest.FollowUp(1, oldJob))
        Wake.idle()
        val job = live().single()
        assertEquals("still the new connection's push job", Input(epoch(), CAUSE_PUSH, 0), input(job.id))
        assertTrue(expedited(job.id))
        // Stale inputs of the old epoch (A, then B, then A's URL again as C): no effect at all.
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_TICK, epoch = epochA))
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_RECHECK, epoch = epochA, followUp = 1))
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_PUSH, epoch = epochA))
        assertTrue(requests.isEmpty())
        assertEquals(epoch(), pendingWake(app))
        assertEquals(job.id, live().single().id)
        assertNotEquals(pushJob.id, oldJob)
        runJob()
        assertNull(pendingWake(app))
        assertEquals(listOf("run_a"), statusReads())
        assertEquals(listOf("Bearer key-2"), auth.distinct())
    }

    @Test
    fun anAbaReturnLeavesOldInputsWithoutEffect() = runTest {
        stored("run_a")
        on("run_a", A, "completed")
        val epochA = epoch()
        container.connect(Credentials("https://b.example/", "key-b"))
        container.connect(Credentials(url(), "key")) // A's URL again, a third epoch
        assertEquals(epochA + 2, epoch())
        stored("run_a")
        push()
        val current = live().single().id
        assertEquals(ListenableWorker.Result.success(), runWake(CAUSE_PUSH, epoch = epochA))
        assertTrue(requests.isEmpty())
        assertEquals(current, live().single().id)
        runJob()
        assertEquals(listOf("run_a"), statusReads())
    }

    @Test
    fun coldRestoreKeepsEpoch() = runTest {
        val e = epoch()
        stored("run_a")
        on("run_a", A, "completed")
        PushStore.locked { Wake.onPush(app, epoch()) }
        restart() // process death; the cold load reads the epoch and keeps it
        assertEquals(e, container.connected()!!.generation)
        assertEquals(e, epoch())
        assertEquals("a job of the previous epoch: nothing", ListenableWorker.Result.success(), runWake(epoch = e - 1))
        assertTrue(requests.isEmpty())
        assertEquals(e, pendingWake(app))
        recoverPush(app, Wake) // the start of that process hands the mark on
        Wake.idle()
        runJob()
        assertEquals(1, shown(A).size)
    }
}
