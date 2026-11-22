package xyz.rycerz.apofis.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DAY = 24 * 3_600_000L

/** The worker itself: what it runs and the Result it reports. */
@RunWith(RobolectricTestRunner::class)
class PublishWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = FakeServer("https://a.example/")
    private val api = FakeRecordApi(server)
    private var current: RecordApi? = api
    private var now = 1_000_000_000L
    private val prefs = FakePrefs(mutableMapOf(Keys.INSTALL_ID to "0b0e9c1e-2f4a-4c55-9d1e-7a3b5c6d7e8f"))
    private var throwing = false
    private val port = FakePort()
    private val flaky = object : UnifiedPushPort by port {
        override fun unregister(c: android.content.Context, r: Registration, connector: Boolean) =
            if (throwing) throw IllegalStateException("broadcast failed") else port.unregister(c, r, connector)
    }
    private val ctx = LogContext(app, EventLog(), prefs)

    private fun TestScope.registrar() =
        PushRegistrar(ctx, prefs, { current }, { 0L }, { now }, backgroundScope, TestPolicy(), flaky).also { ctx.registrar = it }

    private suspend fun run(local: Boolean): ListenableWorker.Result =
        TestListenableWorkerBuilder.from(ctx, PublishWorker::class.java).setInputData(workDataOf("local" to local)).build().doWork()

    private suspend fun PushRegistrar.on() {
        enableNow()
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, VALID).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(PushState.On(NTFY_PACKAGE), ui.value.state)
    }

    @Test
    fun theLocalJobRetriesUntilTheUnregisterWasSent() = runTest {
        val r = registrar()
        r.on()
        throwing = true
        r.disable()
        assertEquals(ListenableWorker.Result.retry(), run(local = true))
        throwing = false
        assertEquals(ListenableWorker.Result.success(), run(local = true))
        assertTrue(!prefs.contains(Keys.REVOKE))
    }

    @Test
    fun theNetworkJobRetriesWithoutAConnection() = runTest {
        val r = registrar()
        r.enableNow()
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, VALID).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
        current = null
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        current = api
        assertEquals(ListenableWorker.Result.success(), run(local = false))
    }

    @Test
    fun theRecordIsRenewedThroughTheWorkerAlone() = runTest {
        for ((days, renewed) in listOf(29 to false, 30 to true, 31 to true)) {
            val s = FakeServer("https://a.example/")
            current = FakeRecordApi(s)
            prefs.edit().clear().putString(Keys.INSTALL_ID, "0b0e9c1e-2f4a-4c55-9d1e-7a3b5c6d7e8f").commit()
            now = 1_000_000_000L
            val r = registrar()
            r.on()
            now += days * DAY
            assertEquals(ListenableWorker.Result.success(), run(local = false))
            assertEquals("after $days days", if (renewed) 2 else 1, s.created.size)
            assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun aRecordThatVanishedIsReplacedByTheWorker() = runTest {
        val r = registrar()
        r.on()
        server.rows.clear() // removed on the server by hand
        now += VERIFY_EVERY_MS
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(1, server.rows.size)
        assertEquals(server.rows.keys.single(), prefs.getString(Keys.RECORD, null))
    }

    // Merge review fixes.

    @Test
    fun aRegisterReplyDoesNotCancelTheVerifierThatAskedForIt() = runTest {
        // The real connector, so its REGISTER reply goes through the real receiver callback.
        resetConnectorStore(app)
        installDistributor(app, NTFY_PACKAGE)
        val r = PushRegistrar(ctx, prefs, { current }, { 0L }, { now }, backgroundScope, TestPolicy(), RealUnifiedPush).also { ctx.registrar = it }
        r.enableNow()
        val token = ctx.sent.last { it.action == "org.unifiedpush.android.distributor.REGISTER" }.getStringExtra("token")!!
        fun reply(schedule: (android.content.Context) -> Unit) = PushReceiver().receive(ctx,
            android.content.Intent("org.unifiedpush.android.connector.NEW_ENDPOINT").setPackage(app.packageName)
                .putExtra("token", token).putExtra("endpoint", VALID), finish = {}, schedule = schedule)
        reply {}
        assertTrue(r.publishPending())
        now += 30 * DAY // a renewal and a verification are due
        ctx.sent.clear()
        var replaced = 0
        lateinit var job: kotlinx.coroutines.Job
        var result: ListenableWorker.Result? = null
        var delivered = false
        api.beforeCall = { name ->
            // While the verifier's server request is in flight, ntfy answers its REGISTER with the same endpoint.
            // Scheduling the network job again would REPLACE (cancel) this running one.
            if (name.startsWith("read") && !delivered) {
                delivered = true
                reply { replaced++; job.cancel() }
            }
        }
        job = launch(kotlinx.coroutines.Dispatchers.IO) { result = run(local = false) }
        job.join()
        assertEquals("the reply did not replace the verifier", 0, replaced)
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("stamped", prefs.contains(Keys.VERIFIED_AT))
        assertEquals("renewed", 2, server.created.size)
        assertEquals("one REGISTER for one verification", 1, ctx.sent.count { it.action == "org.unifiedpush.android.distributor.REGISTER" })
    }

    @Test
    fun aFailedHideIsRetriedNotReportedAsOn() = runTest {
        var hideFails = false
        current = object : RecordApi by api {
            override suspend fun hide(id: String) = if (hideFails) throw xyz.rycerz.apofis.api.ApiError.Network(java.io.IOException("down")) else api.hide(id)
        }
        val r = registrar()
        r.on()
        server.rows.values.single().hidden = false // the record became visible on the server
        now += VERIFY_EVERY_MS // a verification is due
        val stamp = prefs.getLong(Keys.VERIFIED_AT, -1)
        hideFails = true
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertEquals("no false On", PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertEquals("not stamped", stamp, prefs.getLong(Keys.VERIFIED_AT, -1))
        hideFails = false
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertTrue(server.rows.values.single().hidden)
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun aVanishedRecordWhoseReadFailsIsRetried() = runTest {
        val r = registrar()
        r.on()
        server.rows.clear()
        now += VERIFY_EVERY_MS // a verification is due
        val stamp = prefs.getLong(Keys.VERIFIED_AT, -1)
        api.failWith = xyz.rycerz.apofis.api.ApiError.Network(java.io.IOException("down"))
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertTrue("no false On", r.ui.value.state != PushState.On(NTFY_PACKAGE))
        assertEquals("not stamped", stamp, prefs.getLong(Keys.VERIFIED_AT, -1))
        api.failWith = null
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(1, server.rows.size)
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun aColdVerificationSendsOneRegister() = runTest {
        registrar().on()
        val cold = registrar() // a new process: its first operation registers once
        port.calls.clear()
        now += VERIFY_EVERY_MS
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(1, port.calls.count { it.startsWith("register") })
        assertEquals(PushState.On(NTFY_PACKAGE), cold.ui.value.state)
    }
}
