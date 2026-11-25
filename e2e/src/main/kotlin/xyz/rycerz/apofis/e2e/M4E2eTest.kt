package xyz.rycerz.apofis.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Milestone 4: temporary chats and the per-chat model choice. temporaryToggleMarks starts no model run and runs with no
 * live transport (--m4); modelPickIsReachable starts none either (a single --test, once, before the paid choice method);
 * temporaryChatGoneAfterLeaving and modelChoiceReachesRun send one message each, no steer (--m4-model, reserved in the
 * Milestone 4 ledger). Prompts carry the run's nonce in their first line, so the chat's server title does; a chat is
 * left only after every id the app will delete for it is journaled and proved this run's.
 */
@RunWith(AndroidJUnit4::class)
class M4E2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

    private val reply = 300_000L
    private val offline get() = name.methodName == "temporaryToggleMarks"
    private lateinit var session: String

    @Before
    fun start() {
        app.log("scenario ${name.methodName} starts")
        app.scenario = name.methodName
        if (offline) {
            session = app.connectThenOffline()
        } else {
            app.freshStart()
            session = app.connect(app.directUrl)
        }
    }

    @After
    fun cleanUp() {
        if (offline) app.clearThenOnline() else app.journalSent() // the completing journal line, also when it failed
        app.log("scenario ${name.methodName} ends")
    }

    /** The chat title (by its tag) reads [title]. */
    private fun titled(title: String) = app.snapshot().let { t -> t.tagged("chat.title").any { title in t.texts(it) } }

    private fun openDrawer() {
        app.click(app.str("drawer_open"))
        app.waitFor("the open drawer", 10_000) { app.snapshot().tagged("chat.title").isEmpty() && app.has(app.str("settings_title")) }
    }

    /** Back until the chat shows again (a first Back may only close the keyboard). */
    private fun closeDrawer() {
        repeat(3) {
            app.device.pressBack()
            val end = SystemClock.uptimeMillis() + 3_000
            while (SystemClock.uptimeMillis() < end) {
                if (runCatching { app.snapshot().tagged("chat.title").size == 1 }.getOrDefault(false)) return
                SystemClock.sleep(300)
            }
        }
        fail("The chat did not come back after closing the drawer")
    }

    /**
     * Every text of the drawer's chat list, top to bottom, the whole list inspected: scrolled to its start, then page by
     * page to its end, each page merged on its overlap with the one before. Never taps a row.
     */
    private fun drawerLabels(): List<String> {
        openDrawer()
        val panel = app.nodes { it.viewIdResourceName == "drawer.panel" }.singleOrNull() ?: fail("No drawer panel")
        val list = app.nodesIn(panel) { it.isScrollable && !it.isEditable }.firstOrNull() // not the search field
        fun page() = app.nodesIn(list ?: panel) { it.text != null && it.isVisibleToUser && !it.isEditable }
            .sortedBy { Rect().also(it::getBoundsInScreen).top }.map { it.text.toString() }
        fun scroll(action: Int): Boolean = list != null && list.refresh() && list.performAction(action).also { app.device.waitForIdle() }
        var last = page()
        for (i in 0 until 30) {
            if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
            val p = page()
            if (p == last) break
            last = p
        }
        var all = last
        for (i in 0 until 30) {
            if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            val p = page()
            if (p == last) break
            all = mergePages(all, p)
            last = p
        }
        closeDrawer()
        return all
    }

    /**
     * Whether the drawer lists a chat whose stored title contains [text], by the drawer's own search: a matching row, or
     * the "No chats match" line, each positive. No search field, or both or neither shown: inconclusive, the method fails.
     */
    private fun drawerLists(text: String): Boolean {
        openDrawer()
        val field = app.nodes { it.isEditable }.singleOrNull() ?: fail("Inconclusive: no drawer search field")
        app.setField(field, text)
        var listed: Boolean? = null
        app.waitFor("the drawer's search answer", 10_000) {
            val noMatch = app.has(app.str("drawer_no_match"))
            val row = app.has(app.str("chat_temporary_title")) || app.nodes { !it.isEditable && it.text?.contains(text) == true }.isNotEmpty()
            listed = if (noMatch != row) row else null
            listed != null
        }
        app.nodes { it.isEditable }.singleOrNull()?.let { app.setField(it, "") }
        closeDrawer()
        return listed!!
    }

    /** Makes the open, never-created empty chat temporary; returns its new key. */
    private fun toggleOn(): String {
        app.click(app.str("chat_temporary_on"))
        var key: String? = null
        app.waitFor("a temporary chat titled as one", 10_000) {
            titled(app.str("chat_temporary_title")) && app.snapshot().chatKey()?.takeIf { it.startsWith("apofis-tmp-") }.also { key = it } != null
        }
        return key!!
    }

    @Test
    fun temporaryToggleMarks() {
        val temporary = app.str("chat_temporary_title")
        val before = drawerLabels().count { it == temporary }
        val key = toggleOn()
        if (app.chatRow(key) == null || app.chatRow(session) != null) fail("The toggle did not move the chat to its temporary key")
        val after = drawerLabels().count { it == temporary }
        if (after != before) fail("The whole drawer lists $after temporary chats, $before before the toggle: it lists the open one")
        app.log("toggled on: titled as temporary, its row moved, the whole drawer lists no row for it ($before other temporary rows)")
        app.click(app.str("chat_temporary_off"))
        app.waitFor("an ordinary new chat again", 10_000) {
            titled(app.str("chat_new_title")) && app.snapshot().chatKey().let { it != null && !it.startsWith("apofis-tmp-") }
        }
        app.log("toggled back")
    }

    @Test
    fun temporaryChatGoneAfterLeaving() {
        val key = toggleOn()
        val prompt = "Test ${app.nonce}-7. Reply with exactly: apofis-e2e-7"
        app.send(key, prompt)
        // The server refuses a delete while a run holds the session: the turn must have ended before the leave.
        val end = app.awaitSendEnd(key, reply)
        if (end !is SendEnd.Run) fail("The turn did not settle; not leaving")
        val targets = app.proveLeave(key)
        app.click(app.str("drawer_new_chat"))
        val deadline = SystemClock.uptimeMillis() + 60_000 // from the leave
        app.waitFor("another chat open", 10_000) { app.snapshot().chatKey().let { it != null && it != key } }
        val result = targets.associateWith { "no answer" }.toMutableMap()
        val gone = mutableSetOf<String>()
        while (gone.size < targets.size) {
            for (id in targets - gone) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0) break
                result[id] = try {
                    val (code, error) = app.sessionCode(id, left.toInt())
                    when {
                        SystemClock.uptimeMillis() > deadline -> "late" // answered after the limit: does not count
                        code == 404 && error == "session_not_found" -> "404 session_not_found".also { gone += id }
                        code == 404 -> "404 other"
                        else -> "http $code"
                    }
                } catch (e: Exception) {
                    "no answer"
                }
            }
            val left = deadline - SystemClock.uptimeMillis()
            if (gone.size == targets.size || left <= 0) break
            SystemClock.sleep(minOf(10_000L, left))
        }
        targets.forEach { app.log("after leaving, $it: ${result[it]}") }
        val allGone = gone.size == targets.size
        val localGone = app.chatRow(key) == null
        val note = app.has(app.str("chat_temporary_not_deleted")) // the note the app puts into the next chat
        val listed = drawerLists("${app.nonce}-7")
        app.log("after leaving: every id 404 session_not_found within 60 s $allGone, local row gone $localGone, listed $listed, note $note")
        when {
            allGone && localGone && !listed && !note -> app.log("temporary chat after leaving: deleted")
            !localGone && listed && note -> {
                app.log("temporary chat after leaving: kept and listed with the note")
                fail("The temporary chat was kept after leaving")
            }
            else -> fail("Inconclusive after leaving; no outcome named")
        }
    }

    /**
     * Zero starts: the model sheet is reachable through the palette, the alternative is picked and stored, and Server
     * default is restored the same way; nothing is sent. Needs the slash palette (Task 8a-2). Task 13b runs it once,
     * as a single --test, before modelChoiceReachesRun spends its start.
     */
    @Test
    fun modelPickIsReachable() {
        val pick = pickAlternative(app.modelOptions())
        chooseModel(session, pick)
        app.log("model pick reachable: ${if (pick != null) "the alternative picked and stored" else "no alternative: Server default stored"}")
        if (pick != null) {
            chooseModel(session, null)
            app.log("model pick reachable: Server default restored")
        }
    }

    @Test
    fun modelChoiceReachesRun() {
        val pick = pickAlternative(app.modelOptions())
        chooseModel(session, pick) // proved stored before anything is sent
        val prompt = "Automated app test ${app.nonce}-8. Without using any tool, reply with one short line that includes apofis-e2e-8."
        app.send(session, prompt, online = false) // replaces the draft: Send is never pressed on a draft starting with "/"
        val (outcome, detail) = choiceOutcome(app.awaitSendEnd(session, reply), pick)
        app.log("model choice: $outcome$detail")
        if (outcome !in CHOICE_RECORDED) fail("model choice: $outcome")
    }

    /**
     * Opens the model sheet through the palette and taps [pick] (slug to model) or, null, Server default, inside the
     * sheet only: the sheet's list is found once while its title shows and kept (refreshed before each scroll); the rows
     * must have loaded (a load failure fails at once). Before returning, i.e. before any send: the sheet is gone (its
     * handle) and the chat's stored choice is exactly the pick (both empty for Server default). Every wait and failure
     * names a fixed step only: the sheet lists model and provider names.
     */
    private fun chooseModel(key: String, pick: Pair<String, String>?) {
        val handle = app.str("sheet_handle")
        val title = app.str("model_title")
        val target = pick?.second ?: app.str("model_default")
        fun sheet(): AccessibilityNodeInfo? = app.appRoots().firstOrNull { r -> app.nodesIn(r) { it.contentDescription?.toString() == handle }.isNotEmpty() }
        fun textIn(s: AccessibilityNodeInfo, text: String) = app.nodesIn(s) { it.text?.toString() == text }

        app.setField(app.composerField() ?: fail("No composer"), "/m")
        app.paletteRow("model", dump = false)
        var found: AccessibilityNodeInfo? = null
        app.waitFor("the model sheet's list", 10_000, dump = false) {
            found = sheet()?.let { s -> textIn(s, title).firstOrNull() }?.let { t -> generateSequence(t) { it.parent }.firstOrNull { it.isScrollable } }
            found != null
        }
        val list = found!!
        app.waitFor("the model rows loaded", 20_000, dump = false) {
            val s = sheet() ?: fail("The model sheet closed before the pick")
            if (textIn(s, app.str("model_load_failed")).isNotEmpty()) fail("The model list failed to load")
            pick == null || app.nodesIn(s) { n -> n.text?.toString() == pick.second || (n.text?.toString() == pick.first && n.isHeading) }.isNotEmpty()
        }
        val (row, scrolls) = revealIn(object : ScrollList<AccessibilityNodeInfo> {
            override fun find() = sheet()?.let { s -> textIn(s, target).firstOrNull() }
            override fun visible(n: AccessibilityNodeInfo) = n.isVisibleToUser
            override fun scroll(forward: Boolean): Boolean {
                if (!list.refresh()) return false
                val ok = list.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                app.device.waitForIdle()
                return ok
            }
            override fun shown(): Any = sheet()?.let { s -> app.nodesIn(s) { it.isVisibleToUser && it.text != null }.map { "${it.text}@" + Rect().also(it::getBoundsInScreen) } }.orEmpty()
        })
        row ?: fail("The chosen row is not in the model sheet")
        val button = app.clickableOf(row)?.takeIf { it.isEnabled } ?: fail("The chosen row is not an enabled button")
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail("Tapping the chosen row failed")
        app.log("tapped the chosen row after $scrolls scroll(s)") // never its name
        app.waitFor("the model sheet gone", 10_000, dump = false) { sheet() == null }
        val want = pick?.let { "${it.first}|${it.second}" } ?: "|"
        app.waitFor("the chat's stored model choice", 10_000, dump = false) { app.query(CHOICE_OF(key))?.singleOrNull() == want }
    }

    /**
     * The default provider's first model that is not the default model and is listed once across all usable providers
     * (each row is labelled with the bare model id, so a duplicate would make the tap ambiguous), as (slug, model); null
     * when there is none. Usable as the app's sheet lists them: not marked unauthenticated, non-blank, at most 256 chars.
     */
    private fun pickAlternative(o: JSONObject): Pair<String, String>? {
        fun JSONArray.strings() = (0 until length()).map { optString(it) }.filter { it.isNotBlank() && it.length <= 256 }
        val providers = o.optJSONArray("providers") ?: return null
        val usable = (0 until providers.length()).mapNotNull(providers::optJSONObject).filter { it.opt("authenticated") != false }
        val all = usable.flatMap { (it.optJSONArray("models") ?: JSONArray()).strings().distinct() }
        val slugs = usable.map { it.optString("slug") }.toSet()
        val p = usable.firstOrNull { it.optString("slug") == o.optString("provider") } ?: return null
        val m = (p.optJSONArray("models") ?: JSONArray()).strings().firstOrNull { it != o.optString("model") && all.count(it::equals) == 1 && it !in slugs }
        return m?.let { p.optString("slug") to it }
    }

    companion object {
        /** The stored model choice of chat [key] as "provider|model" (each empty when unset). Read only. */
        fun CHOICE_OF(key: String): String {
            require(Regex("apofis-(tmp-)?[0-9a-f-]{36}").matches(key)) { "Not an app session id" }
            return "SELECT ifnull(provider, '') || '|' || ifnull(model, '') FROM SessionEntity WHERE key = '$key';"
        }
    }
}

