package xyz.rycerz.apofis.ui.chat

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
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
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.drawnPixels
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.PulseProbe

private const val KEY = "apofis-a"
private const val HISTORY = "/api/sessions/$KEY/messages"

/**
 * The loading skeleton (Task 10): a real [ChatViewModel] over an in-memory database and a local server whose history
 * answer is held, hosted in the chat screen as AppNav hosts it, for when `firstLoad` is set and cleared; then the screen
 * on a plain state for the node, the motion and the chrome.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChatSkeletonTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val latches = mutableListOf<CountDownLatch>()
    private val stores = mutableListOf<ViewModelStore>()
    private val vms = mutableListOf<ChatViewModel>()
    private val expired = AtomicBoolean(false)
    private val loading = hasContentDescription(ApplicationProvider.getApplicationContext<Context>().getString(R.string.chat_loading))

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
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, RunFollower(api, db, repo, scope), AtomicBoolean(true), scope)
    }

    @After
    fun tearDown() {
        try {
            latches.forEach { it.countDown() }
            val jobs = vms.map { it.viewModelScope.coroutineContext.job }
            stores.forEach { it.clear() }
            runBlocking { withTimeout(60_000) { jobs.joinAll() } }
            runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
            db.close()
            server.close()
            assertFalse("a held answer was never released by its test", expired.get())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private val now = System.currentTimeMillis() / 1000.0
    private val serverRows =
        """{"session_id":"$KEY","data":[{"id":10,"role":"user","content":"hello","timestamp":$now},""" +
            """{"id":11,"role":"assistant","content":"Hello","timestamp":$now}]}"""

    private fun json(body: String, code: Int = 200) =
        { _: RecordedRequest -> MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    private fun gate() = CountDownLatch(1).also { latches += it }

    /** Counts down [arrived] once the request reached its responder, then answers with [answer] once [release] opens. */
    private fun held(release: CountDownLatch, arrived: CountDownLatch, answer: (RecordedRequest) -> MockResponse = json(serverRows)): (RecordedRequest) -> MockResponse = {
        arrived.countDown()
        if (release.await(10, TimeUnit.SECONDS)) {
            answer(it)
        } else {
            expired.set(true)
            MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }
    }

    private fun CountDownLatch.reached() = assertTrue("the history request never arrived", await(10, TimeUnit.SECONDS))

    private fun addSession(onServer: Boolean, stale: Boolean = false) = runBlocking {
        db.sessionDao().upsert(
            SessionEntity(
                key = KEY, currentId = KEY, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
                lastActiveAt = null, messageCount = 0, preview = null, stale = stale, existsOnServer = onServer,
            ),
        )
    }

    private fun vm(): ChatViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(KEY, connected) } })[ChatViewModel::class].also { vms += it }
    }

    private fun history() = requests.count { it.target.startsWith(HISTORY) }

    /** The chat screen bound to [vm], as AppNav hosts it. */
    private fun host(vm: ChatViewModel) = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            val state by vm.state.collectAsState()
            ChatScreen(state, vm, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
        }
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { exists(m) }

    private fun waitGone(m: SemanticsMatcher) = rule.waitUntil(10_000) { !exists(m) }

    private fun ChatViewModel.waitState(condition: (ChatUiState) -> Boolean) = rule.waitUntil(10_000) { condition(state.value) }

    /** One more state emission, written straight to the row, so a later assert reads a state built after everything before. */
    private fun ChatViewModel.settle() {
        val title = "settled ${System.nanoTime()}"
        runBlocking { db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(title = title)) }
        waitState { it.title == title }
        rule.waitForIdle()
    }

    /** The coroutines [action] launched in the view model that are still running. */
    private fun ChatViewModel.launchedBy(action: () -> Unit): List<Job> {
        val before = viewModelScope.coroutineContext.job.children.toSet()
        action()
        return viewModelScope.coroutineContext.job.children.filter { it !in before }.toList()
    }

    // ---- when the view model sets and clears firstLoad ----

    @Test
    fun firstFetchShowsSkeleton() {
        addSession(onServer = true) // on the server, nothing stored on this phone
        val release = gate()
        val arrived = gate()
        routes[HISTORY] = held(release, arrived)
        val vm = vm()
        host(vm)
        arrived.reached()
        waitFor(loading)
        assertTrue(vm.state.value.firstLoad)
        assertTrue(vm.state.value.items.isEmpty())
        release.countDown()
        vm.waitState { it.items.isNotEmpty() }
        vm.waitState { !it.firstLoad }
        waitGone(loading)
        assertEquals(1, history())
    }

    /** Control: a stale chat with stored rows is fetched as before, and the skeleton never shows during that fetch. */
    @Test
    fun cachedChatNeverShows() {
        addSession(onServer = true)
        routes[HISTORY] = json(serverRows)
        val vm = vm()
        host(vm)
        vm.waitState { it.items.isNotEmpty() && !it.firstLoad } // stored now
        val release = gate()
        val arrived = gate()
        routes[HISTORY] = held(release, arrived)
        runBlocking { db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(stale = true)) } // a refresh saw it change
        arrived.reached()
        vm.settle() // a state built while the fetch is held
        assertFalse(vm.state.value.firstLoad)
        assertFalse(exists(loading))
        release.countDown()
        rule.waitUntil(10_000) { runBlocking { db.sessionDao().get(KEY)!!.stale } == false }
        vm.settle()
        assertFalse(vm.state.value.firstLoad)
        assertFalse(exists(loading))
        assertEquals(2, history())
    }

    /** Control: a new chat that exists only on this phone makes no request and stays blank (UI map: an empty chat is blank). */
    @Test
    fun newLocalChatBlank() {
        addSession(onServer = false)
        val vm = vm()
        host(vm)
        vm.settle()
        vm.onOpened()
        vm.settle()
        assertEquals(0, history())
        assertFalse(vm.state.value.firstLoad)
        assertTrue(vm.state.value.items.isEmpty())
        assertFalse(exists(loading))
    }

    @Test
    fun failureReplacesWithLoadFailed() {
        // An open chat's failed fetch is not StatusLine.LOAD_FAILED (that is AppNav's drawer load): firstLoad clears, the
        // skeleton goes, the status line shows the failure and the body is the empty list.
        addSession(onServer = true)
        val release = gate()
        val arrived = gate()
        routes[HISTORY] = held(release, arrived, json("""{"error":{"code":"boom"}}""", 500))
        val vm = vm()
        host(vm)
        arrived.reached()
        waitFor(loading)
        release.countDown()
        vm.waitState { it.status == StatusLine.SERVER_ERROR }
        assertFalse(vm.state.value.firstLoad)
        assertTrue(vm.state.value.items.isEmpty())
        waitGone(loading)
    }

    @Test
    fun cancelledOpenClears() {
        addSession(onServer = false) // the first check fetches nothing
        val vm = vm()
        host(vm)
        vm.settle()
        addSession(onServer = true) // created on the server since; still nothing stored
        val release = gate()
        val arrived = gate()
        routes[HISTORY] = held(release, arrived)
        val open = vm.launchedBy { vm.onOpened() }
        assertEquals(1, open.size)
        arrived.reached()
        vm.waitState { it.firstLoad }
        waitFor(loading)
        runBlocking { withTimeout(10_000) { open.single().cancelAndJoin() } } // the request is cancelled, nothing answered
        vm.waitState { !it.firstLoad }
        waitGone(loading)
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(StatusLine.IDLE, vm.state.value.status)
    }

    /**
     * Control of the structure (each chat has its own view model and an open is single flight): a second open while the
     * first fetch is held starts no second fetch, and when the held fetch ends firstLoad is false.
     */
    @Test
    fun overlappingOpenClears() {
        addSession(onServer = true)
        val release = gate()
        val arrived = gate()
        routes[HISTORY] = held(release, arrived)
        val vm = vm()
        host(vm)
        arrived.reached()
        vm.waitState { it.firstLoad }
        assertTrue(vm.launchedBy { vm.onOpened() }.isEmpty())
        assertEquals(1, history())
        release.countDown()
        vm.waitState { it.items.isNotEmpty() && !it.firstLoad }
        waitGone(loading)
        vm.settle()
        assertFalse(vm.state.value.firstLoad)
        assertEquals(1, history())
    }

    /**
     * Control of the structure: an open that finished, then a new open of the same view model after a refresh made the
     * chat stale and its rows are gone. The first open's finally has run already and cannot touch the second's firstLoad.
     */
    @Test
    fun olderRequestCannotClearNewer() {
        addSession(onServer = true)
        routes[HISTORY] = json(serverRows)
        val vm = vm()
        host(vm)
        vm.waitState { it.items.isNotEmpty() && !it.firstLoad } // the first open ended
        val release = gate()
        val arrived = gate()
        routes[HISTORY] = held(release, arrived)
        runBlocking {
            db.messageDao().deleteSession(KEY)
            db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(stale = true))
        }
        arrived.reached()
        vm.waitState { it.firstLoad && it.items.isEmpty() }
        vm.settle() // a state built after everything the first open could still do
        assertTrue(vm.state.value.firstLoad)
        assertTrue(exists(loading))
        release.countDown()
        vm.waitState { it.items.isNotEmpty() && !it.firstLoad }
        assertEquals(2, history())
    }

    // ---- the skeleton on the screen ----

    private val chat = ChatUiState(KEY, "Plans", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, "", "hermes.example", firstLoad = true)

    private var ui by mutableStateOf(chat)
    private var dragArea: Modifier by mutableStateOf(Modifier)
    private var skeletonColor = Color.Unspecified

    private fun show(reduceMotion: Boolean = true) {
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = reduceMotion, blur = false) {
                skeletonColor = ApofisTheme.colors.skeleton
                ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, dragArea = dragArea)
            }
        }
        rule.waitForIdle()
    }

    private fun frames(n: Int) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun oneNodeLabelled() {
        show()
        // The tree accessibility services read: the bars' own nodes are replaced (the unmerged tree still lists them, empty).
        val nodes = rule.onAllNodes(loading).fetchSemanticsNodes()
        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertTrue("the four bars are separate nodes", node.children.isEmpty())
        assertFalse(node.config.contains(SemanticsActions.OnClick))
    }

    /** It takes no touches: a tap and a drag that start on it reach the drawer's drag area under it, not consumed. */
    @Test
    fun touchesReachTheDragArea() {
        var hits = 0
        dragArea = Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) { if (awaitPointerEvent().changes.any { !it.isConsumed }) hits++ } } }
        show()
        rule.onNode(loading, useUnmergedTree = true).performTouchInput { click(Offset(width * 0.3f, 20f)) }
        val tapped = hits
        assertTrue("a tap on the skeleton did not reach the drag area", tapped > 0)
        rule.onNode(loading, useUnmergedTree = true).performTouchInput { swipeRight() }
        assertTrue("a drag on the skeleton did not reach the drag area", hits > tapped)
    }

    /** Only over an empty list and never in place of the load-failed page. */
    @Test
    fun onlyOverAnEmptyList() {
        val pending = UserMessage("pq1", "later", false, FIXED_NOW, SendState.QUEUED, "q1")
        show()
        assertTrue(exists(loading))
        ui = chat.copy(items = listOf(pending))
        rule.waitForIdle()
        assertFalse(exists(loading))
        ui = chat.copy(status = StatusLine.LOAD_FAILED)
        rule.waitForIdle()
        assertFalse(exists(loading))
        ui = chat.copy(firstLoad = false)
        rule.waitForIdle()
        assertFalse(exists(loading))
    }

    @Test
    fun staticUnderReduceMotion() {
        show(reduceMotion = true)
        // Full alpha: the middle of the first bar (start aligned, 44 dp tall, so 44 px down at xhdpi) is the token itself.
        val pixels = rule.onNode(loading, useUnmergedTree = true).drawnPixels()
        assertEquals(skeletonColor.toArgb(), pixels[pixels.width / 10, 44].toArgb())
        rule.mainClock.autoAdvance = false
        frames(5)
        val loops = PulseProbe.frames
        frames(60)
        assertEquals("a frame loop runs under reduced motion", loops, PulseProbe.frames)
    }

    @Test
    fun chromeStableWithSkeleton() {
        rule.mainClock.autoAdvance = false
        show(reduceMotion = false)
        frames(5)
        assertTrue(exists(loading))
        val bar = ChromeProbe.barCompositions
        val bottom = ChromeProbe.bottomCompositions
        val body = ChromeProbe.bodyCompositions
        val root = ChromeProbe.rootMeasures
        val loops = PulseProbe.frames
        frames(60) // the pulse runs: it is read in a graphics layer, so it recomposes and measures nothing
        assertTrue("the pulse did not run", PulseProbe.frames >= loops + 60)
        assertEquals("header recomposed by the pulse", bar, ChromeProbe.barCompositions)
        assertEquals("composer recomposed by the pulse", bottom, ChromeProbe.bottomCompositions)
        assertEquals("transcript recomposed by the pulse", body, ChromeProbe.bodyCompositions)
        assertEquals("root measured by the pulse", root, ChromeProbe.rootMeasures)
        ui = chat.copy(firstLoad = false) // the load ended: only the body changes
        frames(5)
        assertFalse(exists(loading))
        assertEquals("header recomposed by the end of the load", bar, ChromeProbe.barCompositions)
        assertEquals("composer recomposed by the end of the load", bottom, ChromeProbe.bottomCompositions)
        assertEquals("root measured by the end of the load", root, ChromeProbe.rootMeasures)
    }
}
