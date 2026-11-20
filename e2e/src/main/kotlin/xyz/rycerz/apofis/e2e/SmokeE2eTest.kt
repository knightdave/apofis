package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Connects and leaves the Connect screen; sends nothing, so it costs no model run. */
@RunWith(AndroidJUnit4::class)
class SmokeE2eTest {
    private val app = AppDriver()

    /** Through adb reverse to the device's loopback, which needs no local network access. */
    @Test
    fun connectViaLoopback() {
        app.freshStart()
        app.connect(app.url)
        app.screenshot("smoke-loopback")
        // The new chat exists only on the phone so far: exercises the session bookkeeping without a model run.
        val sessions = app.appSessions()
        if (sessions.size != 1) fail("Expected the one new local chat in the app's database, found $sessions")
        app.record(sessions.map { it.first })
        sessions.forEach { (id, _) -> app.log("cleanup ${app.deleteSession(id, listOf("Automated app test"))}") }
    }

    /** To the emulator's host alias 10.0.2.2, a private address, with local network access granted through adb. */
    @Test
    fun connectViaPrivateAddress() {
        app.freshStart()
        app.connect(app.directUrl)
        app.screenshot("smoke-private-address")
    }

    /** Without the grant the app asks for it, explains why, shows a field error when refused and connects once granted. */
    @Test
    fun privateAddressAsksForLocalNetwork() {
        app.freshStart(grantLocalNetwork = false)
        app.fillConnect(app.directUrl)
        app.click(app.str("connect_button"))
        app.waitFor("the local network rationale while the system asks", 15_000) { app.has(app.str("connect_local_network_rationale")) }
        app.log("asking for local network access; screen: ${app.screen()}")
        app.device.pressBack() // dismissing the system dialog refuses
        app.waitFor("the refusal as a field error", 15_000) { app.has(app.str("connect_error_local_network_denied")) }
        app.grantLocalNetwork()
        app.click(app.str("connect_button"))
        app.awaitChat(app.directUrl)
        app.screenshot("smoke-local-network-granted")
    }
}
