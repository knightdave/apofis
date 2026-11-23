package xyz.rycerz.apofis.ui

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.ZoneOffset
import org.robolectric.Shadows.shadowOf
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.ui.drawer.ActionError
import xyz.rycerz.apofis.ui.drawer.ArchivedScreen
import xyz.rycerz.apofis.ui.drawer.DrawerActions
import xyz.rycerz.apofis.ui.drawer.DrawerContent
import xyz.rycerz.apofis.ui.drawer.DrawerRow
import xyz.rycerz.apofis.ui.drawer.Prompt
import xyz.rycerz.apofis.ui.drawer.PromptActions
import xyz.rycerz.apofis.ui.drawer.PromptHost
import xyz.rycerz.apofis.ui.drawer.PromptKind
import xyz.rycerz.apofis.ui.drawer.PushDrawer
import xyz.rycerz.apofis.ui.drawer.PushDrawerValue
import xyz.rycerz.apofis.ui.drawer.rememberPushDrawerState
import xyz.rycerz.apofis.ui.drawer.groupSessions
import androidx.compose.runtime.Composable
import kotlinx.coroutines.test.runTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ComposeError
import xyz.rycerz.apofis.chat.UnsentText
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
import xyz.rycerz.apofis.ui.connect.ConnectState
import xyz.rycerz.apofis.ui.connect.ConnectViewModel
import xyz.rycerz.apofis.ui.settings.SettingsScreen
import xyz.rycerz.apofis.notify.NotificationAccess
import xyz.rycerz.apofis.notify.Runtime
import xyz.rycerz.apofis.push.PushState
import xyz.rycerz.apofis.push.PushUi
import xyz.rycerz.apofis.ui.settings.NotificationsRow
import xyz.rycerz.apofis.ui.settings.NotificationsUi
import xyz.rycerz.apofis.ui.theme.SeedSchemes

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()
    private fun connectVm() = ConnectViewModel(current = null, willClearCache = { false }, save = {})

    @Test
    fun connect_light() {
        val vm = connectVm()
        rule.shot("connect_light", ThemeMode.LIGHT) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_dark() {
        val vm = connectVm().apply { onUrlChange("http://[200:1234::1]:8642") }
        rule.shot("connect_dark", ThemeMode.DARK) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_error() = runTest {
        val vm = connectVm()
        vm.onUrlChange("hermes.local:8642")
        vm.onKeyChange("sk-123")
        vm.connect()?.join()
        rule.shot("connect_error", ThemeMode.LIGHT) { ConnectScreen(vm, onBack = null) }
    }

    @Test
    fun connect_server_change() {
        val vm = ConnectViewModel(current = null, willClearCache = { true }, save = {}, check = {}) // the server answers at once
        vm.onUrlChange("https://hermes.example:8642")
        vm.onKeyChange("sk-123")
        val check = vm.connect()!!
        shadowOf(Looper.getMainLooper()).idle() // runs what is queued now, then returns
        assertTrue(check.isCompleted)
        assertEquals(ConnectState.ConfirmServerChange, vm.state)
        rule.windowShot("connect_server_change", hasText("Switch server?")) { ConnectScreen(vm, onBack = null) }
    }

    @Composable
    private fun Settings() = SettingsScreen(
        serverUrl = "https://hermes.example:8642/",
        themeMode = ThemeMode.SYSTEM,
        dynamicColor = true,
        pureBlack = false,
        versionName = "0.1.0",
        notifications = { NotificationsRow(NotificationsUi(NotificationAccess(Runtime.GRANTED, true, true, true), PushUi(PushState.NoDistributor), null)) {} },
        onConnection = {},
        onThemeMode = {},
        onDynamicColor = {},
        onPureBlack = {},
        onLicenses = {},
        onBack = {},
    )

    @Test
    fun settings_light() = rule.shot("settings_light", ThemeMode.LIGHT) { Settings() }

    @Test
    fun settings_dark() = rule.shot("settings_dark", ThemeMode.DARK) { Settings() }

    // ---- chat ----

    private val now = FIXED_NOW
    private val utc = ZoneOffset.UTC

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
    ) = ChatUiState("apofis-1", title, status, withLive(items, live) { now }, composer, draft, "hermes.example", live)

    private fun live(text: String = "", steps: List<ToolStep> = emptyList(), status: String = "running", pending: PendingControl? = null) =
        LiveReply("run_1", status, text, steps, pending, Connection.LIVE)

    private fun chatShot(name: String, state: ChatUiState, mode: ThemeMode = ThemeMode.LIGHT, seed: SeedSchemes? = null) =
        rule.shot(name, mode, seed) { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = utc, today = FIXED_TODAY) }

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

    // The conversation under dynamic color, with fixed seeds through the real derivation path.
    @Test
    fun chat_seed_purple_light() = chatShot("chat_seed_purple_light", chat(listOf(question, answer)), seed = PurpleSeed)

    @Test
    fun chat_seed_purple_dark() = chatShot("chat_seed_purple_dark", chat(listOf(question, answer)), ThemeMode.DARK, PurpleSeed)

    @Test
    fun chat_seed_green_light() = chatShot("chat_seed_green_light", chat(listOf(question, answer)), seed = GreenSeed)

    @Test
    fun chat_seed_green_dark() = chatShot("chat_seed_green_dark", chat(listOf(question, answer)), ThemeMode.DARK, GreenSeed)

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

    @Test
    fun chat_approval_without_command() = chatShot(
        "chat_approval_without_command",
        chat(listOf(question), StatusLine.WORKING, ComposerMode.STOP, live = live(pending = PendingControl("approval", "ctl_1", 2, null, null))),
    )

    @Test
    fun chat_unsent_notice() = chatShot(
        "chat_unsent_notice",
        chat(listOf(question, answer), composer = ComposerMode.SEND, draft = "And hidden files too?")
            .copy(unsent = UnsentText(1, "Which of these can I safely delete?", ComposeError.NOT_STORED)),
    )

    @Test
    fun chat_load_failed() = chatShot("chat_load_failed", ChatUiState("", "", StatusLine.LOAD_FAILED, emptyList(), ComposerMode.SEND_DISABLED, ""))

    // ---- drawer ----

    private val today = FIXED_TODAY

    private fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).toEpochSecond(utc).toDouble()

    private fun session(key: String, title: String?, active: Double, pinned: Boolean = false) = SessionEntity(
        key = key, currentId = key, lineageIds = "", title = title, pinned = pinned, archived = false, startedAt = active,
        lastActiveAt = active, messageCount = 4, preview = null, stale = false, existsOnServer = true,
    )

    private val sessions = listOf(
        session("apofis-1", "Largest files", at(0, 11)),
        session("apofis-2", "Kubernetes upgrade checklist", at(30, 9), pinned = true),
        session("apofis-3", "Trip to Lisbon: three days, mostly on foot, with a list of bakeries", at(0, 9)),
        session("apofis-4", "Sourdough starter schedule", at(1, 22)),
        session("apofis-5", null, at(1, 8)),
        session("apofis-6", "Birthday gift ideas", at(6, 18)),
        session("apofis-7", "Plan the garden beds", at(9, 12)),
    )

    private fun rows(query: String = "") = groupSessions(sessions, query, "apofis-1", today, utc).map {
        when ((it as? DrawerRow.Chat)?.key) {
            "apofis-3" -> it.copy(running = true)
            "apofis-4" -> it.copy(needsInput = true)
            else -> it
        }
    }

    @Composable
    private fun DrawerOpen(rows: List<DrawerRow>, query: String = "", hasArchived: Boolean = true, refreshFailed: Boolean = false) {
        PushDrawer(
            rememberPushDrawerState(PushDrawerValue.Open),
            drawerContent = { DrawerContent(rows, query, refreshFailed, hasArchived, object : DrawerActions {}, {}, {}, {}, {}) },
        ) { dragArea ->
            ChatScreen(chat(listOf(question, answer)), object : ChatActions {}, onOpenConnection = {}, zone = utc, today = FIXED_TODAY, dragArea = dragArea)
        }
    }

    private fun prompt(kind: PromptKind, running: Boolean = false, error: ActionError? = null) =
        Prompt(kind, "apofis-3", "Trip to Lisbon: three days, mostly on foot, with a list of bakeries", pinned = false, archived = false, running = running, token = 1, error = error)

    @Test
    fun drawer_groups_light() = rule.shot("drawer_groups_light", ThemeMode.LIGHT) { DrawerOpen(rows()) }

    @Test
    fun drawer_groups_dark() = rule.shot("drawer_groups_dark", ThemeMode.DARK) { DrawerOpen(rows(), refreshFailed = true) }

    @Test
    fun drawer_search() = rule.shot("drawer_search", ThemeMode.LIGHT) { DrawerOpen(rows("plan"), query = "plan", hasArchived = false) }

    @Test
    fun drawer_empty() = rule.shot("drawer_empty", ThemeMode.LIGHT) { DrawerOpen(emptyList(), hasArchived = false) }

    @Test
    fun drawer_actions_sheet() = rule.windowShot("drawer_actions_sheet", hasText("Rename")) {
        DrawerOpen(rows())
        PromptHost(prompt(PromptKind.ACTIONS, error = ActionError.NETWORK), object : PromptActions {})
    }

    @Test
    fun drawer_delete_confirm() = rule.windowShot("drawer_delete_confirm", hasText("The chat will be deleted permanently from the server.")) {
        DrawerOpen(rows())
        PromptHost(prompt(PromptKind.DELETE, running = true), object : PromptActions {})
    }

    @Test
    fun archived_list() = rule.shot("archived_list", ThemeMode.LIGHT) {
        ArchivedScreen(
            listOf(
                DrawerRow.Chat("apofis-8", "Old apartment search", selected = false, running = false, needsInput = false),
                DrawerRow.Chat("apofis-9", "Conference talk outline", selected = false, running = false, needsInput = false),
                DrawerRow.Chat("apofis-10", "", selected = false, running = false, needsInput = false),
            ),
            object : DrawerActions {},
            onBack = {},
        )
    }
}
