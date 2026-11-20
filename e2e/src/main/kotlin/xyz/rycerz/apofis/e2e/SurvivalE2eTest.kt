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
 * granted through adb), reads the open chat's key from the chat screen's tag, journals that chat's ids (and only
 * that chat's) right after each send and again after the reply, asserts on the
 * screen (chat items found by the app's test tags) and on the server's record of that chat, and saves a screenshot.
 * run-e2e.sh deletes the journaled sessions after checking each one's identity, and checks the steer and the
 * stopped run against the server once more from the build host.
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
        app.scenario = name.methodName
        app.freshStart()
        session = app.connect(app.directUrl)
    }

    @After
    fun cleanUp() {
        app.airplane(false)
        app.journalSent() // the scenario's one completing journal line, also when it failed
        app.log("scenario ${name.methodName} ends")
    }

    @Test
    fun connectAndChat() {
        val marker = "apofis-e2e-1"
        val prompt = "Test ${app.nonce}-1. Reply with exactly: $marker"
        val chat = sendPrompt(prompt)
        awaitReply(marker, prompt)
        assertOnce(chat, prompt, marker)
        app.screenshot(name.methodName)
    }

    @Test
    fun replySurvivesAirplaneMode() {
        val (prompt, command, marker) = longRun(25, 2)
        val chat = sendPrompt(prompt)
        awaitToolStep(prompt, command)
        val cut = SystemClock.uptimeMillis()
        app.airplane(true)
        // Proves a real interruption: the device's airplane mode is on and the app itself reports being offline
        // (not a generic "can't reach server", which other faults also produce).
        app.waitFor("airplane mode on and the status line showing Offline", 30_000) { app.airplaneOn() && offline() }
        app.log("app sees the loss: ${app.screen()}")
        app.screenshot("2-airplane-offline")
        SystemClock.sleep((30_000 - (SystemClock.uptimeMillis() - cut)).coerceAtLeast(0))
        app.airplane(false)
        app.waitFor("airplane mode off", 10_000) { !app.airplaneOn() }
        awaitReply(marker, prompt)
        assertOnce(chat, prompt, marker, command)
        app.screenshot(name.methodName)
    }

    @Test
    fun replySurvivesScreenOff() {
        val (prompt, command, marker) = longRun(25, 3)
        val chat = sendPrompt(prompt)
        awaitToolStep(prompt, command)
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
        awaitReply(marker, prompt)
        assertOnce(chat, prompt, marker, command)
        app.screenshot(name.methodName)
    }

    @Test
    fun replySurvivesProcessDeath() {
        val (prompt, command, marker) = longRun(25, 4)
        val chat = sendPrompt(prompt)
        awaitToolStep(prompt, command)
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
        awaitReply(marker, prompt)
        SystemClock.sleep(3_000) // a duplicate would show up right after the first copy
        assertOnce(chat, prompt, marker, command)
        app.screenshot(name.methodName)
    }

    @Test
    fun offlineMessageSendsItself() {
        app.airplane(true)
        // Proves the device is offline before sending: airplane mode is on and the app says Offline.
        app.waitFor("airplane mode on and the status line showing Offline", 30_000) { app.airplaneOn() && offline() }
        val marker = "apofis-e2e-5"
        val prompt = "Test ${app.nonce}-5. Reply with exactly: $marker"
        val chat = sendPrompt(prompt, online = false)
        app.waitFor("the message bubble waiting to send", 15_000) { queued(prompt) }
        // Stays offline long enough that a send that ignored the network would have failed or gone out by now.
        SystemClock.sleep(15_000)
        if (!app.airplaneOn() || !queued(prompt) || !offline()) {
            fail("After 15 s offline the message should still be waiting and the status Offline. Screen: ${app.screen()}")
        }
        app.screenshot("5-offline-waiting")
        app.airplane(false)
        app.waitFor("airplane mode off", 10_000) { !app.airplaneOn() }
        awaitReply(marker, prompt, timeoutMs = 60_000)
        assertOnce(chat, prompt, marker)
        app.screenshot(name.methodName)
    }

    @Test
    fun steerDuringRun() {
        val (prompt, command, marker) = longRun(20, 6)
        val steering = steeringText(app.nonce)
        val chat = sendPrompt(prompt)
        awaitToolStep(prompt, command)
        app.send(chat, steering, button = app.str("chat_steer"))
        app.waitFor("the steering text as a user message", 30_000) { app.snapshot().user(steering) != null }
        // Proves the steer changed this turn's reply: the items are the prompt, its reply, the steering message, then
        // one final reply, and that final reply holds the marker and ends with the word BANANA.
        app.waitFor("this turn's final reply after the steer with $marker, ending with BANANA", reply, meanwhile = app::failOnApproval) {
            val t = app.snapshot()
            idle() && t.steerReply(prompt, steering)?.let { marker in t.replyText(it) && BANANA.containsMatchIn(t.replyText(it)) } == true
        }
        journalAfterReply(chat)
        // Proves the server did it in this turn and in order: the prompt, the command, exactly one steer row of exactly
        // the steering text, then the turn's final answer with the marker ending with BANANA, and no other turn.
        serverAgrees(chat, "one steered turn: the command, the steer row, then a final answer ending with BANANA") { rows ->
            steeredTurn(rows, prompt, command, steering, marker)
        }
        app.screenshot(name.methodName)
    }

    @Test
    fun stopDuringRun() {
        val (prompt, command, _) = longRun(60, 7)
        val chat = sendPrompt(prompt)
        awaitToolStep(prompt, command)
        app.journalRun(chat) // the build host checks this run's final status on the server
        app.clickTag("composer.stop", app.str("chat_stop"))
        app.waitFor("the Stopped notice and the composer back in send mode", 60_000) { app.has(app.str("chat_outcome_stopped")) && idle() }
        journalAfterReply(chat)
        // Proves the stop reached the server: the scenario's own terminal call has its own result marked interrupted.
        serverAgrees(chat, "the command called and its own result interrupted") { rows -> interruptedCall(rows, command) }
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

    /** Sends [prompt] in the chat this scenario opened (its key read from the chat screen's tag); returns that key. */
    private fun sendPrompt(prompt: String, online: Boolean = true): String = session.also { app.send(it, prompt, online = online) }

    /** After the reply the chat must be on the server (its ids are journaled at the scenario's end). */
    private fun journalAfterReply(chat: String) {
        if (!app.chatOnServer(chat)) fail("The chat is not on the server after the reply")
    }

    /** The composer is back to Send: no run is going on. */
    private fun idle(): Boolean {
        val t = app.snapshot()
        return t.tagged("composer.send").any { app.str("chat_send") in t.descs(it) } && t.tagged("composer.stop").isEmpty()
    }

    /** The status line (found by its tag) says Offline. */
    private fun offline(): Boolean = app.snapshot().let { t -> t.tagged("status.line").any { app.str("chat_status_offline") in t.texts(it) } }

    /** The user message [prompt] is shown once, with its own "waiting to send" caption. */
    private fun queued(prompt: String): Boolean = app.snapshot().let { t -> t.user(prompt)?.let { app.str("chat_queued") in t.texts(it.node) } == true }

    /**
     * Waits until the reply right after this scenario's user message shows a running step whose own text is exactly
     * this scenario's command.
     */
    private fun awaitToolStep(prompt: String, command: String) =
        app.waitFor("the running terminal step for \"$command\" in this turn's reply", toolStep, meanwhile = app::failOnApproval) {
            val t = app.snapshot()
            t.replyAfter(prompt)?.let { t.steps(it, app.str("chat_tool_running"), command).isNotEmpty() } == true
        }.also { app.log("tool step running: $command") }

    private fun awaitReply(marker: String, prompt: String, timeoutMs: Long = reply) {
        app.waitFor("a finished reply containing $marker", timeoutMs, meanwhile = app::failOnApproval) {
            // Proves a reply arrived for this turn: the reply item right after this prompt's user item holds the marker.
            idle() && app.snapshot().replyWith(prompt, marker) != null
        }
        app.log("reply with $marker shown")
        journalAfterReply(session)
    }

    /** Polls the server's record of this chat until [check] holds (rows may be written just after the run ends). */
    private fun serverAgrees(chat: String, what: String, check: (List<JSONObject>) -> Boolean) {
        var rows = emptyList<JSONObject>()
        val end = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < end) {
            rows = app.serverMessages(chat)
            if (check(rows)) return
            SystemClock.sleep(1_000)
        }
        app.log("server rows of $chat: ${rows.map { "${it.str("role")}/${it.str("display_kind")}/${it.str("finish_reason")}: ${it.str("content")?.take(120)}" }}")
        fail("The server's record of $chat does not show $what")
    }

    /**
     * The user's message and the reply each appear exactly once, on screen and on the server; with [command], the
     * server also shows that exact terminal command was called.
     */
    private fun assertOnce(chat: String, prompt: String, marker: String, command: String? = null) {
        val tree = app.snapshot()
        // Proves no duplicate on screen: one user item with the prompt, and the reply right after it holds the marker
        // while no other reply on screen does.
        val key = tree.replyWith(prompt, marker)
        val others = tree.items().filter { it.kind == "reply" && it.key != key }.map { it.key }.distinct().filter { marker in tree.replyText(it) }
        if (key == null || others.isNotEmpty()) {
            app.log(app.dump())
            fail("Expected the message and its reply with $marker once each on screen (reply $key, others $others)")
        }
        // Proves no duplicate on the server: one stored user message with the prompt and one final answer with the marker.
        serverAgrees(chat, "the prompt once, one final answer with $marker${command?.let { " and the command \"$it\"" } ?: ""}") { rows ->
            rows.count { it.str("role") == "user" && prompt in (it.str("content") ?: "") } == 1 &&
                finals(rows).count { marker in it } == 1 && (command == null || command in terminalCommands(rows))
        }
    }

    companion object {
        val BANANA = Regex("\\bBANANA\\W*$")

        /** The steering message; run-e2e.sh builds the same text to check the steer row on the server. */
        fun steeringText(nonce: String) = "Test $nonce-6 steer: end your reply with the word BANANA"
    }
}
