package xyz.rycerz.apofis.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.windowShot
import java.time.ZoneOffset

/** Same device and SDK as ScreenshotTest, so these goldens are byte-stable with the others. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class SettingsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Settings() = SettingsScreen(
        serverUrl = "https://hermes.example:8642/",
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = true,
        pureBlack = false,
        blur = false,
        chatFontScale = 1f,
        enterToSend = false,
        haptics = true,
        versionName = "0.1.0",
        notifications = {},
        onConnection = {},
        onThemeMode = {},
        onDynamicColor = {},
        onPureBlack = {},
        onBlur = {},
        onChatFontScale = {},
        onEnterToSend = {},
        onHaptics = {},
        onLicenses = {},
        onBack = {},
        themeSheetOpen = true,
    )

    @Test
    fun settings_theme_sheet() = rule.windowShot("settings_theme_sheet", hasText("Dark") and hasClickAction()) { Settings() }

    private val question = UserMessage("u1", "How do I find the largest files in my home directory?", steered = false, timestamp = FIXED_NOW)
    private val listing = ToolStep("c1", "terminal", "command=du -ah ~ | sort -rh | head", "4.0G  /home/user", false, error = false, running = false, durationSeconds = 1.3)
    private val answer = AssistantReply(
        "r2",
        listOf(listing),
        "Use du and sort:\n\n```sh\ndu -ah ~ | sort -rh | head -n 10\n```\n\nThe biggest entry is your Downloads folder, at 4 GB.",
        FIXED_NOW,
    )
    private val tableReply = AssistantReply(
        "r2",
        emptyList(),
        "The largest folders:\n\n| Folder | Size |\n|---|---|\n| Downloads | 4.0 GB |\n| Videos | 2.1 GB |\n\n```sh\ndu -sh ~/* | sort -rh\n```\n\nDelete what you no longer need.",
        FIXED_NOW,
    )

    private fun chatShot(name: String, reply: AssistantReply) = rule.shot(name) {
        ChatScreen(
            ChatUiState("apofis-1", "Largest files", StatusLine.IDLE, listOf(question, reply), ComposerMode.SEND_DISABLED, "", "hermes.example"),
            object : ChatActions {},
            onOpenConnection = {},
            zone = ZoneOffset.UTC,
            today = FIXED_TODAY,
            chatFontScale = 1.5f,
        )
    }

    @Test
    fun chat_font_scale_large() = chatShot("chat_font_scale_large", answer)

    /** System font scale 2.0 and chat text size 150 %: a reply with a table and a code block, nothing clipped. */
    @Test
    @Config(fontScale = 2f)
    fun chat_large_text() = chatShot("chat_large_text", tableReply)
}
