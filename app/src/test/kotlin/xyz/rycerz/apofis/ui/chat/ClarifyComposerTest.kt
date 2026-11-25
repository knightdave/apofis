package xyz.rycerz.apofis.ui.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.PendingControl
import org.json.JSONObject
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.Answer
import xyz.rycerz.apofis.chat.Command
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.chat.UnsentText
import xyz.rycerz.apofis.sync.MAX_ROW_BYTES
import xyz.rycerz.apofis.chat.AnswerSettle
import xyz.rycerz.apofis.chat.ComposeError
import xyz.rycerz.apofis.chat.Composer
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.PendingState
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.theme.ApofisTheme

private const val KEY = "apofis-a"
private const val RUN = "run_1"

private fun digest(i: Int) = "%064x".format(i)

/**
 * A clarify question answered through the composer (Task 8b): a real [ChatViewModel] over an in-memory database, a real
 * queue and follower, and a stand-in server (the Hermes API server's own toolset has no clarify tool). The status read
 * shows [pendingJson]; [show] changes it and makes the follower read it again (a `clarify.request` event, after which
 * the stream ends and the follower reconnects to wait for the next frame the test releases). Hosted cases drive the chat
 * screen on the test's main clock, which also times injected input and the answer guard; it moves only where a test
 * ticks it or draws a bounded number of frames, never while a test waits for another thread.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ClarifyComposerTest {
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
    private val holds = CopyOnWriteArrayList<CompletableFuture<() -> MockResponse>>()
    private val timedOut = AtomicBoolean(false) // sticky: set only by a wait that really ran out, never by a release

    @Volatile private var pendingJson = "" // what the status read lists as pending_controls
    private val frames = LinkedBlockingQueue<String>() // the next event stream's frames
    private val seq = AtomicLong(0)

    /** How the server answers the next clarify POST; by default it takes the answer for the question posted, which goes. */
    @Volatile private var onClarify: (RecordedRequest) -> MockResponse = { pendingJson = ""; resolved(posted(it)) }

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
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
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, readImage = { ByteArray(10) }, imageSize = { 10L })
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        val composer = Composer(CoroutineScope(SupervisorJob(scope.coroutineContext.job) + Dispatchers.IO))
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope, composer)
        routes["POST /v1/runs/$RUN/clarify"] = { onClarify(it) }
        routes["POST /v1/runs/$RUN/stop"] = json(200, """{"run_id":"$RUN","status":"stopping"}""")
    }

    @After
    fun tearDown() {
        // Releases what still waits: a completed hold and a frame are answers, not timeouts.
        holds.forEach { it.complete { MockResponse.Builder().code(500).build() } }
        repeat(4) { frames.put("id: ${seq.incrementAndGet()}\ndata: {\"event\":\"run.completed\",\"seq\":${seq.get()}}\n\n") }
        val jobs = chats.map { it.viewModelScope.coroutineContext.job }
        stores.forEach { it.clear() }
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
        assertFalse("a held response or event stream timed out", timedOut.get())
    }

    // ---- the stand-in server ----

    private fun response(code: Int, body: String) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
    private fun json(code: Int, body: String) = { _: RecordedRequest -> response(code, body) }

    private fun resolved(id: String = "q_a") = response(200, """{"run_id":"$RUN","status":"resolved","prompt_id":"$id"}""")

    /** The request id a clarify POST carries. */
    private fun posted(r: RecordedRequest) = JSONObject(r.body!!.utf8()).getString("request_id")
    private fun conflict() = response(409, """{"error":{"code":"approval_not_pending"}}""")

    private fun clarify(id: String = "q_a", generation: Long = 3, choices: List<String> = emptyList(), multi: Boolean = false) =
        """{"kind":"clarify","prompt_id":"$id","execution_generation":$generation,"command":null,"question":"Which folder?",""" +
            """"choices":[${choices.joinToString { JSONObject.quote(it) }}],"multi_select":$multi}"""

    private val twoChoices = listOf("Downloads", "Desktop")

    /** A chat on this phone only: opening it makes no request. */
    private suspend fun session() = db.sessionDao().upsert(
        SessionEntity(
            key = KEY, currentId = KEY, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
            lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = false,
        ),
    )

    /** A running run of [KEY] displaying [pending]; its event stream sends what [frames] gets, then drops and resumes. */
    private suspend fun run(pending: String = "") {
        pendingJson = pending
        routes["GET /v1/runs/$RUN"] = {
            response(200, """{"object":"hermes.run","run_id":"$RUN","status":"running","session_id":"$KEY","admission_id":"adm_1","execution_generation":3,"output":"","pending_controls":[$pendingJson]}""")
        }
        routes["GET /v1/runs/$RUN/events"] = {
            // Waits for the frame the test releases. Watchdog: after 60 s the route answers an empty stream (the follower
            // reconnects) and the test is marked failed, asserted at teardown.
            val next = frames.poll(60, TimeUnit.SECONDS) ?: "".also { timedOut.set(true) }
            MockResponse.Builder().body(": open\n\n$next").addHeader("Content-Type", "text/event-stream").build()
        }
        follower.resumeAll()
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, "running", 1L))
        follower.onRunStarted(KEY, RUN)
        show(pending) // the follower reads the status on a control event
    }

    /** The status read lists [pending] from now on, and the follower reads it again. */
    private fun show(pending: String) {
        pendingJson = pending
        val n = seq.incrementAndGet()
        frames.put("id: $n\ndata: {\"event\":\"clarify.request\",\"seq\":$n}\n\n")
    }

    /**
     * The next clarify POST waits until the test completes the returned future with its response. Watchdog: after 60 s it
     * answers 500 and the test is marked failed, asserted at teardown; teardown releases a hold still waiting with a 500.
     */
    private fun holdClarify(): CompletableFuture<() -> MockResponse> {
        val f = CompletableFuture<() -> MockResponse>().also { holds += it }
        onClarify = { runCatching { f.get(60, TimeUnit.SECONDS) }.getOrElse { if (it is TimeoutException) timedOut.set(true); { MockResponse.Builder().code(500).build() } }() }
        return f
    }

    private fun posts(path: String) = requests.filter { it.method == "POST" && it.target.endsWith(path) }
    private fun reads() = requests.count { it.method == "GET" && it.target == "/v1/runs/$RUN" }
    private fun attaches() = requests.count { it.method == "GET" && it.target.startsWith("/v1/runs/$RUN/events") }
    private fun answers() = posts("/clarify").map { it.body!!.utf8() }

    // ---- the chat ----

    private fun vm(): ChatViewModel {
        val store = ViewModelStore().also { stores += it }
        return ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(KEY, connected) } })[ChatViewModel::class].also { chats += it }
    }

    private fun composer() = connected.composer.current(KEY)
    private suspend fun rows() = db.pendingSendDao().rows(KEY)

    /** A user edit drawn from the state now, as the screen makes it. */
    private fun ChatViewModel.type(text: String) = onDraftChange(text, composer().revision)

    private fun draft(text: String) = connected.composer.edit(KEY, text, composer().revision)

    private fun photo(i: Int = 1): Long = connected.composer.addImages(KEY, 1).single().also { connected.composer.setImage(it, digest(i)) }

    /** Unsent text with one photo, as a send that failed leaves it. */
    private suspend fun failedSend(text: String, withPhoto: Boolean = true) {
        draft(text)
        if (withPhoto) photo(7)
        connected.composer.sendWithImages(KEY) { _, _ -> error("not stored") }
        settled()
    }

    private suspend fun ChatViewModel.await(condition: (ChatUiState) -> Boolean): ChatUiState =
        withContext(Dispatchers.Default) { withTimeout(60_000) { state.first(condition) } }

    /** A watchdog only: waits in real time for the server's thread. */
    private suspend fun until(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(60_000) { while (!condition()) delay(5) } }

    private suspend fun settled() = withContext(Dispatchers.Default) { withTimeout(60_000) { connected.composer.settle() } }

    /** Runs [action] and waits for the view model coroutines it started to end. */
    private suspend fun ViewModel.ended(action: () -> Boolean): Boolean {
        val parent = viewModelScope.coroutineContext.job
        val before = parent.children.toSet()
        val result = action()
        val started: List<Job> = parent.children.filter { it !in before }.toList()
        withContext(Dispatchers.Default) { withTimeout(60_000) { started.joinAll() } }
        settled()
        return result
    }

    /** A chat whose live run displays [pending], its view model showing the answer buffer. */
    private suspend fun answering(pending: String = clarify(), before: () -> Unit = {}): ChatViewModel {
        session()
        before()
        run(pending)
        return vm().also { it.await { s -> s.composer == ComposerMode.ANSWER } }
    }

    // ---- the answer and its outcome ----

    @Test
    fun sendAnswersClarify() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        assertTrue(vm.onSendClick())
        vm.await { it.answer == null && it.live?.pending == null }
        val body = answers().single()
        assertTrue(body, body.contains("\"request_id\":\"q_a\"") && body.contains("\"execution_generation\":3") && body.contains("\"answer\":\"Downloads\""))
        assertTrue(posts("/steer").isEmpty())
        assertTrue(rows().isEmpty())
        assertNull(composer().unsent)
    }

    @Test
    fun answer409Expires() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft") })
        vm.type("Downloads")
        onClarify = { pendingJson = ""; conflict() } // read before: pending; 409; read after: gone
        assertTrue(vm.onSendClick())
        val s = vm.await { it.answer == null && it.unsent != null }
        assertEquals("Downloads", s.unsent!!.text)
        assertEquals(ComposeError.ANSWER_EXPIRED, s.unsent!!.error)
        assertEquals("my draft", s.draft)
        assertEquals(1, posts("/clarify").size)
    }

    @Test
    fun answer404Expires() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        onClarify = json(404, """{"error":{"code":"run_not_found"}}""")
        assertTrue(vm.ended { vm.onSendClick() })
        val s = vm.await { it.answer?.sending != true && it.unsent != null }
        assertEquals("Downloads", s.unsent!!.text)
        assertEquals(ComposeError.ANSWER_EXPIRED, s.unsent!!.error)
        assertTrue("not the chat-gone line: ${s.status}", s.status != StatusLine.CHAT_GONE)
    }

    @Test
    fun draftKeptAcrossQuestion() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft"); photo() })
        val s = vm.state.value
        assertEquals("", s.draft)
        assertTrue(s.images.isEmpty())
        vm.type("Downloads")
        assertEquals("my draft", composer().draft)
        assertEquals(listOf(digest(1)), composer().images.map { it.digest })
        assertTrue(vm.onSendClick())
        val back = vm.await { it.answer == null && it.live?.pending == null }
        assertEquals("my draft", back.draft)
        assertEquals(listOf(digest(1)), back.images.map { it.digest })
        assertNull(back.unsent)
    }

    @Test
    fun doubleAnswerSendsOnce() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        val post = holdClarify()
        assertTrue(vm.onSendClick())
        assertFalse(vm.onSendClick())
        until { posts("/clarify").size == 1 }
        assertFalse(vm.onSendClick())
        post.complete { pendingJson = ""; resolved() }
        vm.await { it.answer == null && it.live?.pending == null }
        assertEquals(1, posts("/clarify").size)
    }

    @Test
    fun typedAnswerKeptWhenQuestionSettles() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft") })
        vm.type("Downloads")
        show("")
        val s = vm.await { it.answer == null && it.live?.pending == null }
        assertEquals(ComposeError.ANSWER_EXPIRED, s.unsent?.error)
        assertEquals("Downloads", s.unsent?.text)
        assertEquals("my draft", s.draft)
        assertTrue(posts("/clarify").isEmpty())
    }

    /** A's POST held while B is displayed: A's text read-only in the field; then each outcome settles only A. */
    @Test
    fun bShownWhileAInFlightWaitsForA() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        val a = holdClarify()
        assertTrue(vm.onSendClick())
        until { posts("/clarify").size == 1 }
        show(clarify("q_b"))
        val held = vm.await { it.live?.pending?.requestId == "q_b" }
        assertEquals("Downloads", held.draft)
        assertTrue("read-only while A is sent", held.sending)
        assertEquals("q_a", held.answer?.requestId)
        a.complete { conflict() } // B is still pending: CHANGED, so A expired
        val b = vm.await { it.answer?.requestId == "q_b" }
        assertEquals("", b.draft)
        assertFalse(b.sending)
        assertEquals("Downloads", b.unsent?.text)
        assertEquals(ComposeError.ANSWER_EXPIRED, b.unsent?.error)
        vm.type("Desktop")
        assertEquals("Desktop", composer().answer?.text)

        // B sent while C is displayed: SENT moves nothing; C gets a buffer of its own.
        val notice = composer().unsent
        val bPost = holdClarify()
        assertTrue(vm.onSendClick())
        until { posts("/clarify").size == 2 }
        show(clarify("q_c"))
        vm.await { it.live?.pending?.requestId == "q_c" }
        bPost.complete { resolved("q_b") }
        val c = vm.await { it.answer?.requestId == "q_c" }
        assertEquals("", c.draft)
        assertEquals(notice, c.unsent)
    }

    @Test
    fun successRacingPromptReplacement() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        val a = holdClarify()
        assertTrue(vm.onSendClick())
        until { posts("/clarify").size == 1 }
        show(clarify("q_b"))
        vm.await { it.live?.pending?.requestId == "q_b" }
        a.complete { resolved() }
        val b = vm.await { it.answer?.requestId == "q_b" }
        assertEquals("", b.answer?.text)
        assertFalse(b.answer!!.sending)
        assertNull(b.unsent)
    }

    @Test
    fun settledReadBeforeSentMovesNothing() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft") })
        vm.type("Downloads")
        val a = holdClarify()
        assertTrue(vm.onSendClick())
        until { posts("/clarify").size == 1 }
        show("") // the status read shows the question gone while the POST is held
        val gone = vm.await { it.live?.pending == null }
        assertEquals("Downloads", gone.draft)
        assertTrue(gone.answer!!.sending)
        a.complete { resolved() }
        val s = vm.await { it.answer == null }
        assertNull(s.unsent)
        assertEquals("my draft", s.draft)
    }

    @Test
    fun unclearFailureKeepsAnswerEditable() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        // A server error, a body that is no answer, and a 409 after which the question is still pending: unclear.
        for (reply in listOf(json(500, """{"error":{"code":"internal"}}"""), json(200, "<html>"), json(409, """{"error":{"code":"stale_generation"}}"""))) {
            onClarify = reply
            assertTrue(vm.ended { vm.onSendClick() })
            val s = vm.await { it.answer?.sending == false }
            assertEquals("Downloads", s.draft)
            assertEquals(ComposerMode.ANSWER, s.composer)
            assertNull(s.unsent)
        }
        assertEquals(StatusLine.SERVER_ERROR, vm.state.value.status)
        vm.type("Downloads, please")
        assertEquals("Downloads, please", composer().answer?.text)
        onClarify = { pendingJson = ""; resolved() }
        assertTrue(vm.onSendClick())
        vm.await { it.answer == null && it.live?.pending == null }
        assertTrue(answers().last().contains("\"answer\":\"Downloads, please\""))
        assertEquals(4, posts("/clarify").size)
    }

    @Test
    fun editTypedOnAAfterBShownIsDropped() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft") })
        val onA = composer().revision
        show(clarify("q_b"))
        vm.await { it.answer?.requestId == "q_b" }
        vm.onDraftChange("for A", onA)
        assertEquals("", composer().answer?.text)
        assertEquals("my draft", composer().draft)
        assertNull(composer().unsent)
    }

    /** The notice holds text and a photo when an answer expires: it stays as it is; the answer waits in the field. */
    @Test
    fun existingUnsentImageKept() = runBlocking<Unit> {
        session()
        val vm = vm()
        failedSend("lost words")
        val notice = composer().unsent!!
        assertEquals(1, notice.images.size)
        draft("later draft")
        run(clarify())
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Desktop")
        show("")
        val s = vm.await { it.live?.pending == null && it.answer?.expired == true }
        assertEquals(notice, s.unsent)
        assertEquals("Desktop", s.draft)
        assertEquals(ComposerMode.SEND_DISABLED, s.composer)
        assertFalse(vm.onSendClick())
        vm.type("")
        val back = vm.await { it.answer == null }
        assertEquals("later draft", back.draft)
        assertEquals(notice, back.unsent)
        assertTrue(posts("/clarify").isEmpty())
    }

    @Test
    fun emptiedExpiredAnswerBringsBackDraft() = runBlocking<Unit> {
        session()
        val vm = vm()
        failedSend("lost words", withPhoto = false)
        draft("keep me")
        run(clarify())
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Desktop")
        show("")
        vm.await { it.answer?.expired == true }
        vm.type("Deskto") // still an expired answer: edits apply
        assertEquals("Deskto", composer().answer?.text)
        vm.type("/stop") // an expired answer runs no command either
        assertFalse(vm.onSendClick())
        assertTrue(posts("/stop").isEmpty())
        assertEquals("/stop", composer().answer?.text)
        vm.type("")
        val s = vm.await { it.answer == null }
        assertEquals("keep me", s.draft)
        assertEquals(ComposerMode.STEER, s.composer)
    }

    @Test
    fun noticeFreedTakesExpiredAnswer() = runBlocking<Unit> {
        session()
        val vm = vm()
        failedSend("lost words")
        draft("keep me")
        run(clarify())
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Desktop")
        show(clarify("q_b")) // A expires into the field (the notice is full); B waits for it (ruling 10)
        vm.await { it.answer?.expired == true && it.live?.pending?.requestId == "q_b" }
        vm.onDiscardUnsent()
        vm.onConfirmDiscard(vm.await { it.confirmDiscard != null }.confirmDiscard!!.id)
        val s = vm.await { it.answer?.requestId == "q_b" }
        assertEquals("Desktop", s.unsent?.text)
        assertEquals(ComposeError.ANSWER_EXPIRED, s.unsent?.error)
        assertTrue(s.unsent!!.images.isEmpty())
        assertEquals("", s.draft)
        assertEquals(ComposerMode.ANSWER, s.composer)
        assertEquals("keep me", composer().draft)
    }

    /** An expired answer is left exactly as it is while questions change: an edit drawn from it still applies. */
    @Test
    fun expiredAnswerKeepsItsRevisionAcrossQuestions() = runBlocking<Unit> {
        val c = connected.composer
        failedSend("lost words")
        c.showQuestion(KEY, RUN, "q_a", 3)
        c.editAnswer(KEY, RUN, "q_a", 3, "Downloads", c.current(KEY).revision)
        c.showQuestion(KEY, RUN, "q_b", 3) // the notice is full: A stays in the field, expired
        assertTrue(c.current(KEY).answer!!.expired)
        val drawn = c.current(KEY).revision
        c.showQuestion(KEY, RUN, "q_c", 3)
        assertEquals(drawn, c.current(KEY).revision)
        c.editAnswer(KEY, RUN, "q_a", 3, "Downloads!", drawn)
        assertEquals(Answer(RUN, "q_a", 3, "Downloads!", expired = true), c.current(KEY).answer)
    }

    /** A 404 expires the answer while its question still shows (the notice is full): a choice for it sends nothing. */
    @Test
    fun choiceOnAnswerExpiredBy404SendsNothing() = runBlocking<Unit> {
        session()
        val vm = vm()
        failedSend("lost words")
        run(clarify(choices = twoChoices))
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Downloads")
        onClarify = json(404, """{"error":{"code":"run_not_found"}}""")
        assertTrue(vm.ended { vm.onSendClick() })
        val s = vm.await { it.answer?.expired == true }
        assertEquals("q_a", s.live?.pending?.requestId)
        vm.onClarify("q_a", 3, "Desktop")
        assertFalse(vm.onSendClick())
        assertFalse(composer().answer!!.sending)
        assertEquals(1, posts("/clarify").size)
    }

    /** The ids are the settle's token: a settle for another request, or after a clear, leaves the buffer alone. */
    @Test
    fun settleActsOnlyOnItsOwnClaim() {
        val c = connected.composer
        c.showQuestion(KEY, RUN, "q_a", 3)
        c.editAnswer(KEY, RUN, "q_a", 3, "Downloads", c.current(KEY).revision)
        assertEquals("Downloads", c.claimAnswer(KEY, RUN, "q_a", 3))
        c.settleAnswer(KEY, RUN, "q_b", 3, AnswerSettle.SENT, { null })
        c.settleAnswer(KEY, RUN, "q_a", 4, AnswerSettle.EXPIRED, { null })
        assertTrue(c.current(KEY).answer!!.sending)
        c.clear()
        c.showQuestion(KEY, RUN, "q_a", 3) // the same request, a fresh buffer that nobody claimed
        c.editAnswer(KEY, RUN, "q_a", 3, "Desktop", c.current(KEY).revision)
        c.settleAnswer(KEY, RUN, "q_a", 3, AnswerSettle.SENT, { null }) // the old send's result
        assertEquals(Answer(RUN, "q_a", 3, "Desktop"), c.current(KEY).answer)
    }

    /** Ruling 2: /queue typed as the answer is stored through the answer's claim, with no photo of the set-aside draft. */
    @Test
    fun queueInAnswerModeSendsNoImages() = runBlocking<Unit> {
        val vm = answering(before = { draft("with photo"); photo() })
        vm.type("/queue later")
        assertTrue(vm.ended { vm.onSendClick() })
        val row = rows().single()
        assertEquals("later", row.text)
        assertEquals("", row.images)
        assertEquals(PendingState.QUEUED, row.state)
        assertEquals("with photo", composer().draft)
        assertEquals(listOf(digest(1)), composer().images.map { it.digest })
        assertEquals("", composer().answer?.text) // the question still waits; its buffer is empty again
        assertFalse(composer().answer!!.sending)
        assertEquals("q_a", composer().answer?.requestId)
        assertTrue(posts("/clarify").isEmpty())
        vm.type("Downloads") // and takes edits
        assertEquals("Downloads", composer().answer?.text)
    }

    // ---- fix round 1: the drawn revision, one place that ends a claim, the storing commands ----

    /** The view model coroutines [action] starts, not waited for. */
    private fun ViewModel.started(action: () -> Unit): List<Job> {
        val parent = viewModelScope.coroutineContext.job
        val before = parent.children.toSet()
        action()
        return parent.children.filter { it !in before }.toList()
    }

    private suspend fun List<Job>.ended() = withContext(Dispatchers.Default) { withTimeout(60_000) { joinAll() } }

    /** Astra C1: a submit drawn from another revision of the field takes nothing, whatever the field holds now. */
    @Test
    fun staleRevisionSubmitsNothing() = runBlocking<Unit> {
        session()
        draft("my draft")
        photo()
        val drawnDraft = composer().revision
        run(clarify())
        val vm = vm()
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Downloads")
        val drawnAnswer = composer().revision
        assertFalse(vm.ended { vm.onSendClick(RUN, drawnDraft) }) // an answer, submitted from the draft's drawing
        vm.type("/stop")
        assertFalse(vm.ended { vm.onSendClick(RUN, drawnDraft) }) // a typed command
        assertFalse(vm.ended { vm.onCommand(Command.STOP, drawnDraft) }) // a palette row
        assertEquals("/stop", composer().answer?.text)
        assertFalse(composer().answer!!.sending)
        show("")
        vm.await { it.answer == null }
        val now = composer()
        assertFalse(vm.ended { vm.onSendClick(RUN, drawnAnswer) }) // a message, submitted from the answer's drawing
        assertEquals(now, composer())
        assertTrue(rows().isEmpty())
        assertTrue(requests.none { it.method == "POST" })
    }

    /** Astra R1: a command's clear ticks the revision, so a second submit drawn from the command line takes nothing. */
    @Test
    fun secondSubmitAfterACommandTakesNothing() = runBlocking<Unit> {
        session()
        val vm = vm()
        draft("/fast")
        photo()
        val drawn = composer().revision
        assertTrue(vm.ended { vm.onSendClick(null, drawn) })
        until { composer().draft.isEmpty() }
        assertEquals(ComposerNote(NoteKind.FAST_ON), composer().note) // the note set just before stays
        assertFalse(vm.ended { vm.onSendClick(null, drawn) }) // the same drawing again, before a redraw
        assertEquals(listOf(digest(1)), composer().images.map { it.digest })
        assertTrue(rows().isEmpty())
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test
    fun secondSubmitAfterACommandAnswerTakesNothing() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft"); photo() })
        vm.type("/fast")
        val drawn = composer().revision
        assertTrue(vm.ended { vm.onSendClick(RUN, drawn) })
        until { composer().answer?.text?.isEmpty() == true }
        assertFalse(vm.ended { vm.onSendClick(RUN, drawn) })
        assertEquals("my draft", composer().draft)
        assertEquals(listOf(digest(1)), composer().images.map { it.digest })
        assertTrue(rows().isEmpty())
        assertTrue(posts("/clarify").isEmpty())
    }

    /** Astra I5 (a): after SENT, while the run still reports A, A stays closed; another question, or none, reopens it. */
    @Test
    fun sentQuestionStaysClosedUntilAnotherIsObserved() = runBlocking<Unit> {
        routes["POST /v1/runs/$RUN/steer"] = json(200, fixture("steer_response.json"))
        val vm = answering(clarify(choices = twoChoices), before = { draft("my draft") })
        vm.type("Downloads")
        onClarify = { resolved(posted(it)) } // taken; every status read still shows A (the follow-up read has not landed)
        assertTrue(vm.ended { vm.onSendClick() })
        val s = vm.await { it.answer == null }
        assertEquals("q_a", s.live?.pending?.requestId)
        assertEquals("my draft", s.draft)
        assertEquals(ComposerMode.STEER, s.composer)
        assertTrue("its choices drawn disabled", s.answering)
        vm.type("my draft, edited") // the field is the draft again
        assertEquals("my draft, edited", composer().draft)
        assertNull(composer().answer)
        assertFalse(vm.ended { vm.onClarify("q_a", 3, "Desktop"); false }) // a choice tap: nothing to claim
        assertTrue(vm.ended { vm.onSendClick() }) // a submit sends the draft as a message, never as an answer
        assertEquals(1, posts("/clarify").size)
        assertEquals("my draft, edited", rows().single().text)
        show("") // none observed: nothing more changes
        vm.await { it.live?.pending == null }
        assertNull(composer().answer)
        assertNull(composer().unsent)
        until { composer().closed == null } // the holder saw none, not only the live projection
        show(clarify()) // the same A again, after none: a buffer again
        assertEquals("", vm.await { it.answer != null }.answer?.text)
        show(clarify("q_b"))
        vm.await { it.answer?.requestId == "q_b" }
    }

    /** Astra I5 (b): after a 404 the text is in the notice once and A, still reported, is not reopened. */
    @Test
    fun expiredQuestionIsNotReopened() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft") })
        vm.type("Downloads")
        onClarify = json(404, """{"error":{"code":"run_not_found"}}""")
        assertTrue(vm.ended { vm.onSendClick() })
        val s = vm.await { it.unsent != null }
        assertEquals("q_a", s.live?.pending?.requestId)
        assertNull(s.answer)
        assertEquals("my draft", s.draft)
        vm.type("my draft!")
        assertEquals("my draft!", composer().draft)
        show("")
        vm.await { it.live?.pending == null }
        assertEquals(UnsentText(s.unsent!!.id, "Downloads", ComposeError.ANSWER_EXPIRED), composer().unsent)
        assertNull(composer().answer)
    }

    /** Astra I1 (d): a late result after the book was cleared leaves a new, edited buffer of another question alone. */
    @Test
    fun lateSettleAfterClearLeavesTheNewBufferAlone() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        val post = holdClarify()
        val jobs = vm.started { assertTrue(vm.onSendClick()) }
        until { posts("/clarify").size == 1 }
        val c = connected.composer
        c.clear()
        c.showQuestion(KEY, RUN, "q_b", 3)
        c.editAnswer(KEY, RUN, "q_b", 3, "for B", c.current(KEY).revision)
        post.complete { resolved() }
        jobs.ended()
        assertEquals(Answer(RUN, "q_b", 3, "for B"), composer().answer)
        assertNull(composer().unsent)
    }

    /** Astra I1 (e): a late result after the chat was removed brings no entry back. */
    @Test
    fun lateSettleAfterRemoveLeavesNoEntry() = runBlocking<Unit> {
        val vm = answering()
        vm.type("Downloads")
        val post = holdClarify()
        val jobs = vm.started { assertTrue(vm.onSendClick()) }
        until { posts("/clarify").size == 1 }
        connected.composer.remove(KEY)
        post.complete { resolved() }
        jobs.ended()
        assertFalse(KEY in connected.composer.keys)
    }

    /** Astra I1 (f): an observation whose connection check fails, read inside the update, changes nothing. */
    @Test
    fun observationOfAClosedConnectionChangesNothing() {
        val c = connected.composer
        draft("my draft")
        val before = c.current(KEY)
        c.showQuestion(KEY, RUN, "q_a", 3) { false }
        assertEquals(before, c.current(KEY))
        c.showQuestion(KEY, RUN, "q_a", 3) // control
        assertEquals(Answer(RUN, "q_a", 3, ""), c.current(KEY).answer)
    }

    /** Astra M1: the claim takes only the buffer of exactly its run, request and generation. */
    @Test
    fun claimWithOtherIdsTakesNothing() {
        val c = connected.composer
        c.showQuestion(KEY, RUN, "q_a", 3)
        c.editAnswer(KEY, RUN, "q_a", 3, "Downloads", c.current(KEY).revision)
        val before = c.current(KEY)
        assertNull(c.claimAnswer(KEY, "run_2", "q_a", 3))
        assertNull(c.claimAnswer(KEY, RUN, "q_b", 3, choice = "Desktop"))
        assertNull(c.claimAnswer(KEY, RUN, "q_a", 4))
        assertEquals(before, c.current(KEY))
    }

    /** Astra M1: the view model's own choice gate: A's buffer seeded while the run displays B: no claim, read or POST. */
    @Test
    fun choiceForAnotherRequestIsDroppedByTheViewModel() = runBlocking<Unit> {
        val vm = answering(clarify("q_b", choices = twoChoices))
        until { attaches() >= 2 } // the follower read B and waits on its stream again: no read is in flight
        val c = connected.composer
        c.showQuestion(KEY, RUN, "q_a", 3) // B's blank buffer goes, A's is there; the follower stays on B
        val readsBefore = reads()
        assertFalse(vm.ended { vm.onClarify("q_a", 3, "Desktop"); false })
        assertEquals(Answer(RUN, "q_a", 3, ""), composer().answer)
        assertEquals(readsBefore, reads())
        assertTrue(posts("/clarify").isEmpty())
    }

    /** Lens L1: /steer typed as the answer stores only its text, steer-only, with none of the set-aside photos. */
    @Test
    fun steerInAnswerModeStoresOnlyItsText() = runBlocking<Unit> {
        routes["POST /v1/runs/$RUN/steer"] = json(200, fixture("steer_response.json"))
        val vm = answering(before = { draft("x"); photo() })
        vm.type("/steer go left")
        assertTrue(vm.ended { vm.onSendClick() })
        val row = rows().single()
        assertEquals("go left", row.text)
        assertEquals("", row.images)
        assertTrue(row.steerOnly)
        val steer = posts("/steer").single().body!!.utf8()
        assertTrue(steer, steer.contains("go left") && !steer.contains("/steer"))
        assertTrue(posts("/clarify").isEmpty())
        assertEquals("x", composer().draft)
        assertEquals(listOf(digest(1)), composer().images.map { it.digest })
        assertEquals(Answer(RUN, "q_a", 3, ""), composer().answer) // the question still waits
        assertNull(composer().note)
    }

    /** Lens L1 and Astra M2: a failed store keeps the line in the field; only a steer that stored no row says so. */
    @Test
    fun failedStoreKeepsAnswerText() = runBlocking<Unit> {
        val vm = answering()
        for (line in listOf("/queue " + "x".repeat(MAX_ROW_BYTES + 1), "/steer " + "x".repeat(MAX_ROW_BYTES + 1))) {
            vm.type(line)
            assertTrue(vm.ended { vm.onSendClick() }) // the store throws: nothing stored
            assertEquals(line, composer().answer?.text)
            assertFalse(composer().answer!!.sending)
            assertNull("no reason is known", composer().note)
            assertTrue(rows().isEmpty())
        }
        vm.type("/steer go left")
        assertTrue(vm.ended { vm.onSendClick("run_0") }) // drawn for a run that ended: the store returns without a row
        assertEquals("/steer go left", composer().answer?.text)
        assertEquals(ComposerNote(NoteKind.CMD_NOTHING_RUNNING), composer().note)
        assertTrue(rows().isEmpty())
        assertTrue(posts("/clarify").isEmpty())
    }

    /** Lens L3: /stop typed as the answer runs, and clears only the answer; the set-aside draft stays. */
    @Test
    fun stopTypedAsAnswerRunsAndClearsOnlyTheAnswer() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft") })
        vm.type("/stop")
        assertTrue(vm.ended { vm.onSendClick() })
        assertEquals(1, posts("/stop").size)
        assertTrue(posts("/clarify").isEmpty())
        assertEquals("", composer().answer?.text)
        assertEquals("my draft", composer().draft)
    }

    /**
     * A new view model shows the held answer, never the draft. Read right after it is built: its initial value, or its
     * first combined state when Room was faster. Both must show the answer, so the read is not told apart (switching
     * Main to pause it would race the first view model's own work).
     */
    @Test
    fun newViewModelStartsOnTheAnswer() = runBlocking<Unit> {
        val vm = answering(before = { draft("my draft"); photo() })
        vm.type("Downloads")
        val next = vm().state.value
        assertEquals(ComposerMode.ANSWER, next.composer)
        assertEquals("Downloads", next.draft)
        assertTrue(next.images.isEmpty())
        assertEquals("q_a", next.answer?.requestId)
    }

    // ---- choices ----

    @Test
    fun choiceWithBlankFieldSends() = runBlocking<Unit> {
        val vm = answering(clarify(choices = twoChoices))
        vm.onClarify("q_a", 3, "Desktop")
        vm.await { it.answer == null && it.live?.pending == null }
        assertTrue(answers().single().contains("\"answer\":\"Desktop\""))
    }

    @Test
    fun choiceWithTypedAnswerSendsTheChoice() = runBlocking<Unit> {
        val vm = answering(clarify(choices = twoChoices))
        vm.type("my own words")
        val post = holdClarify()
        vm.onClarify("q_a", 3, "Desktop")
        until { posts("/clarify").size == 1 }
        assertEquals("my own words", vm.state.value.draft) // a choice never edits the field
        post.complete { pendingJson = ""; resolved() }
        vm.await { it.answer == null && it.live?.pending == null }
        val body = answers().single()
        assertTrue(body, body.contains("\"answer\":\"Desktop\"") && !body.contains("my own words"))
    }

    @Test
    fun duplicateChoiceTapSendsOnce() = runBlocking<Unit> {
        val vm = answering(clarify(choices = twoChoices))
        val post = holdClarify()
        vm.onClarify("q_a", 3, "Desktop")
        vm.onClarify("q_a", 3, "Desktop")
        vm.onClarify("q_a", 3, "Downloads")
        until { posts("/clarify").size == 1 }
        post.complete { pendingJson = ""; resolved() }
        vm.await { it.answer == null && it.live?.pending == null }
        assertEquals(1, posts("/clarify").size)
    }

    @Test
    fun expiredChoiceTapSendsNothing() = runBlocking<Unit> {
        session()
        val vm = vm()
        failedSend("lost words")
        run(clarify(choices = twoChoices))
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Downloads")
        show(clarify("q_b", choices = twoChoices))
        val s = vm.await { it.live?.pending?.requestId == "q_b" && it.answer?.expired == true }
        assertEquals("q_a", s.answer?.requestId) // one answer per chat (ruling 10)
        vm.onClarify("q_b", 3, "Desktop")
        vm.onClarify("q_a", 3, "Desktop")
        assertFalse(composer().answer!!.sending)
        assertTrue(posts("/clarify").isEmpty())
        vm.type("") // emptied: B, displayed now, gets its buffer and its choice answers it
        vm.await { it.answer?.requestId == "q_b" }
        vm.onClarify("q_b", 3, "Desktop")
        vm.await { it.answer?.sending == false || it.answer == null }
        until { posts("/clarify").size == 1 }
        assertTrue(answers().single().contains("\"request_id\":\"q_b\""))
    }

    // ---- the hosted screen ----

    private val overlays = Overlays()

    @Composable
    private fun Screen(vm: ChatViewModel, enterToSend: Boolean = false) = ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
        CompositionLocalProvider(LocalAnswerClock provides { rule.mainClock.currentTime }, LocalOverlays provides overlays) {
            val state by vm.state.collectAsState()
            ChatScreen(state, vm, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, enterToSend = enterToSend)
        }
    }

    /** A drawn state with [actions], on the test's clock (no view model drives it). */
    private fun hostDrawn(state: () -> ChatUiState, actions: ChatActions) = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalAnswerClock provides { rule.mainClock.currentTime }, LocalOverlays provides overlays) {
                ChatScreen(state(), actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
            }
        }
    }

    private fun tick(ms: Long = 16) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    /**
     * Draws frames until [shown], at most ten. Main-thread work only: the answer clock moves one frame per frame drawn,
     * never by how long another thread took (wait for those first, with [awaitState] or [until]).
     */
    private fun draw(shown: () -> Boolean) {
        repeat(10) {
            if (shown()) return
            tick()
        }
        assertTrue("not drawn within ten frames", shown())
    }

    private fun ChatViewModel.awaitState(condition: (ChatUiState) -> Boolean) = runBlocking { await(condition) }

    /** Runs a UI [action] and waits for the view model coroutines it started to end and the holder's stores to settle. */
    private fun ChatViewModel.after(action: () -> Unit) {
        val parent = viewModelScope.coroutineContext.job
        val before = parent.children.toSet()
        action()
        val started = parent.children.filter { it !in before }.toList()
        runBlocking {
            withContext(Dispatchers.Default) { withTimeout(60_000) { started.joinAll() } }
            settled()
        }
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private val answerButton = hasTestTag(SEND_TAG) and hasContentDescription("Answer")
    private val steerButton = hasTestTag(SEND_TAG) and hasContentDescription("Send to the running reply")
    private fun field() = rule.onNodeWithTag(FIELD_TAG)
    private fun primary() = rule.onNodeWithTag(SEND_TAG)
    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun choice(label: String) = rule.onNode(hasText(label) and hasClickAction())

    /** Hosts the chat answering [pending] ([before] runs first, on a chat with no run); returns once Answer shows. */
    private fun hosted(pending: String = clarify(), enterToSend: Boolean = false, before: () -> Unit = {}): ChatViewModel {
        runBlocking { session() }
        before()
        runBlocking { run(pending) }
        val vm = vm()
        vm.awaitState { it.composer == ComposerMode.ANSWER }
        rule.setContent { Screen(vm, enterToSend) }
        draw { exists(answerButton) }
        tick() // one frame drawn: input reaches what was composed
        return vm
    }

    /**
     * After the submitting jobs ended: exactly one clarify POST, for that request and generation with [text], taken by
     * the server (the notice holds only [notice], nothing of this answer) and the claim ended.
     */
    private fun sentOnce(text: String, request: String = "q_a", generation: Long = 3, notice: String? = null) {
        assertEquals(1, posts("/clarify").size)
        val body = JSONObject(answers().single())
        assertEquals(request, body.getString("request_id"))
        assertEquals(generation, body.getLong("execution_generation"))
        assertEquals(text, body.getString("answer"))
        assertEquals("the server took it", notice, composer().unsent?.text)
        assertFalse(composer().answer?.sending == true)
    }

    private fun nothingSent() {
        assertFalse("claimed", composer().answer?.sending == true)
        assertTrue(posts("/clarify").isEmpty())
    }

    @Test
    fun labelIsAnswer() {
        hosted()
        primary().assert(hasContentDescription("Answer"))
        assertTrue(exists(hasText("Your answer"))) // the field's hint in the answering mode
        rule.onNode(hasText("Which folder?")).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun plusDisabledInAnswerMode() {
        hosted()
        rule.onNode(hasContentDescription("Add photos")).assertIsNotEnabled()
    }

    @Test
    fun blankAnswerDisablesPrimary() {
        hosted()
        primary().assertIsNotEnabled()
        field().performTextInput("Downloads")
        draw { fieldText() == "Downloads" }
        primary().assertIsEnabled()
        field().performTextClearance()
        draw { fieldText() == "" }
        primary().assertIsNotEnabled()
    }

    @Test
    fun stopShownBesideAnswer() {
        val vm = hosted()
        field().performTextInput("Downloads")
        tick(400)
        vm.after {
            rule.onNodeWithTag(STOP_TAG).performClick()
            assertFalse("no answer claimed", composer().answer!!.sending)
        }
        assertEquals(1, posts("/stop").size)
        assertTrue(posts("/clarify").isEmpty())
        assertEquals("Downloads", composer().answer?.text)
    }

    @Test
    fun putBackDisabledWhileAnswering() {
        val vm = hosted(before = { runBlocking { failedSend("lost words") } })
        rule.onNode(hasText("Put back") and hasClickAction()).assertIsNotEnabled()
        rule.onNode(hasText("Discard") and hasClickAction()).assertIsEnabled()
        rule.onNode(hasText("Put back") and hasClickAction()).performClick()
        vm.onPutBack() // the holder refuses too
        assertEquals("lost words", composer().unsent?.text)
        assertEquals("", composer().draft)
        assertEquals("", composer().answer?.text)
    }

    @Test
    fun multiSelectNoButtons() {
        val vm = hosted(clarify(choices = twoChoices, multi = true))
        val pending = vm.state.value.live!!.pending!!
        assertEquals(twoChoices, pending.choices) // decoded from the status JSON
        assertTrue(pending.multiSelect)
        assertTrue(exists(hasText("Which folder?")))
        assertTrue(exists(hasText("Downloads"))) // listed as text
        assertFalse(exists(hasText("Downloads") and hasClickAction()))
        assertFalse(exists(hasText("Desktop") and hasClickAction()))
    }

    @Test
    fun choiceButtonSendsText() {
        val vm = hosted(clarify(choices = twoChoices))
        tick(400)
        vm.after { choice("Desktop").performClick() }
        sentOnce("Desktop")
    }

    /** Lens L2: a choice follows the answer guard: an early tap sends nothing, one after the interval sends once. */
    @Test
    fun choicePressFollowsTheGuard() {
        val vm = hosted(clarify(choices = twoChoices))
        vm.after { choice("Desktop").performClick() } // the question appeared a few frames ago
        nothingSent()
        tick(400)
        vm.after { choice("Desktop").performClick() }
        sentOnce("Desktop")
    }

    @Test
    fun choiceDisabledWhileTheAnswerIsSent() {
        val vm = hosted(clarify(choices = twoChoices))
        vm.type("Downloads")
        tick(400)
        val post = holdClarify()
        primary().performClick()
        runBlocking { until { posts("/clarify").size == 1 } }
        draw { runCatching { choice("Desktop").assertIsNotEnabled() }.isSuccess }
        vm.after { post.complete { pendingJson = ""; resolved() } }
        runBlocking { vm.await { it.answer == null && it.live?.pending == null } }
        assertEquals(1, posts("/clarify").size)
    }

    @Test
    fun imeSendInAnswerModeAnswers() {
        val vm = hosted(enterToSend = true)
        field().performTextInput("Downloads")
        tick(400)
        vm.after { field().performImeAction() }
        sentOnce("Downloads")
    }

    @Test
    fun enterBeforeTheIntervalSendsNothing() {
        val vm = hosted(enterToSend = true)
        field().performTextInput("Downloads")
        vm.after { field().performKeyInput { pressKey(Key.Enter) } } // inside the interval: judged when it arrives
        nothingSent()
        tick(400)
        vm.after { field().performKeyInput { pressKey(Key.Enter) } }
        sentOnce("Downloads")
    }

    @Test
    fun answerTypedEarlyIsKeptAndSentOnceAfterTheInterval() {
        val vm = hosted()
        field().performTextInput("Downloads")
        vm.after { primary().performClick() } // too early: nothing, and nothing used up
        tick(150)
        vm.after { primary().performClick() }
        nothingSent()
        tick(200)
        vm.after { primary().performClick() } // the typed answer is still there
        sentOnce("Downloads")
    }

    @Test
    fun pressBegunInsideTheIntervalReleasedAfterSendsNothing() {
        val vm = hosted()
        vm.type("Downloads")
        draw { fieldText() == "Downloads" }
        primary().performTouchInput { down(center) } // begun early
        tick(400)
        vm.after { primary().performTouchInput { up() } } // released late
        tick()
        nothingSent()
        vm.after { primary().performClick() } // a fresh press after the interval
        sentOnce("Downloads")
    }

    @Test
    fun aReplacedRequestWaitsItsOwnInterval() {
        val vm = hosted()
        tick(1_000)
        show(clarify("q_b"))
        vm.awaitState { it.answer?.requestId == "q_b" }
        vm.type("Desktop")
        draw { fieldText() == "Desktop" }
        vm.after { primary().performClick() } // B was drawn a frame or two ago: too early
        nothingSent()
        tick(400)
        vm.after { primary().performClick() }
        sentOnce("Desktop", request = "q_b")
    }

    @Test
    @Config(qualifiers = "w640dp-h220dp-xhdpi")
    fun shortWindowAnswerGoesThrough() {
        val vm = hosted()
        field().performTextInput("Downloads")
        tick(300)
        vm.after { primary().performClick() }
        sentOnce("Downloads")
    }

    /** A press begun on A, released while B is displayed with an answer typed: nothing; B's own press answers B. */
    @Test
    fun changedPromptDuringPressSendsNothing() = pressAcrossReplacement("q_b", 3)

    @Test
    fun newGenerationMidPressSendsNothing() = pressAcrossReplacement("q_a", 4)

    private fun pressAcrossReplacement(request: String, generation: Long) {
        val vm = hosted()
        field().performTextInput("Downloads")
        tick(400)
        primary().performTouchInput { down(center) }
        show(clarify(request, generation))
        vm.awaitState { it.answer?.let { a -> a.requestId == request && a.generation == generation } == true }
        vm.type("Desktop")
        draw { fieldText() == "Desktop" }
        tick(400) // well past the new request's interval: only the press's own start can refuse it
        vm.after { primary().performTouchInput { up() } }
        tick()
        nothingSent()
        vm.after { primary().performClick() }
        sentOnce("Desktop", request, generation, notice = "Downloads") // A's typed text expired when B replaced it
        assertNull("B was taken, not expired into the occupied notice", composer().answer)
    }

    @Test
    fun pressSpanningModalIgnored() {
        val vm = hosted()
        field().performTextInput("Downloads")
        tick(400)
        primary().performTouchInput { down(center) }
        tick()
        vm.openReasoningSheet() // a window opens over the chat while the press is held
        draw { exists(hasText("Reasoning for this chat")) }
        vm.closeSheet()
        draw { !exists(hasText("Reasoning for this chat")) }
        tick(400)
        vm.after { primary().performTouchInput { up() } }
        tick()
        nothingSent()
        vm.after { primary().performClick() }
        sentOnce("Downloads")
    }

    @Test
    fun pressBegunAsSteerReleasedInAnswerSendsNothing() {
        runBlocking { session(); run() }
        draft("steer this")
        val vm = vm()
        vm.awaitState { it.composer == ComposerMode.STEER }
        rule.setContent { Screen(vm) }
        draw { exists(steerButton) }
        tick(400)
        primary().performTouchInput { down(center) }
        show(clarify())
        vm.awaitState { it.answer != null }
        vm.type("Downloads")
        draw { exists(answerButton) && fieldText() == "Downloads" }
        tick(400)
        vm.after { primary().performTouchInput { up() } }
        tick()
        nothingSent()
        assertTrue(posts("/steer").isEmpty())
        assertTrue(runBlocking { rows() }.isEmpty())
        vm.after { primary().performClick() }
        sentOnce("Downloads")
    }

    @Test
    fun pressBegunAsAnswerReleasedAfterSettleSendsNothing() {
        val vm = hosted(before = { draft("keep me") })
        field().performTextInput("Downloads")
        tick(400)
        primary().performTouchInput { down(center) }
        show("")
        vm.awaitState { it.answer == null && it.composer == ComposerMode.STEER }
        draw { exists(steerButton) }
        tick(400)
        vm.after { primary().performTouchInput { up() } }
        tick()
        assertTrue(posts("/clarify").isEmpty())
        assertTrue(posts("/steer").isEmpty())
        assertTrue(runBlocking { rows() }.isEmpty())
        assertEquals("keep me", composer().draft)
        assertEquals("Downloads", composer().unsent?.text)
    }

    /** A press on A's choice released while B (same labels) is displayed: no POST, and B never gets A's choice. */
    @Test
    fun choiceForChangedRequestSendsNothing() {
        val vm = hosted(clarify(choices = twoChoices))
        tick(400)
        choice("Desktop").performTouchInput { down(center) }
        show(clarify("q_b", choices = twoChoices))
        vm.awaitState { it.answer?.requestId == "q_b" }
        repeat(3) { tick() } // B's block drawn
        tick(400)
        vm.after { choice("Desktop").performTouchInput { up() } }
        tick()
        vm.after { vm.onClarify("q_a", 3, "Desktop") } // the view model also re-reads the displayed request
        nothingSent()
        vm.after { choice("Desktop").performClick() } // B's own press
        sentOnce("Desktop", request = "q_b")
    }

    /** New behaviour: after the screen is recreated the field shows the answer and the draft still waits apart. */
    @Test
    fun rotationKeepsAnswerAndDraftApart() {
        runBlocking { session() }
        draft("my draft")
        runBlocking { run(clarify()) }
        val vm = vm()
        vm.awaitState { it.composer == ComposerMode.ANSWER }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Screen(vm) }
        draw { exists(answerButton) }
        field().performTextInput("Downloads")
        draw { fieldText() == "Downloads" }
        rule.mainClock.autoAdvance = true // recreating the screen needs frames
        restoration.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        draw { exists(answerButton) }
        assertEquals("Downloads", fieldText())
        assertEquals("my draft", composer().draft)
        show("")
        vm.awaitState { it.answer == null }
        draw { fieldText() == "my draft" }
        assertEquals("Downloads", composer().unsent?.text)
    }

    /** Astra C1: a submit drawn for answer A, after the holder settled A and shows a draft with a photo, takes nothing. */
    @Test
    fun staleDrawnAnswerSendsNothing() {
        runBlocking { session() }
        val vm = vm() // observes no run: the test drives the holder
        val c = connected.composer
        draft("my draft")
        photo()
        c.showQuestion(KEY, RUN, "q_a", 3)
        c.editAnswer(KEY, RUN, "q_a", 3, "Downloads", c.current(KEY).revision)
        val drawnRevision = c.current(KEY).revision
        assertEquals("Downloads", c.claimAnswer(KEY, RUN, "q_a", 3))
        c.settleAnswer(KEY, RUN, "q_a", 3, AnswerSettle.SENT, { null })
        val stale = drawn("Downloads").copy(draftRevision = drawnRevision)
        hostDrawn({ stale }, vm)
        draw { exists(answerButton) }
        tick(400)
        // The button, the IME action and Enter all call the same submit lambda (ComposerParts' submit -> onSend).
        vm.after { primary().performClick() }
        assertTrue(runBlocking { rows() }.isEmpty())
        assertTrue(requests.none { it.method == "POST" })
        assertEquals("my draft", composer().draft)
        assertEquals(listOf(digest(1)), composer().images.map { it.digest })
    }

    /** Astra I4: Stop drawn for R1 beside an answer being sent, released after R2 is drawn: nothing; a fresh press stops R2. */
    @Test
    fun heldStopNeverStopsTheNextRun() {
        val stops = CopyOnWriteArrayList<String>()
        val actions = object : ChatActions {
            override fun onStopClick(runId: String) {
                stops += runId
            }
        }
        val first = drawn("Downloads").let { it.copy(sending = true, answering = true, answer = it.answer!!.copy(sending = true)) }
        var ui by mutableStateOf(first)
        hostDrawn({ ui }, actions)
        draw { exists(hasTestTag(STOP_TAG)) }
        tick(400)
        rule.onNodeWithTag(STOP_TAG).performTouchInput { down(center) }
        ui = first.copy(live = first.live!!.copy(runId = "run_2"))
        repeat(3) { tick() }
        rule.onNodeWithTag(STOP_TAG).performTouchInput { up() }
        tick()
        assertTrue("$stops", stops.isEmpty())
        rule.onNodeWithTag(STOP_TAG).performClick()
        assertEquals(listOf("run_2"), stops)
    }

    /** Astra I7, lens L4: choices drawn from a bounded list, each at least 48 dp wide, a long label bounded in height. */
    @Test
    fun choicesAreBounded() {
        val five = listOf("One", "Two", "Three", "Four", "Five")
        val vm = hosted(clarify(choices = five))
        assertTrue(five.none { exists(hasText(it)) }) // more than four: none drawn, the field still answers
        show(clarify("q_b", choices = listOf("", "A", "A")))
        vm.awaitState { it.answer?.requestId == "q_b" }
        draw { exists(hasText("A") and hasClickAction()) }
        val buttons = rule.onAllNodes(hasText("A") and hasClickAction()).fetchSemanticsNodes()
        assertEquals(2, buttons.size)
        assertFalse("a blank choice drawn", exists(hasText("") and hasClickAction() and !hasSetTextAction()))
        val dp48 = with(rule.density) { 48.dp.toPx() }
        assertTrue(buttons.all { it.size.width >= dp48 && it.size.height >= dp48 })
        val long = (1..400).joinToString("\n") { "Line $it of a very long choice" }.take(8_000)
        show(clarify("q_c", choices = listOf(long)))
        vm.awaitState { it.answer?.requestId == "q_c" }
        draw { exists(hasText(long) and hasClickAction()) }
        assertTrue(rule.onNode(hasText(long) and hasClickAction()).fetchSemanticsNode().size.height < with(rule.density) { 150.dp.toPx() })
        tick(400)
        vm.after { choice(long).performClick() }
        sentOnce(long, request = "q_c")
        show(clarify("q_d", choices = twoChoices, multi = true))
        vm.awaitState { it.answer?.requestId == "q_d" }
        draw { exists(hasText("Desktop")) }
        assertTrue(exists(hasText("Downloads")))
        assertFalse(exists(hasText("Downloads") and hasClickAction()))
    }

    /** The expired answer's sentence, drawn while the notice holds other text. */
    @Test
    fun expiredAnswerSentenceDrawn() {
        val expired = drawn("Downloads").let {
            it.copy(composer = ComposerMode.SEND_DISABLED, unsent = UnsentText(1, "lost words", ComposeError.NOT_STORED), answer = it.answer!!.copy(expired = true))
        }
        hostDrawn({ expired }, object : ChatActions {})
        draw { exists(hasText("Not sent: the question had already expired.")) }
        assertTrue(exists(hasText("lost words")))
    }

    // ---- the chrome and the golden (a drawn state, no view model) ----

    private val shownControl = PendingControl("clarify", "q_a", 3, null, "Which folder should I clean up first?", twoChoices)

    private fun drawn(text: String, live: LiveReply = LiveReply(RUN, "running", "Checking the folders.", emptyList(), shownControl, Connection.LIVE)): ChatUiState {
        val items = listOf(UserMessage("u1", "Clean up my files, please.", false, FIXED_NOW))
        return ChatUiState(
            KEY, "Files", StatusLine.WORKING, withLive(items, live) { FIXED_NOW }, ComposerMode.ANSWER, text, "hermes.example", live,
            answer = Answer(RUN, "q_a", 3, text),
        )
    }

    @Test
    fun chromeStableInAnswerMode() {
        var ui by mutableStateOf(drawn("Down"))
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
            }
        }
        repeat(5) { tick() }
        val bottom = ChromeProbe.bottomCompositions
        for (t in 1..5) { // tokens: the composer and its answer are not recomposed
            val more = ui.live!!.copy(text = "Checking the folders" + " more".repeat(t))
            ui = ui.copy(live = more, items = withLive(ui.items.take(1), more) { FIXED_NOW })
            repeat(2) { tick() }
        }
        assertEquals("the composer while answering", bottom, ChromeProbe.bottomCompositions)
        val body = ChromeProbe.bodyCompositions
        for (text in listOf("Downl", "Downlo", "Download")) { // typing the answer: the transcript is not recomposed
            ui = ui.copy(draft = text, answer = ui.answer!!.copy(text = text))
            repeat(2) { tick() }
        }
        assertEquals("the transcript while typing an answer", body, ChromeProbe.bodyCompositions)
        assertTrue(ChromeProbe.bottomCompositions > bottom)
    }

    /** The golden: a question with two choices, a typed answer in the composer, light theme. */
    @Test
    fun chat_clarify_answer() {
        rule.mainClock.autoAdvance = true
        rule.shot("chat_clarify_answer") {
            ChatScreen(drawn("Downloads, then Desktop"), object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
        }
        assertNotNull(rule.onNodeWithTag(SEND_TAG).fetchSemanticsNode())
    }
}
