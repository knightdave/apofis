package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.shot

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChromeScreenshotTest {
    @Composable
    private fun ButtonOverText() {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp)) {
            Text(
                "The scroll button floats over the conversation. Without blur it keeps a page backplate under its " +
                    "glass fill, so the arrow stays readable over any text behind it.",
                color = MaterialTheme.colorScheme.onSurface,
            )
            ScrollToBottomButton(visible = true, backdrop = null, onClick = {}, Modifier.align(Alignment.CenterEnd))
        }
    }

    @Test
    fun scroll_button_light() = shot("scroll_button_light", ThemeMode.LIGHT) { ButtonOverText() }

    @Test
    fun scroll_button_dark() = shot("scroll_button_dark", ThemeMode.DARK) { ButtonOverText() }

    @Test
    fun chat_scrolled_under_bar() {
        val items = (1..8).flatMap {
            listOf(
                UserMessage("u$it", "Question $it about the garden beds", steered = false, timestamp = FIXED_NOW),
                AssistantReply(
                    "r$it",
                    emptyList(),
                    "Answer $it. Raised beds warm up sooner in spring and drain better after rain, so seedlings can go in a " +
                        "few weeks earlier than in the open ground.",
                    FIXED_NOW,
                ),
            )
        }
        val state = ChatUiState("apofis-1", "Plan the garden beds", StatusLine.IDLE, items, ComposerMode.SEND_DISABLED, "", "hermes.example")
        shot("chat_scrolled_under_bar") {
            // A few rows up from the bottom, so a paragraph sits under the bar's gradient.
            ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = rememberLazyListState(3, 160))
        }
    }
}
