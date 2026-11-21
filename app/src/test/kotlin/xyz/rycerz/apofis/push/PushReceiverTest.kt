package xyz.rycerz.apofis.push

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

private const val ACTION = "org.unifiedpush.android.connector."
private const val OTHER = "com.example.other"

@RunWith(RobolectricTestRunner::class)
class PushReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val log = EventLog()
    private var commitsSucceed = true
    private val prefs = FakePrefs { log.step("commit ${it.sorted()}"); commitsSucceed }
    private val wake = FakePrefs()
    private var sinkCommits = true
    private val sinkCalls = mutableListOf<Context>()

    // Stands in for Task 4's sink: a checked commit of the wake demand.
    private val sink = PushSink { _ ->
        sinkCalls += app
        log.step("demand")
        sinkCommits && wake.edit().putLong(WAKE_DEMAND, wake.getLong(WAKE_DEMAND, 0) + 1).commit()
    }
    private val ctx = object : ContextWrapperWithWake(LogContext(app, log, prefs, sink)) {}
    private val scheduled = mutableListOf<Context>()
    private var schedule: (Context) -> Unit = { log.step("schedule"); scheduled += it }
    private val server = FakeServer("https://a.example/")

    /** LogContext plus the `wake` prefs from [wake], so its contents can be checked on the fake disk. */
    private open inner class ContextWrapperWithWake(private val base: LogContext) : android.content.ContextWrapper(base), PushSinkProvider by base {
        val sent get() = base.sent
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = if (name == WAKE_PREFS) wake else base.getSharedPreferences(name, mode)
    }

    @Before
    fun setUp() {
        resetConnectorStore(app)
        installDistributor(app, NTFY_PACKAGE)
        installDistributor(app, OTHER)
        ShadowLog.clear()
    }

    private fun TestScope.registrar() =
        PushRegistrar(ctx, prefs, { FakeRecordApi(server) }, { 0L }, { 0L }, backgroundScope, TestPolicy(), RealUnifiedPush)

    /** Turns push on through the real connector and returns the token ntfy was given for the current instance. */
    private suspend fun TestScope.enabled(): Pair<PushRegistrar, String> {
        val r = registrar()
        r.enableNow()
        return r to tokenOf(r.currentInstance())
    }

    private fun tokenOf(instance: String): String {
        val reg = ctx.sent.last { it.action == "org.unifiedpush.android.distributor.REGISTER" && it.`package` == NTFY_PACKAGE }
        assertEquals(instance, instanceOf(prefs))
        return reg.getStringExtra("token")!!
    }

    private fun intent(action: String, token: String?, build: Intent.() -> Unit = {}) = Intent(ACTION + action).apply {
        setPackage(app.packageName)
        if (token != null) putExtra("token", token)
        build()
    }

    private fun deliver(i: Intent) {
        log.events.clear()
        ctx.sent.clear()
        PushReceiver().receive(ctx, i, finish = { log.step("finish") }, schedule = schedule)
    }

    private fun logged() = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }

    @Test
    fun durableBeforeAck() = runTest {
        val (_, token) = enabled()
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") })
        assertEquals(
            listOf("commit [endpoint_new, endpoint_new_token]", "broadcast MESSAGE_ACK to $NTFY_PACKAGE", "schedule", "finish"),
            log.events,
        )
        assertEquals(VALID, prefs.disk[Keys.ENDPOINT_NEW])

        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1, 2)); putExtra("id", "m2") })
        assertEquals(listOf("demand", "broadcast MESSAGE_ACK to $NTFY_PACKAGE", "finish"), log.events)
        assertEquals(1L, wake.disk[WAKE_DEMAND])
    }

    @Test
    fun bodyNeverRead() = runTest {
        val (_, token) = enabled()
        var reads = 0
        val message = object : Intent(ACTION + "MESSAGE") {
            override fun getByteArrayExtra(name: String): ByteArray? {
                if (name == "bytesMessage") reads++
                return super.getByteArrayExtra(name)
            }
        }.apply {
            setPackage(app.packageName)
            putExtra("token", token)
            putExtra("bytesMessage", """{"session_id":"apofis-x","kind":"finished"}""".toByteArray())
        }
        PushReceiver().receive(ctx, message, finish = {}, schedule = schedule)
        assertEquals("only the connector reads the bytes, once", 1, reads)
        assertEquals(1, sinkCalls.size) // PushSink.onPush(context) and TestPush.onWake() take no body at all
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun commitFailure() = runTest {
        val (_, token) = enabled()
        commitsSucceed = false
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") })
        assertTrue("an endpoint that is not on disk is not ACKed", ctx.sent.none { it.action == "org.unifiedpush.android.distributor.MESSAGE_ACK" })
        assertTrue(scheduled.isEmpty())
        assertNull(prefs.disk[Keys.ENDPOINT_NEW])
        val logs = logged()
        assertTrue("logged by class", "PushReceiver" in logs)
        assertFalse("nothing secret logged", "upAbC123xyz" in logs || token in logs)
        // While the write is still not on disk, the next event is not acted on at all: no sink, no ACK.
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m4") })
        assertTrue(sinkCalls.isEmpty())
        assertTrue(ctx.sent.isEmpty())
        // The disk recovers: the next event writes the whole map first and hands the endpoint on for publishing.
        commitsSucceed = true
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) })
        assertTrue("endpoint now durable", prefs.disk[Keys.ENDPOINT_NEW] == VALID)
        assertEquals(1, scheduled.size)
    }

    @Test
    fun migrationsAreNeverFollowed() = runTest {
        for (action in listOf("UNREGISTERED", "TEMP_UNAVAILABLE")) {
            for (token in listOf(null, "not-a-token")) {
                resetConnectorStore(app)
                val (r, known) = enabled()
                val before = prefs.all.toMap()
                deliver(intent(action, token) { putExtra("newDistributor", OTHER) })
                assertEquals("$action, token #${listOf(null, "not-a-token").indexOf(token)}", before, prefs.all.toMap())
                assertEquals(emptyList<Intent>(), ctx.sent)
                assertTrue(scheduled.isEmpty())
                assertEquals(NTFY_PACKAGE, RealUnifiedPush.saved(ctx))
                deliver(intent("NEW_ENDPOINT", known) { putExtra("endpoint", VALID) }) // the live registration still works
                assertEquals(VALID, prefs.getString(Keys.ENDPOINT_NEW, null))
                r.disable()
                scheduled.clear()
            }
        }
        // Known token, TEMP_UNAVAILABLE with a replacement, even an allowed one: nothing changes.
        resetConnectorStore(app)
        val (r, token) = enabled()
        for (replacement in listOf(OTHER, NTFY_PACKAGE)) {
            val before = prefs.all.toMap()
            deliver(intent("TEMP_UNAVAILABLE", token) { putExtra("newDistributor", replacement) })
            assertEquals(before, prefs.all.toMap())
            assertEquals(emptyList<Intent>(), ctx.sent)
        }
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) })
        r.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)

        // Known token, UNREGISTERED with a replacement: a plain unregistration; nothing goes to the other package.
        val record = prefs.getString(Keys.RECORD, null)!!
        scheduled.clear()
        deliver(intent("UNREGISTERED", token) { putExtra("newDistributor", OTHER) })
        assertTrue(ctx.sent.none { it.`package` == OTHER })
        assertEquals(1, scheduled.size)
        r.publishPending()
        assertEquals(PushState.Off, r.ui.value.state)
        assertEquals("the record stays on the server", listOf(record), server.rows.keys.toList())
        assertNull(RealUnifiedPush.saved(ctx))
    }

    @Test
    fun revocationJournaledBeforeTheConnectorDropsIt() = runTest {
        val (r, token) = enabled()
        deliver(intent("UNREGISTERED", token))
        assertEquals(
            listOf("commit [unregistered_instance]", "broadcast UNREGISTER to $NTFY_PACKAGE", "schedule", "finish"),
            log.events,
        )
        r.publishPending()
        assertEquals(PushState.Off, r.ui.value.state)

        // Death right where the connector broadcasts, after it deleted its registration: the revocation is on disk.
        resetConnectorStore(app)
        val (_, token2) = enabled()
        log.killAt = log.count() + 2 // our commit, then death before the connector's UNREGISTER broadcast
        runCatching { deliver(intent("UNREGISTERED", token2)) }
        log.killAt = 0
        assertEquals(instanceOf(prefs), prefs.disk[Keys.UNREGISTERED_INSTANCE])
        assertTrue(needsWork(prefs.restart(), setOf(NTFY_PACKAGE)))
    }

    @Test
    fun untrustedTokensChangeNothing() = runTest {
        val (_, token) = enabled()
        // The connector holds a fallback registration of the same instance with a package outside the policy (left by
        // another build): set up through the plain connector receiver, which follows migrations.
        val plain = object : org.unifiedpush.android.connector.MessagingReceiver() {
            override fun getKeyManager(context: Context) = PlainKeyManager
            override fun onNewEndpoint(context: Context, endpoint: org.unifiedpush.android.connector.data.PushEndpoint, instance: String) {}
            override fun onRegistrationFailed(context: Context, reason: org.unifiedpush.android.connector.FailedReason, instance: String) {}
            override fun onUnregistered(context: Context, instance: String) {}
            override fun onMessage(context: Context, message: org.unifiedpush.android.connector.data.PushMessage, instance: String) {}
        }
        plain.onReceive(ctx, intent("TEMP_UNAVAILABLE", token) { putExtra("newDistributor", OTHER) })
        val foreign = ctx.sent.last { it.action == "org.unifiedpush.android.distributor.REGISTER" && it.`package` == OTHER }.getStringExtra("token")!!
        for (action in listOf("NEW_ENDPOINT", "UNREGISTERED", "MESSAGE")) {
            deliver(intent(action, foreign) { putExtra("endpoint", VALID); putExtra("bytesMessage", byteArrayOf(1)) })
            assertEquals(action, listOf("finish"), log.events) // dropped before the connector could act on it
        }
        assertNull(prefs.getString(Keys.UNREGISTERED_INSTANCE, null))
        assertTrue(sinkCalls.isEmpty())
        // The allowed registration survived and still delivers.
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) })
        assertEquals(token, prefs.getString(Keys.ENDPOINT_NEW_TOKEN, null))
    }

    @Test
    fun oldInstanceEventsDropped() = runTest {
        val r = registrar()
        r.enableNow()
        val old = tokenOf("g1")
        r.disable()
        RealUnifiedPush.register(ctx, "g1") { _, _ -> } // a connector registration of an old instance that is still known
        r.enableNow()
        val live = tokenOf("g3")
        scheduled.clear()
        deliver(intent("NEW_ENDPOINT", old) { putExtra("endpoint", VALID) })
        assertEquals(listOf("finish"), log.events)
        assertNull(prefs.getString(Keys.ENDPOINT_NEW, null))
        assertTrue(scheduled.isEmpty())
        deliver(intent("NEW_ENDPOINT", live) { putExtra("endpoint", VALID) })
        assertEquals(live, prefs.getString(Keys.ENDPOINT_NEW_TOKEN, null))
    }

    @Test
    fun lateEventsAreNotHandled() = runTest {
        val (_, token) = enabled()
        log.events.clear()
        ctx.sent.clear()
        var finished = false
        PushReceiver().receive(ctx, intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") },
            finish = { finished = true }, schedule = schedule, receivedAt = 0, now = { ADMISSION_MS + 1 })
        assertTrue(finished)
        assertTrue("neither committed nor ACKed", log.events.isEmpty() && ctx.sent.isEmpty())
        assertNull(prefs.getString(Keys.ENDPOINT_NEW, null))
    }

    @Test
    fun heldWorkManagerNeverHoldsTheReceiver() = runTest {
        // A WorkManager whose executor never runs: scheduling must not wait for it.
        val held = Executor { }
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setTaskExecutor(held).setExecutor(held).build())
        PublishWorker.schedule(app) // returns at once, no wait for the held executor (and so no timeout)
        val (_, token) = enabled()
        var finished = false
        PushReceiver().receive(ctx, intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") }, finish = { finished = true })
        assertTrue(finished)
        assertEquals(VALID, prefs.disk[Keys.ENDPOINT_NEW])
        assertTrue(ctx.sent.any { it.action == "org.unifiedpush.android.distributor.MESSAGE_ACK" })
    }

    /**
     * Stands in for Task 4's wake: demand and done counters with the connection epoch they belong to, recovered at
     * process start into exactly one wake job, and never across a connection change.
     */
    private var epoch = 1L
    private var wakeStore = wake // a restart reads it back from disk
    private val wakeSink = object : PushSink {
        override fun onPush(context: Context) =
            wakeStore.edit().putLong(WAKE_DEMAND, wakeStore.getLong(WAKE_DEMAND, 0) + 1).putLong(WAKE_EPOCH, epoch).commit()
        override fun recover(context: Context) {
            val w = wakeStore
            if (w.getLong(WAKE_EPOCH, 0) == epoch && w.getLong(WAKE_DEMAND, 0) > w.getLong(WAKE_DONE, 0)) {
                WorkManager.getInstance(context).enqueueUniqueWork("apofis-wake", androidx.work.ExistingWorkPolicy.KEEP,
                    androidx.work.OneTimeWorkRequestBuilder<HandedOn>().build())
            }
        }
    }

    @Test
    fun deathAfterAck() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val (_, token) = enabled()
        val withWake = object : ContextWrapperWithWake(LogContext(app, log, prefs, wakeSink)) {}
        // MESSAGE: the demand is on disk before the ACK; the process dies right after the ACK.
        log.killAt = log.count() + 2 // ACK, then death at finish
        runCatching {
            PushReceiver().receive(withWake, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m1") },
                finish = { log.step("finish") }, schedule = schedule)
        }
        log.killAt = 0
        assertEquals(1L, wake.disk[WAKE_DEMAND])
        // Restart: the process start hands the demand on, as one wake job.
        wakeStore = wake.restart()
        recoverPush(object : ContextWrapperWithWake(LogContext(app, log, prefs.restart(), wakeSink)) {}, wakeSink)
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork("apofis-wake").get().size)
        // A wake of the old connection does not become demand of the new one.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        epoch++
        wakeSink.recover(app)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork("apofis-wake").get().isEmpty())
        // NEW_ENDPOINT: a process start schedules the publish, which completes it.
        log.killAt = log.count() + 3 // commit, ACK, death at schedule
        runCatching { deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m2") }) }
        log.killAt = 0
        PublishWorker.recover(app, prefs.restart())
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.NAME).get().size)
        val restarted = registrar()
        assertTrue(restarted.publishPending())
        assertEquals(PushState.On(NTFY_PACKAGE), restarted.ui.value.state)
    }

    @Test
    fun demandCommitFails() = runTest {
        val (_, token) = enabled()
        sinkCommits = false
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m3") })
        assertEquals(1L, wake.disk[WAKE_LOST])
        assertNull(wake.disk[WAKE_DEMAND])
        // A sink that throws loses the wake the same way.
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }.also { sinkCommits = true })
        val throwing = LogContext(app, log, prefs, PushSink { throw IllegalStateException("disk") })
        PushReceiver().receive(object : ContextWrapperWithWake(throwing) {}, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, {}, schedule)
        assertEquals(2L, wake.disk[WAKE_LOST])
        val logs = logged()
        assertTrue("lost wake logged", "PushReceiver" in logs && "lost" in logs)
        assertFalse("token not logged", token in logs)
    }

    @Test
    fun wakeCheckAndCommitAreOneStep() = runTest {
        val (_, token) = enabled()
        val inSink = CountDownLatch(1)
        val release = CountDownLatch(1)
        var epochAtCommit = 0L
        val slow = LogContext(app, log, prefs, PushSink {
            inSink.countDown()
            release.await(10, TimeUnit.SECONDS)
            epochAtCommit = epoch
            true
        })
        val receiver = Thread { PushReceiver().receive(object : ContextWrapperWithWake(slow) {}, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, {}, schedule) }
        receiver.start()
        assertTrue(inSink.await(10, TimeUnit.SECONDS))
        // A connection change (AppContainer bumps its generation under the push lock) must wait for the hand-off.
        val bump = Thread { PushStore.locked { epoch++ } }
        bump.start()
        val watchdog = System.nanoTime() + 10_000_000_000
        while (bump.state != Thread.State.BLOCKED && System.nanoTime() < watchdog) Thread.yield()
        assertEquals(Thread.State.BLOCKED, bump.state)
        release.countDown()
        receiver.join(10_000)
        bump.join(10_000)
        assertEquals(1L, epochAtCommit)
        assertEquals(2L, epoch)
    }

    @Test
    fun registrationFailedCallback() = runTest {
        val (r, token) = enabled()
        assertEquals(PushState.Registering, r.ui.value.state)
        deliver(intent("REGISTRATION_FAILED", token) { putExtra("reason", "NETWORK") })
        assertEquals(1, scheduled.size)
        r.publishPending()
        assertEquals(PushState.Off, r.ui.value.state)
        assertTrue(server.created.isEmpty())
    }

    @Test
    fun onlyOurReceiverIsDeclared() {
        for (action in listOf("MESSAGE", "UNREGISTERED", "NEW_ENDPOINT", "REGISTRATION_FAILED", "TEMP_UNAVAILABLE")) {
            val found = app.packageManager.queryBroadcastReceivers(Intent(ACTION + action).setPackage(app.packageName), 0)
            assertEquals(action, listOf(PushReceiver::class.java.name), found.map { it.activityInfo.name })
        }
    }
}

/** The stand-in wake job. */
class HandedOn(context: Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(context, params) {
    override fun doWork() = Result.success()
}
