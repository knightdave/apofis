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
 * live transport (--m4); temporaryChatGoneAfterLeaving and modelChoiceReachesRun send one message each, no steer
 * (--m4-model, reserved in the Milestone 4 ledger). Prompts carry the run's nonce in their first line, so the chat's
 * server title does; the chat is journaled before it is left.
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

    /** "Temporary chat" rows in the open drawer (the chat, and so its own title, is hidden then); the drawer is closed again. */
    private fun temporaryRows(): Int {
        app.click(app.str("drawer_open"))
        app.waitFor("the open drawer", 10_000) { app.snapshot().tagged("chat.title").isEmpty() && app.has(app.str("settings_title")) }
        val n = app.nodes { it.text?.toString() == app.str("chat_temporary_title") }.size
        app.device.pressBack()
        app.waitFor("the chat back", 10_000) { app.snapshot().tagged("chat.title").size == 1 }
        return n
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
        val before = temporaryRows()
        val key = toggleOn()
        if (app.chatRow(key) == null || app.chatRow(session) != null) fail("The toggle did not move the chat to its temporary key")
        if (temporaryRows() != before) fail("The drawer lists the open temporary chat")
        app.log("toggled on: titled as temporary, not in the drawer")
        app.click(app.str("chat_temporary_off"))
        app.waitFor("an ordinary new chat again", 10_000) {
            titled(app.str("chat_new_title")) && app.snapshot().chatKey().let { it != null && !it.startsWith("apofis-tmp-") }
        }
        app.log("toggled back")
    }

    @Test
    fun temporaryChatGoneAfterLeaving() {
        val before = temporaryRows()
        val key = toggleOn()
        val prompt = "Test ${app.nonce}-7. Reply with exactly: apofis-e2e-7"
        app.send(key, prompt)
        awaitReplyEnded(prompt)
        val ids = app.chatIds(key)
        app.journalSent(ids) // before leaving: afterwards the app has no row to read
        ids.forEach { app.serverMessages(it) } // each id proved this run's (its title holds the nonce)
        app.click(app.str("drawer_new_chat"))
        app.waitFor("another chat open", 10_000) { app.snapshot().chatKey().let { it != null && it != key } }
        val gone = mutableSetOf<String>()
        val end = SystemClock.uptimeMillis() + 60_000
        while (gone.size < ids.size && SystemClock.uptimeMillis() < end) {
            for (id in ids - gone) if (app.sessionCode(id) == (404 to "session_not_found")) gone += id
            if (gone.size < ids.size) SystemClock.sleep(10_000)
        }
        val listed = temporaryRows() != before
        val outcome = if (gone.size == ids.size && !listed) "deleted" else "kept and listed with the note"
        app.log("temporary chat after leaving: $outcome (${gone.size} of ${ids.size} ids gone, listed $listed)")
        if (outcome != "deleted") fail("The temporary chat was not deleted after leaving")
    }

    @Test
    fun modelChoiceReachesRun() {
        // The sheet is opened through the palette's row: a row tap runs /model without sending the draft "/m".
        app.setField(app.composerField() ?: fail("No composer"), "/m")
        app.paletteRow("model")
        app.waitFor("the model sheet", 10_000) { app.has(app.str("model_title")) }
        val pick = pickAlternative(app.modelOptions())
        if (pick != null) {
            val (row, scrolls) = revealIn(SheetList(pick.second))
            row ?: fail("The picked model's row is not in the model sheet")
            val target = clickableOf(row) ?: fail("The picked model's row is not clickable")
            val b = Rect().also(target::getBoundsInScreen)
            app.log("tapping the picked model's row after $scrolls scroll(s)") // never the model's name
            if (!app.device.click(b.centerX(), b.centerY())) fail("Tapping the picked model's row failed")
        } else {
            app.log("model choice: not proved: no alternative")
            app.click(app.str("model_default"))
        }
        app.waitFor("the model sheet closed", 10_000) { !app.has(app.str("model_title")) }

        val marker = "apofis-e2e-8"
        // A short tool call keeps the run alive long enough to read its id from the app's database.
        val prompt = "Automated app test ${app.nonce}-8. Use the terminal tool to run exactly this command: sleep 8 # $marker . Then reply with one short sentence that includes $marker."
        app.send(session, prompt, online = false) // replaces the draft: Send is never pressed on a draft starting with "/"
        var run: String? = null
        app.waitFor("the run the app follows", 120_000, meanwhile = app::failOnApproval) {
            if (app.snapshot().user(prompt)?.let { app.str("chat_retry") in app.snapshot().texts(it.node) } == true) {
                app.log("model choice: refused before admission")
                fail("The send was refused before admission")
            }
            run = app.query(RUN_OF(session))?.singleOrNull()?.takeIf { Regex("run_[0-9a-f]{32}").matches(it) }
            run != null
        }
        awaitReplyEnded(prompt)
        val runtime = app.runRecord(run!!, session).optJSONObject("runtime")
        if (pick == null) return
        val model = runtime?.optString("model") == pick.second
        val provider = runtime?.optString("provider") == pick.first
        val outcome = when {
            runtime == null -> "not proved: no runtime"
            model && provider -> "proved"
            else -> "served differs"
        }
        app.log("model choice: $outcome (runtime.model echoes the pick: $model, runtime.provider echoes its slug: $provider)")
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

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = if (n.isClickable) n else n.parent?.let(::clickableOf)

    /** The model sheet's list as [revealIn] sees it: the row whose text is exactly [model], scrolled by its list. */
    private inner class SheetList(private val model: String) : ScrollList<AccessibilityNodeInfo> {
        private fun list(): AccessibilityNodeInfo? =
            app.nodes { it.text?.toString() == app.str("model_title") }.firstOrNull()?.let { n -> generateSequence(n) { it.parent }.firstOrNull { it.isScrollable } }

        override fun find() = app.nodes { it.text?.toString() == model }.firstOrNull()
        override fun visible(n: AccessibilityNodeInfo) = n.isVisibleToUser
        override fun scroll(forward: Boolean): Boolean {
            val ok = list()?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            app.device.waitForIdle()
            return ok
        }
        override fun shown(): Any = app.nodes { it.isVisibleToUser && it.text != null }.map { "${it.text}@" + Rect().also(it::getBoundsInScreen) }
    }

    /** Waits until this turn's reply right after [prompt] is shown and no run is going on. */
    private fun awaitReplyEnded(prompt: String) {
        app.waitFor("the reply to this turn, ended", reply, meanwhile = app::failOnApproval) { app.idle() && app.snapshot().replyAfter(prompt) != null }
        app.log("reply ended")
    }

    companion object {
        /** The run the app follows for chat [key], or its send's run id once stored (read only, never journaled). */
        fun RUN_OF(key: String): String {
            require(Regex("apofis-(tmp-)?[0-9a-f-]{36}").matches(key)) { "Not an app session id" }
            return "SELECT runId FROM ActiveRunEntity WHERE sessionKey = '$key' UNION SELECT runId FROM PendingSendEntity WHERE sessionKey = '$key' AND runId IS NOT NULL;"
        }
    }
}
