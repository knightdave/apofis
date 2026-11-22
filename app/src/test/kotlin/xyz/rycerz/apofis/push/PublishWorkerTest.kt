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
        hideFails = true
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertEquals("no false On", PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertTrue("the proof went before the repair", !prefs.contains(Keys.VERIFIED_AT))
        // The local job, queued beside it, succeeds without the server: it proves nothing about the record.
        run(local = true)
        assertEquals("still a server error after local work", PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        // A new process over the same prefs knows it too.
        assertEquals(PushState.ServerError(NTFY_PACKAGE), registrar().ui.value.state)
        ctx.registrar = r
        hideFails = false
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertTrue(server.rows.values.single().hidden)
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun aMissingRowIsRepairedBeforeOnEvenWhenItsEndpointComesLate() = runTest {
        val r = registrar()
        r.on()
        server.rows.clear() // the row is gone on the server
        port.lostRegistration = true // and the next REGISTER gets a new token, whose endpoint has not arrived yet
        now += VERIFY_EVERY_MS
        assertEquals(ListenableWorker.Result.success(), run(local = false)) // waiting for an endpoint is not a retry
        assertTrue("nothing stamped for a row known to be missing", !prefs.contains(Keys.VERIFIED_AT))
        // (a) A record known unusable is a server error, also after the local job and in a new process.
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        run(local = true)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), registrar().ui.value.state)
        ctx.registrar = r
        // Inside the hour, the same URL arrives for the new token.
        val token = registrationOf(prefs)?.token
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, VALID).putString(Keys.ENDPOINT_NEW_TOKEN, token).commit())
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals("recreated", 1, server.rows.size)
        assertTrue("and hidden", server.rows.values.single().hidden)
        assertEquals(server.rows.keys.single(), prefs.getString(Keys.RECORD, null))
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun aReadThatCannotReachTheServerKeepsAProvenOn() = runTest {
        val r = registrar()
        r.on()
        now += VERIFY_EVERY_MS
        api.failWith = xyz.rycerz.apofis.api.ApiError.Network(java.io.IOException("down"))
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertEquals("the last proof stands", PushState.On(NTFY_PACKAGE), r.ui.value.state)
        assertTrue(prefs.contains(Keys.VERIFIED_AT))
        api.failWith = null
        assertEquals(ListenableWorker.Result.success(), run(local = false))
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
        assertEquals("a read that fails proves nothing: unchanged", stamp, prefs.getLong(Keys.VERIFIED_AT, -1))
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

    // Merge review fixes, round 3.

    @Test
    fun aFailedRecreateStaysAServerError() = runTest { // (b)
        val r = registrar()
        r.on()
        server.rows.clear() // the row is gone
        now += VERIFY_EVERY_MS
        api.sendFails = true // the reservation succeeds, the recreate does not
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertTrue(prefs.contains(Keys.PENDING_ID))
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        run(local = true)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), registrar().ui.value.state)
        ctx.registrar = r
        api.sendFails = false
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun anUnreachableReadWithADisplacedTokenChangesNothing() = runTest { // (c)
        val r = registrar()
        r.on()
        port.lostRegistration = true
        now += VERIFY_EVERY_MS
        r.verify(force = true) // re-registers: the new token's endpoint has not arrived yet
        val before = r.ui.value.state
        val stored = prefs.all.toMap()
        api.failWith = xyz.rycerz.apofis.api.ApiError.Network(java.io.IOException("down"))
        r.verify(force = true)
        assertEquals("the state does not change", before, r.ui.value.state)
        assertEquals(PushState.Registering, r.ui.value.state)
        assertEquals(stored - Keys.FLUSH, prefs.all.toMap() - Keys.FLUSH)
    }

    @Test
    fun aFailedHideFromTheUserCheckIsScheduledAndRepaired() = runTest { // (d)
        var scheduled = 0
        var hideFails = false
        current = object : RecordApi by api {
            override suspend fun hide(id: String) = if (hideFails) throw xyz.rycerz.apofis.api.ApiError.Network(java.io.IOException("down")) else api.hide(id)
        }
        val r = PushRegistrar(ctx, prefs, { current }, { 0L }, { now }, backgroundScope, TestPolicy(), flaky) { scheduled++ }.also { ctx.registrar = it }
        r.on()
        server.rows.values.single().hidden = false
        hideFails = true
        scheduled = 0
        r.verify(force = true)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertTrue("work is scheduled", scheduled > 0)
        // Without an API the worker does not report success.
        val withServer = current
        current = null
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        current = withServer
        hideFails = false
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertTrue(server.rows.values.single().hidden)
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    @Test
    fun anUnrenewableRecordIsAServerError() = runTest { // (e)
        val r = registrar()
        r.on()
        prefs.edit().putInt(Keys.SEQ, MAX_SEQ).commit()
        now += ROTATE_AFTER_MS
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        run(local = true)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), registrar().ui.value.state)
    }

    @Test
    fun aStoredEndpointThePolicyRefusesIsAServerError() = runTest { // (f)
        val loopback = "http://127.0.0.1:41234"
        val endpoint = "$loopback/upWIRECHECK012?up=1"
        val r = PushRegistrar(ctx, prefs, { current }, { 0L }, { now }, backgroundScope, TestPolicy(loopback = loopback), flaky).also { ctx.registrar = it }
        r.enableNow()
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, endpoint).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        // The running policy no longer accepts that origin; the token and the URL are unchanged.
        val strict = PushRegistrar(ctx, prefs, { current }, { 0L }, { now }, backgroundScope, TestPolicy(), flaky).also { ctx.registrar = it }
        assertEquals(PushState.ServerError(NTFY_PACKAGE), strict.ui.value.state)
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertEquals(PushState.ServerError(NTFY_PACKAGE), strict.ui.value.state)
    }

    @Test
    fun aFirstPublicationThatCannotReachTheServerIsAServerError() = runTest { // (g)
        val r = registrar()
        r.enableNow()
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, VALID).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
        api.sendFails = true
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertEquals(PushState.ServerError(NTFY_PACKAGE), r.ui.value.state)
        assertEquals(PushState.ServerError(NTFY_PACKAGE), registrar().ui.value.state)
        ctx.registrar = r
        api.sendFails = false
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
    }

    // Final check fixes.

    @Test
    fun aRevocationDuringRefreshDoesNotCrash() = runTest {
        var armed = false
        // The refresh reads the record's endpoint; right after that read, a revocation removes it (and the record).
        val racing = object : android.content.SharedPreferences by prefs {
            override fun getString(key: String, defValue: String?): String? {
                val value = prefs.getString(key, defValue)
                if (armed && key == Keys.ENDPOINT) {
                    armed = false
                    assertTrue(prefs.edit().remove(Keys.ENDPOINT).remove(Keys.RECORD).commit())
                }
                return value
            }
        }
        val r = PushRegistrar(ctx, racing, { current }, { 0L }, { now }, backgroundScope, TestPolicy(), flaky).also { ctx.registrar = it }
        r.enableNow()
        assertTrue(prefs.edit().putString(Keys.ENDPOINT_NEW, VALID).putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit())
        assertEquals(ListenableWorker.Result.success(), run(local = false))
        assertEquals(PushState.On(NTFY_PACKAGE), r.ui.value.state)
        armed = true
        r.refresh() // on the receiver's thread, without the registrar mutex: no exception
        assertTrue(r.ui.value.state in setOf(PushState.On(NTFY_PACKAGE), PushState.ServerError(NTFY_PACKAGE), PushState.Registering))
    }

    @Test
    fun aConnectionThatCannotBeBuiltIsARetry() = runTest {
        val r = registrar()
        r.on()
        val broken = PushRegistrar(ctx, prefs, { throw IllegalStateException("closed") }, { 0L }, { now }, backgroundScope, TestPolicy(), flaky)
            .also { ctx.registrar = it }
        assertEquals(ListenableWorker.Result.retry(), run(local = true))
        assertEquals(ListenableWorker.Result.retry(), run(local = false))
        assertTrue(broken.ui.value.state is PushState)
    }

    @Test
    fun aLeftoverFlagAfterOffIsNoWork() = runTest {
        val r = registrar()
        r.on()
        r.disable()
        assertTrue(prefs.edit().putBoolean(Keys.SERVER_UNDONE, true).commit()) // left over
        current = null
        assertEquals(ListenableWorker.Result.success(), run(local = false))
    }
}
