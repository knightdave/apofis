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
        app.quiet = name.methodName in QUIET_METHODS // first: setup, picker, Send and every failure name their step only
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
     * page to its end, each page merged on its overlap with the one before. Reaching neither end within the bound, or
     * losing the list node, fails "inconclusive: drawer too long to inspect": it never returns a partial walk. Never taps.
     */
    private fun drawerLabels(): List<String> {
        val tooLong = "drawer too long to inspect"
        openDrawer()
        val panel = app.nodes { it.viewIdResourceName == "drawer.panel" }.singleOrNull() ?: fail("No drawer panel")
        val list = app.nodesIn(panel) { it.isScrollable && !it.isEditable }.firstOrNull() // not the search field
        fun page() = app.nodesIn(list ?: panel) { it.text != null && it.isVisibleToUser && !it.isEditable }
            .sortedBy { Rect().also(it::getBoundsInScreen).top }.map { it.text.toString() }
        fun toEnd(action: Int, each: (List<String>) -> Unit) {
            if (list == null) return // a list that fits: one page
            repeat(40) { if (!app.scrollStep(list, action, tooLong, ::page)) return else each(page()) }
            fail("inconclusive: $tooLong")
        }
        toEnd(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {}
        var all = page()
        toEnd(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { all = mergePages(all, it) }
        closeDrawer()
        return all
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
        // The server refuses a delete while a run holds the session: the turn must have ended before the leave, on the
        // server and on the phone (no run it follows, no pending send), before the ids are read and proved.
        val end = app.awaitSendEnd(key, reply)
        if (end !is SendEnd.Run) fail("The turn did not settle; not leaving")
        app.awaitLocalSettlement(key)
        val targets = app.proveLeave(key) // then nothing else before the tap that leaves
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
        val localGone = app.chatGone(key) // the checked query: an unreadable answer fails, never "no row"
        // The note the app puts into the next chat when the delete failed, read from that chat's notes.
        val note = app.nodes { it.viewIdResourceName == "chat.notes" }.any { n -> app.nodesIn(n) { it.text?.toString() == app.str("chat_temporary_not_deleted") }.isNotEmpty() }
        // The drawer is not observed: that it draws only local rows is pinned by the app's unit tests.
        app.log("after leaving: every id 404 session_not_found within 60 s $allGone, local row gone $localGone, note $note")
        when {
            allGone && localGone && !note -> app.log("temporary chat after leaving: deleted")
            !localGone && note -> {
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
        // The independent, identity-checked session evidence, whatever the run record gave: counts only.
        val rows = runCatching { app.serverMessages(session) }.getOrNull()
        app.log("session evidence: ${if (rows == null) "unreadable" else "identity proved, user rows ${rows.count { it.str("role") == "user" }}, final answers ${finals(rows).size}"}")
        if (outcome !in CHOICE_RECORDED) fail("model choice: $outcome")
    }

    /**
     * Opens the model sheet through the palette (its row tapped in the app's window by its own action) and taps [pick]
     * (slug to model) or, null, Server default, inside the sheet only. The sheet's list is found once while its title
     * shows and kept (none: a list that fits, searched as it is). With a pick, the sheet must first show loaded rows (a
     * provider heading besides its title; the load-failed text fails at once). Rows are matched as rows: clickable, not a
     * heading. A visible hit needs no scroll; otherwise the kept list is searched to both ends ([AppDriver.seek]); an
     * exhausted bound is "inconclusive: list too long". Before returning, i.e. before any send: the sheet is gone (its
     * handle) and the chat's stored provider and model equal the pick field by field (both empty for Server default).
     */
    private fun chooseModel(key: String, pick: Pair<String, String>?) {
        val handle = app.str("sheet_handle")
        val title = app.str("model_title")
        val target = pick?.second ?: app.str("model_default")
        fun sheet(): AccessibilityNodeInfo? = app.appRoots().firstOrNull { r -> app.nodesIn(r) { it.contentDescription?.toString() == handle }.isNotEmpty() }
        fun rows(s: AccessibilityNodeInfo, label: String) = app.nodesIn(s) { it.text?.toString() == label && !it.isHeading && app.clickableOf(it) != null }

        app.setField(app.composerField() ?: fail("No composer"), "/m")
        app.paletteRow("model")
        var list: AccessibilityNodeInfo? = null
        app.waitFor("the model sheet", 10_000) {
            val heading = sheet()?.let { s -> app.nodesIn(s) { it.isHeading && it.text?.toString() == title }.firstOrNull() } ?: return@waitFor false
            list = generateSequence(heading) { it.parent }.firstOrNull { it.isScrollable }
            true
        }
        app.waitFor("the model rows loaded", 20_000) {
            val s = sheet() ?: fail("The model sheet closed before the pick")
            if (app.nodesIn(s) { it.text?.toString() == app.str("model_load_failed") }.isNotEmpty()) fail("The model list failed to load")
            if (pick == null) rows(s, target).isNotEmpty() else app.nodesIn(s) { it.isHeading && it.text?.toString() != title }.isNotEmpty()
        }
        val shown = { sheet()?.let { s -> app.nodesIn(s) { it.isVisibleToUser && it.text != null }.map { "${it.text}@" + Rect().also(it::getBoundsInScreen) } }.orEmpty() }
        val row = app.seek(list, 60, "list too long", "the model list is gone", shown) { sheet()?.let { s -> rows(s, target).firstOrNull() } }
            ?: fail("The chosen row is not in the model sheet")
        val button = app.clickableOf(row)?.takeIf { it.isEnabled } ?: fail("The chosen row is not an enabled button")
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) fail("Tapping the chosen row failed")
        app.log("tapped the chosen row") // never its name
        app.waitFor("the model sheet gone", 10_000) { sheet() == null }
        val want = "p${hex(pick?.first.orEmpty())} m${hex(pick?.second.orEmpty())}"
        app.waitFor("the chat's stored model choice", 10_000) { app.query(CHOICE_OF(key))?.singleOrNull() == want }
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
        val p = usable.firstOrNull { it.optString("slug") == o.optString("provider") } ?: return null
        val m = (p.optJSONArray("models") ?: JSONArray()).strings().firstOrNull { it != o.optString("model") && all.count(it::equals) == 1 }
        return m?.let { p.optString("slug") to it }
    }

    companion object {
        /** The methods whose screens list model and provider names: the driver runs them with fixed diagnostics. */
        val QUIET_METHODS = setOf("modelChoiceReachesRun", "modelPickIsReachable")

        /**
         * The stored model choice of chat [key] as "p<provider> m<model>", each field hex-encoded on its own (empty when
         * unset), so that no character of a name can make two different pairs read the same. Read only.
         */
        fun CHOICE_OF(key: String): String {
            require(Regex("apofis-(tmp-)?[0-9a-f-]{36}").matches(key)) { "Not an app session id" }
            return "SELECT 'p' || hex(ifnull(provider, '')) || ' m' || hex(ifnull(model, '')) FROM SessionEntity WHERE key = '$key';"
        }

        /** [text] as SQLite's hex() gives it: its UTF-8 bytes, upper-case. */
        fun hex(text: String): String = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it) }
    }
}

