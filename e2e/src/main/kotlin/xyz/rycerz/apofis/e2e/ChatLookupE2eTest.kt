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

    /** The drawer's new-chat row: a node with the text (the chat bar's New chat button has it only as a description). */
    private fun drawerNewChat() = app.nodes { it.text?.toString() == app.str("drawer_new_chat") }.isNotEmpty()

    private fun chatShown() = app.snapshot().tagged("chat.title").size == 1 && app.composerField() != null

    private fun drawerShown() = drawerNewChat() && app.snapshot().tagged("chat.title").isEmpty() && app.composerField() == null

    /** The push drawer: opened by its button and by a swipe on the chat, closed by Back and by a tap on the pushed chat. */
    @Test
    fun pushDrawer() {
        app.freshStart()
        app.connect(app.url)
        if (drawerNewChat()) fail("The closed drawer's rows are in the accessibility tree")

        app.click(app.str("drawer_open"))
        app.waitFor("the open drawer, the chat hidden", 10_000) { drawerShown() }
        app.screenshot(name.methodName + "-open")
        app.device.pressBack()
        app.waitFor("the chat back after Back", 10_000) { chatShown() && !drawerNewChat() }

        val w = app.device.displayWidth
        val y = app.device.displayHeight / 2
        app.device.swipe(w * 2 / 10, y, w * 8 / 10, y, 20) // from the middle of the empty list
        app.waitFor("the drawer opened by a swipe", 10_000) { drawerShown() }
        if (!app.device.click(w * 9 / 10, y)) fail("Tapping the pushed chat failed") // the scrim over the pushed chat
        app.waitFor("the chat back after a tap on the scrim", 10_000) { chatShown() && !drawerNewChat() }
    }
}
