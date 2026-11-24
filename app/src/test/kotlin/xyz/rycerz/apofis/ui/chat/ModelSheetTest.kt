package xyz.rycerz.apofis.ui.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.ModelOptions
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.api.ProviderModels
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.db.choice
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.ViewHaptics

private const val KEY = "apofis-a"
private const val OPTIONS = "/api/model/options"

/**
 * The model and reasoning sheets on the hosted chat screen, driven by a real [ChatViewModel] over an in-memory
 * database and a local server: what they show, what a pick stores (on this phone only), and which late answers are
 * dropped. The last two cases host the screen on a plain state for the answer guard and the chrome counters.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ModelSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private val open = AtomicBoolean(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val latches = mutableListOf<CountDownLatch>()
    private val stores = mutableListOf<ViewModelStore>()
    private val vms = mutableListOf<ChatViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher()) // view model work runs at once, as in ChatViewModelTest
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes[request.target.substringBefore('?')]?.invoke(request) ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val api = HermesApi(server.url("/").toString(), "secret-key")
        val queue = SendQueue(api, db, { _, _ -> }, System::currentTimeMillis) // no run is followed here
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        val follower = RunFollower(api, db, repo, scope)
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, follower, open, scope)
    }

    @After
    fun tearDown() {
        latches.forEach { it.countDown() }
        val jobs = vms.map { it.viewModelScope.coroutineContext.job }
        stores.forEach { it.clear() }
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun json(body: String, code: Int = 200) =
        { _: RecordedRequest -> MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    private val minimal =
        """{"providers":[{"slug":"alpha","models":["alpha-large","alpha-small"],"usage":{"used":1}},{"slug":"beta","models":["beta-1"]}],""" +
            """"model":"alpha-large","provider":"alpha"}"""

    private fun addSession(choice: ChatModel = ChatModel(), key: String = KEY, onServer: Boolean = false) = runBlocking {
        db.sessionDao().upsert(
            SessionEntity(
                key = key, currentId = key, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
                lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = onServer,
                model = choice.model, provider = choice.provider, reasoning = choice.reasoning, fast = choice.fast,
            ),
        )
    }

    private fun stored(key: String = KEY) = runBlocking { db.sessionDao().get(key)!!.choice() }

    private fun vm(key: String = KEY): ChatViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(key, connected) } })[ChatViewModel::class].also { vms += it }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    /** The chat screen bound to [vm], as AppNav hosts it; [haptics] replaces the theme's feedback. */
    private fun host(vm: ChatViewModel, haptics: Haptics? = null) = rule.setContent {
        Themed {
            val screen = @Composable {
                val state by vm.state.collectAsState()
                ChatScreen(state, vm, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
            }
            if (haptics == null) screen() else CompositionLocalProvider(LocalHaptics provides haptics) { screen() }
        }
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { exists(m) }

    private fun waitGone(m: SemanticsMatcher) = rule.waitUntil(10_000) { !exists(m) }

    /** The model list's rows and headings in order, as TalkBack reads them. */
    private fun listed() = rule.onNode(hasTestTag("model.list")).onChildren().fetchSemanticsNodes()
        .map { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString() }

    private fun selected() = rule.onAllNodes(isSelected()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }

    private fun optionRequests() = requests.count { it.target.startsWith(OPTIONS) }

    /** The coroutines [action] launched in the view model that are still running (the request or the write it started). */
    private fun ChatViewModel.launchedBy(action: () -> Unit): List<Job> {
        val before = viewModelScope.coroutineContext.job.children.toSet()
        action()
        return viewModelScope.coroutineContext.job.children.filter { it !in before }.toList()
    }

    @Test
    fun defaultFirstAndCurrentChecked() {
        addSession(ChatModel("alpha-small", "alpha"))
        routes[OPTIONS] = json(minimal)
        val vm = vm()
        host(vm)
        rule.waitUntil(10_000) { vm.state.value.choice.model == "alpha-small" }
        vm.openModelSheet()
        waitFor(hasText("beta-1"))
        assertEquals(listOf("Server default", "alpha", "alpha-large", "alpha-small", "beta", "beta-1"), listed())
        assertEquals(listOf("alpha-small"), selected())
        assertTrue(exists(hasText("alpha") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)))
        vm.closeSheet()
        waitGone(hasText("beta-1"))
        runBlocking { connected.repo.setChoice(KEY, ChatModel()) }
        vm.openModelSheet()
        waitFor(hasText("beta-1"))
        assertEquals(listOf("Server default"), selected())
    }

    @Test
    fun pickStoresLocallyOnly() {
        addSession(ChatModel(reasoning = "high", fast = true))
        routes[OPTIONS] = json(minimal)
        val vm = vm()
        host(vm)
        vm.openModelSheet()
        waitFor(hasText("beta-1"))
        rule.onNodeWithText("beta-1").performClick()
        waitGone(hasText("beta-1")) // the sheet closes on a pick
        rule.waitUntil(10_000) { stored() == ChatModel("beta-1", "beta", "high", true) }
        assertNull(vm.state.value.sheet)
        assertEquals(listOf(OPTIONS), requests.map { it.target.substringBefore('?') }) // the list, and nothing for the pick
    }

    @Test
    fun reasoningPick() {
        addSession(ChatModel("alpha-large", "alpha"))
        val vm = vm()
        host(vm)
        vm.openReasoningSheet()
        waitFor(hasText("Medium"))
        assertEquals(listOf("Server default"), selected())
        rule.onNodeWithText("High").performClick()
        waitGone(hasText("Medium"))
        rule.waitUntil(10_000) { stored() == ChatModel("alpha-large", "alpha", "high") }
        rule.waitUntil(10_000) { vm.state.value.choice.reasoning == "high" }
        vm.openReasoningSheet()
        waitFor(hasText("Medium"))
        assertEquals(listOf("High"), selected())
        rule.onNodeWithText("Off").performClick()
        rule.waitUntil(10_000) { stored().reasoning == "none" }
        vm.openReasoningSheet()
        waitFor(hasText("Medium"))
        rule.onNodeWithText("Server default").performClick()
        rule.waitUntil(10_000) { stored() == ChatModel("alpha-large", "alpha") }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun fastToggles() {
        addSession(ChatModel("alpha-large", "alpha"))
        val vm = vm()
        rule.waitUntil(10_000) { vm.state.value.choice.model == "alpha-large" }
        assertTrue(vm.toggleFast()) // unset counts as off
        rule.waitUntil(10_000) { vm.state.value.choice.fast == true }
        assertEquals(ChatModel("alpha-large", "alpha", fast = true), stored())
        assertEquals(false, vm.toggleFast())
        rule.waitUntil(10_000) { vm.state.value.choice.fast == false }
        assertEquals(ChatModel("alpha-large", "alpha", fast = false), stored())
        assertTrue(vm.toggleFast())
        rule.waitUntil(10_000) { stored().fast == true }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun failureShowsRetryAndDefaultStaysSelectable() {
        addSession(ChatModel("alpha-large", "alpha"))
        val calls = AtomicInteger()
        routes[OPTIONS] = { if (calls.getAndIncrement() == 0) MockResponse.Builder().code(500).build() else json(minimal)(it) }
        val vm = vm()
        host(vm)
        vm.openModelSheet()
        waitFor(hasText("Could not load models"))
        assertTrue(exists(hasText("Try again")))
        assertTrue(!exists(hasText("alpha-large")))
        rule.onNodeWithText("Try again").performClick()
        waitFor(hasText("alpha-large"))
        assertTrue(!exists(hasText("Could not load models")))
        assertEquals(2, optionRequests())
        vm.closeSheet()
        routes[OPTIONS] = json("{", 200) // malformed: also a failure
        vm.openModelSheet()
        waitFor(hasText("Could not load models"))
        rule.onNodeWithText("Server default").performClick()
        rule.waitUntil(10_000) { stored() == ChatModel() }
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun lateOptionsAfterSwitchDropped() {
        addSession()
        val release = CountDownLatch(1).also { latches += it }
        routes[OPTIONS] = { release.await(10, TimeUnit.SECONDS); json(minimal)(it) }
        val vm = vm()
        val fetching = vm.launchedBy { vm.openModelSheet() } // with any history check the chat's first state started
        rule.waitUntil(10_000) { optionRequests() == 1 }
        open.set(false) // a server or key switch closed this connection: its generation is over
        release.countDown()
        runBlocking { withTimeout(10_000) { fetching.joinAll() } }
        assertEquals(ChoiceSheet.Model(), vm.state.value.sheet) // still waiting: the old generation's list never shows
        val picking = vm.launchedBy { vm.pickModel("alpha", "alpha-large") } // a pick in the old generation stores nothing
        runBlocking { withTimeout(10_000) { picking.joinAll() } }
        assertEquals(ChatModel(), stored())
    }

    @Test
    fun lateOptionsAfterChatChangeDropped() {
        addSession()
        addSession(key = "apofis-b")
        val release = CountDownLatch(1).also { latches += it }
        routes[OPTIONS] = { release.await(10, TimeUnit.SECONDS); json(minimal)(it) }
        val a = vm()
        val fetching = a.launchedBy { a.openModelSheet() } // with any history check the chat's first state started
        rule.waitUntil(10_000) { optionRequests() == 1 }
        stores.single().clear() // another chat opened: AppNav clears the view model of the chat it leaves
        val b = vm("apofis-b")
        release.countDown()
        runBlocking { withTimeout(10_000) { fetching.joinAll() } }
        assertTrue("the request ended with its chat", fetching.any { it.isCancelled })
        assertEquals(ChoiceSheet.Model(), a.state.value.sheet)
        assertNull(b.state.value.sheet)
        assertEquals(ChatModel(), stored())
        assertEquals(ChatModel(), stored("apofis-b"))
    }

    @Test
    fun missingStoredChoiceStillSent() {
        val retired = ChatModel("retired-model", "alpha")
        addSession(retired, onServer = true)
        routes[OPTIONS] = json(minimal)
        routes["/api/sessions/$KEY/messages"] = json("""{"session_id":"$KEY","data":[]}""")
        routes["/v1/runs"] = json(fixture("run_start.json"), 202)
        val vm = vm()
        host(vm)
        rule.waitUntil(10_000) { vm.state.value.choice == retired }
        vm.openModelSheet()
        waitFor(hasText("beta-1"))
        assertEquals(emptyList<String>(), selected()) // not in the list: nothing checked, not even Server default
        vm.closeSheet()
        runBlocking { connected.repo.send(KEY, "hi") }
        assertEquals(buildRunBody(KEY, "hi", choice = retired), requests.single { it.target == "/v1/runs" }.body!!.utf8())
    }

    @Test
    fun sheetScrollsWhenLong() {
        addSession()
        val providers = (0 until 40).joinToString(",") { p -> """{"slug":"p$p","models":[${(0 until 25).joinToString(",") { "\"p$p-m$it\"" }}]}""" }
        routes[OPTIONS] = json("""{"providers":[$providers]}""")
        val vm = vm()
        host(vm)
        vm.openModelSheet()
        waitFor(hasText("p0-m0"))
        val composed = rule.onAllNodes(hasText("-m", substring = true)).fetchSemanticsNodes().size
        assertTrue("only what is on screen is composed: $composed of 1000", composed in 1..24) // one provider holds 25
        rule.onNode(hasTestTag("model.list")).performScrollToNode(hasText("p39-m24"))
        rule.onNodeWithText("p39-m24").assertIsDisplayed().performClick()
        rule.waitUntil(10_000) { stored() == ChatModel("p39-m24", "p39") }
    }

    @Test
    fun oneHapticPerPick() {
        addSession()
        routes[OPTIONS] = json(minimal)
        val calls = CopyOnWriteArrayList<Int>()
        val vm = vm()
        host(vm, ViewHaptics({ calls += it; true }, { true }))
        vm.openModelSheet()
        waitFor(hasText("beta-1"))
        rule.onNodeWithText("beta-1").performClick()
        waitGone(hasText("beta-1"))
        assertEquals(1, calls.size)
        vm.openReasoningSheet()
        waitFor(hasText("Low"))
        rule.onNodeWithText("Low").performClick()
        waitGone(hasText("Low"))
        assertEquals(2, calls.size)
    }

    @Test
    fun sheetsSayRequested() {
        addSession()
        routes[OPTIONS] = json(minimal)
        val vm = vm()
        host(vm)
        vm.openModelSheet()
        waitFor(hasText("Model for this chat"))
        rule.onNodeWithText("The server may use another model").assertIsDisplayed()
        vm.openReasoningSheet()
        waitFor(hasText("Reasoning for this chat"))
        rule.onNodeWithText("The server may ignore this setting").assertIsDisplayed()
        assertTrue(!exists(hasText("The server may use another model")))
    }

    // ---- the screen on a plain state ----

    private val approval = PendingControl("approval", "ctl_1", 1, "rm -rf ~/old", null)
    private val live = LiveReply("run_1", "running", "Checking.", emptyList(), approval, Connection.LIVE)
    private val chat = ChatUiState(
        "k", "Chat", StatusLine.WORKING, withLive(listOf(AssistantReply("r0", emptyList(), "Earlier.", FIXED_NOW)), live) { FIXED_NOW },
        ComposerMode.STOP, "", "hermes.example", live,
    )
    private val options = ModelOptions("alpha-large", "alpha", listOf(ProviderModels("alpha", listOf("alpha-large", "alpha-small"))))

    private fun tick(ms: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    @Test
    fun aPressBegunBeforeTheModelSheetAnswersNothing() {
        val approvals = ArrayList<ApprovalChoice>()
        val actions = object : ChatActions {
            override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {
                approvals += choice
            }
        }
        rule.mainClock.autoAdvance = false
        var ui by mutableStateOf(chat)
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalAnswerClock provides { rule.mainClock.currentTime }) {
                    ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
                }
            }
        }
        tick(1_000)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) } // well after the interval
        tick(50)
        ui = ui.copy(sheet = ChoiceSheet.Model())
        tick(48)
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        ui = ui.copy(sheet = null)
        tick(48)
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        tick(16)
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick() // a press after the sheet is answered: closing restarts nothing
        assertEquals(listOf(ApprovalChoice.ONCE), approvals)
    }

    @Test
    fun theChromeStaysWhileASheetChanges() {
        var ui by mutableStateOf(chat)
        rule.setContent { Themed { ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val frames = { n: Int -> repeat(n) { Snapshot.sendApplyNotifications(); rule.mainClock.advanceTimeByFrame() } }
        frames(5)
        val bar = ChromeProbe.barCompositions
        val bottom = ChromeProbe.bottomCompositions
        val body = ChromeProbe.bodyCompositions
        val root = ChromeProbe.rootMeasures
        for (next in listOf(
            ui.copy(sheet = ChoiceSheet.Model()),
            ui.copy(sheet = ChoiceSheet.Model(options)),
            ui.copy(sheet = ChoiceSheet.Model(options), choice = ChatModel("alpha-small", "alpha")),
            ui.copy(sheet = ChoiceSheet.Reasoning, choice = ChatModel("alpha-small", "alpha", "low")),
            ui.copy(sheet = null, choice = ChatModel("alpha-small", "alpha", "low", true)),
        )) {
            ui = next
            frames(5)
            assertEquals("header recomposed by $next", bar, ChromeProbe.barCompositions)
            assertEquals("composer recomposed by $next", bottom, ChromeProbe.bottomCompositions)
            assertEquals("transcript recomposed by $next", body, ChromeProbe.bodyCompositions)
            assertEquals("root measured by $next", root, ChromeProbe.rootMeasures)
        }
    }
}
