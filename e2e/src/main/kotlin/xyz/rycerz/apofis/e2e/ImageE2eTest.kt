package xyz.rycerz.apofis.e2e

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Milestone 4: a shared photo sent in a temporary chat reaches the server as the app sent it (one model run, --m4-model,
 * reserved in the Milestone 4 ledger). The caption carries the run's nonce, so the chat's server title does. The JPEG is
 * hashed before the send; the stored row is read after the run settled, identity-checked, and reduced to fixed facts
 * whatever the app's own confirmation did; the app's binding, pending rows and bubble are checked after that.
 */
@RunWith(AndroidJUnit4::class)
class ImageE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

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
        app.journalSent() // the completing journal line, also when it failed
        app.log("scenario ${name.methodName} ends")
    }

    private fun draft() = app.composerField()?.text?.toString()

    @Test
    fun imageSendConfirms() {
        val caption = "apofis image check ${app.nonce}"
        val image = app.str("chat_image", 1, 1)
        app.share(SMALL, caption)
        app.awaitShareSheet(caption, images = 1, failed = false, accept = true)
        app.click(app.str("share_accept"))
        app.waitFor("a new chat holding the caption and one decoded image", 15_000) {
            app.snapshot().chatKey().let { it != null && it != session } && draft() == caption && app.composerImages() == listOf(image to true)
        }
        app.click(app.str("chat_temporary_on"))
        var key = ""
        app.waitFor("the unsent new chat temporary, the caption and the image moved with it", 10_000) {
            key = app.snapshot().chatKey().orEmpty()
            key.startsWith("apofis-tmp-") && draft() == caption && app.composerImages() == listOf(image to true)
        }
        val staged = app.stagedImage() // the one JPEG the send will carry, hashed before it
        app.send(key, caption) // journals "send <key>" first

        val end = app.awaitSendEnd(key, 300_000)
        // The server's own record, read after the identity check and whatever the app confirmed: fixed facts only.
        val f = storedImageRow(app.serverMessages(key), caption, staged)
        app.log("stored row: user rows ${f.userRows}, caption equal ${f.captionEqual}, suffix form ${f.suffixForm}, hints ${f.hints}, screenshots ${f.screenshots}, digest prefix matches the staged JPEG ${f.prefixMatches}, kind internal_notification ${f.internalNotification}")
        if (end !is SendEnd.Run || end.record.optString("status") != "completed") fail("The run did not complete")
        if (f.userRows != 1 || !f.suffixForm || !f.prefixMatches) fail("The stored row is not the caption, a blank line, one hint naming the sent JPEG and one [screenshot] line")

        // The app's side, each a separate requirement, each waited for (bounded) while a correct app is still working.
        app.waitFor("no pending send left for the chat", 60_000) {
            app.query("SELECT count(*) FROM PendingSendEntity WHERE sessionKey = '$key';")?.singleOrNull() == "0"
        }
        var bound: String? = null
        app.waitFor("the confirmed send's image in the app's database", 30_000) {
            bound = app.query("SELECT digests FROM ImageBindingEntity WHERE sessionKey = '$key';")?.singleOrNull()
            bound != null
        }
        if (bound != staged) fail("The confirmed send is not bound to the staged JPEG")
        // The bubble, reacquired each time: one decoded, actionable image and no Retry. A loading placeholder (same label,
        // not clickable) or a Retry is "not yet"; only the timeout fails.
        app.waitFor("the bubble with its caption, one decoded image and no failed state", 60_000) {
            val b = revealBubble(caption) ?: return@waitFor false
            app.nodesIn(b) { it.contentDescription?.toString() == image && it.isClickable }.size == 1 &&
                app.nodesIn(b) { it.text?.toString() == app.str("chat_retry") }.isEmpty()
        }
        app.log("bubble: the caption, one decoded image, no failed state")
        app.log("server title starts with [Image attached: ${app.serverTitle(key).startsWith("[Image attached")}")

        app.awaitLocalSettlement(key) // the phone has settled the run before the ids are read and proved
        app.proveLeave(key) // every id the leave deletes, journaled and proved this run's; else no leave
        app.click(app.str("drawer_new_chat")) // nothing else between the proof and this tap
        // The ids are journaled: the harness's cleanup later finds each gone (404) or proves and deletes it.
        app.waitFor("another chat open", 10_000) { app.snapshot().chatKey().let { it != null && it != key } }
    }

    /**
     * The one user item showing [caption], visible, brought into view through the transcript list (the largest scrollable
     * node of the chat screen, outside the composer area): no tagged item is needed to find that list. Null when the list
     * holds it nowhere or holds it more than once.
     */
    private fun revealBubble(caption: String): AccessibilityNodeInfo? {
        fun bubbles() = app.nodes { it.viewIdResourceName?.startsWith("user:") == true }.filter { n -> app.nodesIn(n) { it.text?.toString() == caption }.isNotEmpty() }
        val screen = app.nodes { it.viewIdResourceName?.startsWith("chat.key:") == true }.singleOrNull() ?: return null
        val bottom = app.nodes { it.viewIdResourceName == "chat.bottom" }.singleOrNull()?.let { Rect().also(it::getBoundsInScreen) }
        fun area(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen).let { it.width() * it.height() }
        val list = app.nodesIn(screen) { n -> n.isScrollable && !n.isEditable && Rect().also(n::getBoundsInScreen).let { r -> bottom == null || !bottom.contains(r) } }
            .maxByOrNull(::area)
        val shown = { app.nodes { it.isVisibleToUser && it.text != null && !it.isEditable }.map { "${it.text}@" + Rect().also(it::getBoundsInScreen) } }
        return app.seek(list, 40, "transcript too long", "the transcript list is gone", shown) { bubbles().singleOrNull() }
    }
}

/**
 * The structure of the stored user row of an image send, as fixed facts (no text or path). [internalNotification] is
 * recorded only: the app reads the row the same way with that display kind or none.
 */
data class ImageRowFacts(
    val userRows: Int,
    val captionEqual: Boolean,
    val suffixForm: Boolean,
    val hints: Int,
    val screenshots: Int,
    val prefixMatches: Boolean,
    val internalNotification: Boolean,
)

/**
 * [rows]' one user row against [caption] and the staged JPEG's [digest]: the caption, a blank line, then exactly one
 * `[Image attached at: ...api_<32 hex>.jpg]` line and one `[screenshot]` line, the 32 hex the digest's first 32.
 */
fun storedImageRow(rows: List<JSONObject>, caption: String, digest: String): ImageRowFacts {
    val users = rows.filter { it.str("role") == "user" }
    val content = users.singleOrNull()?.str("content").orEmpty()
    val internalNotification = users.singleOrNull()?.str("display_kind") == "internal_notification"
    val captionEqual = content.startsWith("$caption\n\n")
    val lines = content.removePrefix("$caption\n\n").split('\n')
    val hint = Regex("\\[Image attached at: [^\\n\\]]*api_([0-9a-f]{32})\\.jpg]")
    val hints = lines.mapNotNull { hint.matchEntire(it)?.groupValues?.get(1) }
    val shots = lines.count { it == "[screenshot]" }
    val form = captionEqual && lines.size == 2 && hint.matches(lines[0]) && lines[1] == "[screenshot]"
    return ImageRowFacts(users.size, captionEqual, form, hints.size, shots, hints.singleOrNull() == digest.take(32), internalNotification)
}
