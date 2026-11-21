package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.time.ZoneOffset
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
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** Message rows alone on the page color, and the Select view. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MessagesScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val question = UserMessage("u1", "How do I find the largest files in my home directory?", steered = false, timestamp = FIXED_NOW)
    private val answer = AssistantReply("r2", emptyList(), "Use du and sort. The biggest entry is your Downloads folder, at 4 GB.", FIXED_NOW)

    @Test
    fun message_action_rows() {
        val rows = ChatRows(ZoneOffset.UTC).rows(listOf(question, answer)).filter { it !is DateRow }
        shot("message_action_rows") {
            Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                for (row in rows) when (row) {
                    is UserRow -> UserBubble(row.message, onRetry = {}, onCancel = {}, onSelect = {}, textOf = { null })
                    is TextRow -> TextBlock(row)
                    is FooterRow -> ReplyFooter(row.reply, null, emptyMap(), answering = false, actions = object : ChatActions {})
                    else -> error("unexpected $row")
                }
            }
        }
    }

    @Test
    fun user_bubble_collapsed() {
        val message = UserMessage("u1", (1..20).joinToString("\n") { "Line $it of a long pasted log" }, steered = false, timestamp = FIXED_NOW)
        shot("user_bubble_collapsed") {
            Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                UserBubble(message, onRetry = {}, onCancel = {}, onSelect = {}, textOf = { null })
            }
        }
    }

    /** Driven by the rule's clock and captured only once the view is shown (see the legacy window helper's flake). */
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
        captureScreenRoboImage(
            "src/test/screenshots/select_text_view.png",
            RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f)),
        )
    }
}
