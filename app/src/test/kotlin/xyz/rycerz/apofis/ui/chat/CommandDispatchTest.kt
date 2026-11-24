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
import androidx.lifecycle.ViewModel
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNotNull
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
import xyz.rycerz.apofis.chat.Composer
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
import xyz.rycerz.apofis.sync.MAX_ROW_BYTES
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

    /** The thread the latest store ran on, read where it arms its worker (inside the store, before its transaction). */
    @Volatile private var storeThread: Thread? = null

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
            api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, schedule = { storeThread = Thread.currentThread(); storeGate?.await() },
            readImage = { ByteArray(10) }, imageSize = { 10L },
        )
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        // The holder's own scope (a child of the fixture's): settling it waits for stores only, not for the follower.
        val composer = Composer(CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO))
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope, composer)
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

    /** [online]: a flow that never emits keeps the state at its first value (the shown choice never catches up). */
    private fun vm(key: String = KEY, online: Flow<Boolean> = flowOf(true), onNew: () -> Unit = {}): ChatViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(key, connected, online, onNew) } })[ChatViewModel::class]
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

    /** A live run of [KEY] with [status] whose stream stays open until the test ends. */
    private suspend fun heldRun(status: String = "running") {
        routes["GET /v1/runs/$RUN"] = json(
            200,
            """{"object":"hermes.run","run_id":"$RUN","status":"$status","session_id":"$KEY","admission_id":"adm_1","execution_generation":3,"output":"","pending_controls":[]}""",
        )
        val release = latch()
        routes["GET /v1/runs/$RUN/events"] = {
            release.hold()
            MockResponse.Builder().body(": open\n\nid: 9\ndata: {\"event\":\"run.completed\",\"seq\":9}\n\n").addHeader("Content-Type", "text/event-stream").build()
        }
        follower.resumeAll()
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, status, 1L))
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

    /** Every store the holder took has ended (stored, failed or refused): the composer's own completion, not a poll. */
    private suspend fun settled() = withContext(Dispatchers.Default) { withTimeout(60_000) { connected.composer.settle() } }

    /** The view model coroutines [action] started. */
    private fun ViewModel.launchedBy(action: () -> Boolean): Pair<Boolean, List<Job>> {
        val parent = viewModelScope.coroutineContext.job
        val before = parent.children.toSet()
        val result = action()
        return result to parent.children.filter { it !in before }.toList()
    }

    /** Runs [action] and waits for its END: the coroutines it started joined and the holder's stores settled. */
    private suspend fun ChatViewModel.ended(action: () -> Boolean): Boolean {
        val (result, started) = launchedBy(action)
        withContext(Dispatchers.Default) { withTimeout(60_000) { started.joinAll() } }
        settled()
        return result
    }

    /** Every state [ChatViewModel.state] shows from now on, in order. */
    private fun ChatViewModel.states() = CopyOnWriteArrayList<ChatUiState>().also { seen -> scope.launch(Dispatchers.Unconfined) { state.collect { seen += it } } }

    /** After the claim, no state shows the field released before the stored key was published. */
    private fun List<ChatUiState>.publishedBeforeRelease(key: String) {
        val claimed = indexOfFirst { it.sending }
        assertTrue("the holder claimed it", claimed >= 0)
        val released = drop(claimed).filter { !it.sending }
        assertTrue("the claim was released", released.isNotEmpty())
        assertTrue("released before the key was published: $released", released.all { it.storedSends == 1L && it.storedKey == key })
    }

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
        session() // local only: opening it makes no request, so every request would be the command's
        val vm = vm()
        photo()
        vm.type("/status now")
        assertFalse(vm.ended { vm.onSendClick() })
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
        settled()
        assertEquals(emptyList<String>(), lines()) // no request of any method
    }

    @Test
    fun filePathSent() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/etc/hosts is broken")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals("/etc/hosts is broken", rows().single().text)
        assertEquals("", composer().draft)
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
        val (ran, newChat) = first.launchedBy { a.onSendClick() } // the drawer's New chat, started by the command
        assertTrue(ran)
        withContext(Dispatchers.Default) { withTimeout(60_000) { newChat.joinAll() } }
        assertEquals("", composer(empty).draft)
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
        val states = vm.states()
        vm.type("/steer  go left ")
        assertTrue(vm.ended { vm.onSendClick() })
        val row = rows().single()
        assertEquals(PendingState.STEERED, row.state) // delivered inside the store, before the holder settled
        assertEquals("go left", row.text)
        assertTrue(row.steerOnly)
        assertFalse(row.requestBody.contains("/steer"))
        val steer = requests.single { it.target == "/v1/runs/$RUN/steer" }
        assertTrue(steer.body!!.utf8().contains("go left"))
        assertFalse(steer.body!!.utf8().contains("/steer"))
        assertEquals("", composer().draft)
        val done = vm.await { it.storedSends == 1L }
        assertEquals(row.idempotencyKey, done.storedKey) // pinned like a send (ruling 1)
        states.publishedBeforeRelease(row.idempotencyKey)
        assertEquals(1, accepted.get())
        assertTrue(lines().none { it == "POST /v1/runs" })
    }

    /** The store, and with it the steer's request body, is built off the main thread, as a plain send's is. */
    @Test
    fun steerIsStoredOffTheMainThread() = runBlocking<Unit> {
        session(onServer = true)
        steerOk()
        heldRun()
        val vm = vm()
        vm.await { it.live?.status == "running" }
        vm.type("/steer go left")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(PendingState.STEERED, rows().single().state)
        assertNotNull(storeThread)
        assertNotEquals(android.os.Looper.getMainLooper().thread, storeThread)
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
        settled()
        assertEquals("/steer go left", composer().unsent?.text) // shown as not stored, with Put back
        assertEquals("", composer().draft)
        assertTrue(rows().isEmpty())
        assertTrue(lines().none { it.startsWith("POST") })
        assertEquals(0L, vm.state.value.storedSends)
    }

    /** Lens L8: a live run that is not running takes no steer. */
    @Test
    fun steerOnARunNotRunningRefused() = runBlocking<Unit> {
        session(onServer = true)
        heldRun(status = "queued")
        val vm = vm()
        vm.await { it.live?.status == "queued" }
        vm.type("/steer go")
        assertFalse(vm.ended { vm.onSendClick() })
        assertEquals(ComposerNote(NoteKind.CMD_NOTHING_RUNNING), composer().note)
        assertEquals("/steer go", composer().draft)
        assertTrue(rows().isEmpty())
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
        vm.type("/steer  ") // the refusal order: missing text first, photos or not (lens L16)
        assertFalse(vm.onSendClick())
        assertEquals(ComposerNote(NoteKind.CMD_NEEDS_TEXT), composer().note)
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
        assertTrue(vm.ended { vm.onSendClick() })
        val row = rows().single()
        assertEquals(PendingState.FAILED, row.state)
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
        val states = vm.states()
        vm.type("/queue later please")
        assertTrue(vm.ended { vm.onSendClick() })
        val row = rows().single()
        assertEquals("later please", row.text)
        assertEquals(PendingState.QUEUED, row.state) // never a steer, though a run is running
        assertFalse(row.steerOnly)
        assertFalse(row.requestBody.contains("/queue"))
        vm.await { it.draft.isEmpty() && it.storedSends == 1L && it.storedKey == row.idempotencyKey }
        states.publishedBeforeRelease(row.idempotencyKey)
        assertEquals(1, accepted.get())
        connected.sendQueue.drain() // a drain finds the chat busy: it starts after the running reply
        assertEquals(PendingState.QUEUED, rows().single().state)
        assertTrue(lines().none { it.startsWith("POST") })
    }

    /** Astra I1: while /queue is being stored, a second Enter, an edit and another send are refused; one row. */
    @Test
    fun queueBeingStoredRefusesEverySecondSend() = runBlocking<Unit> {
        session(onServer = true)
        heldRun()
        val vm = vm()
        vm.await { it.live != null }
        storeGate = CompletableDeferred()
        vm.type("/queue A")
        assertTrue(vm.onSendClick())
        assertFalse("a second Enter", vm.onSendClick())
        vm.type("/queue B")
        assertEquals("the field takes no edit while it stores", "/queue A", composer().draft)
        assertFalse("another send", vm.onSendClick())
        assertFalse("another command", vm.onCommand(Command.MODEL))
        storeGate!!.complete(Unit)
        settled()
        assertEquals(listOf("A"), rows().map { it.text })
        assertEquals("", composer().draft)
    }

    /** Astra I2: the photo of a /queue being stored cannot go out with a second send too. */
    @Test
    fun queuedPhotoGoesInOneRowOnly() = runBlocking<Unit> {
        session(onServer = true)
        heldRun()
        val vm = vm()
        vm.await { it.live != null }
        photo(1)
        storeGate = CompletableDeferred()
        vm.type("/queue first")
        assertTrue(vm.onSendClick())
        vm.type("/queue second")
        assertFalse(vm.onSendClick())
        connected.composer.removeImage(KEY, composer().images.single().id)
        assertEquals("the photo stays with the store", 1, composer().images.size)
        storeGate!!.complete(Unit)
        settled()
        assertEquals(listOf(digest(1)), rows().map { it.images })
        assertTrue(composer().images.isEmpty())
    }

    /** Lens L9: a /queue too large to send stores nothing and keeps the draft with the note. */
    @Test
    fun queueTooLargeKeepsTheDraft() = runBlocking<Unit> {
        session(onServer = true)
        val vm = vm()
        val line = "/queue " + "a".repeat(MAX_ROW_BYTES + 1)
        vm.type(line)
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(ComposerNote(NoteKind.IMAGES_TOO_LARGE), composer().note)
        assertEquals(line, composer().draft)
        assertNull(composer().unsent)
        assertTrue(rows().isEmpty())
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
        assertTrue(vm.ended { vm.onSendClick() })
        val row = rows().single()
        assertEquals("look at these", row.text)
        assertEquals(listOf(digest(1), digest(2)).joinToString(","), row.images)
        assertEquals(PendingState.QUEUED, row.state)
        assertEquals("", composer().draft)
        assertTrue(composer().images.isEmpty()) // the failed photo leaves with it, as with a send
    }

    @Test
    fun queueWithoutRunStartsNow() = runBlocking<Unit> {
        session(onServer = true)
        routes["POST /v1/runs"] = json(202, """{"run_id":"run_9","status":"started","replayed":false}""")
        val vm = vm()
        vm.type("/queue now please")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(PendingState.SENDING, rows().single().state)
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
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals("", composer().draft)
        val patch = requests.single { it.method == "PATCH" }
        assertEquals("Hello world", JSONObject(patch.body!!.utf8()).getString("title"))
        vm.await { it.title == "Hello world" && it.status == StatusLine.IDLE }

        // The guard ended with the command: the same line again (same text, same revision) renames again.
        vm.type("/title  Hello   world ")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(2, requests.count { it.method == "PATCH" })
    }

    /** Lens L13: /stop and /title answered 500 keep the draft and show the status line, not the title note. */
    @Test
    fun serverErrorsKeepTheDraft() = runBlocking<Unit> {
        session(onServer = true)
        routes["POST /v1/runs/$RUN/stop"] = json(500, "{}")
        routes["PATCH /api/sessions/$KEY"] = json(500, "{}")
        heldRun()
        val vm = vm()
        vm.await { it.live != null }
        vm.type("/stop")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals("/stop", composer().draft)
        vm.await { it.status == StatusLine.SERVER_ERROR }
        vm.type("/title Plans B")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals("/title Plans B", composer().draft)
        assertNull(composer().note)
        assertEquals(StatusLine.SERVER_ERROR, vm.state.value.status)
        assertEquals("Plans", db.sessionDao().get(KEY)!!.title)
    }

    @Test
    fun titleRefusedKeepsDraft() = runBlocking<Unit> {
        session(onServer = true)
        routes["PATCH /api/sessions/$KEY"] = json(400, """{"error":{"code":"invalid_title","message":"Title is already in use"}}""")
        val vm = vm()
        vm.type("/title Taken")
        assertTrue(vm.ended { vm.onSendClick() })
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
        val (ran, renaming) = vm.launchedBy { vm.onSendClick() }
        assertTrue(ran)
        withContext(Dispatchers.IO) { assertTrue(arrived.await(10, TimeUnit.SECONDS)) }
        assertFalse("Enter again while it runs does nothing", vm.onSendClick())
        vm.type("/title Hello there") // edited while the rename is in flight
        release.countDown()
        withContext(Dispatchers.Default) { withTimeout(60_000) { renaming.joinAll() } } // the command has ended
        assertEquals("Hello", db.sessionDao().get(KEY)!!.title)
        assertEquals("/title Hello there", composer().draft)
        assertEquals(1, requests.count { it.method == "PATCH" })
    }

    @Test
    fun longTitleIsCutNotRefused() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/title " + "a".repeat(200))
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals("a".repeat(TITLE_MAX), db.sessionDao().get(KEY)!!.title)
        assertEquals("", composer().draft)
    }

    @Test
    fun titleKeepsSupplementaryCharacters() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/title Trip $FACE " + FACE.repeat(200))
        assertTrue(vm.ended { vm.onSendClick() })
        val title = db.sessionDao().get(KEY)!!.title!!
        assertEquals("Trip $FACE " + FACE.repeat(TITLE_MAX - 7), title)
        assertEquals(TITLE_MAX, title.codePointCount(0, title.length))
    }

    @Test
    fun titleControlsRemoved() = runBlocking<Unit> {
        session()
        val vm = vm()
        // A bell, a zero-width space, a no-break space, a line separator and a bidi override. Hermes drops U+2028 (it
        // does not turn it into a space), so "the" and "week" join, as on the server.
        vm.type("/title Plans\u0007 for\u200B\u00A0the\u2028week\u202E")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals("Plans for theweek", db.sessionDao().get(KEY)!!.title)
    }

    @Test
    fun invisibleOnlyTitleRefusedLocally() = runBlocking<Unit> {
        session(onServer = true)
        val vm = vm()
        for (line in listOf("/title \u200B\u2060\u0007", "/title \uFFFC", "/title \u2065\uFFFC")) { // U+FFFC: what Hermes drops (Astra I4)
            vm.type(line)
            assertFalse(vm.ended { vm.onSendClick() })
            assertEquals(ComposerNote(NoteKind.CMD_NEEDS_TEXT), composer().note)
            assertEquals(line, composer().draft)
        }
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
        assertTrue(vm.ended { vm.onSendClick() })
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
        assertTrue(vm.ended { vm.onCommand(Command.STOP) })
        assertEquals("", composer().draft)
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
        for (line in listOf("/model", "/reasoning", "/fast", "/title Plans B", "/stop", "/status", "/new now", "/title", "/queue", "/new")) {
            vm.type(line)
            vm.ended { vm.onSendClick() } // each one has ENDED before the next: a pin or a signal at its end would show
        }
        assertEquals("Plans B", db.sessionDao().get(KEY)!!.title)
        assertEquals(true, db.sessionDao().get(KEY)!!.fast)
        assertEquals(1, lines().count { it.endsWith("/stop") })
        assertEquals(0L, vm.state.value.storedSends)
        assertNull(vm.state.value.storedKey)
        assertEquals(0, accepted.get())
        assertTrue(rows().isEmpty())
    }

    /** Lens L10: /model and /reasoning open their own sheets, clear the draft and store nothing. */
    @Test
    fun modelAndReasoningOpenTheirSheets() = runBlocking<Unit> {
        session()
        routes["GET /api/model/options"] = json(200, """{"providers":[],"model":null,"provider":null}""")
        val vm = vm()
        vm.type("/model")
        assertTrue(vm.ended { vm.onSendClick() })
        assertTrue(vm.await { it.sheet != null }.sheet is ChoiceSheet.Model)
        assertEquals("", composer().draft)
        vm.closeSheet()
        vm.type("/reasoning")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(ChoiceSheet.Reasoning, vm.await { it.sheet != null }.sheet)
        assertEquals("", composer().draft)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun fastShowsTheStoredValue() = runBlocking<Unit> {
        session()
        val vm = vm()
        vm.type("/fast")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(ComposerNote(NoteKind.FAST_ON), composer().note)
        assertEquals(true, db.sessionDao().get(KEY)!!.fast)
        assertEquals("", composer().draft)
        vm.type("/fast")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(ComposerNote(NoteKind.FAST_OFF), composer().note)
        assertEquals(false, db.sessionDao().get(KEY)!!.fast)
    }

    /** Astra M1: the note follows the stored choice, also when the shown one differs (it has not caught up). */
    @Test
    fun fastShowsTheStoredValueNotTheShownOne() = runBlocking<Unit> {
        session()
        db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(fast = true))
        val vm = vm(online = MutableSharedFlow()) // never emits: the shown state stays at its first value
        assertNull(vm.state.value.choice.fast)
        vm.type("/fast")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(ComposerNote(NoteKind.FAST_OFF), composer().note)
        assertEquals(false, db.sessionDao().get(KEY)!!.fast)
        assertNull(vm.state.value.choice.fast)
    }

    /** Lens L11: with nothing stored (no row for the chat) /fast shows no note and keeps the draft. */
    @Test
    fun fastWithNothingStoredShowsNothing() = runBlocking<Unit> {
        val key = "apofis-none"
        val vm = vm(key)
        vm.type("/fast", key)
        assertTrue(vm.ended { vm.onSendClick() })
        assertNull(composer(key).note)
        assertEquals("/fast", composer(key).draft)
        assertNull(db.sessionDao().get(key))
    }

    /** Astra M2: a cleared view model takes nothing: no claim, no store, no command. */
    @Test
    fun dispatchAfterClearedTakesNothing() = runBlocking<Unit> {
        session()
        val vm = vm()
        stores.last().clear()
        connected.composer.edit(KEY, "hello", composer().revision)
        assertFalse(vm.onSendClick())
        connected.composer.edit(KEY, "/queue later", composer().revision)
        assertFalse(vm.onSendClick())
        assertFalse(vm.onCommand(Command.NEW))
        settled()
        assertTrue(rows().isEmpty())
        assertEquals("/queue later", composer().draft)
        assertEquals(emptyList<String>(), lines())
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

    /** Issue 16 row 5: the screen's Steer passes the run it was drawn for, through the gate, to the view model. */
    @Test
    fun steerButtonPassesItsDrawnRun() {
        val passed = CopyOnWriteArrayList<String?>()
        val actions = object : ChatActions {
            override fun onSendClick(steerRun: String?): Boolean = passed.add(steerRun).let { false }
        }
        val live = xyz.rycerz.apofis.run.LiveReply(RUN, "running", "", emptyList(), null, xyz.rycerz.apofis.run.Connection.LIVE)
        val state = ChatUiState(KEY, "Plans", StatusLine.WORKING, emptyList(), ComposerMode.STEER, "and also", live = live)
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                ChatScreen(state, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
            }
        }
        rule.onNode(hasTestTag(SEND_TAG)).performClick()
        assertEquals(listOf<String?>(RUN), passed)
    }

    @Test
    fun drawnRunIsTheSteerTarget() = runBlocking<Unit> {
        session(onServer = true)
        steerOk()
        heldRun()
        val vm = vm()
        vm.await { it.live != null }
        vm.type("about the old reply")
        assertTrue(vm.ended { vm.onSendClick("run_0") }) // drawn for a run that has ended: queued, not steering run_1
        assertEquals(PendingState.QUEUED, rows().single().state)

        // Lens L7: /steer goes to the drawn run too. run_0 has ended, so nothing is stored; run_1 is never steered.
        vm.type("/steer x")
        assertTrue(vm.ended { vm.onSendClick("run_0") })
        assertEquals("/steer x", composer().unsent?.text)
        assertEquals(1, rows().size)
        assertTrue(lines().none { it.endsWith("/steer") })
        assertNotEquals(null, vm.state.value.live)
    }

}
