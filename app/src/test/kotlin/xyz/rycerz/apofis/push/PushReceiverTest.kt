package xyz.rycerz.apofis.push

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeoutException
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
    private val prefs = HookedPrefs(pushPrefs(app)) { log.step("commit ${it.sorted()}"); commitsSucceed }
    private val wake = app.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
    private var sinkCommits = true
    private val sinkCalls = mutableListOf<Context>()

    // Stands in for Task 4's sink: a checked commit of the wake demand.
    private val sink = PushSink { c ->
        sinkCalls += c
        log.step("demand")
        sinkCommits && wake.edit().putLong(WAKE_DEMAND, wake.getLong(WAKE_DEMAND, 0) + 1).commit()
    }
    private val ctx = LogContext(app, log, prefs, sink)
    private val enqueued = mutableListOf<Context>()
    private var enqueue: (Context) -> Unit = { log.step("enqueue"); enqueued += it }
    private val server = FakeServer("https://a.example/")

    @Before
    fun setUp() {
        resetConnectorStore()
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
        assertEquals(instance, prefs.let { "g" + it.getLong(Keys.GENERATION, 0) })
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
        PushReceiver().receive(ctx, i, finish = { log.step("finish") }, enqueue = enqueue)
    }

    private fun logged() = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable}" }

    @Test
    fun durableBeforeAck() = runTest {
        val (_, token) = enabled()
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") })
        assertEquals(
            listOf("commit [endpoint_new, endpoint_new_instance]", "broadcast MESSAGE_ACK to $NTFY_PACKAGE", "enqueue", "finish"),
            log.events,
        )
        assertEquals(VALID, prefs.getString(Keys.ENDPOINT_NEW, null))

        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1, 2)); putExtra("id", "m2") })
        assertEquals(listOf("demand", "broadcast MESSAGE_ACK to $NTFY_PACKAGE", "finish"), log.events)
        assertEquals(1L, wake.getLong(WAKE_DEMAND, 0))
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
        val test = TestPush({ 0L }, okhttp3.OkHttpClient()) { null }
        val withTest = LogContext(app, log, prefs, sink, test)
        PushReceiver().receive(withTest, message, finish = {}, enqueue = enqueue)
        assertEquals("only the connector reads the bytes, once", 1, reads)
        assertEquals(1, sinkCalls.size) // PushSink.onPush(context) and TestPush.onWake() take no body at all
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun commitFailure() = runTest {
        val (_, token) = enabled()
        commitsSucceed = false
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) })
        assertTrue(enqueued.isEmpty())
        assertEquals("finish", log.events.last())
        assertNull(prefs.getString(Keys.ENDPOINT_NEW, null))
        val logs = logged()
        assertTrue(logs, "PushReceiver" in logs)
        assertFalse(logs, "upAbC123xyz" in logs || token in logs)
    }

    @Test
    fun foreignReplacement() = runTest {
        for (action in listOf("UNREGISTERED", "TEMP_UNAVAILABLE")) {
            for (token in listOf(null, "not-a-token")) {
                resetConnectorStore()
                val (r, known) = enabled()
                val before = prefs.all.toMap()
                deliver(intent(action, token) { putExtra("newDistributor", OTHER) })
                assertEquals("$action $token", before, prefs.all.toMap())
                assertEquals(emptyList<Intent>(), ctx.sent)
                assertTrue(enqueued.isEmpty())
                assertEquals(NTFY_PACKAGE, RealUnifiedPush.saved(ctx))
                // The live registration still works.
                deliver(intent("NEW_ENDPOINT", known) { putExtra("endpoint", VALID) })
                assertEquals(VALID, prefs.getString(Keys.ENDPOINT_NEW, null))
                r.disable()
                enqueued.clear()
            }
        }
        // Known token, TEMP_UNAVAILABLE: a no-op, no fallback added.
        resetConnectorStore()
        val (r, token) = enabled()
        val before = prefs.all.toMap()
        deliver(intent("TEMP_UNAVAILABLE", token) { putExtra("newDistributor", OTHER) })
        assertEquals(before, prefs.all.toMap())
        assertEquals(emptyList<Intent>(), ctx.sent)
        assertTrue(enqueued.isEmpty())
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) })
        assertTrue(ctx.sent.none { it.`package` == OTHER })
        r.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)

        // Known token, UNREGISTERED: a plain unregister; nothing ever goes to the foreign package.
        val record = prefs.getString(Keys.RECORD, null)!!
        enqueued.clear()
        deliver(intent("UNREGISTERED", token) { putExtra("newDistributor", OTHER) })
        assertTrue(ctx.sent.none { it.`package` == OTHER })
        assertEquals(listOf("org.unifiedpush.android.distributor.UNREGISTER" to NTFY_PACKAGE), ctx.sent.map { it.action to it.`package` })
        assertEquals(1, enqueued.size)
        r.publishPending()
        assertEquals(PushState.Off, r.ui.value.state)
        assertEquals(listOf(record), server.deleted)
        assertNull(RealUnifiedPush.saved(ctx))
    }

    @Test
    fun oldInstanceIgnored() = runTest {
        val r = registrar()
        r.enableNow()
        r.disable()
        r.enableNow()
        r.disable()
        r.enableNow() // g5: g1 and g3 were unregistered by disable; register g3 again to have a live old token
        val live = tokenOf("g5")
        prefs.edit().putLong(Keys.GENERATION, 3).commit()
        RealUnifiedPush.register(ctx, "g3")
        val old = tokenOf("g3")
        prefs.edit().putLong(Keys.GENERATION, 5).commit()

        deliver(intent("NEW_ENDPOINT", old) { putExtra("endpoint", VALID) })
        assertNull(prefs.getString(Keys.ENDPOINT_NEW, null))
        assertTrue(enqueued.isEmpty())
        val unregister = ctx.sent.single { it.action == "org.unifiedpush.android.distributor.UNREGISTER" }
        assertEquals(old, unregister.getStringExtra("token"))
        // g3 is gone, g5 still works.
        deliver(intent("NEW_ENDPOINT", old) { putExtra("endpoint", VALID) })
        assertEquals(listOf("finish"), log.events)
        deliver(intent("NEW_ENDPOINT", live) { putExtra("endpoint", VALID) })
        assertEquals("g5", prefs.getString(Keys.ENDPOINT_NEW_INSTANCE, null))
    }

    // Step 2c (I5).

    @Test
    fun blockedEnqueue() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val (_, token) = enabled()
        enqueue = { log.step("enqueue"); throw TimeoutException() } // WorkManager did not accept the work within its bound
        deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") })
        assertEquals(
            listOf("commit [endpoint_new, endpoint_new_instance]", "broadcast MESSAGE_ACK to $NTFY_PACKAGE", "enqueue", "finish"),
            log.events,
        )
        // A later process start finds the durable endpoint and enqueues the publish.
        PublishWorker.recover(app)
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.NAME).get().size)
    }

    @Test
    fun deathAfterAck() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val (_, token) = enabled()
        enqueue = { log.step("enqueue"); throw Death() }
        runCatching { deliver(intent("NEW_ENDPOINT", token) { putExtra("endpoint", VALID); putExtra("id", "m1") }) }
        assertTrue(log.events.indexOf("broadcast MESSAGE_ACK to $NTFY_PACKAGE") > log.events.indexOf("commit [endpoint_new, endpoint_new_instance]"))
        PublishWorker.recover(app)
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.NAME).get().size)
        val restarted = registrar()
        restarted.publishPending()
        assertEquals(PushState.On(NTFY_PACKAGE), restarted.ui.value.state)
        assertEquals(1, server.created.size)
        // Nothing left to do: the next start enqueues nothing.
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        PublishWorker.recover(app)
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.NAME).get().isEmpty())
    }

    @Test
    fun demandCommitFails() = runTest {
        val (_, token) = enabled()
        sinkCommits = false
        deliver(intent("MESSAGE", token) { putExtra("bytesMessage", byteArrayOf(1)); putExtra("id", "m3") })
        assertEquals(1L, wake.getLong(WAKE_LOST, 0))
        assertEquals(0L, wake.getLong(WAKE_DEMAND, 0))
        val logs = logged()
        assertTrue(logs, "PushReceiver" in logs && "lost" in logs)
        assertFalse(logs, token in logs)
    }

    @Test
    fun registrationFailedCallback() = runTest {
        val (r, token) = enabled()
        assertEquals(PushState.Registering, r.ui.value.state)
        deliver(intent("REGISTRATION_FAILED", token) { putExtra("reason", "NETWORK") })
        assertEquals(1, enqueued.size)
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
