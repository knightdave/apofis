package xyz.rycerz.apofis.ui

import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.chat.withLive
import xyz.rycerz.apofis.ui.connect.ConnectScreen
import xyz.rycerz.apofis.ui.connect.ConnectViewModel
import xyz.rycerz.apofis.ui.settings.SettingsScreen
import xyz.rycerz.apofis.ui.theme.ApofisTheme

// Goldens are recorded on one fixed SDK and device so they are byte-stable; change both only together with a re-record.
// The small threshold absorbs antialiasing noise between machines, not layout changes.
private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ScreenshotTest {
    private fun shot(name: String, mode: ThemeMode, content: @Composable () -> Unit) =
        captureRoboImage("src/test/screenshots/$name.png", options) {
            ApofisTheme(mode = mode, dynamicColor = false, pureBlack = false, content = content)
        }

    private fun connectVm() = ConnectViewModel(current = null, willClearCache = { false }, save = {})

    @Test
    fun connect_light() {
        val vm = connectVm()
        shot("connect_light", ThemeMode.LIGHT) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_dark() {
        val vm = connectVm().apply { onUrlChange("http://[200:1234::1]:8642") }
        shot("connect_dark", ThemeMode.DARK) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_error() = runTest {
        val vm = connectVm()
        vm.onUrlChange("hermes.local:8642")
        vm.onKeyChange("sk-123")
        vm.connect()?.join()
        shot("connect_error", ThemeMode.LIGHT) { ConnectScreen(vm, onBack = null) }
    }

    @Composable
    private fun Settings() = SettingsScreen(
        serverUrl = "https://hermes.example:8642/",
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = true,
        pureBlack = false,
        versionName = "0.1.0",
        onConnection = {},
        onThemeMode = {},
        onDynamicColor = {},
        onPureBlack = {},
        onLicenses = {},
        onBack = {},
    )

    @Test
    fun settings_light() = shot("settings_light", ThemeMode.LIGHT) { Settings() }

    @Test
    fun settings_dark() = shot("settings_dark", ThemeMode.DARK) { Settings() }

    // ---- chat ----

    private val now = System.currentTimeMillis() / 1000.0 // "Today" in every golden, whatever the recording date

    private val question = UserMessage("u1", "How do I find the largest files in my home directory?", steered = false, timestamp = now)
    private val listing = ToolStep("c1", "terminal", "command=du -ah ~ | sort -rh | head", "4.0G  /home/user", false, error = false, running = false, durationSeconds = 1.3)
    private val answer = AssistantReply(
        "r2",
        listOf(listing),
        "Use du and sort:\n\n```sh\ndu -ah ~ | sort -rh | head -n 10\n```\n\nThe biggest entry is your Downloads folder, at 4 GB.",
        now,
    )

    private fun chat(
        items: List<ChatItem>,
        status: StatusLine = StatusLine.IDLE,
        composer: ComposerMode = ComposerMode.SEND_DISABLED,
        draft: String = "",
        live: LiveReply? = null,
        title: String = "Largest files",
    ) = ChatUiState("apofis-1", title, status, withLive(items, live), composer, draft, "hermes.example", live)

    private fun live(text: String = "", steps: List<ToolStep> = emptyList(), status: String = "running", pending: PendingControl? = null) =
        LiveReply("run_1", status, text, steps, pending, Connection.LIVE)

    private fun chatShot(name: String, state: ChatUiState, mode: ThemeMode = ThemeMode.LIGHT) =
        shot(name, mode) { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}) }

    @Test
    fun chat_empty() = chatShot("chat_empty", chat(emptyList(), title = ""))

    @Test
    fun chat_conversation_light() = chatShot("chat_conversation_light", chat(listOf(question, answer)))

    @Test
    fun chat_conversation_dark() = chatShot("chat_conversation_dark", chat(listOf(question, answer)), ThemeMode.DARK)

    @Test
    fun chat_streaming() = chatShot(
        "chat_streaming",
        chat(listOf(question), StatusLine.WORKING, ComposerMode.STOP, live = live("Use du and sort. The command below lists the ten")),
    )

    @Test
    fun chat_tool_running() = chatShot(
        "chat_tool_running",
        chat(listOf(question), StatusLine.WORKING, ComposerMode.STEER, draft = "Skip hidden folders", live = live(steps = listOf(listing.copy(result = null, running = true, durationSeconds = null)))),
    )

    @Test
    fun chat_tool_error() = chatShot(
        "chat_tool_error",
        chat(listOf(question, answer.copy(steps = listOf(listing.copy(result = "du: cannot read directory", error = true)), text = "I could not read some folders, so the sizes may be too small."))),
    )

    @Test
    fun chat_queued_and_failed() = chatShot(
        "chat_queued_and_failed",
        chat(
            listOf(
                question,
                answer,
                UserMessage("pa", "And in /var?", false, now, SendState.FAILED, "a", error = "unconfirmed", attempted = true),
                UserMessage("pb", "Also sort them by date", false, now, SendState.QUEUED, "b"),
            ),
        ),
    )

    @Test
    fun chat_approval() = chatShot(
        "chat_approval",
        chat(
            listOf(question),
            StatusLine.WORKING,
            ComposerMode.STOP,
            live = live(steps = listOf(listing), pending = PendingControl("approval", "ctl_1", 2, "rm -rf ~/Downloads/old-isos", null)),
        ),
    )

    @Test
    fun chat_stopped_notice() = chatShot(
        "chat_stopped_notice",
        chat(listOf(question, answer.copy(steps = emptyList(), text = "Use du and sort. The command", outcome = ReplyOutcome.STOPPED))),
    )

    @Test
    fun chat_unknown_notice() = chatShot(
        "chat_unknown_notice",
        chat(listOf(question), StatusLine.WORKING, ComposerMode.STOP, live = live("Use du and sort.", status = "unknown")),
    )

    @Test
    fun chat_offline() = chatShot(
        "chat_offline",
        chat(listOf(question, answer), StatusLine.OFFLINE, ComposerMode.SEND, draft = "What about /var?"),
    )

    @Test
    @Config(qualifiers = "pl-w360dp-h760dp-xhdpi")
    fun chat_polish_locale() = chatShot(
        "chat_polish_locale",
        chat(
            listOf(
                UserMessage("u1", "Jak znaleźć największe pliki w katalogu domowym?", false, now),
                AssistantReply("r2", listOf(listing), "Użyj du i sort:\n\n```sh\ndu -ah ~ | sort -rh | head -n 10\n```", now, outcome = ReplyOutcome.INTERRUPTED),
                UserMessage("pb", "A w /var?", false, now, SendState.QUEUED, "b"),
            ),
            StatusLine.KEY_REJECTED,
            title = "Największe pliki",
        ),
    )
}