/** The outcomes of modelChoiceReachesRun that are recorded results; any other fails the method. */
val CHOICE_RECORDED = setOf("proved", "served differs", "not proved: no runtime", "not proved: no alternative")

/**
 * modelChoiceReachesRun's outcome word and its fixed detail (booleans and fixed phrases only, never a name), from how its
 * one send ended and the [pick] (null: Server default):
 * - "proved": a completed run whose served runtime model and provider both equal the pick;
 * - "served differs": a completed run whose runtime names a model or provider, not both equal to the pick;
 * - "not proved: no runtime": a completed run whose runtime is absent or has both fields blank;
 * - "not proved: no alternative": Server default, and its run completed;
 * - "not admitted (never attempted): zero starts": the send failed and the app's own flag says no run request of it
 *   may have reached the server;
 * - "unproved failure, starts unknown": anything else (a run that ended otherwise, a failure after a possible request,
 *   no settled run in time).
 */
fun choiceOutcome(end: SendEnd, pick: Pair<String, String>?): Pair<String, String> = when (end) {
    SendEnd.NotAdmitted -> "not admitted (never attempted): zero starts" to ""
    is SendEnd.Unknown -> "unproved failure, starts unknown" to " (${end.why})"
    is SendEnd.Run -> {
        val status = end.record.optString("status")
        if (status != "completed") {
            "unproved failure, starts unknown" to " (the run ended $status)"
        } else if (pick == null) {
            "not proved: no alternative" to ""
        } else {
            val rt = end.record.optJSONObject("runtime")?.takeIf { it.optString("model").isNotBlank() || it.optString("provider").isNotBlank() }
            val model = rt?.optString("model") == pick.second
            val provider = rt?.optString("provider") == pick.first
            val word = when {
                rt == null -> "not proved: no runtime"
                model && provider -> "proved"
                else -> "served differs"
            }
            word to " (runtime.model echoes the pick: $model, runtime.provider echoes its slug: $provider)"
        }
    }
}

/** Two pages of one list joined: [next] without its longest start that repeats the end of [all]. */
fun mergePages(all: List<String>, next: List<String>): List<String> {
    val k = (minOf(all.size, next.size) downTo 0).first { all.takeLast(it) == next.take(it) }
    return all + next.drop(k)
}