/** Every outcome word modelChoiceReachesRun can log. */
val CHOICE_WORDS = listOf(
    "proved", "served differs", "not proved: no runtime", "not proved: no alternative", "not admitted: zero starts",
    "unknown: run record unreadable", "unproved failure, starts unknown",
)

/** The outcomes of modelChoiceReachesRun that are recorded results; any other fails the method. */
val CHOICE_RECORDED = setOf("proved", "served differs", "not proved: no runtime", "not proved: no alternative")

/**
 * modelChoiceReachesRun's outcome word and its fixed detail (booleans and fixed phrases only, never a name), from how its
 * one send ended and the [pick] (null: Server default):
 * - "proved": a completed run whose served runtime model and provider both equal the pick;
 * - "served differs": a completed run whose runtime names a model or provider, not both equal to the pick;
 * - "not proved: no runtime": a completed run whose runtime is absent or has both fields blank;
 * - "not proved: no alternative": Server default, and its run completed;
 * - "not admitted: zero starts": no run id anywhere and the send failed with the app's attempted mark cleared. The app
 *   marks a send as attempted before the request and clears the mark only on a definite refusal or a failure before
 *   the request left: the request may have reached the server and been refused; no run was admitted;
 * - "unknown: run record unreadable": the phone holds the run's id, but every read of its record failed transiently;
 * - "unproved failure, starts unknown": anything else (a run that ended otherwise, a failure after a possible request,
 *   an invalid or foreign record, no settled run in time).
 */
fun choiceOutcome(end: SendEnd, pick: Pair<String, String>?): Pair<String, String> = when (end) {
    SendEnd.NotAdmitted -> "not admitted: zero starts" to ""
    SendEnd.RecordUnreadable -> "unknown: run record unreadable" to ""
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
