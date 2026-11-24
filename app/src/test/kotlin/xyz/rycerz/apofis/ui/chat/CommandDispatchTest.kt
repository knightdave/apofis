package xyz.rycerz.apofis.ui.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.Command
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.DraftImage
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.PendingState
import xyz.rycerz.apofis.sync.SendError
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.TITLE_MAX
import xyz.rycerz.apofis.sync.isTemporaryKey
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.drawer.DrawerViewModel
import xyz.rycerz.apofis.ui.drawer.NewChatState
import xyz.rycerz.apofis.ui.theme.ApofisTheme

private const val KEY = "apofis-a"
private const val RUN = "run_1"
private const val FACE = "😀"
private const val LETTER = "\uD835\uDC9C" // a letter outside the BMP: one code point, two UTF-16 units

private fun digest(i: Int) = "%064x".format(i)

/**
 * The one submit path with slash commands: a real [ChatViewModel] over an in-memory database, a real queue and follower
 * and a local server. Requests are routed by method and path. The last two cases host the chat screen for the editor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class CommandDispatchTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private lateinit var follower: RunFollower
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val stores = CopyOnWriteArrayList<ViewModelStore>()
    private val chats = CopyOnWriteArrayList<ChatViewModel>()
    private val drawers = CopyOnWriteArrayList<DrawerViewModel>()
    private val latches = CopyOnWriteArrayList<CountDownLatch>()
    private val timedOut = AtomicBoolean(false)

    /** When set, a store waits before its transaction (after the dispatch returned) until it completes. */
    @Volatile private var storeGate: CompletableDeferred<Unit>? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes["${request.method} ${request.target.substringBefore('?')}"]?.invoke(request) ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).allowMainThreadQueries().build()
        val api = HermesApi(server.url("/").toString(), "secret-key")
        lateinit var f: RunFollower
        val queue = SendQueue(
            api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, schedule = { storeGate?.await() },
            readImage = { ByteArray(10) }, imageSize = { 10L },
        )
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
    }

    @After
    fun tearDown() {
        latches.forEach { it.countDown() }
        storeGate?.complete(Unit)
        runBlocking { withTimeout(60_000) { drawers.forEach { it.refreshJob?.join() } } }
        val jobs = (chats + drawers).map { it.viewModelScope.coroutineContext.job }
        stores.forEach { it.clear() }
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
        assertFalse("a held response timed out", timedOut.get())
    }

    private fun latch() = CountDownLatch(1).also { latches += it }

    private fun CountDownLatch.hold() {
        if (!await(10, TimeUnit.SECONDS)) timedOut.set(true)
    }

    private fun vm(key: String = KEY, onNew: () -> Unit = {}): ChatViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(key, connected, onNew = onNew) } })[ChatViewModel::class]
            .also { chats += it }
    }

    private fun drawer(): DrawerViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory { initializer { DrawerViewModel(connected, zone = { ZoneOffset.UTC }) } })[DrawerViewModel::class]
            .also { drawers += it }
    }

    private fun json(code: Int, body: String) = { _: RecordedRequest -> MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    /** A chat; one on the server answers its first history fetch (made when the chat opens) with no rows. */
    private suspend fun session(key: String = KEY, onServer: Boolean = false, title: String? = "Plans") = db.sessionDao().also {
        routes["GET /api/sessions/$key/messages"] = json(200, """{"session_id":"$key","data":[]}""")
    }.upsert(
        SessionEntity(
            key = key, currentId = key, lineageIds = "", title = title, pinned = false, archived = false, startedAt = 1.0,
            lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = onServer,
        ),
    )

    /** A live run of [KEY] whose stream stays open until the test ends. */
    private suspend fun heldRun() {
        routes["GET /v1/runs/$RUN"] = json(
            200,
            """{"object":"hermes.run","run_id":"$RUN","status":"running","session_id":"$KEY","admission_id":"adm_1","execution_generation":3,"output":"","pending_controls":[]}""",
        )
        val release = latch()
        routes["GET /v1/runs/$RUN/events"] = {
            release.hold()
            MockResponse.Builder().body(": open\n\nid: 9\ndata: {\"event\":\"run.completed\",\"seq\":9}\n\n").addHeader("Content-Type", "text/event-stream").build()
        }
        follower.resumeAll()
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, "running", 1L))
        follower.onRunStarted(KEY, RUN)
    }

    private fun steerOk() {
        routes["POST /v1/runs/$RUN/steer"] = json(200, fixture("steer_response.json"))
    }

    private fun stopOk() {
        routes["POST /v1/runs/$RUN/stop"] = json(200, """{"run_id":"$RUN","status":"stopping"}""")
    }

    private fun titled(title: String) = json(200, """{"object":"hermes.session","session":{"id":"$KEY","source":"api_server","title":${JSONObject.quote(title)},"pinned":false,"archived":false,"hidden":false,"started_at":1.0,"message_count":0}}""")

    private fun ChatViewModel.type(text: String, key: String = KEY) = onDraftChange(text, connected.composer.current(key).revision)

    private suspend fun ChatViewModel.await(condition: (ChatUiState) -> Boolean): ChatUiState =
        withContext(Dispatchers.Default) { withTimeout(60_000) { state.first(condition) } }

    private suspend fun <T> StateFlow<T>.await(condition: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(60_000) { first(condition) } }

    /** A watchdog only: waits in real time for work on other threads (the server, Room). */
    private suspend fun until(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(60_000) { while (!condition()) delay(5) } }

    private fun composer(key: String = KEY) = connected.composer.current(key)
    private suspend fun rows(key: String = KEY) = db.pendingSendDao().rows(key)
    private fun lines() = requests.map { "${it.method} ${it.target}" }
    private fun writes() = requests.filter { it.method != "GET" } // an open chat on the server fetches its history

    /** A draft photo: ready with [i]'s digest, processing, or failed. */
    private fun photo(i: Int = 1, ready: Boolean = true, failed: Boolean = false): Long {
        val id = connected.composer.addImages(KEY, 1).single()
        if (ready) connected.composer.setImage(id, digest(i)) else if (failed) connected.composer.setImage(id, null)
        return id
    }

    /** Counts [ChatViewModel.sendAccepted]. */
    private fun ChatViewModel.accepts() = AtomicInteger().also { n -> scope.launch(Dispatchers.Unconfined) { sendAccepted.collect { n.incrementAndGet() } } }

    // ---- refusals ----

    @Test
    fun unknownCommandSendsNothing() = runBlocking<Unit> {
        session(onServer = true)
        val vm = vm()
        photo()
        vm.type("/status now")
        assertFalse(vm.onSendClick())
        assertEquals(ComposerNote(NoteKind.CMD_UNKNOWN, "/status"), composer().note)
        assertEquals("/status now", composer().draft)
        assertEquals(1, composer().images.size)
        assertTrue(rows().isEmpty())

        vm.type("/" + "x".repeat(40) + LETTER.repeat(10)) // the word is cut for the note, never inside a pair
        assertFalse(vm.onSendClick())
        assertEquals("/" + "x".repeat(31), composer().note!!.arg)
        vm.type("/" + "x".repeat(30) + LETTER.repeat(3))
        assertFalse(vm.onSendClick())
        assertEquals("/" + "x".repeat(30) + LETTER, composer().note!!.arg)
        assertTrue(writes().isEmpty())
    }

    @Test
    fun filePathSent() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/etc/hosts is broken")
        assertTrue(vm.onSendClick())
        until { rows().isNotEmpty() }
        assertEquals("/etc/hosts is broken", rows().single().text)
        vm.await { it.draft.isEmpty() }
    }

    @Test
    fun noArgCommandWithTextDoesNothing() = runBlocking<Unit> {
        session()
        val vm = vm()
        for (word in listOf("new", "stop", "model", "reasoning", "fast")) {
            vm.type("/$word please")
            assertFalse(vm.onSendClick())
            assertEquals(ComposerNote(NoteKind.CMD_NO_TEXT, "/$word"), composer().note)
            assertEquals("/$word please", composer().draft)
        }
        for (word in listOf("steer", "queue", "title")) {
            vm.type("/$word  ")
            assertFalse(vm.onSendClick())
            assertEquals(ComposerNote(NoteKind.CMD_NEEDS_TEXT), composer().note)
        }
        assertNull(vm.state.value.sheet)
        assertEquals(null, db.sessionDao().get(KEY)!!.fast)
        assertTrue(rows().isEmpty())
        assertTrue(writes().isEmpty())
    }

    @Test
    fun stopWithoutRunNotes() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/stop")
        assertFalse(vm.onSendClick())
        assertEquals(ComposerNote(NoteKind.CMD_NOTHING_RUNNING), composer().note)
        assertEquals("/stop", composer().draft)
        vm.type("/steer go")
        assertFalse(vm.onSendClick())
        assertEquals(ComposerNote(NoteKind.CMD_NOTHING_RUNNING), composer().note)
        assertTrue(writes().isEmpty())
    }

    // ---- /new ----

    @Test
    fun newNeverTogglesOrStacks() = runBlocking<Unit> {
        // From a never-created empty ordinary chat: the same chat stays, no new row.
        val first = drawer()
        val empty = first.selected.await { it != null }!!
        val a = vm(empty) { first.newChat() }
        a.type("/new", empty)
        assertTrue(a.onSendClick())
        assertEquals("", composer(empty).draft)
        first.refreshJob?.join()
        assertEquals(empty, first.selected.value)
        assertEquals(listOf(empty), db.sessionDao().all().map { it.key })

        // From an empty temporary chat: a new ordinary chat, and the temporary one is left (deleted).
        val tmp = first.toggleTemporary(first.newChatButton.await { it.key == empty && it.state == NewChatState.TEMPORARY_OFF })!!
        assertTrue(isTemporaryKey(tmp))
        val b = vm(tmp) { first.newChat() }
        b.type("/new", tmp)
        assertTrue(b.onSendClick())
        val next = first.selected.await { it != tmp }!!
        assertFalse(isTemporaryKey(next))
        until { db.sessionDao().get(tmp) == null }

        // From a chat with messages: a new ordinary chat; the old one stays.
        db.messageDao().insertAll(listOf(MessageEntity(next, 0, 1, "user", null, "hello", null, null, null, null, 1.0, null)))
        val c = vm(next) { first.newChat() }
        c.type("/new", next)
        assertTrue(c.onSendClick())
        val third = first.selected.await { it != next }!!
        assertFalse(isTemporaryKey(third))
        assertEquals(setOf(next, third), db.sessionDao().all().map { it.key }.toSet())
        assertTrue(requests.none { it.method == "DELETE" || it.target.startsWith("/v1/runs") })
    }

    // ---- /steer ----

    @Test
    fun steerStoresArgumentOnly() = runBlocking<Unit> {
        session(onServer = true)
        steerOk()
        heldRun()
        val vm = vm()
        val accepted = vm.accepts()
        vm.await { it.live?.status == "running" }
        vm.type("/steer  go left ")
        assertTrue(vm.onSendClick())
        until { rows().singleOrNull()?.state == PendingState.STEERED }
        val row = rows().single()
        assertEquals("go left", row.text)
        assertTrue(row.steerOnly)
        assertFalse(row.requestBody.contains("/steer"))
        val steer = requests.single { it.target == "/v1/runs/$RUN/steer" }
        assertTrue(steer.body!!.utf8().contains("go left"))
        assertFalse(steer.body!!.utf8().contains("/steer"))
        assertEquals("", composer().draft)
        val done = vm.await { it.storedSends == 1L }
        assertEquals(row.idempotencyKey, done.storedKey) // pinned like a send (ruling 1)
        assertEquals(1, accepted.get())
        assertTrue(lines().none { it == "POST /v1/runs" })
    }

    @Test
    fun steerAfterRunEndedCreatesNoRun() = runBlocking<Unit> {
        session(onServer = true)
        heldRun()
        val vm = vm()
        vm.await { it.live?.status == "running" }
        storeGate = CompletableDeferred()
        vm.type("/steer go left")
        assertTrue(vm.onSendClick()) // the dispatch saw it running
        db.activeRunDao().delete(RUN) // it ends before the insert
        storeGate!!.complete(Unit)
        until { composer().note != null }
        assertEquals(ComposerNote(NoteKind.CMD_NOTHING_RUNNING), composer().note)
        assertEquals("/steer go left", composer().draft)
        assertTrue(rows().isEmpty())
        assertTrue(lines().none { it.startsWith("POST") })
        assertEquals(0L, vm.state.value.storedSends)
    }

    @Test
    fun steerWithImagesRefused() = runBlocking<Unit> {
        session(onServer = true)
        heldRun()
        val vm = vm()
        vm.await { it.live?.status == "running" }
        for (ready in listOf(true, false)) {
            if (!ready) connected.composer.current(KEY).images.forEach { connected.composer.removeImage(KEY, it.id) }
            photo(ready = ready, failed = !ready) // a ready photo, then only a failed one: either refuses
            vm.type("/steer look")
            assertFalse(vm.onSendClick())
            assertEquals(ComposerNote(NoteKind.CMD_STEER_NO_IMAGES), composer().note)
            assertEquals("/steer look", composer().draft)
            assertEquals(1, composer().images.size)
        }
        assertTrue(rows().isEmpty())
        assertTrue(lines().none { it.startsWith("POST") })
    }

    @Test
    fun steerRefusedByServerStartsNoRun() = runBlocking<Unit> {
        session(onServer = true)
        routes["POST /v1/runs/$RUN/steer"] = json(409, """{"error":{"code":"steer_not_accepted","message":"not accepted"}}""")
        routes["POST /v1/runs"] = json(202, """{"run_id":"run_2","status":"started","replayed":false}""")
        heldRun()
        val vm = vm()
        vm.await { it.live?.status == "running" }
        vm.type("/steer go left")
        assertTrue(vm.onSendClick())
        until { rows().singleOrNull()?.state == PendingState.FAILED }
        val row = rows().single()
        assertEquals(SendError.STEER_NOT_DELIVERED, row.error)
        assertFalse(row.attempted)
        db.activeRunDao().delete(RUN)
        connected.sendQueue.drain() // the run's end drains: nothing of it starts
        assertEquals(PendingState.FAILED, rows().single().state)
        assertTrue(lines().none { it == "POST /v1/runs" })
        assertEquals("", composer().draft) // it was stored: the bubble shows the failure
    }

    // ---- /queue ----

    @Test
    fun queueStoresArgumentOnly() = runBlocking<Unit> {
        session(onServer = true)
        heldRun()
        val vm = vm()
        val accepted = vm.accepts()
        vm.await { it.live != null }
        vm.type("/queue later please")
        assertTrue(vm.onSendClick())
        until { rows().isNotEmpty() }
        val row = rows().single()
        assertEquals("later please", row.text)
        assertEquals(PendingState.QUEUED, row.state) // never a steer, though a run is running
        assertFalse(row.steerOnly)
        assertFalse(row.requestBody.contains("/queue"))
        vm.await { it.draft.isEmpty() && it.storedSends == 1L && it.storedKey == row.idempotencyKey }
        assertEquals(1, accepted.get())
        assertTrue(lines().none { it.startsWith("POST") }) // it starts after the running reply
    }

    @Test
    fun queueWithImagesQueuesWholeMessage() = runBlocking<Unit> {
        session(onServer = true)
        heldRun()
        val vm = vm()
        vm.await { it.live != null }
        photo(1)
        photo(2)
        photo(ready = false, failed = true)
        val processing = photo(ready = false)
        vm.type("/queue look at these")
        assertFalse("a processing photo: nothing yet, like a send", vm.onSendClick())
        assertNull(composer().note)
        connected.composer.removeImage(KEY, processing)
        vm.type("/queue look at these")
        assertTrue(vm.onSendClick())
        until { rows().isNotEmpty() }
        val row = rows().single()
        assertEquals("look at these", row.text)
        assertEquals(listOf(digest(1), digest(2)).joinToString(","), row.images)
        assertEquals(PendingState.QUEUED, row.state)
        vm.await { it.draft.isEmpty() && it.images.isEmpty() } // the failed photo leaves with it, as with a send
    }

    @Test
    fun queueWithoutRunStartsNow() = runBlocking<Unit> {
        session(onServer = true)
        routes["POST /v1/runs"] = json(202, """{"run_id":"run_9","status":"started","replayed":false}""")
        val vm = vm()
        vm.type("/queue now please")
        assertTrue(vm.onSendClick())
        until { rows().singleOrNull()?.state == PendingState.SENDING }
        assertEquals(1, lines().count { it == "POST /v1/runs" })
        assertTrue(requests.single { it.target == "/v1/runs" }.body!!.utf8().contains("now please"))
    }

    // ---- /title ----

    @Test
    fun titleRenames() = runBlocking<Unit> {
        session(onServer = true)
        routes["PATCH /api/sessions/$KEY"] = titled("Hello world")
        val vm = vm()
        vm.type("/title  Hello   world ")
        assertTrue(vm.onSendClick())
        vm.await { it.title == "Hello world" && it.draft.isEmpty() }
        val patch = requests.single { it.method == "PATCH" }
        assertEquals("Hello world", JSONObject(patch.body!!.utf8()).getString("title"))
        assertEquals(StatusLine.IDLE, vm.state.value.status)
    }

    @Test
    fun titleRefusedKeepsDraft() = runBlocking<Unit> {
        session(onServer = true)
        routes["PATCH /api/sessions/$KEY"] = json(400, """{"error":{"code":"invalid_title","message":"Title is already in use"}}""")
        val vm = vm()
        vm.type("/title Taken")
        assertTrue(vm.onSendClick())
        until { composer().note != null }
        assertEquals(ComposerNote(NoteKind.CMD_TITLE_REFUSED), composer().note)
        assertEquals("/title Taken", composer().draft)
        assertEquals("Plans", db.sessionDao().get(KEY)!!.title)
        assertEquals(StatusLine.IDLE, vm.state.value.status) // not a server error
    }

    @Test
    fun titleSuccessAfterEditKeepsNewText() = runBlocking<Unit> {
        session(onServer = true)
        val release = latch()
        val arrived = CountDownLatch(1)
        routes["PATCH /api/sessions/$KEY"] = {
            arrived.countDown()
            release.hold()
            titled("Hello")(it)
        }
        val vm = vm()
        vm.type("/title Hello")
        assertTrue(vm.onSendClick())
        withContext(Dispatchers.IO) { assertTrue(arrived.await(10, TimeUnit.SECONDS)) }
        assertFalse("Enter again while it runs does nothing", vm.onSendClick())
        vm.type("/title Hello there") // edited while the rename is in flight
        release.countDown()
        vm.await { it.title == "Hello" }
        assertEquals("/title Hello there", composer().draft)
        assertEquals(1, requests.count { it.method == "PATCH" })
    }

    @Test
    fun longTitleIsCutNotRefused() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/title " + "a".repeat(200))
        assertTrue(vm.onSendClick())
        vm.await { it.title == "a".repeat(TITLE_MAX) && it.draft.isEmpty() }
    }

    @Test
    fun titleKeepsSupplementaryCharacters() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/title Trip $FACE " + FACE.repeat(200))
        assertTrue(vm.onSendClick())
        until { db.sessionDao().get(KEY)!!.title != "Plans" }
        val title = db.sessionDao().get(KEY)!!.title!!
        assertEquals("Trip $FACE " + FACE.repeat(TITLE_MAX - 7), title)
        assertEquals(TITLE_MAX, title.codePointCount(0, title.length))
    }

    @Test
    fun titleControlsRemoved() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/title Plans\u0007 for​ the week‮")
        assertTrue(vm.onSendClick())
        vm.await { it.title == "Plans for the week" }
    }

    @Test
    fun invisibleOnlyTitleRefusedLocally() = runBlocking<Unit> {
        session(onServer = true)
        val vm = vm()
        vm.type("/title ​⁠\u0007")
        assertFalse(vm.onSendClick())
        assertEquals(ComposerNote(NoteKind.CMD_NEEDS_TEXT), composer().note)
        assertEquals("/title ​⁠\u0007", composer().draft)
        assertTrue(writes().isEmpty())
        assertEquals("Plans", db.sessionDao().get(KEY)!!.title)
    }

    // ---- the one submit path ----

    /** Control (true since Milestone 2 Task 8): an edit and Enter in one frame send the edited text. */
    @Test
    fun sameFrameEditAndEnterSendsTheEdit() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("hello")
        vm.type("hello there")
        assertTrue(vm.onSendClick())
        until { rows().isNotEmpty() }
        assertEquals("hello there", rows().single().text)
    }

    /** Control: with nothing to send, the IME action sends nothing. */
    @Test
    fun disabledImeSendsNothing() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("  \n ")
        assertFalse(vm.onSendClick())
        assertTrue(rows().isEmpty())
        assertNull(composer().note)
        assertTrue(writes().isEmpty())
    }

    @Test
    fun paletteCommandUsesTheSameDispatch() = runBlocking<Unit> {
        session(onServer = true)
        stopOk()
        val vm = vm()
        vm.type("/st")
        assertFalse(vm.onCommand(Command.STOP))
        val tapped = composer().note
        vm.type("/stop")
        assertFalse(vm.onSendClick())
        assertEquals(ComposerNote(NoteKind.CMD_NOTHING_RUNNING), tapped)
        assertEquals(tapped, composer().note)
        vm.type("/ti")
        assertFalse(vm.onCommand(Command.TITLE)) // a text command from the palette needs its text
        assertEquals(ComposerNote(NoteKind.CMD_NEEDS_TEXT), composer().note)

        heldRun()
        vm.await { it.live != null }
        vm.type("/st")
        assertTrue(vm.onCommand(Command.STOP))
        until { lines().contains("POST /v1/runs/$RUN/stop") }
        vm.await { it.draft.isEmpty() }
        assertEquals(1, lines().count { it.endsWith("/stop") })
    }

    @Test
    fun commandDoesNotScrollOrFollow() = runBlocking<Unit> {
        session()
        stopOk()
        routes["GET /api/model/options"] = json(200, """{"providers":[],"model":null,"provider":null}""")
        heldRun()
        val vm = vm()
        val accepted = vm.accepts()
        vm.await { it.live != null }
        for (line in listOf("/model", "/reasoning", "/fast", "/title Plans B", "/stop", "/status", "/new now", "/title", "/queue")) {
            vm.type(line)
            vm.onSendClick()
        }
        vm.await { it.title == "Plans B" && it.sheet is ChoiceSheet.Reasoning }
        until { lines().any { it.endsWith("/stop") } && db.sessionDao().get(KEY)!!.fast == true }
        vm.type("/new")
        assertTrue(vm.onSendClick())
        assertEquals(0L, vm.state.value.storedSends)
        assertNull(vm.state.value.storedKey)
        assertEquals(0, accepted.get())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun fastShowsTheStoredValue() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/fast")
        assertTrue(vm.onSendClick())
        until { composer().note != null }
        assertEquals(ComposerNote(NoteKind.FAST_ON), composer().note)
        assertEquals(true, db.sessionDao().get(KEY)!!.fast)
        assertEquals("", composer().draft)
        vm.type("/fast")
        assertTrue(vm.onSendClick())
        until { composer().note?.kind == NoteKind.FAST_OFF }
        assertEquals(false, db.sessionDao().get(KEY)!!.fast)
    }

    // ---- the hosted editor ----

    @Composable
    private fun Screen(vm: ChatViewModel) = ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
        val state by vm.state.collectAsState()
        ChatScreen(state, vm, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
    }

    private val expand = hasContentDescription("Expand the editor")
    private val collapse = hasContentDescription("Collapse the editor")
    private fun shows(m: androidx.compose.ui.test.SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun commandCollapsesExpandedEditorOnlyWhenItRan() {
        runBlocking { session() }
        val vm = vm()
        rule.setContent { Screen(vm) }
        vm.type("/status\nof\nthings")
        rule.waitUntil(10_000) { shows(expand) }
        rule.onNode(expand).performClick()
        rule.waitUntil(10_000) { shows(collapse) }
        rule.onNode(hasTestTag(SEND_TAG)).performClick()
        rule.waitUntil(10_000) { composer().note?.kind == NoteKind.CMD_UNKNOWN }
        rule.waitForIdle()
        assertTrue("a refused command keeps the editor expanded", shows(collapse))

        vm.type("/title Plans\nfor\nweek")
        rule.waitForIdle()
        rule.onNode(hasTestTag(SEND_TAG)).performClick()
        rule.waitUntil(10_000) { vm.state.value.title == "Plans for week" }
        rule.waitUntil(10_000) { !shows(collapse) }
    }

    /** Control: the expanded editor is not restored after a configuration change. */
    @Test
    fun rotationResetsExpandedEditor() {
        runBlocking { session() }
        val vm = vm()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Screen(vm) }
        vm.type("a\nb\nc")
        rule.waitUntil(10_000) { shows(expand) }
        rule.onNode(expand).performClick()
        rule.waitUntil(10_000) { shows(collapse) }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertFalse(shows(collapse))
        assertTrue(shows(expand))
        assertEquals("a\nb\nc", composer().draft)
    }

    @Test
    fun drawnRunIsTheSteerTarget() = runBlocking<Unit> {
        session(onServer = true)
        steerOk()
        heldRun()
        val vm = vm()
        vm.await { it.live != null }
        vm.type("about the old reply")
        assertTrue(vm.onSendClick("run_0")) // drawn for a run that has ended: queued, not steering run_1
        until { rows().isNotEmpty() }
        assertEquals(PendingState.QUEUED, rows().single().state)
        assertTrue(lines().none { it.endsWith("/steer") })
        assertNotEquals(null, vm.state.value.live)
    }
}
