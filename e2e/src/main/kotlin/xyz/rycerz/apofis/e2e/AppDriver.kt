package xyz.rycerz.apofis.e2e

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.net.HttpURLConnection
import java.net.URL

const val APP = "xyz.rycerz.apofis"
const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
const val OUT_DIR = "/data/local/tmp/apofis-e2e" // written as the shell user; run-e2e.sh pulls it
private const val TAG = "ApofisE2e"

fun fail(message: String): Nothing = throw AssertionError(message)

/**
 * Drives the app from this separate test process: shell commands, accessibility actions and the app's own string
 * resources. Arguments: apofisUrl (loopback, through adb reverse), apofisDirectUrl (the emulator's host alias,
 * 10.0.2.2) and apofisKey.
 */
class AppDriver {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val res = instrumentation.context.packageManager.getResourcesForApplication(APP)

    fun arg(name: String): String = requireNotNull(args.getString(name)?.takeIf(String::isNotEmpty)) { "Instrumentation argument $name is missing" }

    val url get() = arg("apofisUrl")
    val directUrl get() = arg("apofisDirectUrl")
    private val key get() = arg("apofisKey")

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

    // ---- app lifecycle

    /** Clears the app's data (a fresh install as far as the app can tell), grants local network access, launches. */
    fun freshStart(grantLocalNetwork: Boolean = true) {
        shell("pm clear $APP")
        if (grantLocalNetwork) grantLocalNetwork()
        launch()
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

    fun appRunning() = shell("pidof $APP").isNotBlank()

    fun airplane(on: Boolean) {
        shell("cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
        log("airplane mode ${if (on) "on" else "off"}")
    }

    // ---- reading the screen

    fun nodes(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?) {
            node ?: return
            if (match(node)) found += node
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        val ua = instrumentation.uiAutomation
        ua.clearCache() // nodes from another process can be stale otherwise
        ua.windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(ua.rootInActiveWindow) }.forEach(::walk)
        return found
    }

    private fun AccessibilityNodeInfo.label() = (text ?: contentDescription)?.toString()

    /** The bottom edge of the app bar (its Settings button), so the chat title is not taken for a message. */
    fun appBarBottom(): Int = nodes { it.contentDescription?.toString() == str("settings_title") }.firstOrNull()
        ?.let { android.graphics.Rect().also(it::getBoundsInScreen).bottom } ?: 0

    fun inContent(n: AccessibilityNodeInfo, barBottom: Int) = android.graphics.Rect().also(n::getBoundsInScreen).top >= barBottom

    fun has(text: String) = nodes { it.label() == text }.isNotEmpty()
    fun hasDesc(desc: String) = nodes { it.contentDescription?.toString() == desc }.isNotEmpty()
    fun hasState(state: String) = nodes { it.stateDescription?.toString() == state }.isNotEmpty()
    fun textsContaining(part: String) = nodes { it.text?.contains(part) == true }.map { it.text.toString() }

    /** Visible texts outside text fields (a field may hold the key), for failure messages. */
    fun screen() = nodes { it.className != "android.widget.EditText" && !it.label().isNullOrEmpty() }.joinToString(" | ") { it.label()!! }

    /** Polls [condition] every half second, calling [meanwhile] each round; fails naming [what] after [timeoutMs]. */
    fun waitFor(what: String, timeoutMs: Long, meanwhile: () -> Unit = {}, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            meanwhile()
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(500)
        }
        fail("Not seen within ${timeoutMs / 1000} s: $what. Screen: ${screen()}")
    }

    // ---- acting

    private fun setField(field: AccessibilityNodeInfo, text: String) {
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
        val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
        log("click \"$label\": ${target.className} $bounds")
        // A real tap at the element's centre (UI Automator injects it); the element was found by its label above.
        if (!device.click(bounds.centerX(), bounds.centerY())) fail("Tapping \"$label\" failed")
    }

    /** Fills the Connect screen and waits for the chat; asserts it is a new, empty chat. */
    fun connect(serverUrl: String) {
        fillConnect(serverUrl)
        click(str("connect_button"))
        awaitChat(serverUrl)
    }

