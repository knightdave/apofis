package xyz.rycerz.apofis.ui.drawer

import androidx.compose.ui.test.junit4.createComposeRule
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.DrawerButton
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.NewChatButton
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.shot

/** The temporary chat's marks in the bar: the fixed title and the toggle's state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class TemporaryScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val question = UserMessage("u1", "Which plants can share a raised bed with tomatoes?", steered = false, timestamp = FIXED_NOW)
    private val answer = AssistantReply("r1", emptyList(), "Basil, marigolds and lettuce grow well beside tomatoes.", FIXED_NOW)

    private fun chatShot(name: String, state: ChatUiState, button: NewChatState) = rule.shot(name) {
        ChatScreen(
            state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY,
            navigationIcon = { DrawerButton {} }, appBarActions = { NewChatButton(button) {} },
        )
    }

    private fun chat(key: String, title: String, items: List<xyz.rycerz.apofis.chat.ChatItem>) =
        ChatUiState(key, title, StatusLine.IDLE, items, ComposerMode.SEND_DISABLED, "", "hermes.example")

    @Test
    fun chat_temporary_empty() = chatShot("chat_temporary_empty", chat("apofis-tmp-1", "", emptyList()), NewChatState.TEMPORARY_ON)

    @Test
    fun chat_temporary_with_message() =
        chatShot("chat_temporary_with_message", chat("apofis-tmp-1", "Which plants can share a raised bed", listOf(question, answer)), NewChatState.NEW)

    @Test
    fun chat_ordinary_empty_topbar() = chatShot("chat_ordinary_empty_topbar", chat("apofis-1", "", emptyList()), NewChatState.TEMPORARY_OFF)
}
