package xyz.rycerz.apofis.e2e

import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.StringReader
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Push on the emulator, before the owner installs the hook (run-e2e.sh --push, --push-model). Each scenario has its own
 * install id minted by the harness (so no scenario's record ids depend on another's), starts from cleared app data with
 * that identity injected, connects, and drives Settings, Notifications. The e2e APK is the distributor
 * ([FakeDistributor]); its endpoint is the harness's fake ntfy topic. The app never deletes a push record: every
 * scenario's records stay on the server until the harness's cleanup (rule C3) removes them from the journaled intents.
 * Gate 1 scenarios start no model run; [pushWakesKilledApp] starts exactly one.
 */
@RunWith(AndroidJUnit4::class)
class PushE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

    private lateinit var push: Push
    private lateinit var session: String

    @Before
    fun start() {
        app.log("scenario ${name.methodName} starts")
        app.scenario = name.methodName
        push = Push(app)
        app.freshStart()
        session = app.connect(app.directUrl)
    }

    @After
    fun cleanUp() {
        // Also when the scenario failed: its intents and its one completing chat line, if it sent.
        runCatching { app.exportPushIntents(listOf(name.methodName)) }.onFailure { app.journal("pushintent-missing ${name.methodName}") }
        app.journalSent()
        app.log("scenario ${name.methodName} ends")
    }

    // ---- gate 1: no model run

    /**
     * Enable, a cold wake with nothing stored, a foreign distributor's temp-unavailable and migration (never followed),
     * enable again, and the distributor's unregistration. The records stay on the server throughout.
     */
    @Test
    fun connectorLifecycle() {
        openSettings()
        enablePush()
        assertRecord(1)
        assertNoRecord(2) // exactly -1
        val first = push.token()

        // A cold delivery with no stored run and an empty outbox: the app starts, checks, posts nothing, does not crash.
        killInBackground()
        val since = app.deviceTime()
        push.deliver("an arbitrary body".toByteArray())
        app.waitFor("the app started by the delivery and its check logged", 60_000) {
            app.pidof().first == 0 && app.wakeLines(since).isNotEmpty()
        }
        if (!noRunChecked(app.wakeLines(since))) fail("A check after the cold delivery found runs: ${app.wakeLines(since)}")
        assertQuiet(since)

        app.launch()
        openSettings()
        awaitRow("settings_notif_on")
        val registered = push.registrations().size
        push.tempUnavailable(OTHER)
        SystemClock.sleep(3_000)
        awaitRow("settings_notif_on")
        assertNoTrace(OTHER)
        push.migrate(OTHER)
        awaitRow("settings_notif_off") // the migration is taken as an unregistration
        app.waitFor("the app unregistering its token at the e2e distributor", 15_000) {
            push.events("org.unifiedpush.android.distributor.UNREGISTER").any { it.token == first }
        }
        assertNoTrace(OTHER)
        if (push.registrations().size != registered) fail("The app registered again after the migration")
        assertRecord(1) // the app never deletes a record

        // Enabling again reserves the next id of the same install (its sequence survives Off).
        enablePush()
        assertRecord(2)
        assertRecord(1)
        push.unregister()
        awaitRow("settings_notif_off")
        assertRecord(2)
        assertRecord(1)
        app.screenshot(name.methodName)
    }

    /** Hostile and odd pushes with push on and nothing stored: no crash, no notification, every check finds no run. */
    @Test
    fun hostilePushes() {
        openSettings()
        enablePush()
        val since = app.deviceTime()
        val (rc, pid) = app.pidof()
        if (rc != 0) fail("The app is not running before the pushes")
        push.deliver(ByteArray(0))
        push.deliver(ByteArray(4096) { 0xFF.toByte() })
        push.deliver("""{"kind":7}""".toByteArray())
        repeat(20) { push.deliver("""{"session_id":"apofis-${UUID.randomUUID()}","kind":"finished"}""".toByteArray()) }
        app.waitFor("a check after the pushes", 60_000) { app.wakeLines(since).isNotEmpty() }
        SystemClock.sleep(5_000) // a replacing job's line, if any, comes within this
        val lines = app.wakeLines(since)
        if (!noRunChecked(lines)) fail("A check after hostile pushes found runs: $lines")
        val (after, pidAfter) = app.pidof()
        app.log("pushes delivered; pid $pid before, $pidAfter after (exit $after)")
        assertQuiet(since)
        app.screenshot(name.methodName)
    }

    /** Blocked notifications in Settings, then the body-blind test push: one delivered, one not. */
    @Test
    fun permissionAndTestPush() {
        openSettings()
        enablePush()
        app.shell("pm revoke $APP $POST_NOTIFICATIONS") // the system kills the app for this
        app.launch()
        openSettings()
        awaitRow("settings_notif_blocked")
        // Never asked in the app (the harness granted it): the first tap asks; the dialog is dismissed (refused).
        tapRow()
        app.waitFor("the system's notification permission dialog", 15_000) { app.device.currentPackageName != APP }
        app.device.pressBack()
        app.waitFor("the app in front again", 15_000) { app.device.currentPackageName == APP }
        awaitRow("settings_notif_blocked")
        tapRow()
        app.waitFor("the sheet offering system settings and Turn off push", 10_000) {
            app.has(app.str("settings_notif_system")) && app.has(app.str("settings_notif_turn_off"))
        }
        if (app.has(app.str("settings_notif_test"))) fail("A blocked sheet offers the test push")
        app.device.pressBack()
        val out = app.shell("pm grant $APP $POST_NOTIFICATIONS")
        if (out.isNotBlank()) fail("pm grant failed: $out")
        app.device.pressHome()
        app.launch() // resumed: Settings reads the access again
        if (!onSettings()) openSettings()
        awaitRow("settings_notif_on")

        tapRow()
        val since = app.deviceTime()
        tap(app.str("settings_notif_test"))
        val firstSend = SystemClock.uptimeMillis()
        app.waitFor("Test sent, waiting", 15_000) { app.has(app.str("settings_notif_test_sent")) }
        val body = pollFor()
        if (body.decodeToString() != """{"kind":"test"}""") fail("The test push body is not the app's own test body")
        push.deliver(body)
        app.waitFor("A push arrived", 30_000) { app.has(app.str("settings_notif_test_received")) }
        // A test push is a delivered push: it starts a wake check, which finds no run and settles nothing.
        app.waitFor("the wake check of the test push", 60_000) { app.wakeLines(since).isNotEmpty() }
        if (!noRunChecked(app.wakeLines(since))) fail("The test push's check found runs: ${app.wakeLines(since)}")
        assertQuiet(since)

        // A second test within 10 s is refused silently: send it after that, and deliver nothing.
        SystemClock.sleep((11_000 - (SystemClock.uptimeMillis() - firstSend)).coerceAtLeast(0))
        tap(app.str("settings_notif_test"))
        app.waitFor("Test sent, waiting (second test)", 15_000) { app.has(app.str("settings_notif_test_sent")) }
        pollFor() // taken from the topic, never delivered
        app.waitFor("No push arrived after the 60 s window", 80_000) { app.has(app.str("settings_notif_test_none")) }
        app.screenshot(name.methodName)
    }

    // ---- gate 2: one model run

    /**
     * The app is killed while its run goes on; once the server has the final answer, the real hook (anonymous approval
     * wake, from the fake's synthetic database) wakes it through the fake ntfy; the check settles the run and notifies.
     * Proof only if the preconditions held before the delivery and the one settling check was caused by the push.
     */
    @Test
    fun pushWakesKilledApp() {
        openSettings()
        enablePush()
        app.device.pressBack()
        val marker = "apofis-e2e-8"
        val prompt = "Test ${app.nonce}-8. Reply with exactly: $marker"
        app.send(session, prompt)
        killInBackground()
        // Read only: wait until the server stores this turn's final answer.
        app.waitFor("the final answer with $marker on the server", 300_000) { finals(app.serverMessages(session)).any { marker in it } }
        if (app.pidof().first != 1) fail("The app process is back before the delivery")
        val runs = app.query("SELECT count(*) FROM ActiveRunEntity WHERE sessionKey = '$session';").single().toInt()
        val sends = app.query("SELECT state FROM PendingSendEntity WHERE sessionKey = '$session';")
        val outbox = app.query("SELECT count(*) FROM NoticeOutboxEntity;").single().toInt()
        if (!wakePreconditionsHold(runs, sends, outbox)) fail("Not proved: preconditions (runs=$runs sends=$sends outbox=$outbox)")
        val since = app.deviceTime()
        push.trigger()
        push.deliver(pollFor())
        val title = app.serverTitle(session)
        app.device.openNotification()
        app.waitFor("the notification \"${app.str("notif_reply_finished")}\" for the chat", 60_000) {
            app.has(app.str("notif_reply_finished")) && app.nodes { it.text?.toString()?.contains(title) == true }.isNotEmpty()
        }
        val lines = app.wakeLines(since)
        if (!provedByPush(lines)) fail("Not proved: the settling check was not the push's alone: $lines")
        app.click(app.str("notif_reply_finished"))
        app.waitFor("the chat with the marker reply", 30_000) { app.snapshot().replyWith(prompt, marker) != null }
        app.screenshot(name.methodName)
    }

    // ---- helpers

    private fun killInBackground() {
        app.device.pressHome()
        val (rc, pid) = app.pidof()
        if (rc != 0 || !Regex("\\d+").matches(pid)) fail("Could not read the app's pid before killing it (exit $rc)")
        app.shell("am kill $APP")
        app.waitFor("the app process $pid gone after am kill", 15_000) {
            val (after, pids) = app.pidof()
            after == 1 && pids.isEmpty() && app.processGone(pid)
        }
    }

    /** No notification of the app and no crash since [since]. */
    private fun assertQuiet(since: String) {
        val posted = app.notifications()
        if (posted.isNotEmpty()) fail("The app posted ${posted.size} notification(s)")
        val crashed = app.crashes(since)
        if (crashed.isNotEmpty()) fail("The app crashed: ${crashed.take(3)}")
    }

    /** Neither the app's prefs nor its databases mention [pkg]: no registration was made with it. */
    private fun assertNoTrace(pkg: String) {
        val r = app.sh("run-as $APP grep -rlF -- $pkg shared_prefs databases; echo rc=$?").trim()
        if (r != "rc=1") fail("The app's data mentions $pkg (or could not be searched): $r")
    }

    private fun assertRecord(seq: Int) {
        val id = "apofis-push-${app.pushInstalls.getValue(name.methodName)}-$seq"
        var last: Pair<Int, Any?> = 0 to null
        app.waitFor("record -$seq on the server, hidden, with this run's marker", 30_000) {
            val (code, s) = app.pushRecord(id).also { last = it }
            code == 200 && s != null && s.optString("source") == "api_server" && s.optBoolean("hidden") &&
                s.optString("title").let { it.startsWith("$id op-") && it.endsWith(" e2e-${app.nonce}") }
        }
        app.log("record -$seq present (HTTP ${last.first})")
    }

    private fun assertNoRecord(seq: Int) {
        val code = app.pushRecord("apofis-push-${app.pushInstalls.getValue(name.methodName)}-$seq").first
        if (code != 404) fail("Record -$seq answered $code, expected 404")
    }

    private fun pollFor(): ByteArray {
        var body: ByteArray? = null
        app.waitFor("a message at the fake ntfy topic", 20_000) { push.poll().also { body = it } != null }
        return body!!
    }

    private fun onSettings() = app.has(app.str("settings_title")) && rowNode() != null

    private fun openSettings() {
        if (onSettings()) return
        app.click(app.str("drawer_open"))
        app.click(app.str("settings_title"))
        app.waitFor("Settings with the Notifications row", 15_000) { rowNode() != null }
    }

    /** The Notifications row: the clickable node whose texts start with the row title (not the section heading). */
    private fun rowNode(): AccessibilityNodeInfo? {
        val title = app.str("settings_notifications")
        fun texts(n: AccessibilityNodeInfo): List<String> =
            listOfNotNull(n.text?.toString()) + (0 until n.childCount).mapNotNull(n::getChild).flatMap(::texts)
        return app.nodes { it.isClickable && segments(texts(it)).firstOrNull() == title }.singleOrNull()
    }

    /** The row's value, by the app's own string names. */
    private fun rowValue(): String? {
        val n = rowNode() ?: return null
        fun texts(n: AccessibilityNodeInfo): List<String> =
            listOfNotNull(n.text?.toString()) + (0 until n.childCount).mapNotNull(n::getChild).flatMap(::texts)
        val shown = segments(texts(n))
        return VALUES.firstOrNull { app.str(it) in shown.drop(1) }
    }

    private fun awaitRow(value: String) = app.waitFor("Notifications showing \"${app.str(value)}\"", 60_000) { rowValue() == value }

    private fun tapRow() {
        app.waitFor("the Notifications row available", 30_000) { rowNode()?.isEnabled == true }
        val n = rowNode() ?: fail("No Notifications row")
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail("Tapping the Notifications row failed")
    }

    /** Clicks the enabled element labelled [label] (or its clickable ancestor) through its own action, after scrolling to it. */
    private fun tap(label: String) {
        fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = if (n.isClickable) n else n.parent?.let(::clickableOf)
        fun find() = app.nodes { (it.text ?: it.contentDescription)?.toString() == label }.firstNotNullOfOrNull { clickableOf(it)?.takeIf { c -> c.isEnabled } }
        app.waitFor("an enabled \"$label\"", 30_000) { find() != null }
        val n = find() ?: fail("No enabled \"$label\"")
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        if (!n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail("Tapping \"$label\" failed")
        app.log("tapped \"$label\"")
    }

    /** Turns push on from the row (Off: one tap enables) and waits for On. */
    private fun enablePush() {
        awaitRow("settings_notif_off")
        tapRow()
        awaitRow("settings_notif_on")
    }

    companion object {
        const val OTHER = "com.example.other"
        val VALUES = listOf(
            "settings_notif_on", "settings_notif_off", "settings_notif_no_distributor", "settings_notif_blocked",
            "settings_notif_channel_blocked", "settings_notif_registering", "settings_notif_server_error",
        )
    }
}

