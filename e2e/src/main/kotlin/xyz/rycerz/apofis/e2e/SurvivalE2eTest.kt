package xyz.rycerz.apofis.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * The milestone's survival scenarios against the real Hermes server (each costs one model run). Every scenario starts
 * from cleared app data, connects to the emulator's host alias (a private address, so local network access is
 * granted through adb), journals the new chat's id before sending, asserts on the screen and on the server's record
 * of that chat, and saves a screenshot. run-e2e.sh deletes the journaled sessions after checking each one's identity.
 */
@RunWith(AndroidJUnit4::class)
class SurvivalE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

    private val reply = 300_000L // a slow model reply, including a 60 s tool call
    private val toolStep = 180_000L
    private lateinit var session: String

    @Before
    fun start() {
        app.log("scenario ${name.methodName} starts")
        app.freshStart()
        session = app.connect(app.directUrl)
    }

    @After
    fun cleanUp() {
        app.airplane(false)
        // A compressed chat gets new ids: journal those too (already journaled ids are skipped).
        app.appSessionIds().filter { it.startsWith("apofis-") }.forEach(app::journal)
        app.log("scenario ${name.methodName} ends")
    }

    @Test
    fun connectAndChat() {
        val marker = "apofis-e2e-1"
        val prompt = "Test ${app.nonce}-1. Reply with exactly: $marker"
        app.send(prompt)
        awaitReply(marker, prompt)
        assertOnce(prompt, marker)
        app.screenshot(name.methodName)
    }

    @Test
    fun replySurvivesAirplaneMode() {
        val (prompt, command, marker) = longRun(25, 2)
        app.send(prompt)
        awaitToolStep(command)
        val cut = SystemClock.uptimeMillis()
        app.airplane(true)
        // Proves a real interruption: the device's airplane mode is on and the app itself reports being offline
        // (not a generic "can't reach server", which other faults also produce).
        app.waitFor("airplane mode on and the status line showing Offline", 30_000) { app.airplaneOn() && app.has(app.str("chat_status_offline")) }
        app.log("app sees the loss: ${app.screen()}")
        app.screenshot("2-airplane-offline")
        SystemClock.sleep((30_000 - (SystemClock.uptimeMillis() - cut)).coerceAtLeast(0))
        app.airplane(false)
        app.waitFor("airplane mode off", 10_000) { !app.airplaneOn() }
        awaitReply(marker, prompt, command)
        assertOnce(prompt, marker, command)
        app.screenshot(name.methodName)
    }

    @Test
    fun replySurvivesScreenOff() {
        val (prompt, command, marker) = longRun(25, 3)
        app.send(prompt)
        awaitToolStep(command)
        // Proves the starting state was read: the screen is reported awake before it is turned off.
        if (wakefulness() != "Awake") fail("The screen was not awake before the test turned it off")
        app.shell("input keyevent KEYCODE_SLEEP")
        // Proves the screen really went off: wakefulness is read (unreadable fails) and names a not-awake state.
        app.waitFor("the screen off (Asleep or Dozing)", 10_000) { wakefulness() in setOf("Asleep", "Dozing") }
        app.log("screen off: ${wakefulness()}")
        SystemClock.sleep(40_000)
        app.shell("input keyevent KEYCODE_WAKEUP")
        app.shell("wm dismiss-keyguard")
        app.waitFor("the screen on and the app in front", 20_000) { wakefulness() == "Awake" && app.device.currentPackageName == APP }
        awaitReply(marker, prompt, command)
        assertOnce(prompt, marker, command)
        app.screenshot(name.methodName)
    }

    @Test
    fun replySurvivesProcessDeath() {
        val (prompt, command, marker) = longRun(25, 4)
        app.send(prompt)
        awaitToolStep(command)
        // Proves the query works and names the process: pidof succeeds (exit 0) with one numeric pid.
        val (rc, pid) = app.pidof()
        if (rc != 0 || !Regex("\\d+").matches(pid)) fail("Could not read the app's pid before killing it (exit $rc, \"$pid\")")
        app.forceStop()
        // Proves the process is gone: pidof ran and found nothing (exit 1, empty), and that pid's /proc entry is gone.
        app.waitFor("the app process $pid gone after force-stop", 10_000) {
            val (after, pids) = app.pidof()
            after == 1 && pids.isEmpty() && app.processGone(pid)
        }
        app.log("app process $pid killed")
        SystemClock.sleep(30_000)
        app.launch()
        awaitReply(marker, prompt, command)
        SystemClock.sleep(3_000) // a duplicate would show up right after the first copy
        assertOnce(prompt, marker, command)
        app.screenshot(name.methodName)
    }

    @Test
    fun offlineMessageSendsItself() {
        app.airplane(true)
        // Proves the device is offline before sending: airplane mode is on and the app says Offline.
        app.waitFor("airplane mode on and the status line showing Offline", 30_000) { app.airplaneOn() && app.has(app.str("chat_status_offline")) }
        val marker = "apofis-e2e-5"
        val prompt = "Test ${app.nonce}-5. Reply with exactly: $marker"
        app.send(prompt)
        app.waitFor("the message bubble waiting to send", 15_000) { app.has(prompt) && app.has(app.str("chat_queued")) }
        // Stays offline long enough that a send that ignored the network would have failed or gone out by now.
        SystemClock.sleep(15_000)
        if (!app.airplaneOn() || !app.has(prompt) || !app.has(app.str("chat_queued")) || !app.has(app.str("chat_status_offline"))) {
            fail("After 15 s offline the message should still be waiting and the status Offline. Screen: ${app.screen()}")
        }
        app.screenshot("5-offline-waiting")
        app.airplane(false)
        app.waitFor("airplane mode off", 10_000) { !app.airplaneOn() }
        awaitReply(marker, prompt, timeoutMs = 60_000)
        assertOnce(prompt, marker)
        app.screenshot(name.methodName)
    }

    @Test
    fun steerDuringRun() {
        val (prompt, command, _) = longRun(20, 6)
        val steering = "End your reply with the word BANANA"
        app.send(prompt)
        awaitToolStep(command)
        app.send(steering, button = app.str("chat_steer"))
        app.waitFor("the steering text as a user bubble", 30_000) { app.has(steering) }
        // Proves the steer changed the reply: the turn's reply paragraph (below the prompt) ends with BANANA.
        app.waitFor("a finished reply ending with BANANA", reply, meanwhile = { app.answerApproval(command) }) {
            idle() && lastReply(prompt)?.let { BANANA.containsMatchIn(it) } == true
        }
        if (app.snapshot().bubbles(steering).size != 1) fail("The steering bubble is not shown once after the reply. Screen: ${app.screen()}")
        // Proves the server did it: the command ran, the steer reached the run, and the stored final answer ends with BANANA.
        serverAgrees("the command, the steer and a final answer ending with BANANA") { rows ->
            command in terminalCommands(rows) && rows.any { steering in (it.str("content") ?: "") } &&
                finals(rows).lastOrNull()?.let { BANANA.containsMatchIn(it) } == true
        }
        app.screenshot(name.methodName)
    }

    @Test
    fun stopDuringRun() {
        val (prompt, command, _) = longRun(60, 7)
        app.send(prompt)
        awaitToolStep(command)
        app.click(app.str("chat_stop"))
        app.waitFor("the Stopped notice and the composer back in send mode", 60_000) { app.has(app.str("chat_outcome_stopped")) && idle() }
        // Proves the stop reached the server: the scenario's command ran there and its tool result says interrupted.
        serverAgrees("the command called and its result interrupted") { rows ->
            command in terminalCommands(rows) && rows.any { it.str("role") == "tool" && "interrupted" in (it.str("content") ?: "").lowercase() }
        }
        app.screenshot(name.methodName)
    }

    // ---- helpers

    private data class Run(val prompt: String, val command: String, val marker: String)

    /**
     * A prompt whose first line (so the chat's server title) carries this run's nonce, asking for one terminal
     * command. The marker sits in a shell comment: the command stays one segment, so the app's running step shows it
     * whole (Hermes summarises compound commands as "first + 1 command").
     */
    private fun longRun(seconds: Int, n: Int): Run {
        val marker = "apofis-e2e-$n"
        val command = "sleep $seconds # $marker"
        return Run("Automated app test ${app.nonce}-$n. Use the terminal tool to run exactly this command: $command . Then reply with one short sentence that includes $marker.", command, marker)
    }

    /** The screen's power state from dumpsys; an unreadable answer fails instead of counting as any state. */
    private fun wakefulness(): String =
        Regex("mWakefulness=(\\w+)").find(app.shell("dumpsys power"))?.groupValues?.get(1) ?: fail("dumpsys power did not report mWakefulness")

    /** The composer is back to Send: no run is going on. */
    private fun idle() = app.hasDesc(app.str("chat_send")) && !app.hasDesc(app.str("chat_stop"))

    /** The one bubble showing [prompt]; null when it is missing or shown more than once. */
    private fun bubble(tree: UiTree, prompt: String) = tree.bubbles(prompt).singleOrNull()

    private fun lastReply(prompt: String): String? {
        val tree = app.snapshot()
        val b = bubble(tree, prompt) ?: return null
        return tree.replies("", b).maxByOrNull { it.top }?.text
    }

    /** Waits until the running step for exactly this scenario's command is on screen. */
    private fun awaitToolStep(command: String) = app.waitFor("the running terminal step for \"$command\"", toolStep, meanwhile = { app.answerApproval(command) }) {
        // Proves the expected command is the one running: a step in the Running state whose own text holds the command.
        app.snapshot().steps(app.str("chat_tool_running"), command).isNotEmpty()
    }.also { app.log("tool step running: $command") }

    private fun awaitReply(marker: String, prompt: String, command: String? = null, timeoutMs: Long = reply) =
        app.waitFor("a finished reply containing $marker", timeoutMs, meanwhile = { command?.let(app::answerApproval) }) {
            // Proves a reply arrived for this turn: an assistant reply paragraph below the prompt holds the marker.
            val tree = app.snapshot()
            idle() && bubble(tree, prompt)?.let { tree.replies(marker, it).isNotEmpty() } == true
        }.also { app.log("reply with $marker shown") }

    /** Final assistant answers in [rows] (the stored reply text of each turn). */
    private fun finals(rows: List<JSONObject>) =
        rows.filter { it.str("role") == "assistant" && it.str("finish_reason") == "stop" }.mapNotNull { it.str("content") }.filter(String::isNotBlank)

    /** Polls the server's record of this chat until [check] holds (rows may be written just after the run ends). */
    private fun serverAgrees(what: String, check: (List<JSONObject>) -> Boolean) {
        var rows = emptyList<JSONObject>()
        val end = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < end) {
            rows = app.serverMessages(session)
            if (check(rows)) return
            SystemClock.sleep(1_000)
        }
        app.log("server rows of $session: ${rows.map { "${it.str("role")}/${it.str("display_kind")}/${it.str("finish_reason")}: ${it.str("content")?.take(120)}" }}")
        fail("The server's record of $session does not show $what")
    }

    /**
     * The user's message and the reply each appear exactly once, on screen and on the server; with [command], the
     * server also shows that exact terminal command was called.
     */
    private fun assertOnce(prompt: String, marker: String, command: String? = null) {
        val tree = app.snapshot()
        val bubbles = tree.bubbles(prompt)
        val replies = bubbles.singleOrNull()?.let { tree.replies(marker, it) }.orEmpty()
        // Proves no duplicate on screen: one bubble with the prompt and one reply paragraph with the marker.
        if (bubbles.size != 1 || replies.size != 1) {
            app.log(app.dump())
            fail("Expected the message and the reply once each on screen, found ${bubbles.size} and ${replies.size}")
        }
        // Proves no duplicate on the server: one stored user message with the prompt and one final answer with the marker.
        serverAgrees("the prompt once, one final answer with $marker${command?.let { " and the command \"$it\"" } ?: ""}") { rows ->
            rows.count { it.str("role") == "user" && prompt in (it.str("content") ?: "") } == 1 &&
                finals(rows).count { marker in it } == 1 && (command == null || command in terminalCommands(rows))
        }
    }

    private companion object {
        val BANANA = Regex("BANANA\\W*$")
    }
}
