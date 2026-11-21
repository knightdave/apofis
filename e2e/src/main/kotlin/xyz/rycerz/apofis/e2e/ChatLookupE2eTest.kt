package xyz.rycerz.apofis.e2e

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * The chat screen's tags and labels through the real accessibility tree. Types into the composer but sends nothing, so
 * it costs no model run and creates nothing on the server.
 */
@RunWith(AndroidJUnit4::class)
class ChatLookupE2eTest {
    private val app = AppDriver()

    @get:Rule val name = TestName()

    @get:Rule val evidence = Evidence(app)

    private fun sendButtons(): List<AccessibilityNodeInfo> = app.nodes { it.viewIdResourceName == "composer.send" }

    private fun descs(n: AccessibilityNodeInfo): List<String> =
        listOfNotNull(n.contentDescription?.toString()) + (0 until n.childCount).mapNotNull(n::getChild).flatMap(::descs)

    @Test
    fun newChatChrome() {
        app.freshStart()
        app.connect(app.url)
        val tree = app.snapshot()
        val titles = tree.tagged("chat.title")
        if (titles.size != 1 || app.str("chat_new_title") !in tree.texts(titles.single())) fail("Expected one chat.title with the new chat title, found ${titles.size}")
        if (tree.tagged("status.line").size != 1) fail("Expected one status.line")
        if (app.composerField() == null) fail("No editable composer.field")
        val send = sendButtons().singleOrNull() ?: fail("Expected one composer.send")
        if (app.str("chat_send") !in descs(send)) fail("composer.send is not labelled ${app.str("chat_send")}")
        if (send.isEnabled) fail("composer.send is enabled while the composer is empty")

        app.setField(app.composerField() ?: fail("No composer.field"), "Not sent")
        app.waitFor("composer.send enabled with text", 10_000) { sendButtons().singleOrNull()?.isEnabled == true }
        app.screenshot(name.methodName)
        app.setField(app.composerField() ?: fail("No composer.field"), "")
        app.waitFor("composer.send disabled again", 10_000) { sendButtons().singleOrNull()?.isEnabled == false }
    }
}
