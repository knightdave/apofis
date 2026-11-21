package xyz.rycerz.apofis.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
}
