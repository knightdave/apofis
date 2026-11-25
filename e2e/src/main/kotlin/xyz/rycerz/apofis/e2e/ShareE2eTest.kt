package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Milestone 4: sharing into the app (no model run, run by --m4). Each scenario connects, then runs with airplane mode on
 * (no live transport) and shares generated PNGs from this APK's own provider; it never sends and makes no request of
 * its own. The app's data is cleared before airplane mode goes off. The harness checks that exactly one
 * "ApofisShare image TOO_LARGE" line was logged.
 */
@RunWith(AndroidJUnit4::class)
class ShareE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

    private lateinit var chat: String

    @Before
    fun start() {
        app.log("scenario ${name.methodName} starts")
        app.scenario = name.methodName
        chat = app.connectThenOffline()
    }

    @After
    fun cleanUp() {
        app.clearThenOnline()
        app.log("scenario ${name.methodName} ends")
    }

    /** No tile in the draft strip, whatever its state: no Remove control and no image label of any state. */
    private fun noTile() = app.draftTiles() == 0 && app.composerImages().isEmpty()

    /** The composer's text (the draft; never the key: the Connect screen is gone). */
    private fun draft() = app.composerField()?.text?.toString()

    @Test
    fun shareTextAndImageOpensNewChat() {
        val draft = "Draft ${app.nonce} stays"
        app.setField(app.composerField() ?: fail("No composer"), draft)
        app.waitFor("the draft in the composer, no tile", 10_000) { draft() == draft && noTile() }
        val caption = "Shared ${app.nonce} caption"
        app.share(SMALL, caption)
        app.awaitShareSheet(caption, images = 1, failed = false, accept = true)
        app.cancelShare()
        // The draft's text and its tiles, both unchanged: Cancel attached nothing, not even a failed or processing tile.
        if (app.openChatKey() != chat || draft() != draft || !noTile()) fail("Cancel changed the open chat, its draft or its tiles")
        app.log("cancel kept the chat, its draft and its (no) tiles")

        val before = app.localKeys()
        app.share(SMALL, caption)
        app.awaitShareSheet(caption, images = 1, failed = false, accept = true)
        app.click(app.str("share_accept"))
        var key = ""
        app.waitFor("a new chat holding the shared caption and one decoded image in its composer", 15_000) {
            key = app.snapshot().chatKey().orEmpty()
            key.isNotEmpty() && key !in before && draft() == caption && app.composerImages() == listOf(app.str("chat_image", 1, 1) to true) && app.draftTiles() == 1
        }
        // New (not in the app before), ordinary, and only on the phone: no other chat's draft was replaced.
        if (key.startsWith("apofis-tmp-") || app.chatRow(key)?.onServer != false) fail("The share did not open a new, ordinary, local-only chat")
        val pending = app.pendingCount()
        if (pending != 0) fail("The share left $pending pending sends")
        app.log("accept opened a new ordinary local-only chat with the shared draft and its image; 0 pending sends")
    }

    @Test
    fun pixelCapJustOverRefused() {
        // Read through this process's own resolver first: both within the byte cap, sized as named, on either side of the
        // pixel cap. So the refusal below can only come from the pixel check.
        for ((fixture, size) in listOf(OVER to (8001 to 8000), AT to (8000 to 8000))) {
            val (bytes, w, h) = app.fixtureHeader(fixture)
            if (bytes > MAX_SOURCE_BYTES || w != size.first || h != size.second) fail("Fixture $fixture: $bytes bytes, ${w}x$h")
            app.log("fixture $fixture: $bytes bytes, ${w}x$h, ${w.toLong() * h} pixels")
        }
        if (8001L * 8000 <= MAX_PIXELS || 8000L * 8000 > MAX_PIXELS) fail("The fixtures are not on either side of the pixel cap")

        // No caption: Accept shows whenever there is text or an image read, so "only Cancel" needs none.
        app.share(OVER)
        app.awaitShareSheet(null, images = 0, failed = true, accept = false)
        app.cancelShare()
        app.share(AT)
        app.awaitShareSheet(null, images = 1, failed = false, accept = true)
        app.cancelShare()
    }
}
