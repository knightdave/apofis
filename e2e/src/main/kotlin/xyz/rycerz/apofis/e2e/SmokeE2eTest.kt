package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Connects and leaves the Connect screen; sends nothing, so it costs no model run and creates no server session. */
@RunWith(AndroidJUnit4::class)
class SmokeE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

    /** Through adb reverse to the device's loopback, which needs no local network access. */
    @Test
    fun connectViaLoopback() {
        app.freshStart()
        app.connect(app.url)
        app.screenshot(name.methodName)
    }

    /** To the emulator's host alias 10.0.2.2, a private address, with local network access granted through adb. */
    @Test
    fun connectViaPrivateAddress() {
        app.freshStart()
        app.connect(app.directUrl)
        app.screenshot(name.methodName)
    }

    /**
     * Without the grant, a literal private address asks first and shows why. A refusal does not stop the attempt; it
     * fails (the platform blocks it), and the error offers "Allow local network access and retry", which connects
     * once access is granted.
     */
    @Test
    fun privateAddressAsksForLocalNetwork() {
        app.freshStart(grantLocalNetwork = false)
        app.fillConnect(app.directUrl)
        app.click(app.str("connect_button"))
        app.waitFor("the local network rationale while the system asks", 15_000) { app.has(app.str("connect_local_network_rationale")) }
        app.log("asking for local network access; screen: ${app.screen()}")
        app.device.pressBack() // dismissing the system dialog refuses
        app.waitFor("the failed attempt offering local network access", 40_000) {
            app.has(app.str("connect_error_unreachable_local_network")) && app.has(app.str("connect_local_network_retry"))
        }
        app.grantLocalNetwork()
        app.click(app.str("connect_local_network_retry")) // already granted: the request returns at once, then retries
        app.awaitChat(app.directUrl)
        app.screenshot(name.methodName)
    }
}
