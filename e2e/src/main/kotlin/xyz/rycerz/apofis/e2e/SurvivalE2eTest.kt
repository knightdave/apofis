package xyz.rycerz.apofis.e2e

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * The milestone's survival scenarios against the real Hermes server (each costs one model run). Every scenario starts
 * from cleared app data, connects to the emulator's host alias (a private address, so local network access is
 * granted through adb), uses the new chat the app opens, asserts on visible text and saves a screenshot. Afterwards
 * the sessions the app created are recorded for run-e2e.sh and deleted through the API.
 */
@RunWith(AndroidJUnit4::class)
class SurvivalE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val failureShot = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) = app.screenshot("failed-${description.methodName}")
    }

    private val reply = 300_000L // a slow model reply, including a 60 s tool call
    private val toolStep = 180_000L

    @Before
    fun start() {
        app.log("scenario ${name.methodName} starts")
        app.freshStart()
        app.connect(app.directUrl)
    }

    @After
    fun cleanUp() {
        app.airplane(false)
        val sessions = app.appSessions()
        app.record(sessions.map { it.first })
        sessions.forEach { (id, _) -> app.log("cleanup ${app.deleteSession(id, TITLES)}") }
        app.log("scenario ${name.methodName} ends")
    }

    @Test
    fun connectAndChat() {
        val prompt = "Reply with exactly: apofis-e2e-1"
        app.send(prompt)
        awaitReply("apofis-e2e-1", prompt)
        assertOnce(prompt, "apofis-e2e-1")
        app.screenshot("1-connectAndChat")
    }

    @Test
    fun replySurvivesAirplaneMode() {
        val (prompt, command) = longRun(25, 2)
        app.send(prompt)
        awaitToolStep(command)
        val cut = SystemClock.uptimeMillis()
        app.airplane(true)
        // The interruption must be real: the app itself has to see the network gone.
        app.waitFor("the status line showing Offline or Can't reach server after airplane mode", 30_000) {
            app.has(app.str("chat_status_offline")) || app.has(app.str("chat_status_unreachable"))
        }
        app.log("app sees the loss: ${app.screen()}")
        app.screenshot("2-airplane-offline")
        SystemClock.sleep((30_000 - (SystemClock.uptimeMillis() - cut)).coerceAtLeast(0))
        app.airplane(false)
        awaitReply("apofis-e2e-2", prompt, command)
        assertOnce(prompt, "apofis-e2e-2")
        app.screenshot("2-replySurvivesAirplaneMode")
    }

    @Test
    fun replySurvivesScreenOff() {
        val (prompt, command) = longRun(25, 3)
        app.send(prompt)
        awaitToolStep(command)
        app.shell("input keyevent KEYCODE_SLEEP")
        app.waitFor("the screen off", 10_000) { wakefulness() != "Awake" }
        app.log("screen off: ${wakefulness()}")
        SystemClock.sleep(40_000)
        app.shell("input keyevent KEYCODE_WAKEUP")
        app.shell("wm dismiss-keyguard")
        app.waitFor("the screen on and the app in front", 20_000) { wakefulness() == "Awake" && app.device.currentPackageName == APP }
        awaitReply("apofis-e2e-3", prompt, command)
        assertOnce(prompt, "apofis-e2e-3")
        app.screenshot("3-replySurvivesScreenOff")
    }

    @Test
    fun replySurvivesProcessDeath() {
        val (prompt, command) = longRun(25, 4)
        app.send(prompt)
        awaitToolStep(command)
        app.forceStop()
        app.waitFor("the app process gone after force-stop", 10_000) { !app.appRunning() }
        app.log("app process killed")
        SystemClock.sleep(30_000)
        app.launch()
        awaitReply("apofis-e2e-4", prompt, command)
        SystemClock.sleep(3_000) // a duplicate would show up right after the first copy
        assertOnce(prompt, "apofis-e2e-4")
        app.screenshot("4-replySurvivesProcessDeath")
    }

    @Test
    fun offlineMessageSendsItself() {
        app.airplane(true)
        app.waitFor("the status line showing Offline after airplane mode", 30_000) { app.has(app.str("chat_status_offline")) }
        val prompt = "Reply with exactly: apofis-e2e-5"
        app.send(prompt)
        app.waitFor("the message bubble waiting to send", 15_000) { app.has(prompt) && app.has(app.str("chat_queued")) }
        // Stays offline long enough that a send that ignored the network would have failed or gone out by now.
        SystemClock.sleep(15_000)
        if (!app.has(prompt) || !app.has(app.str("chat_queued")) || !app.has(app.str("chat_status_offline"))) {
            fail("After 15 s offline the message should still be waiting and the status Offline. Screen: ${app.screen()}")
        }
        app.screenshot("5-offline-waiting")
        app.airplane(false)
        awaitReply("apofis-e2e-5", prompt, timeoutMs = 60_000)
        assertOnce(prompt, "apofis-e2e-5")
        app.screenshot("5-offlineMessageSendsItself")
    }

    @Test
    fun steerDuringRun() {
        val (prompt, command) = longRun(20, 6)
        val steering = "End your reply with the word BANANA"
        app.send(prompt)
        awaitToolStep(command)
        app.send(steering, button = app.str("chat_steer"))
        app.waitFor("the steering text as a user bubble", 30_000) { app.has(steering) }
        app.waitFor("a finished reply ending with BANANA", reply, meanwhile = { app.approveIfAsked(command) }) {
            idle() && replyTexts(listOf(prompt, steering)) { true }.any { BANANA.containsMatchIn(it) }
        }
        app.log("replies: ${replyTexts(listOf(prompt, steering)) { true }}")
        if (!app.has(steering)) fail("The steering bubble is gone after the reply. Screen: ${app.screen()}")
        app.screenshot("6-steerDuringRun")
    }

    @Test
    fun stopDuringRun() {
        val (prompt, command) = longRun(60, 7)
        app.send(prompt)
        awaitToolStep(command)
        app.click(app.str("chat_stop"))
        app.waitFor("the Stopped notice and the composer back in send mode", 60_000) { app.has(app.str("chat_outcome_stopped")) && idle() }
        app.screenshot("7-stopDuringRun")
    }

    // ---- helpers

    private fun longRun(seconds: Int, n: Int): Pair<String, String> {
        val command = "sleep $seconds; echo apofis-e2e-$n"
        return "Automated app test. Use the terminal tool to run exactly: $command . Then reply with one short sentence that includes the marker." to command
    }

    private fun wakefulness() = Regex("mWakefulness=(\\w+)").find(app.shell("dumpsys power"))?.groupValues?.get(1)

    /** The composer is back to Send: no run is going on. */
    private fun idle() = app.hasDesc(app.str("chat_send")) && !app.hasDesc(app.str("chat_stop"))

    /** Message texts below the app bar that are neither the user's own ([own]) nor a tool row (a button) nor a field. */
    private fun replyTexts(own: List<String>, match: (String) -> Boolean): List<String> {
        val bar = app.appBarBottom()
        return app.nodes { n: AccessibilityNodeInfo ->
            val t = n.text?.toString() ?: return@nodes false
            // "command=..." is a terminal step's argument preview, not the reply.
            !n.isClickable && n.className != "android.widget.EditText" && t !in own && "command=" !in t && match(t) && app.inContent(n, bar)
        }.map { it.text.toString() }
    }

    private fun awaitToolStep(command: String) = app.waitFor("the running terminal step for \"$command\"", toolStep, meanwhile = { app.approveIfAsked(command) }) {
        app.hasState(app.str("chat_tool_running"))
    }.also { app.log("tool step running") }

    private fun awaitReply(marker: String, prompt: String, command: String? = null, timeoutMs: Long = reply) =
        app.waitFor("a finished reply containing $marker", timeoutMs, meanwhile = { command?.let(app::approveIfAsked) }) {
            idle() && replyTexts(listOf(prompt)) { marker in it }.isNotEmpty()
        }.also { app.log("reply: ${replyTexts(listOf(prompt)) { marker in it }}") }

    /** The user's message and the reply each appear exactly once. */
    private fun assertOnce(prompt: String, marker: String) {
        val bar = app.appBarBottom()
        val prompts = app.nodes { it.text?.toString() == prompt && app.inContent(it, bar) }.size
        val replies = replyTexts(listOf(prompt)) { marker in it }
        if (prompts != 1 || replies.size != 1) fail("Expected the message and the reply once each, found $prompts and ${replies.size}: $replies")
    }

    private companion object {
        val TITLES = listOf("Automated app test", "Reply with exactly: apofis-e2e")
        val BANANA = Regex("BANANA\\W*$")
    }
}