// ---- the push scenarios' decisions, pure: HarnessSelfTest feeds them canned input

/** A journaled create intent: "pushintent <id> <id> op-<16 hex> e2e-<nonce>" (the build host checks the same form). */
val PUSH_INTENT = Regex("pushintent (apofis-push-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,5}) \\1 op-[0-9a-f]{16} e2e-[A-Za-z0-9]{1,20}")

/** Accessibility texts as the separate lines and merged parts a person reads. */
fun segments(texts: List<String>) = texts.flatMap { it.split("\n", ", ") }.map(String::trim).filter(String::isNotEmpty)

/** The string entries of a SharedPreferences file, or null when it is not one whole, well-formed file. */
fun pushPrefs(xml: String): Map<String, String>? = runCatching {
    val p = Xml.newPullParser()
    p.setInput(StringReader(xml))
    val out = mutableMapOf<String, String>()
    var closed = false
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        if (p.eventType == XmlPullParser.START_TAG && p.name == "string") out[p.getAttributeValue(null, "name")] = p.nextText()
        if (p.eventType == XmlPullParser.END_TAG && p.name == "map") closed = true
    }
    out.takeIf { closed }
}.getOrNull()

/** The injected identity reads back exactly as written: these three keys and no other value for them. */
fun injectionConfirmed(read: String, install: String, nonce: String, origin: String): Boolean =
    pushPrefs(read.trim()) == mapOf("install_id" to install, "e2e_nonce" to nonce, "e2e_origin" to origin)

