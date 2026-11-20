package xyz.rycerz.apofis

import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import xyz.rycerz.apofis.api.HoldingQueue
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.ui.connect.ConnectState
import xyz.rycerz.apofis.ui.connect.ConnectViewModel

@RunWith(RobolectricTestRunner::class)
class BackAfterRecreateTest {
    private val app = ApplicationProvider.getApplicationContext<ApofisApp>()

    private fun until(what: String, check: () -> Boolean) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for $what")
    }

    @Test
    fun firstRunBackAfterRotationCancelsTheCheck() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val server = MockWebServer()
        val holding = HoldingQueue()
        server.dispatcher = holding
        val response = holding.hold(MockResponse.Builder().code(200).body(fixture("capabilities.json")).build())
        server.start()
        try {
            // Without animations: the spinner's endless animation would keep every idle wait busy until the check ended.
            Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val back = app.container.connectBack
            until("the Connect screen") { back.target != null }
            val vm: ConnectViewModel = back.target!!
            vm.onUrlChange(server.url("/").toString())
            vm.onKeyChange("sk-1")
            vm.connect()
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS) != null)

            controller.recreate() // rotation while the check runs
            assertEquals(ConnectState.Testing, vm.state)
            assertTrue(back.target === vm) // the same retained view model
            controller.get().onBackPressedDispatcher.onBackPressed()

            assertEquals(ConnectState.Idle, vm.state)
            assertFalse(back.busy.value)
            response.countDown()
            Thread.sleep(3_500) // past the moment the response arrives
            until("the late result to be dropped") { true }
            assertEquals(ConnectState.Idle, vm.state)
            assertNull(app.container.credentials.load())
            assertNull(app.container.connection.value)
            controller.pause().stop().destroy()
            holding.verify()
        } finally {
            server.close()
        }
    }
}
