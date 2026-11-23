package xyz.rycerz.apofis.e2e

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.rules.TestWatcher
import org.junit.runner.Description

const val APP = "xyz.rycerz.apofis"
const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
const val OUT_DIR = "/data/local/tmp/apofis-e2e" // written as the shell user; run-e2e.sh pulls it
const val SESSIONS = "$OUT_DIR/sessions.txt" // the session journal, created empty by run-e2e.sh before the tests run
private const val TAG = "ApofisE2e"
private val SESSION_ID = Regex("apofis-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val RUN_ID = Regex("run_[0-9a-f]{32}")
private val SCENARIO = Regex("[A-Za-z]{1,40}")
private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

fun fail(message: String): Nothing = throw AssertionError(message)

/**
 * Drives the app from this separate test process: shell commands, accessibility actions and the app's own string
 * resources. Arguments: apofisUrl (loopback, through adb reverse), apofisDirectUrl (the emulator's host alias,
 * 10.0.2.2), apofisNonce (this run's id, put into every prompt's first line, so into the chat's server title) and
 * apofisKey.
 */
class AppDriver {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val res = instrumentation.context.packageManager.getResourcesForApplication(APP)

    fun arg(name: String): String = requireNotNull(args.getString(name)?.takeIf(String::isNotEmpty)) { "Instrumentation argument $name is missing" }

    val url get() = arg("apofisUrl")
    val directUrl get() = arg("apofisDirectUrl")
    val nonce get() = arg("apofisNonce").also { require(Regex("[0-9a-f]{8}").matches(it)) { "Bad apofisNonce" } }
    private val key get() = arg("apofisKey")

    /** The push install the harness minted for each push scenario; empty when the run has no push provisioning. */
    val pushInstalls: Map<String, String> by lazy {
        val raw = args.getString("apofisPushInstalls") ?: return@lazy emptyMap()
        raw.split(',').associate { it.substringBefore('=') to it.substringAfter('=') }
            .also { m -> require(m.all { (k, v) -> SCENARIO.matches(k) && UUID.matches(v) }) { "Bad apofisPushInstalls" } }
    }
    val pushPort get() = arg("apofisPushPort").toInt().also { require(it in 1024..65535) { "Bad apofisPushPort" } }
    val pushTopic get() = arg("apofisPushTopic").also { require(Regex("up[A-Za-z0-9]{12}").matches(it)) { "Bad apofisPushTopic" } }

    /** The scenario now running, written into each journal line. */
    var scenario = "none"

    /** A string of the app, by resource name: the tests find elements by what the user sees. */
    fun str(name: String, vararg format: Any): String =
        res.getString(res.getIdentifier(name, "string", APP).also { require(it != 0) { "No string $name in the app" } }, *format)

    fun log(message: String) = Log.i(TAG, message)

    /** Runs [command] as the shell user (split on spaces, no shell quoting); [input] goes to its stdin. */
    fun shell(command: String, input: String? = null): String {
        val (out, stdin) = instrumentation.uiAutomation.executeShellCommandRw(command)
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { if (input != null) it.write(input.toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes().decodeToString() }
    }

    /** Runs [script] in the device's sh as the shell user, so it can use quoting, `$?` and redirections. */
    fun sh(script: String): String = shell("sh", script + "\n")

    // ---- app lifecycle

    /**
     * Clears the app's data (checked), grants local network access and (API 33+) notifications, launches. With push
     * provisioning, the push record intents in the app's data are journaled before the clear (an interrupted scenario's
     * too), and a push scenario's identity is written into the cleared data and read back before the launch.
     */
    fun freshStart(grantLocalNetwork: Boolean = true) {
        if (pushInstalls.isNotEmpty()) exportPushIntents(pushInstalls.keys) // whose they are is unknown if unreadable: all
        val cleared = shell("pm clear $APP").trim()
        if (cleared != "Success") fail("pm clear did not confirm: $cleared")
        if (grantLocalNetwork) grantLocalNetwork()
        // Granted up front, so the first send shows no permission dialog over the screen the tests drive.
        if (Build.VERSION.SDK_INT >= 33) {
            val out = shell("pm grant $APP $POST_NOTIFICATIONS")
            if (out.isNotBlank()) fail("pm grant $POST_NOTIFICATIONS failed: $out")
        }
        pushInstalls[scenario]?.let(::injectPushIdentity)
        launch()
    }

    /**
     * Writes this scenario's push identity (its install id, the run nonce, the fake ntfy's loopback origin) into the
     * cleared app's push prefs and reads them back: anything but exactly those values fails the scenario before push
     * can be turned on. With them the debug app titles every record "<id> op-<marker> e2e-<nonce>" and keeps an intent.
     */
    private fun injectPushIdentity(install: String) {
        val origin = "http://127.0.0.1:$pushPort"
        val xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
            "    <string name=\"install_id\">$install</string>\n    <string name=\"e2e_nonce\">$nonce</string>\n" +
            "    <string name=\"e2e_origin\">$origin</string>\n</map>"
        val read = shell("run-as $APP sh", "mkdir -p shared_prefs && cat > shared_prefs/push.xml <<'EOF'\n$xml\nEOF\ncat shared_prefs/push.xml\n")
        if (!injectionConfirmed(read, install, nonce, origin)) fail("The push identity was not read back as written; push stays off")
        log("push identity written and read back")
    }

    /**
     * Journals "pushintent <id> <title>" for every create intent in the app's push prefs, or "pushintent-missing
     * <scenario>" for each of [ifUnreadable] when they cannot be read: the build host then deletes nothing of those.
     */
    fun exportPushIntents(ifUnreadable: Collection<String>) {
        val read = sh("run-as $APP sh -c 'if [ -e shared_prefs/push.xml ]; then cat shared_prefs/push.xml && echo && echo @@READ; else echo @@NONE; fi'")
        pushIntentLines(read, ifUnreadable).forEach(::journal)
    }

    /** The device clock in seconds, for `logcat -T`. */
    fun deviceTime(): String = sh("date +%s").trim().also { if (!Regex("\\d+").matches(it)) fail("Bad device time: $it") } + ".000"

    /** The app's wake summaries (debug builds, tag ApofisWake) logged since [since]. */
    fun wakeLines(since: String): List<String> = sh("logcat -d -v raw -T $since -s ApofisWake:I").lines().filter { it.startsWith("cause=") }

    /** Crash lines of the app since [since]. */
    fun crashes(since: String): List<String> = sh("logcat -d -b crash -v raw -T $since").lines().filter { APP in it }

    /** The app's active notifications as `cmd notification list` keys; fails when the list cannot be read. */
    fun notifications(): List<String> {
        val out = sh("cmd notification list; echo rc=$?").trim().lines()
        if (out.lastOrNull() != "rc=0") fail("cmd notification list failed: $out")
        return out.dropLast(1).filter { "|$APP|" in it }
    }

    /** The rows of [sql] on the app's database, one line each (read through run-as, never the key). */
    fun query(sql: String): List<String> = shell("run-as $APP sqlite3 databases/apofis.db", "$sql\n").lines().filter(String::isNotBlank)

    /** The server title of session [id], after proving it is this run's (as [serverMessages] does). */
    fun serverTitle(id: String): String {
        if (!SESSION_ID.matches(id)) fail("Not an app session id")
        val (code, body) = http("GET", "api/sessions/$id")
        val s = if (code == 200) runCatching { JSONObject(body).getJSONObject("session") }.getOrNull() else null
        if (s?.optString("id") != id || !s.optString("title").contains(nonce)) fail("GET $id returned $code without this run's identity")
        return s.optString("title")
    }

    /** Push record [id] of this scenario's install on the server, read with the tests' key: its HTTP code and session. */
    fun pushRecord(id: String): Pair<Int, JSONObject?> {
        val install = pushInstalls[scenario] ?: fail("No push install for $scenario")
        if (!Regex("apofis-push-$install-[1-9][0-9]{0,5}").matches(id)) fail("Not a record of this scenario's install")
        val (code, body) = http("GET", "api/sessions/$id")
        return code to (if (code == 200) runCatching { JSONObject(body).getJSONObject("session") }.getOrNull() else null)
    }

    fun grantLocalNetwork() {
        val out = shell("pm grant $APP $LOCAL_NETWORK")
        if (out.isNotBlank()) fail("pm grant $LOCAL_NETWORK failed: $out")
        log("granted $LOCAL_NETWORK")
    }

    fun launch() {
        shell("am start -W -n $APP/.MainActivity")
        waitFor("the app in the foreground", 20_000) { device.currentPackageName == APP }
    }

    fun forceStop() = shell("am force-stop $APP")

    /** The app's process ids from `pidof`, with its exit code: 0 found, 1 none found; anything else is a failed query. */
    fun pidof(): Pair<Int, String> {
        val out = sh("pidof $APP; echo \"rc=$?\"").trim().lines()
        val rc = out.lastOrNull()?.removePrefix("rc=")?.toIntOrNull() ?: fail("pidof gave no exit code: $out")
        return rc to out.dropLast(1).joinToString(" ").trim()
    }

    /** True only when the shell positively saw that /proc/[pid] does not exist. */
    fun processGone(pid: String): Boolean = when (val r = sh("if [ -d /proc/$pid ]; then echo alive; else echo gone; fi").trim()) {
        "gone" -> true
        "alive" -> false
        else -> fail("Could not check /proc/$pid: $r")
    }

    fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        log("airplane mode ${if (on) "on" else "off"} requested")
    }

    /** The device's airplane mode setting: "1" on, "0" off; anything else fails. */
    fun airplaneOn(): Boolean = when (val v = shell("settings get global airplane_mode_on").trim()) {
        "1" -> true
        "0" -> false
        else -> fail("Unexpected airplane_mode_on: $v")
    }

    // ---- reading the screen

    fun nodes(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (match(node)) found += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        roots().forEach(::walk)
        return found
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val ua = instrumentation.uiAutomation
        ua.clearCache() // nodes from another process can be stale otherwise
        return ua.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ua.rootInActiveWindow) }
    }

    /** The app's own windows as plain data, for [UiTree]'s decisions. */
    fun snapshot(): UiTree {
        val out = mutableListOf<UiNode>()
        fun walk(n: AccessibilityNodeInfo?, parent: Int) {
            n ?: return
            val r = Rect().also(n::getBoundsInScreen)
            val id = out.size
            out += UiNode(
                id, parent, n.text?.toString(), n.contentDescription?.toString(), n.stateDescription?.toString(), n.className?.toString(),
                r.left, r.top, r.right, r.bottom, n.isClickable, n.isEnabled, n.isHeading, n.liveRegion, n.isEditable, n.viewIdResourceName,
            )
            for (i in 0 until n.childCount) walk(n.getChild(i), id)
        }
        roots().filter { it.packageName == APP }.forEach { walk(it, -1) }
        return UiTree(out)
    }

    /** The snapshot as lines for logcat when an assertion fails (text fields are left out: one may hold the key). */
    fun dump(): String = snapshot().nodes.filterNot { it.editable }.joinToString("\n") {
        "${it.id}<${it.parent} ${it.resId ?: ""} ${it.cls?.substringAfterLast('.')} [${it.left},${it.top},${it.right},${it.bottom}]" +
            "${if (it.clickable) " click" else ""}${if (it.heading) " heading" else ""}${if (it.liveRegion != 0) " live" else ""}" +
            " text=${it.text} desc=${it.desc} state=${it.state}"
    }

    private fun AccessibilityNodeInfo.label() = (text ?: contentDescription)?.toString()

    fun has(text: String) = nodes { it.label() == text }.isNotEmpty()
    fun hasDesc(desc: String) = nodes { it.contentDescription?.toString() == desc }.isNotEmpty()

    /** Visible texts outside text fields (a field may hold the key), for failure messages. */
    fun screen() = nodes { it.className != "android.widget.EditText" && !it.label().isNullOrEmpty() }.joinToString(" | ") { it.label()!! }

    /**
     * Polls [condition] every half second, calling [meanwhile] each round; fails naming [what] after [timeoutMs].
     * An AssertionError (a failed observation) ends the wait at once; other exceptions (stale nodes) count as "not yet".
     */
    fun waitFor(what: String, timeoutMs: Long, meanwhile: () -> Unit = {}, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            meanwhile()
            val ok = try {
                condition()
            } catch (e: Exception) {
                false
            }
            if (ok) return
            SystemClock.sleep(500)
        }
        log("screen when \"$what\" timed out:\n${dump()}")
        fail("Not seen within ${timeoutMs / 1000} s: $what. Screen: ${screen()}")
    }

    // ---- acting

    fun setField(field: AccessibilityNodeInfo, text: String) {
        // Not UiObject2.setText: it logs the text it sets, and the key must never reach logcat.
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) fail("Could not fill a text field")
    }

    /** Clicks the clickable element showing [label] as its text or content description (or a child's text). */
    fun click(label: String) {
        // The labelled node itself or its nearest clickable ancestor (Compose may keep an icon's label on the icon).
        fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = if (n.isClickable) n else n.parent?.let(::clickableOf)
        fun find() = nodes { it.label() == label }.firstNotNullOfOrNull { n -> clickableOf(n)?.takeIf { it.isEnabled } }
        waitFor("an enabled button \"$label\"", 10_000) { find() != null }
        val target = find() ?: fail("No enabled button \"$label\". Screen: ${screen()}")
        val bounds = Rect().also(target::getBoundsInScreen)
        log("click \"$label\": ${target.className} $bounds")
        // A real tap at the element's centre (UI Automator injects it); the element was found by its label above.
        if (!device.click(bounds.centerX(), bounds.centerY())) fail("Tapping \"$label\" failed")
    }

    /**
     * Clicks the enabled element tagged [tag] whose own or child content description is [desc], through its own
     * accessibility action, so the click goes to that element and not to whatever is at its position.
     */
    fun clickTag(tag: String, desc: String) {
        fun AccessibilityNodeInfo.descs(): List<String> =
            listOfNotNull(contentDescription?.toString()) + (0 until childCount).mapNotNull(::getChild).flatMap { it.descs() }
        fun find() = nodes { it.viewIdResourceName == tag && it.isEnabled && desc in it.descs() }.singleOrNull()
        waitFor("an enabled \"$desc\" ($tag)", 10_000) { find() != null }
        val target = find() ?: fail("No enabled \"$desc\" ($tag). Screen: ${screen()}")
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail("Clicking \"$desc\" ($tag) failed")
        log("clicked \"$desc\" ($tag)")
    }

    /** Fills the Connect screen and waits for the new chat; returns its id. Nothing is journaled: nothing was sent. */
    fun connect(serverUrl: String): String {
        fillConnect(serverUrl)
        click(str("connect_button"))
        return awaitChat(serverUrl)
    }

    fun fillConnect(serverUrl: String) {
        waitFor("the Connect screen", 20_000) { has(str("connect_title")) }
        val fields = nodes { it.className == "android.widget.EditText" } // screen order: server URL, then API key
        if (fields.size != 2) fail("Expected the URL and key fields, found ${fields.size}")
        setField(fields[0], serverUrl)
        setField(fields[1], key)
        device.waitForIdle()
    }

    /**
     * Waits for the chat and asserts it is a new, empty one: the chat screen's tag names its key, and that key's row is
     * the only local-only one in the app's database, with no other id. Returns the key.
     */
    fun awaitChat(serverUrl: String): String {
        waitFor("the chat after connecting to $serverUrl", 30_000) { hasDesc(str("drawer_open")) && !has(str("connect_title")) }
        // A fresh install has no synced chats, so the app must open a new local one; never write into another chat.
        // By the chat screen's own tags: the closed drawer (its New chat button, its search field) is in the tree too.
        waitFor("a new, empty chat", 10_000) {
            snapshot().let { t -> t.tagged("chat.title").any { str("chat_new_title") in t.texts(it) } } && composerField() != null
        }
        log("connected to $serverUrl")
        val key = openChatKey()
        var row: ChatRow? = null
        waitFor("the new chat in the app's database", 10_000) { chatRow(key).also { row = it } != null }
        // Other rows may exist: the app lists the server's app chats on every return to the foreground, which includes
        // earlier scenarios' chats not yet deleted. None of them may be local only.
        val local = shell("run-as $APP sqlite3 databases/apofis.db", "SELECT count(*) FROM SessionEntity WHERE existsOnServer = 0;\n").trim()
        val r = row!!
        if (local != "1" || r.currentId != key || r.lineage.isNotEmpty() || r.onServer) fail("Expected one new local chat in the app's database ($local local rows)")
        return key
    }

    /** The chat's message field, by its tag (not "the only text field": the closed drawer's search field is one too). */
    fun composerField(): AccessibilityNodeInfo? = nodes { it.viewIdResourceName == "composer.field" && it.isEditable }.singleOrNull()

    /** The key of the chat open on screen, from the chat screen's tag; fails unless exactly one, an app session id. */
    fun openChatKey(): String {
        var key: String? = null
        waitFor("the open chat's key on the chat screen", 10_000) { snapshot().chatKey().also { key = it } != null }
        return key?.takeIf(SESSION_ID::matches) ?: fail("The open chat's key is not an app session id")
    }

    /** One row of the app's sessions table: its current server id, earlier ids, and whether the server has it. */
    data class ChatRow(val currentId: String, val lineage: List<String>, val onServer: Boolean)

    /** The app's database row of chat [key], and no other row; null when there is none. Fails on anything it cannot parse. */
    fun chatRow(key: String): ChatRow? {
        if (!SESSION_ID.matches(key)) fail("Not an app session id")
        val out = shell("run-as $APP sqlite3 databases/apofis.db", "SELECT currentId, lineageIds, existsOnServer FROM SessionEntity WHERE key = '$key';\n")
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val cols = lines.single().split('|')
        val lineage = cols.getOrNull(1)?.split(',')?.filter(String::isNotEmpty).orEmpty()
        if (lines.size != 1 || cols.size != 3 || !SESSION_ID.matches(cols[0]) || lineage.any { !SESSION_ID.matches(it) } || cols[2] !in setOf("0", "1")) {
            fail("Unexpected output reading the app's chat")
        }
        return ChatRow(cols[0], lineage, cols[2] == "1")
    }

    /** Whether the app has created chat [key] (the chat this test opened) on the server. Reads only that chat's row. */
    fun chatOnServer(key: String): Boolean = (chatRow(key) ?: fail("The chat $key is gone from the app's database")).onServer

    /** The chat this scenario sent a message in, if any; [journalSent] writes its one completing line at the end. */
    private var sentChat: String? = null

    /**
     * Writes the scenario's one completing journal line when it sent a message: "chat <scenario> <key> <ids>", the
     * chat's server ids (current first, then earlier ones), or "none" when the app never created it on the server. The
     * build host requires exactly one such line for every scenario with a send line. Call once, at the scenario's end.
     */
    fun journalSent() {
        val key = sentChat ?: return
        sentChat = null
        val row = chatRow(key)
        val ids = if (row?.onServer == true) (listOf(row.currentId) + row.lineage).distinct().joinToString(",") else "none"
        journal("chat $scenario $key $ids")
    }

    /** Journals the id of the run the app is following for chat [sessionKey] (exactly one), with that chat's id. */
    fun journalRun(sessionKey: String): String {
        if (!SESSION_ID.matches(sessionKey)) fail("Not an app session id")
        val ids = shell("run-as $APP sqlite3 databases/apofis.db", "SELECT runId FROM ActiveRunEntity WHERE sessionKey = '$sessionKey';\n").lines().filter(String::isNotBlank)
        val id = ids.singleOrNull()?.takeIf(RUN_ID::matches) ?: fail("Expected one active run for the chat, found ${ids.size}")
        journal("run $id $sessionKey")
        return id
    }

    /**
     * Appends [line] to the device's session journal (once) and reads it back. run-e2e.sh pulls the journal even when
     * this process dies, and the build host reads or deletes on the server only what it names, after checking each
     * session's own record.
     */
    fun journal(line: String) {
        val p = line.split(' ')
        val ok = when (p[0]) {
            "send" -> p.size == 3 && SESSION_ID.matches(p[1]) && SCENARIO.matches(p[2])
            "chat" -> p.size == 4 && SCENARIO.matches(p[1]) && SESSION_ID.matches(p[2]) &&
                (p[3] == "none" || p[3].split(',').let { ids -> ids.size in 1..8 && ids.all(SESSION_ID::matches) })
            "run" -> p.size == 3 && RUN_ID.matches(p[1]) && SESSION_ID.matches(p[2])
            "pushintent" -> PUSH_INTENT.matches(line)
            "pushintent-missing" -> p.size == 2 && SCENARIO.matches(p[1])
            else -> false
        }
        if (!ok) fail("Not a journal line")
        val r = sh("[ -f $SESSIONS ] && { grep -qx '$line' $SESSIONS || echo '$line' >> $SESSIONS; } && grep -qx '$line' $SESSIONS && echo journaled").trim()
        if (r != "journaled") fail("Could not journal \"$line\": $r")
        log("journaled $line")
    }

    /**
     * Sends [text] in the open chat, which must be [chat] (read from the chat screen's tag first): journals "send <chat>
     * <scenario>" before pressing the button showing [button] (Send, or Send to the running reply), so the build host
     * knows a message may have created a session. When [online], waits until the app has created the chat on the server.
     */
    fun send(chat: String, text: String, button: String = str("chat_send"), online: Boolean = true) {
        if (openChatKey() != chat) fail("The open chat is not the one this test opened")
        val composer = composerField() ?: fail("No composer. Screen: ${screen()}")
        setField(composer, text)
        device.waitForIdle()
        if (sentChat != null && sentChat != chat) fail("A scenario sends in one chat only")
        sentChat = chat
        journal("send $chat $scenario")
        clickTag("composer.send", button)
        log("sent: $text")
        if (online) waitFor("the chat on the server", 15_000) { chatOnServer(chat) }
    }

    /**
     * The harness never approves anything: an approval block on screen fails the test at once, without touching it
     * (the owner's server does not ask for approval for these commands).
     */
    fun failOnApproval() {
        if (snapshot().approvalShown(str("chat_approval_title"))) {
            log("APPROVAL requested by the server; not answered")
            fail("The server asked for approval; the harness never approves, so the scenario fails")
        }
    }

    fun screenshot(name: String) {
        sh("mkdir -p $OUT_DIR && screencap -p $OUT_DIR/$name.png")
        log("screenshot $name.png")
    }

    /** Writes the test's own result file next to the screenshots: run-e2e.sh requires one per test in full mode. */
    fun result(d: Description, outcome: String) {
        sh("mkdir -p $OUT_DIR/results && echo $outcome > $OUT_DIR/results/${d.testClass.simpleName}.${d.methodName}.txt")
    }

    // ---- the server's record (read only; deletion is run-e2e.sh's job)

    /**
     * The rows of session [id] on the server, oldest first. First proves the session is this run's (a GET on [id]
     * names [id], an app id, with this run's nonce in its title); fails unless both answers name exactly [id].
     */
    fun serverMessages(id: String): List<JSONObject> {
        if (!SESSION_ID.matches(id)) fail("Not an app session id")
        val (sc, sb) = http("GET", "api/sessions/$id")
        val s = if (sc == 200) runCatching { JSONObject(sb).getJSONObject("session") }.getOrNull() else null
        if (s?.optString("id") != id || !s.optString("title").contains(nonce)) fail("GET $id returned $sc without this run's identity")
        val (code, body) = http("GET", "api/sessions/$id/messages?order=oldest&include_compacted=true")
        if (code != 200) fail("GET messages of $id returned $code")
        val json = JSONObject(body)
        if (json.optString("session_id") != id) fail("The server answered for ${json.optString("session_id")}, not $id")
        val rows = json.getJSONArray("data")
        return (0 until rows.length()).map(rows::getJSONObject)
    }

    private fun http(method: String, path: String): Pair<Int, String> {
        val c = URL(url.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Authorization", "Bearer $key")
        return try {
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}

/** Saves a screenshot when a test fails and writes every test's own result file. */
class Evidence(private val app: AppDriver) : TestWatcher() {
    override fun succeeded(description: Description) = app.result(description, "PASS")

    override fun failed(e: Throwable?, description: Description) {
        runCatching { app.screenshot("failed-${description.methodName}") }
        app.result(description, "FAIL")
    }
}