    fun fillConnect(serverUrl: String) {
        waitFor("the Connect screen", 20_000) { has(str("connect_title")) }
        val fields = nodes { it.className == "android.widget.EditText" } // screen order: server URL, then API key
        if (fields.size != 2) fail("Expected the URL and key fields, found ${fields.size}")
        setField(fields[0], serverUrl)
        setField(fields[1], key)
        device.waitForIdle()
    }

    fun awaitChat(serverUrl: String) {
        waitFor("the chat after connecting to $serverUrl", 30_000) { hasDesc(str("settings_title")) && !has(str("connect_title")) }
        // A fresh install has no synced chats, so the app must open a new local one; never write into another chat.
        waitFor("a new, empty chat", 10_000) { has(str("chat_new_title")) && nodes { it.className == "android.widget.EditText" }.size == 1 }
        log("connected to $serverUrl")
    }

    /** Types [text] into the composer and presses [button] (Send, or Send to the running reply). */
    fun send(text: String, button: String = str("chat_send")) {
        val composer = nodes { it.className == "android.widget.EditText" }.singleOrNull() ?: fail("No composer. Screen: ${screen()}")
        setField(composer, text)
        device.waitForIdle()
        click(button)
        log("sent: $text")
    }

    /**
     * Approves an approval block only when it asks for exactly [command], the one the test requested; any other
     * request fails the test. Returns true when it approved.
     */
    fun approveIfAsked(command: String): Boolean {
        if (!has(str("chat_approval_title"))) return false
        if (textsContaining(command).isEmpty()) fail("An approval asked for something other than \"$command\". Screen: ${screen()}")
        log("APPROVAL requested for: $command; allowing once")
        click(str("chat_approval_once"))
        SystemClock.sleep(1000)
        return true
    }

    fun screenshot(name: String) {
        shell("mkdir -p $OUT_DIR")
        shell("screencap -p $OUT_DIR/$name.png")
        log("screenshot $name.png")
    }

    // ---- sessions the app created

    /**
     * Ids of the sessions in the app's database. After [freshStart] the app has no synced chats (the drawer that
     * would list them does not exist yet), so these are only sessions the app created during this test.
     */
    fun appSessions(): List<Pair<String, String?>> {
        val out = shell("run-as $APP sqlite3 databases/apofis.db", "SELECT currentId, lineageIds, title FROM SessionEntity;\n")
        return out.lines().filter { it.isNotBlank() }.flatMap { line ->
            val cols = line.split('|', limit = 3)
            if (!cols[0].startsWith("apofis-")) fail("Unexpected output reading the app's sessions: $out")
            (listOf(cols[0]) + cols.getOrElse(1) { "" }.split(',')).filter { it.isNotBlank() }.distinct().map { it to cols.getOrNull(2) }
        }
    }

    /** Records [ids] for run-e2e.sh, which checks after the run that each one is gone from the server. */
    fun record(ids: Collection<String>) {
        if (ids.isEmpty()) return
        shell("mkdir -p $OUT_DIR")
        shell("tee -a $OUT_DIR/sessions.txt", ids.joinToString("\n", postfix = "\n"))
    }

    /**
     * Deletes [id] on the server, but only after a GET confirms it is an app session (prefix apofis-) whose title is
     * one this test set ([titlePrefixes]) or none. Returns a description of what happened.
     */
    fun deleteSession(id: String, titlePrefixes: List<String>): String {
        if (!id.startsWith("apofis-")) return "$id: not an app session, left alone"
        val (code, body) = http("GET", "api/sessions/$id")
        if (code == 404) return "$id: not on the server"
        if (code != 200) return "$id: GET returned $code, left alone"
        val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        if (!title.isNullOrEmpty() && titlePrefixes.none(title::startsWith)) return "$id: title \"$title\" is not this test's, left alone"
        val (deleted, _) = http("DELETE", "api/sessions/$id")
        return "$id: DELETE returned $deleted"
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
