package xyz.rycerz.apofis.push

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import okhttp3.ResponseBody.Companion.toResponseBody
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
@org.robolectric.annotation.Config(application = Application::class) // no app start-up work racing the tests
class PushReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val log = EventLog()
    private var commitsSucceed = true
    private val prefs = FakePrefs { log.step("commit ${it.sorted()}"); commitsSucceed }
    private val wake = FakePrefs()
    private var sinkCommits = true
    private val sinkCalls = mutableListOf<Context>()

    // Stands in for the wake sink: a checked commit of the wake mark.
    private val sink = PushSink { _, epoch ->
        sinkCalls += app
        log.step("demand")
        sinkCommits && wake.edit().putLong(WAKE_PENDING, epoch).commit()
    }
    private val ctx = object : ContextWrapperWithWake(LogContext(app, log, prefs, sink)) {}
    private val scheduled = mutableListOf<Context>()
    private var schedule: (Context) -> Unit = { log.step("schedule"); scheduled += it }
    private val server = FakeServer("https://a.example/")

    /** LogContext plus the `wake` prefs from [wake], so its contents can be checked on the fake disk. */
    private open inner class ContextWrapperWithWake(val base: LogContext) : android.content.ContextWrapper(base), PushSinkProvider by base {
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
        assertEquals(0L, wake.disk[WAKE_PENDING])
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
        val stored = prefs.all.values.joinToString() + wake.all.values.joinToString()
        assertFalse("the body appears in no log", "apofis-x" in logged())
        assertFalse("the body appears in no stored value", "apofis-x" in stored)
    }

    @Test
    fun commitFailure() = runTest {
        val (_, token) = enabled()
        commitsSucceed = false
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") })
        assertTrue("an endpoint that is not on disk is not ACKed", ctx.sent.none { it.action == "org.unifiedpush.android.distributor.MESSAGE_ACK" })
        assertEquals("recovery scheduled", 1, scheduled.size)
        scheduled.clear()
        assertNull(prefs.disk[Keys.ENDPOINT_NEW])
        val logs = logged()
        assertTrue("logged by class", "PushReceiver" in logs)
        assertFalse("nothing secret logged", "upAbC123xyz" in logs || token in logs)
        // While the write is still not on disk, the next event is not acted on at all: no sink, no ACK.
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m4") })
        assertTrue(sinkCalls.isEmpty())
        assertTrue(ctx.sent.isEmpty())
        scheduled.clear() // each failed write asks for recovery again
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
        assertTrue(needsWork(prefs.restart(), setOf(NTFY_PACKAGE), 0))
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
    fun everyBroadcastFinishesWithinTheBudget() {
        // A lane held by earlier work and a deadline the test fires by hand.
        val lane = mutableListOf<Runnable>()
        val deadline = mutableListOf<Runnable>()
        var finishes = 0
        var worked = 0
        admit({ lane += it }, { deadline += it }, { finishes++ }) { worked++ }
        deadline.single().run() // the budget runs out while the event still waits for the lane
        assertEquals(1, finishes)
        lane.single().run() // the lane frees up: the abandoned event is not handled (no commit, no ACK)
        assertEquals(0, worked)
        assertEquals(1, finishes)
        // Started, but the deadline comes before its claim: the claim fails, it commits nothing.
        lane.clear(); deadline.clear(); finishes = 0
        var committed = false
        admit({ lane += it }, { deadline += it }, { finishes++ }) { h -> deadline.single().run(); if (h.claim()) committed = true; h.finishOnce() }
        lane.single().run()
        assertFalse(committed)
        assertEquals(1, finishes)
        // Claimed, and the deadline comes during its own write: the broadcast is finished only once that write returned.
        lane.clear(); deadline.clear(); finishes = 0
        admit({ lane += it }, { deadline += it }, { finishes++ }) { h ->
            if (h.claim()) {
                deadline.single().run()
                assertEquals("not finished while its write is in progress", 0, finishes)
                committed = true
                h.done()
                assertEquals(1, finishes)
            }
            h.finishOnce()
        }
        lane.single().run()
        assertTrue(committed)
        assertEquals(1, finishes)
    }

    @Test
    fun aWakeOfAnotherConnectionEpochIsDropped() = runTest {
        val (_, token) = enabled() // journaled under connection epoch 0
        app.getSharedPreferences(xyz.rycerz.apofis.CACHE_PREFS, Context.MODE_PRIVATE).edit().putLong(xyz.rycerz.apofis.CONNECTION_EPOCH, 1).commit()
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m1") })
        assertTrue(sinkCalls.isEmpty())
        assertEquals("dropped before the connector: no ACK", listOf("finish"), log.events)
    }

    @Test
    fun aFailedEndpointLeavesOnAndRecovers() = runTest {
        val (r, token) = enabled()
        ctx.base.registrar = r
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) })
        r.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        // ntfy sends a replacement endpoint; it cannot be written. No other event, UI action or restart follows.
        commitsSucceed = false
        scheduled.clear()
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID2); putExtra("id", "m1") })
        assertEquals("On is left at once", PushState.Registering, r.ui.value.state)
        assertEquals("recovery is scheduled", 1, scheduled.size)
        // The scheduled work runs once the disk takes writes again.
        commitsSucceed = true
        assertTrue(r.publishPending())
        assertEquals("""{"v":1,"endpoint":"$VALID2"}""", server.created.last().second)
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
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

    private var epoch = 0L // a stand-in connection epoch for the lock-ordering test

    /** The real wake sink, with a step logged where it hands on, so a death can be placed there. */
    private val wakeSink = object : PushSink {
        override fun onPush(context: Context, epoch: Long) = Wake.onPush(context, epoch)
        override fun recover(context: Context) {
            log.step("wake")
            Wake.recover(context)
            Wake.idle()
        }
    }

    private fun liveWakeJobs() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(WAKE_WORK).get().filter { !it.state.isFinished }

    @Test
    fun deathAfterAck() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val (_, token) = enabled()
        val withWake = object : ContextWrapperWithWake(LogContext(app, log, prefs, wakeSink)) {}
        // MESSAGE: the mark is on disk before the ACK; the process dies after the ACK, before the first schedule.
        log.events.clear()
        log.killAt = log.count() + 2 // ACK, then death at the hand-on
        runCatching {
            PushReceiver().receive(withWake, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m1") },
                finish = { log.step("finish") }, schedule = schedule)
        }
        log.killAt = 0
        Wake.idle()
        assertEquals(listOf("broadcast MESSAGE_ACK to $NTFY_PACKAGE", "death before wake"), log.events.take(2))
        assertEquals(0L, wake.restart().disk[WAKE_PENDING])
        assertTrue("nothing scheduled before the death", liveWakeJobs().isEmpty())
        // Restart: the process start hands the mark on as the wake job (the real worker checks it; WakeWorkerTest).
        recoverPush(object : ContextWrapperWithWake(LogContext(app, log, prefs.restart(), wakeSink)) {}, wakeSink)
        assertEquals(1, liveWakeJobs().size)
        // A mark of the old connection is not handed on to the new one.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val cache = app.getSharedPreferences(xyz.rycerz.apofis.CACHE_PREFS, Context.MODE_PRIVATE)
        cache.edit().putLong(xyz.rycerz.apofis.CONNECTION_EPOCH, 1).commit()
        wakeSink.recover(withWake)
        assertTrue(liveWakeJobs().isEmpty())
        cache.edit().putLong(xyz.rycerz.apofis.CONNECTION_EPOCH, 0).commit()
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
        assertNull(wake.disk[WAKE_PENDING])
        // A sink that throws loses the wake the same way.
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }.also { sinkCommits = true })
        val throwing = LogContext(app, log, prefs, PushSink { _, _ -> throw IllegalStateException("disk") })
        PushReceiver().receive(object : ContextWrapperWithWake(throwing) {}, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, {}, schedule)
        assertEquals(2L, wake.disk[WAKE_LOST])
        val logs = logged()
        assertTrue("lost wake logged", "PushReceiver" in logs && "lost" in logs)
        assertFalse("token not logged", token in logs)
    }

    @Test
    fun theHandOffEndsWhenTheSinkReturnsBeforeTheLossIsCounted() = runTest {
        val (_, token) = enabled()
        wake.onCommit = { log.step("wake commit ${it.sorted()}"); true }
        lateinit var h: Handoff
        val late = LogContext(app, log, prefs, PushSink { _, _ -> h.deadline(); false }) // the deadline passes inside the sink
        h = Handoff { log.step("broadcast finished") }
        log.events.clear()
        PushReceiver().receive(object : ContextWrapperWithWake(late) {}, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, h::finishOnce, schedule, h)
        assertEquals(listOf("broadcast finished", "wake commit [lost]"), log.events.filter { it.startsWith("broadcast finished") || it.startsWith("wake") })
    }

    @Test
    fun aFailedDemandCommitIsCountedLostAndNeverRecovered() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val (_, token) = enabled()
        val real = object : ContextWrapperWithWake(LogContext(app, log, prefs, Wake)) {}
        wake.onCommit = { WAKE_PENDING !in it } // the mark's commit fails; the loss count's own commit works
        PushReceiver().receive(real, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, {}, schedule)
        assertEquals(1L, wake.disk[WAKE_LOST])
        assertNull("the loss count's commit did not persist the failed mark", wake.disk[WAKE_PENDING])
        Wake.recover(real) // a process start now: nothing to hand on
        Wake.idle()
        assertTrue("nothing to recover", WorkManager.getInstance(app).getWorkInfosForUniqueWork(WAKE_WORK).get().isEmpty())
        // The next push works.
        wake.onCommit = { true }
        PushReceiver().receive(real, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, {}, schedule)
        assertEquals(0L, wake.disk[WAKE_PENDING])
        assertEquals(1L, wake.disk[WAKE_LOST])
        Wake.idle()
        assertEquals("its job is scheduled after the hand-off", 1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(WAKE_WORK).get().size)
    }

    @Test
    fun wakeCheckAndCommitAreOneStep() = runTest {
        val (_, token) = enabled()
        val inSink = CountDownLatch(1)
        val release = CountDownLatch(1)
        var epochAtCommit = 0L
        val slow = LogContext(app, log, prefs, PushSink { _, _ ->
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
        assertEquals(0L, epochAtCommit)
        assertEquals(1L, epoch)
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

    // Fix round 4.

    @Test
    fun aRevocationPastTheDeadlineIsLeftForRedelivery() = runTest {
        val (r, token) = enabled()
        log.events.clear(); ctx.sent.clear()
        val late = Handoff {}.also { it.deadline() } // the deadline came first
        PushReceiver().receive(ctx, intent("UNREGISTERED", token), finish = { log.step("finish") }, schedule = schedule, handoff = late)
        assertNull(prefs.disk[Keys.UNREGISTERED_INSTANCE])
        assertEquals("not handed to the connector: the registration is intact", listOf("finish"), log.events)
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) })
        assertEquals(VALID, prefs.getString(Keys.ENDPOINT_NEW, null))
        assertEquals(PushState.Registering, r.ui.value.state)
    }

    @Test
    fun startupNeverRegistersARevokedInstance() = runTest {
        val (_, token) = enabled()
        log.killAt = log.count() + 2 // the revocation commit, then death before the connector's UNREGISTER broadcast
        runCatching { deliver(intent("UNREGISTERED", token)) }
        log.killAt = 0
        ctx.sent.clear()
        val restarted = PushRegistrar(ctx, prefs.restart(), { FakeRecordApi(server) }, { 0L }, { 0L }, backgroundScope, TestPolicy(), RealUnifiedPush)
        restarted.publishPending()
        assertTrue("no REGISTER for the revoked instance", ctx.sent.none { it.action == "org.unifiedpush.android.distributor.REGISTER" })
        assertEquals(PushState.Off, restarted.ui.value.state)
    }

    // Fix round 5.

    @Test
    fun anEarlierUnwrittenChangeIsFlushedBeforeTheRevocationIsClaimed() = runTest {
        val (_, token) = enabled()
        lateinit var h: Handoff
        var finished = 0
        h = Handoff { finished++ }
        var failNext = false
        prefs.onCommit = { keys ->
            log.step("commit ${keys.sorted()}")
            if (Keys.FLUSH in keys) h.deadline() // the deadline comes while an earlier unwritten change is flushed
            !failNext.also { failNext = false }
        }
        // Once the event is being handled, an unrelated registrar write fails: memory holds it, the disk does not.
        val revocation = object : Intent("org.unifiedpush.android.connector.UNREGISTERED") {
            var dirtied = false
            override fun getStringExtra(name: String): String? {
                if (name == "token" && !dirtied) {
                    dirtied = true
                    failNext = true
                    assertFalse(PushStore.commit(prefs) { putString(Keys.PENDING_ID, "apofis-push-x-1") })
                }
                return super.getStringExtra(name)
            }
        }.apply { setPackage(app.packageName); putExtra("token", token) }
        PushReceiver().receive(ctx, revocation, finish = { h.finishOnce() }, schedule = schedule, handoff = h)
        assertTrue("the event was never claimed: left for redelivery", h.abandoned())
        assertNull(prefs.disk[Keys.UNREGISTERED_INSTANCE])
        assertTrue("not handed to the connector", ctx.sent.none { it.action == "org.unifiedpush.android.distributor.UNREGISTER" })
        assertEquals(1, finished)
    }

    @Test
    fun startupWithPushOffReadsOnlyThePushAndWakeState() {
        val read = mutableListOf<String>()
        val counting = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                read += name
                return super.getSharedPreferences(name, mode)
            }
        }
        recoverPush(counting, Wake) // as the app starts: no WorkManager, no database
        Wake.idle() // the wake lane reads the epoch and the mark
        assertEquals(listOf(PUSH_PREFS, xyz.rycerz.apofis.CACHE_PREFS, WAKE_PREFS), read)
    }

    // Closing fixes.

    @Test
    fun aFailedOwnWriteFinishesTheBroadcastAtOnce() = runTest {
        val (_, token) = enabled()
        var finished = 0
        lateinit var h: Handoff
        h = Handoff { finished++ }
        prefs.onCommit = { keys ->
            log.step("commit ${keys.sorted()}")
            if (Keys.ENDPOINT_NEW in keys) {
                h.deadline() // the deadline comes during the event's own write, which then fails
                false
            } else {
                true
            }
        }
        var finishedAtRecovery = -1
        PushReceiver().receive(ctx, intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") },
            finish = { h.finishOnce() }, schedule = { finishedAtRecovery = finished }, handoff = h)
        assertEquals("finished once, before any recovery work", 1, finishedAtRecovery)
        assertEquals(1, finished)
        assertTrue("the failed event is not ACKed", ctx.sent.none { it.action == "org.unifiedpush.android.distributor.MESSAGE_ACK" })
    }

    @Test
    fun aDeliveredWakeCompletesTheTestPush() = runTest {
        val (_, token) = enabled()
        val answering = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok")
                .body("".toResponseBody()).build()
        }.build()
        val test = TestPush({ 0L }, answering) { it }
        assertTrue(test.send("https://ntfy.sh/upAbC123xyz_-9?up=1"))
        val withTest = object : ContextWrapperWithWake(LogContext(app, log, prefs, sink, test)) {}
        PushReceiver().receive(withTest, intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)) }, {}, schedule)
        assertEquals(TestPushResult.WAKE_ARRIVED, test.lastResult.value)
    }
}

/** The stand-in wake job. */
