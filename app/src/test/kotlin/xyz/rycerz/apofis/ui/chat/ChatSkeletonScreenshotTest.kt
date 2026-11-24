package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.shot

/** The loading skeleton on the chat screen, static (goldens run with reduced motion), light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChatSkeletonScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val loading = ChatUiState("apofis-1", "Largest files", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, "", "hermes.example", firstLoad = true)

    private fun skeletonShot(name: String, mode: ThemeMode) = rule.shot(name, mode) {
        ChatScreen(loading, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
    }

    @Test
    fun chat_skeleton_light() = skeletonShot("chat_skeleton_light", ThemeMode.LIGHT)

    @Test
    fun chat_skeleton_dark() = skeletonShot("chat_skeleton_dark", ThemeMode.DARK)
}
