package xyz.rycerz.apofis.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.StringReader
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * Push on the emulator, before the owner installs the hook (run-e2e.sh --push; gate 2 is
 * `--test PushE2eTest#pushWakesKilledApp` alone). Each scenario has its own install id minted by the harness (so no
 * scenario's record ids depend on another's), starts from cleared app data with that identity injected, connects, and
 * drives Settings, Notifications. The e2e APK is the distributor ([FakeDistributor]); its endpoint is the harness's
 * fake ntfy topic. The app never deletes a push record: every scenario's records stay on the server until the harness's
 * cleanup (rule C3) removes them from the journaled intents. Gate 1 scenarios start no model run; [pushWakesKilledApp]
 * starts exactly one. Nothing here logs the push prefs, an endpoint or a token.
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
     * Enable, a cold wake with nothing stored, a foreign distributor's temp-unavailable and migration, enable again, and
     * the distributor's unregistration; the records stay on the server throughout. The emulator claim for the foreign
     * package is narrow (ruling on I10): push Off after the migration, an UNREGISTER with the old token, no further
     * REGISTER at this test distributor and no stored mention of the foreign package. Where an outgoing REGISTER goes
     * is covered by Task 2's unit tests (PushReceiverTest, PushRegistrarTest).
     */
    @Test
    fun connectorLifecycle() {
        openSettings()
        enablePush()
        assertRecord(1, current = true)
        assertNoRecord(2) // the expected ids only; the exact namespace is the host's listing at the end of the run
        val first = push.token()

        // A cold delivery with no stored run and an empty outbox: the app starts, checks, posts nothing, does not crash.
        killInBackground()
        val since = app.deviceTime()
        deliverQuietly(since, "an arbitrary body".toByteArray(), "the cold delivery")
        if (app.pidof().first != 0) fail("The app is not running after the cold delivery")
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
        app.waitFor("the app unregistering its old token at the e2e distributor", 15_000) { push.unregistrations().any { it.token == first } }
        assertNoTrace(OTHER)
        if (push.registrations().size != registered) fail("The app registered again at the test distributor after the migration")
        assertRecord(1, current = false) // the app never deletes a record

        // Enabling again reserves the next id of the same install (its sequence survives Off), with a new token.
        enablePush()
        if (push.token() == first) fail("The second enable reused the revoked token")
        assertRecord(2, current = true)
        assertRecord(1, current = false)
        push.unregister()
        awaitRow("settings_notif_off")
        assertRecord(2, current = false)
        assertRecord(1, current = false)
        app.screenshot(name.methodName)
    }

    /** Hostile and odd pushes with push on and nothing stored: same process, no crash, none posted, checks find no run. */
    @Test
    fun hostilePushes() {
        openSettings()
        enablePush()
        val since = app.deviceTime()
        val (rc, pid) = app.pidof()
        if (rc != 0 || !Regex("\\d+").matches(pid)) fail("The app is not running before the pushes (exit $rc)")
        deliverQuietly(since, ByteArray(0), "the hostile pushes") {
            push.deliver(ByteArray(4096) { 0xFF.toByte() })
            push.deliver("""{"kind":7}""".toByteArray())
            repeat(20) { push.deliver("""{"session_id":"apofis-${UUID.randomUUID()}","kind":"finished"}""".toByteArray()) }
        }
        val (after, pidAfter) = app.pidof()
        if (after != 0 || pidAfter != pid) fail("The app's process changed during the pushes (pid $pid, then \"$pidAfter\", exit $after)")
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
        // Never asked in the app (the harness granted it): the first tap asks; the dialog's deny button refuses.
        tapRow()
        val deny = { app.nodes { it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true && it.packageName?.endsWith("permissioncontroller") == true }.singleOrNull() }
        app.waitFor("the system's notification permission dialog (its deny button)", 15_000) { deny() != null }
        if (deny()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) fail("Tapping the permission dialog's deny button failed")
        app.waitFor("the app in front again", 15_000) { app.device.currentPackageName == APP }
        awaitRow("settings_notif_blocked")
        tapRow()
        sheetAction("settings_notif_system")
        sheetAction("settings_notif_turn_off")
        if (inSheet(app.str("settings_notif_test")) != null) fail("A blocked sheet offers the test push")
        app.device.pressBack()
        val out = app.shell("pm grant $APP $POST_NOTIFICATIONS")
        if (out.isNotBlank()) fail("pm grant failed: $out")
        app.device.pressHome()
        app.launch() // resumed: Settings reads the access again
        if (!onSettings()) openSettings()
        awaitRow("settings_notif_on")

        tapRow()
        val since = app.deviceTime()
        sheetAction("settings_notif_test").performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val firstSend = SystemClock.uptimeMillis()
        app.waitFor("Test sent, waiting", 15_000) { app.has(app.str("settings_notif_test_sent")) }
        val body = pollFor()
        if (body.decodeToString() != """{"kind":"test"}""") fail("The test push body is not the app's own test body")
        // At the delivery boundary: still waiting, nothing arrived yet; then the result follows this delivery.
        if (!app.has(app.str("settings_notif_test_sent")) || app.has(app.str("settings_notif_test_received"))) {
            fail("The test result changed before the delivery")
        }
        // A test push is a delivered push: it starts a wake check, which finds no run and settles nothing.
        deliverQuietly(since, body, "the test push")
        app.waitFor("A push arrived", 30_000) { app.has(app.str("settings_notif_test_received")) }
        assertQuiet(since)

        // A second test within 10 s is refused silently: send it after that, and deliver nothing.
        SystemClock.sleep((11_000 - (SystemClock.uptimeMillis() - firstSend)).coerceAtLeast(0))
        sheetAction("settings_notif_test").performAction(AccessibilityNodeInfo.ACTION_CLICK)
        app.waitFor("Test sent, waiting (second test)", 15_000) { app.has(app.str("settings_notif_test_sent")) }
        pollFor() // taken from the topic, never delivered
        app.waitFor("No push arrived after the 60 s window", 80_000) { app.has(app.str("settings_notif_test_none")) }
        app.screenshot(name.methodName)
    }

    // ---- gate 2: one model run

    /**
     * The app is killed while its run goes on; once the server has finished the run, the real hook (anonymous approval
     * wake, from the fake's synthetic database) wakes it through the fake ntfy; the check settles the run and notifies.
     * Proved only when, immediately before the delivery, the process is gone and the chat has exactly its stored run,
     * no queued or steering send and an empty outbox; the hook's one post is the only message; exactly one new
     * notification of this chat appears within 60 s of the delivery; and the one check that settled anything after
     * the delivery was caused by the push. Anything less is "not proved", never a second send.
     */
    @Test
    fun pushWakesKilledApp() {
        openSettings()
        enablePush()
        assertRecord(1, current = true)
        app.device.pressBack()
        val marker = "apofis-e2e-8"
        // One turn with a tool call long enough to get the process down before the run ends.
        val prompt = "Automated app test ${app.nonce}-8. Use the terminal tool to run exactly this command: sleep 45 # $marker . " +
            "Then reply with one short sentence that includes $marker."
        app.send(session, prompt)
        var runId = ""
        app.waitFor("the run stored by the app", 60_000, meanwhile = app::failOnApproval) {
            app.query(WAKE_PRECONDITION_SQL(session))?.let(::storedRun)?.also { runId = it } != null
        }
        killInBackground()
        // Read only: the server finishes the run and stores its final answer.
        app.waitFor("the run finished on the server", 300_000) { app.runStatus(runId, session) in TERMINAL_RUN }
        if (app.runStatus(runId, session) != "completed") fail("Not proved: the run ended ${app.runStatus(runId, session)}")
        app.waitFor("the final answer with $marker on the server", 30_000) { finals(app.serverMessages(session)).any { marker in it } }

        val since = app.deviceTime() // the log cursor, before every read it bounds
        if (push.poll() != null) fail("Not proved: the fake topic held a message before the trigger")
        push.trigger() // 200 only when this invocation's child posted exactly once to the fake
        val body = pollFor()
        if (!body.contentEquals(WAKE_BODY) || push.poll() != null) fail("Not proved: the topic did not hold exactly the hook's one wake")
        val title = app.serverTitle(session)
        // Right before the delivery: every slow read first, then as the last step the process found gone, then the
        // broadcast with nothing in between.
        val out = app.query(WAKE_PRECONDITION_SQL(session)) ?: fail("Not proved: the precondition query failed")
        if (wakePrecondition(out) != runId) fail("Not proved: the precondition does not hold (stored run, no queued send, empty outbox)")
        val before = app.wakeLines(since)
        if (before.any { (wakeLine(it)?.settled ?: 1) > 0 }) fail("Not proved: a check settled (or logged unreadably) before the delivery")
        if (app.notificationsTagged(session).isNotEmpty()) fail("Not proved: a notification for the chat was already posted")
        val (rc, pids) = app.pidof()
        if (rc != 1 || pids.isNotEmpty()) fail("Not proved: the app process is not gone at the delivery")
        val deadline = SystemClock.uptimeMillis() + 60_000 // absolute: an observation completing after it does not count
        push.deliver(body)
        val within = { what: String, condition: () -> Boolean ->
            if (!observedBy(deadline, SystemClock::uptimeMillis, { SystemClock.sleep(500) }, condition)) fail("Not proved: not seen within 60 s of the delivery: $what")
        }
        within("one new notification of this chat") { app.notificationsTagged(session).size == 1 }
        app.device.openNotification()
        var row: AccessibilityNodeInfo? = null
        within("that notification in the shade with the chat title and \"${app.str("notif_reply_finished")}\"") {
            notificationRows(title).singleOrNull()?.also { row = it } != null
        }
        if (app.notificationsTagged(session).size != 1) fail("Not proved: not exactly one notification of this chat")
        // The settling check logs its line after the post: wait for it, then read the lines once.
        app.waitFor("the settling check's line", 30_000) { app.wakeLines(since).drop(before.size).any { (wakeLine(it)?.settled ?: 0) > 0 } }
        val lines = app.wakeLines(since)
        if (lines.take(before.size) != before || !provedByPush(before, lines.drop(before.size))) fail("Not proved: the settling check was not the push's alone: $lines")
        if (row?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) fail("Tapping the notification failed")
        app.waitFor("this chat (its key) with the marker reply", 30_000) { app.snapshot().let { t -> t.chatKey() == session && t.replyWith(prompt, marker) != null } }
        app.screenshot(name.methodName)
    }

    // ---- helpers

    /** The shade's notification rows that show both [title] and "Reply finished" in their own texts. */
    private fun notificationRows(title: String): List<AccessibilityNodeInfo> {
        val finished = app.str("notif_reply_finished")
        fun texts(n: AccessibilityNodeInfo): List<String> =
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString()) + (0 until n.childCount).mapNotNull(n::getChild).flatMap(::texts)
        return app.nodes { n -> n.isClickable && texts(n).let { t -> t.any { title in it } && t.any { finished in it } } }
            .filter { n -> (0 until n.childCount).mapNotNull(n::getChild).none { c -> c.isClickable && texts(c).let { t -> t.any { title in it } && t.any { finished in it } } } }
    }

    private fun killInBackground() {
        app.device.pressHome()
        val (rc, pid) = app.pidof()
        if (rc != 0 || !Regex("\\d+").matches(pid)) fail("Could not read the app's pid before killing it (exit $rc)")
        // am kill ends only a process that is safe to kill (cached): asked again until background work has ended. Never
        // force-stop, which would leave the app stopped, so no distributor broadcast could start it.
        app.waitFor("the app process $pid gone after am kill", 90_000, meanwhile = { app.shell("am kill $APP") }) {
            val (after, pids) = app.pidof()
            after == 1 && pids.isEmpty() && app.processGone(pid)
        }
    }

    /**
     * Delivers [body] (and whatever [more] delivers right after) and waits for the new wake check that this delivery
     * caused: new lines with a push cause, every new line a check that took no run and settled nothing.
     */
    private fun deliverQuietly(since: String, body: ByteArray, what: String, more: () -> Unit = {}) {
        val before = app.wakeLines(since).size
        push.deliver(body)
        more()
        app.waitFor("the push-caused wake check after $what", 60_000) { app.wakeLines(since).drop(before).any { wakeLine(it)?.cause == "push" } }
        val new = app.wakeLines(since).drop(before)
        if (!quietPushChecked(new)) fail("The wake checks after $what were not quiet push checks: $new")
    }

    /** None of the app's notifications is active and no crash was logged since [since] (both reads must succeed). */
    private fun assertQuiet(since: String) {
        val posted = app.notifications()
        if (posted.isNotEmpty()) fail("The app has ${posted.size} active notification(s)")
        val crashed = app.crashes(since)
        if (crashed.isNotEmpty()) fail("The app crashed (${crashed.size} crash log lines)")
    }

    /** Neither the app's prefs nor its databases mention [pkg]: no registration with it was stored. */
    private fun assertNoTrace(pkg: String) {
        val r = app.sh("run-as $APP grep -rlF -- $pkg shared_prefs databases; echo rc=$?").trim()
        if (r != "rc=1") fail("The app's data mentions $pkg (or could not be searched)")
    }

    /**
     * Record -[seq] on the server matches the app's own journaled intent for it exactly (id, source, hidden, title); for
     * the [current] record, the app's stored record and endpoint are that id and the fake's topic. Compared, never logged.
     */
    private fun assertRecord(seq: Int, current: Boolean) {
        val id = "apofis-push-${app.pushInstalls.getValue(name.methodName)}-$seq"
        app.waitFor("record -$seq on the server as the app's intent says${if (current) ", and the app's current record" else ""}", 30_000) {
            val (code, s) = app.pushRecord(id)
            code == 200 && s != null && recordMatches(app.pushPrefsRead(), s, id, push.endpoint, current)
        }
        app.log("record -$seq checked")
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

    // ---- Settings, found by text and tag, scrolled to

    private fun AccessibilityNodeInfo.texts(): List<String> =
        listOfNotNull(text?.toString()) + (0 until childCount).mapNotNull(::getChild).flatMap { it.texts() }

    /** Settings is on screen: one of its section headings is visible. */
    private fun onSettings() = app.nodes { it.isHeading && it.isVisibleToUser && it.text?.toString() in SECTIONS.map(app::str) }.isNotEmpty()

    /** Opens Settings (from the chat's drawer) and scrolls its list until the Notifications group is on screen. */
    private fun openSettings() {
        if (!onSettings()) {
            app.click(app.str("drawer_open"))
            app.click(app.str("settings_title"))
            app.waitFor("Settings", 15_000) { onSettings() }
        }
    }

    /** The Settings screen's own scrolling container: a visible scrollable holding its section headings (the sheet has none). */
    private fun settingsList(): AccessibilityNodeInfo? {
        val titles = SECTIONS.map(app::str)
        fun heading(n: AccessibilityNodeInfo): Boolean =
            (n.isHeading && n.text?.toString() in titles) || (0 until n.childCount).mapNotNull(n::getChild).any(::heading)
        return app.nodes { it.isScrollable && it.isVisibleToUser && heading(it) }.firstOrNull()
    }

    /** The Notifications row: the clickable node whose texts start with the row title (not the section heading). */
    private fun rowNode(): AccessibilityNodeInfo? =
        app.nodes { it.isClickable && segments(it.texts()).firstOrNull() == app.str("settings_notifications") }.singleOrNull()

    /** The row's own title (clickable or not yet: a row still loading shows its title only), never the section heading. */
    private fun rowTitle(): AccessibilityNodeInfo? =
        app.nodes { n ->
            !n.isHeading && (n.text?.toString() == app.str("settings_notifications") || (n.isClickable && segments(n.texts()).firstOrNull() == app.str("settings_notifications")))
        }.firstOrNull()

    /** Discovers the row by the bounded scroll first (it may not exist until the list has scrolled to it). */
    private fun showRow() = discover("the Notifications row", ::settingsList) { rowTitle() }

    /** The row's value, by the app's own string names (reading needs no scrolling: the value is its own node text). */
    private fun rowValue(): String? {
        val shown = segments(rowNode()?.texts() ?: return null)
        return VALUES.firstOrNull { app.str(it) in shown.drop(1) }
    }

    private fun awaitRow(value: String) {
        showRow()
        app.waitFor("Notifications showing \"${app.str(value)}\"", 60_000) { rowValue() == value }
    }

    private fun tapRow() {
        showRow()
        app.waitFor("the Notifications row available", 30_000) { rowNode()?.isEnabled == true }
        if (rowNode()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) fail("Tapping the Notifications row failed")
    }

    /**
     * The open Notifications sheet: the app window that shows the "Notifications" title and none of Settings' section
     * headings (the sheet is a window of its own, where test tags are not exposed); its scrolling column when it has one.
     */
    private fun sheet(): AccessibilityNodeInfo? {
        val title = app.str("settings_notifications")
        fun has(n: AccessibilityNodeInfo, p: (AccessibilityNodeInfo) -> Boolean): Boolean = p(n) || (0 until n.childCount).mapNotNull(n::getChild).any { has(it, p) }
        fun scrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            if (n.isScrollable) n else (0 until n.childCount).mapNotNull(n::getChild).firstNotNullOfOrNull(::scrollable)
        val root = app.appRoots().singleOrNull { r -> has(r) { it.text?.toString() == title } && !has(r) { it.isHeading } } ?: return null
        return scrollable(root) ?: root
    }

    /** The node labelled [label] inside [root] (the kept sheet root), clickable or not. */
    private fun labelIn(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            if ((n.text ?: n.contentDescription)?.toString() == label) n else (0 until n.childCount).mapNotNull(n::getChild).firstNotNullOfOrNull(::find)
        root.refresh()
        return find(root)
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = if (n.isClickable) n else n.parent?.let(::clickableOf)

    private fun inSheet(label: String): AccessibilityNodeInfo? = sheet()?.let { labelIn(it, label) }?.let(::clickableOf)

    /** A sheet action by its string: discovered by the bounded scroll in the sheet (its root kept), then enabled. */
    private fun sheetAction(stringName: String): AccessibilityNodeInfo {
        val label = app.str(stringName)
        val found = discover("\"$label\" in the sheet", ::sheet) { root -> labelIn(root, label) }
        app.waitFor("an enabled \"$label\" in the sheet", 30_000) { found.refresh() && clickableOf(found)?.isEnabled == true }
        return clickableOf(found) ?: fail("\"$label\" in the sheet is not a control")
    }

    /**
     * Discovers [what] on screen: one bounded scrolling search ([reveal]) in the [container] selected for it, repeated
     * until the target exists (a row or sheet still loading) or 30 s pass.
     */
    private fun discover(what: String, container: () -> AccessibilityNodeInfo?, finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        app.waitFor("$what, scrolled to", 30_000) { container()?.let { c -> reveal(what, c) { finder(c) } }?.also { found = it } != null }
        return found!!
    }

    /**
     * Looks for [what] and brings it on screen by scrolling [container] (kept for the whole search) with [revealIn];
     * progress is what is visible in it, texts and bounds. Logs how many scrolls it took; null when not found.
     */
    private fun reveal(what: String, container: AccessibilityNodeInfo, finder: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        fun visible(n: AccessibilityNodeInfo): List<String> = (if (n.isVisibleToUser) listOf("${n.text}@" + Rect().also(n::getBoundsInScreen)) else emptyList()) +
            (0 until n.childCount).mapNotNull(n::getChild).flatMap(::visible)
        val (found, scrolls) = revealIn(object : ScrollList<AccessibilityNodeInfo> {
            override fun find() = finder()
            override fun visible(n: AccessibilityNodeInfo) = n.isVisibleToUser
            override fun scroll(forward: Boolean): Boolean {
                val ok = container.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                app.device.waitForIdle()
                return ok
            }
            override fun shown(): Any = container.also { it.refresh() }.let(::visible)
        })
        if (found != null) app.log("$what found after $scrolls scroll(s)")
        return found
    }

    /** Turns push on from the row (Off: one tap enables) and waits for On. */
    private fun enablePush() {
        awaitRow("settings_notif_off")
        tapRow()
        awaitRow("settings_notif_on")
    }

    companion object {
        const val OTHER = "com.example.other"
        val TERMINAL_RUN = setOf("completed", "failed", "cancelled", "interrupted") // RunFollower.kt
        val SECTIONS = listOf("settings_connection", "settings_appearance", "settings_behaviour", "settings_notifications", "settings_about")
        val VALUES = listOf(
            "settings_notif_on", "settings_notif_off", "settings_notif_no_distributor", "settings_notif_blocked",
            "settings_notif_channel_blocked", "settings_notif_registering", "settings_notif_server_error",
        )
    }
}

// ---- the push scenarios' decisions, pure: HarnessSelfTest feeds them canned input

/** The hook's anonymous approval wake (apofis_push.push_body), the only body the fake's trigger accepts as proof. */
val WAKE_BODY = """{"kind":"needs_input"}""".toByteArray()

/** A journaled create intent: "pushintent <id> <id> op-<16 hex> e2e-<nonce>" (the build host checks the same form). */
val PUSH_INTENT = Regex("pushintent (apofis-push-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[1-9][0-9]{0,5}) \\1 op-[0-9a-f]{16} e2e-[A-Za-z0-9]{1,20}")

/**
 * The wake precondition of chat [key] in one read-only statement: its stored runs and their ids, its sends in the app's
 * queued state or steering ('sending' is the healthy row of the stored run itself), and the outbox rows.
 */
fun WAKE_PRECONDITION_SQL(key: String): String {
    require(Regex("apofis-[0-9a-f-]{36}").matches(key)) { "Not an app session id" }
    return "SELECT (SELECT count(*) FROM ActiveRunEntity WHERE sessionKey = '$key'), " +
        "(SELECT group_concat(runId) FROM ActiveRunEntity WHERE sessionKey = '$key'), " +
        "(SELECT count(*) FROM PendingSendEntity WHERE sessionKey = '$key' AND state IN ('queued', 'steering')), " +
        "(SELECT count(*) FROM NoticeOutboxEntity);"
}

/** The stored run when the precondition's one row holds: exactly one run, no queued or steering send, an empty outbox. */
fun wakePrecondition(rows: List<String>): String? =
    rows.singleOrNull()?.let { Regex("1\\|(run_[0-9a-f]{32})\\|0\\|0").matchEntire(it) }?.groupValues?.get(1)

/** The run once the app has stored exactly one for the chat (whatever else the row says): to wait for before killing. */
fun storedRun(rows: List<String>): String? =
    rows.singleOrNull()?.let { Regex("1\\|(run_[0-9a-f]{32})\\|\\d+\\|\\d+").matchEntire(it) }?.groupValues?.get(1)

/** A scrolling list as [revealIn] sees it: the target (null until it exists), its visibility, a scroll, what is shown. */
interface ScrollList<N> {
    fun find(): N?
    fun visible(n: N): Boolean
    fun scroll(forward: Boolean): Boolean
    fun shown(): Any
}

/**
 * The target, visible, and the scrolls it took: looked for first, then after each scroll forward and then backward, at
 * most [cap] each way; a direction ends when a scroll is refused or nothing shown changed (texts or bounds).
 */
fun <N> revealIn(list: ScrollList<N>, cap: Int = 15): Pair<N?, Int> {
    var scrolls = 0
    for (forward in listOf(true, false)) {
        var steps = 0
        while (steps++ < cap) {
            list.find()?.takeIf(list::visible)?.let { return it to scrolls }
            val before = list.shown()
            if (!list.scroll(forward)) break
            scrolls++
            if (list.shown() == before) break
        }
    }
    return list.find()?.takeIf(list::visible) to scrolls
}

/**
 * Polls [condition] until the absolute [deadline] of [now]: true only for an observation that completed by it (the
 * time is read after the probe too, so one that finished late does not count). Only a not-yet observation is retried:
 * a false result or an ordinary exception (a stale UI node, as [AppDriver.waitFor] retries). An evidence failure
 * (an AssertionError from fail()) or any other Error escapes at once.
 */
fun observedBy(deadline: Long, now: () -> Long, pause: () -> Unit, condition: () -> Boolean): Boolean {
    while (now() <= deadline) {
        val seen = try {
            condition()
        } catch (e: Exception) {
            false
        }
        if (seen) return now() <= deadline
        pause()
    }
    return false
}

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

/** What may be logged about an identity read back: a fixed category, never the bytes read. */
fun identityDiagnostic(read: String): String = if (pushPrefs(read.trim()) == null) "not a whole prefs file" else "values differ"

/** What may be logged about an intent export read: a fixed category, never the bytes read. */
fun exportDiagnostic(read: String): String {
    val out = read.trimEnd()
    return when {
        out == "@@NONE" -> "no prefs file"
        !out.endsWith("\n@@READ") -> "unreadable"
        pushPrefs(out.removeSuffix("@@READ")) == null -> "malformed"
        else -> "read"
    }
}

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

/**
 * Server session [server] is record [id] exactly as the app's own intent in [prefsRead] says: same id, source
 * api_server, hidden, and the intent's exact title; for the [current] record also the app's stored record id and
 * endpoint equal [id] and [endpoint]. Returns only the verdict: the values are never logged.
 */
fun recordMatches(prefsRead: String, server: JSONObject, id: String, endpoint: String, current: Boolean): Boolean {
    val prefs = pushPrefs(prefsRead.trim()) ?: return false
    val title = prefs["test_intents"].orEmpty().lines().firstOrNull { it.substringBefore('\t') == id }?.substringAfter('\t') ?: return false
    if (server.optString("id") != id || server.optString("source") != "api_server" || !server.optBoolean("hidden") ||
        server.optString("title") != title
    ) {
        return false
    }
    return !current || (prefs["record"] == id && prefs["endpoint"] == endpoint)
}

/** One ApofisWake summary: "cause=<c> runs=<n> settled=<n> failed=<n> outcome=<o>"; null for anything else. */
data class WakeLine(val cause: String, val runs: Int, val settled: Int, val failed: Int)

fun wakeLine(s: String): WakeLine? = Regex("cause=([a-z]+) runs=(\\d+) settled=(\\d+) failed=(\\d+) outcome=[A-Za-z_]+").matchEntire(s.trim())
    ?.groupValues?.let { WakeLine(it[1], it[2].toInt(), it[3].toInt(), it[4].toInt()) }

/** The new lines after a quiet delivery: at least one push-caused check, and every line a check that took no run. */
fun quietPushChecked(new: List<String>): Boolean {
    val parsed = new.map { wakeLine(it) ?: return false }
    return parsed.any { it.cause == "push" } && parsed.all { it.runs == 0 && it.settled == 0 && it.failed == 0 }
}

/**
 * Before the delivery no check settled anything; after it exactly one check settled anything, it settled one run, and
 * its cause is one only a pending push mark produces. Any unparsed line fails.
 */
fun provedByPush(before: List<String>, after: List<String>): Boolean {
    val pre = before.map { wakeLine(it) ?: return false }
    val post = after.map { wakeLine(it) ?: return false }
    val settling = post.filter { it.settled > 0 }
    return pre.none { it.settled > 0 } && settling.size == 1 && settling[0].cause == "push" && settling[0].settled == 1
}