/**
 * The journal lines for a read of the push prefs ("<file>\n@@READ", or "@@NONE" when there is no file): one
 * "pushintent" per intent; any read failure, malformed file or intent line gives "pushintent-missing" for each of
 * [ifUnreadable] instead, so the build host deletes nothing of those installs.
 */
fun pushIntentLines(read: String, ifUnreadable: Collection<String>): List<String> {
    val out = read.trimEnd()
    if (out == "@@NONE") return emptyList()
    val missing = ifUnreadable.map { "pushintent-missing $it" }
    if (!out.endsWith("\n@@READ")) return missing
    val prefs = pushPrefs(out.removeSuffix("@@READ")) ?: return missing
    val lines = prefs["test_intents"].orEmpty().lines().filter(String::isNotEmpty).map { "pushintent " + it.replaceFirst('\t', ' ') }
    return if (lines.all(PUSH_INTENT::matches)) lines else missing
}

/** One ApofisWake summary: "cause=<c> runs=<n> settled=<n> failed=<n> outcome=<o>"; null for anything else. */
data class WakeLine(val cause: String, val runs: Int, val settled: Int, val failed: Int)

fun wakeLine(s: String): WakeLine? = Regex("cause=([a-z]+) runs=(\\d+) settled=(\\d+) failed=(\\d+) outcome=[A-Za-z_]+").matchEntire(s.trim())
    ?.groupValues?.let { WakeLine(it[1], it[2].toInt(), it[3].toInt(), it[4].toInt()) }

/** Every line is a well-formed summary of a check that took no run and settled nothing (and there is one). */
fun noRunChecked(lines: List<String>) = lines.isNotEmpty() && lines.all { wakeLine(it)?.let { w -> w.runs == 0 && w.settled == 0 && w.failed == 0 } == true }

/** Before the delivery: the chat has a stored run, no send waiting to go, and the outbox is empty. */
fun wakePreconditionsHold(runs: Int, sendStates: List<String>, outbox: Int) =
    runs == 1 && sendStates.none { it == "queued" || it == "sending" } && outbox == 0

/** Exactly one check settled anything, it settled one run, and its cause is one only a pending push mark produces. */
fun provedByPush(lines: List<String>): Boolean {
    val parsed = lines.map { wakeLine(it) ?: return false }
    val settling = parsed.filter { it.settled > 0 }
    return settling.size == 1 && settling[0].cause == "push" && settling[0].settled == 1
}
