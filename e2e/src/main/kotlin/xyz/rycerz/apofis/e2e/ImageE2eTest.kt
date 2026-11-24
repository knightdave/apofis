package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Milestone 4: a shared photo sent in a temporary chat reaches the server as the app sent it (one model run, --m4-model,
 * reserved in the Milestone 4 ledger). The caption carries the run's nonce, so the chat's server title does; the stored
 * row is checked here, by the test, after the server's identity check.
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
        app.waitFor("a new chat holding the caption and one image", 15_000) {
            app.snapshot().chatKey().let { it != null && it != session } && draft() == caption && app.hasDesc(image)
        }
        app.click(app.str("chat_temporary_on"))
        var key = ""
        app.waitFor("the unsent new chat temporary, the caption and the image moved with it", 10_000) {
            key = app.snapshot().chatKey().orEmpty()
            key.startsWith("apofis-tmp-") && draft() == caption && app.hasDesc(image)
        }
        app.send(key, caption) // journals "send <key>" first

        app.waitFor("the reply to this turn, ended", 300_000, meanwhile = app::failOnApproval) { app.idle() && app.snapshot().replyAfter(caption) != null }
        // The bubble: the caption, one thumbnail, no failed state.
        val t = app.snapshot()
        val bubble = t.user(caption) ?: fail("The sent message is not shown once with its caption")
        if (t.descs(bubble.node).count { it == image } != 1 || app.str("chat_retry") in t.texts(bubble.node)) fail("The bubble does not show one image without a failed state")

        // The sent JPEG: the one image bound to the confirmed send, named by its SHA-256, whose stored bytes hash to it.
        var digest: String? = null
        app.waitFor("the confirmed send's image in the app's database", 30_000) {
            digest = app.query("SELECT digests FROM ImageBindingEntity WHERE sessionKey = '$key';")?.singleOrNull()?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
            digest != null
        }
        val d = digest!!
        val stored = app.shell("run-as $APP sha256sum files/images/$d.jpg").trim().substringBefore(' ')
        if (stored != d) fail("The stored image's bytes do not hash to its name")
        if (app.pendingCount() != 0) fail("A pending send is left")

        // The stored row, read after the identity check: the caption, a blank line, one hint naming api_<the first 32 hex
        // of the sent JPEG's SHA-256>.jpg, one [screenshot] line.
        val rows = app.serverMessages(key).filter { it.str("role") == "user" }
        val content = rows.singleOrNull()?.str("content") ?: fail("Expected one user row on the server, found ${rows.size}")
        val m = Regex("\\[Image attached at: [^\\n\\]]*api_([0-9a-f]{32})\\.jpg]\\n\\[screenshot]").matchEntire(content.removePrefix("$caption\n\n"))
        if (!content.startsWith("$caption\n\n") || m == null) fail("The stored row is not the caption, a blank line, one image hint and one [screenshot] line")
        if (m.groupValues[1] != d.take(32)) fail("The stored hint does not name the sent JPEG")
        app.log("stored row: caption, one hint naming the sent JPEG, one [screenshot] line")
        app.log("server title starts with [Image attached: ${app.serverTitle(key).startsWith("[Image attached")}")

        val ids = app.chatIds(key)
        app.journalSent(ids) // before leaving: afterwards the app has no row to read
        app.click(app.str("drawer_new_chat"))
        // Leaving deletes the temporary chat on the phone's side; the harness finds the ids gone (404) or deletes them.
        app.waitFor("another chat open", 10_000) { app.snapshot().chatKey().let { it != null && it != key } }
    }
}
