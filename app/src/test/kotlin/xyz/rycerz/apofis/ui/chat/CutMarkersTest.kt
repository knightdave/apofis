package xyz.rycerz.apofis.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.theme.ApofisTheme

private const val NOT_STORED = "Tool calls not stored on this phone: 3"
private const val SHORTENED = "Shortened on this phone; the full text is on the server"

/** The marks of a row stored cut to the bound (issue 16 row 4): one line per cut, none on an uncut row. */
@RunWith(RobolectricTestRunner::class)
class CutMarkersTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) { content() }
    }

    @Composable
    private fun Footer(reply: AssistantReply) = ReplyFooter(reply, null, emptyMap(), answering = false, actions = object : ChatActions {})

    @Composable
    private fun Bubble(message: UserMessage) = UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ listOf(message) }, {}))

    @Test
    fun replyShowsDroppedCallsAndShortened() {
        var reply by mutableStateOf(AssistantReply("r2", emptyList(), "cut", FIXED_NOW))
        show { Footer(reply) }
        rule.onNodeWithText(NOT_STORED).assertDoesNotExist()
        rule.onNodeWithText(SHORTENED).assertDoesNotExist()

        reply = reply.copy(droppedToolCalls = 3) // each line has its own condition
        rule.onNodeWithText(NOT_STORED).assertIsDisplayed()
        rule.onNodeWithText(SHORTENED).assertDoesNotExist()

        reply = reply.copy(droppedToolCalls = 0, shortened = true)
        rule.onNodeWithText(NOT_STORED).assertDoesNotExist()
        rule.onNodeWithText(SHORTENED).assertIsDisplayed()

        reply = reply.copy(droppedToolCalls = 3)
        rule.onNodeWithText(NOT_STORED).assertIsDisplayed()
        rule.onNodeWithText(SHORTENED).assertIsDisplayed()
    }

    @Test
    fun userRowShowsShortened() {
        var message by mutableStateOf(UserMessage("u1", "cut", steered = false, timestamp = FIXED_NOW))
        show { Bubble(message) }
        rule.onNodeWithText(SHORTENED).assertDoesNotExist()

        message = message.copy(shortened = true)
        rule.onNodeWithText(SHORTENED).assertIsDisplayed()

        message = message.copy(steered = true) // a cut steer message keeps the line
        rule.onNodeWithText(SHORTENED).assertIsDisplayed()
    }
}
