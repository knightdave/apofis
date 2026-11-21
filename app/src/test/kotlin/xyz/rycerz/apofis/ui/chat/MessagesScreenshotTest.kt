package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * Message rows alone on the page color (cropped to the component), and the Select view. Every capture is driven by
 * the rule's own clock and taken only after the content is asserted displayed; no shared capture helper is used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MessagesScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))
    private val question = UserMessage("u1", "How do I find the largest files in my home directory?", steered = false, timestamp = FIXED_NOW)
    private val answer = AssistantReply("r2", emptyList(), "Use du and sort. The biggest entry is your Downloads folder, at 4 GB.", FIXED_NOW)
    private val actions = RowActions({ Shown(listOf(question, answer), null) }, {})

    private fun component(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                Column(Modifier.testTag("component").width(360.dp).background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(20 * 16L)
        rule.onNodeWithTag("component").assertIsDisplayed()
    }

    /** Fails when an action button draws no glyph: its 28 dp box must hold clearly more than background pixels. */
    private fun assertGlyphsDrawn(expected: Int) {
        val buttons = rule.onAllNodes(hasContentDescription("Copy") or hasContentDescription("Select text"))
        val nodes = buttons.fetchSemanticsNodes()
        assertEquals(expected, nodes.size)
        for (i in nodes.indices) {
            buttons[i].assertIsDisplayed()
            val pixels = buttons[i].captureToImage().toPixelMap()
            val background = pixels[1, 1]
            var ink = 0
            for (x in 0 until pixels.width) for (y in 0 until pixels.height) if (pixels[x, y] != background) ink++
            assertTrue("button $i draws its glyph ($ink pixels)", ink >= 40)
        }
    }

    @Test
    fun message_action_rows() {
        val rows = ChatRows(ZoneOffset.UTC).rows(listOf(question, answer)).filter { it !is DateRow }
        component {
            for (row in rows) when (row) {
                is UserRow -> UserBubble(row.message, onRetry = {}, onCancel = {}, actions = actions)
                is TextRow -> TextBlock(row)
                is FooterRow -> ReplyFooter(row.reply, null, emptyMap(), answering = false, actions = object : ChatActions {}, rowActions = actions)
                else -> error("unexpected $row")
            }
        }
        assertGlyphsDrawn(4)
        rule.onNodeWithTag("component").captureRoboImage("src/test/screenshots/message_action_rows.png", options)
    }

    @Test
    fun user_bubble_collapsed() {
        val message = UserMessage("u1", (1..20).joinToString("\n") { "Line $it of a long pasted log" }, steered = false, timestamp = FIXED_NOW)
        component { UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ Shown(listOf(message), null) }, {})) }
        assertGlyphsDrawn(2)
        rule.onNodeWithTag("component").captureRoboImage("src/test/screenshots/user_bubble_collapsed.png", options)
    }

    @Test
    fun select_text_view() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                // As ChatScreen hosts it: a full-screen dialog.
                Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    SelectTextScreen("Use du and sort:\n\ndu -ah ~ | sort -rh | head -n 10\n\nThe biggest entry is your Downloads folder, at 4 GB.") {}
                }
            }
        }
        rule.mainClock.advanceTimeBy(20 * 16L)
        rule.onNode(hasText("Select text") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNode(hasText("The biggest entry", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        captureScreenRoboImage("src/test/screenshots/select_text_view.png", options)
    }
}
