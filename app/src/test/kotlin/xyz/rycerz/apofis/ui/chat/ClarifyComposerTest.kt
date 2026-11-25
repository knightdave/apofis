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
import xyz.rycerz.apofis.chat.Answer
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
 * shows [pendingJson]; [show] changes it and makes the follower read it again (a `clarify.request` event). Hosted cases
 * drive the chat screen on the test's main clock, which also times injected input and the answer guard.
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
    private val timedOut = AtomicBoolean(false)

    @Volatile private var pendingJson = "" // what the status read lists as pending_controls
    private val frames = LinkedBlockingQueue<String>() // the next event stream's frames
    private val seq = AtomicLong(0)

    /** How the server answers the next clarify POST; by default it takes the answer and the question goes. */
    @Volatile private var onClarify: (RecordedRequest) -> MockResponse = { pendingJson = ""; resolved() }

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
        assertFalse("a held response timed out", timedOut.get())
    }

    // ---- the stand-in server ----

    private fun response(code: Int, body: String) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
    private fun json(code: Int, body: String) = { _: RecordedRequest -> response(code, body) }

    private fun resolved(id: String = "q_a") = response(200, """{"run_id":"$RUN","status":"resolved","prompt_id":"$id"}""")
    private fun conflict() = response(409, """{"error":{"code":"approval_not_pending"}}""")

    private fun clarify(id: String = "q_a", generation: Long = 3, choices: List<String> = emptyList(), multi: Boolean = false) =
        """{"kind":"clarify","prompt_id":"$id","execution_generation":$generation,"command":null,"question":"Which folder?",""" +
            """"choices":[${choices.joinToString { "\"$it\"" }}],"multi_select":$multi}"""

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
            val next = frames.poll(5, TimeUnit.SECONDS).orEmpty() // nothing: an empty stream, so the follower reconnects
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

    /** The next clarify POST waits until the test completes the returned future with its response. */
    private fun holdClarify(): CompletableFuture<() -> MockResponse> {
        val f = CompletableFuture<() -> MockResponse>().also { holds += it }
        onClarify = { runCatching { f.get(10, TimeUnit.SECONDS) }.getOrElse { timedOut.set(true); { MockResponse.Builder().code(500).build() } }() }
        return f
    }

    private fun posts(path: String) = requests.filter { it.method == "POST" && it.target.endsWith(path) }
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
        session()
        val vm = vm()
        failedSend("lost words")
        run(clarify())
        vm.await { it.composer == ComposerMode.ANSWER }
        vm.type("Downloads")
        show(clarify("q_b"))
        vm.await { it.answer?.expired == true && it.live?.pending?.requestId == "q_b" }
        val drawn = composer().revision
        show(clarify("q_c"))
        vm.await { it.live?.pending?.requestId == "q_c" }
        vm.onDraftChange("Downloads!", drawn)
        assertEquals("Downloads!", composer().answer?.text)
        assertEquals("q_a", composer().answer?.requestId)
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
        c.settleAnswer(KEY, RUN, "q_b", 3, AnswerSettle.SENT)
        c.settleAnswer(KEY, RUN, "q_a", 4, AnswerSettle.EXPIRED)
        assertTrue(c.current(KEY).answer!!.sending)
        c.clear()
        c.showQuestion(KEY, RUN, "q_a", 3) // the same request, a fresh buffer that nobody claimed
        c.editAnswer(KEY, RUN, "q_a", 3, "Desktop", c.current(KEY).revision)
        c.settleAnswer(KEY, RUN, "q_a", 3, AnswerSettle.SENT) // the old send's result
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
        assertTrue(posts("/clarify").isEmpty())
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

    private fun tick(ms: Long = 16) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    /** Frames on the manual clock until [condition] holds (work on other threads in between). */
    private fun waitFor(condition: () -> Boolean) = rule.waitUntil(10_000) { tick(); condition() }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private val answerButton = hasTestTag(SEND_TAG) and hasContentDescription("Answer")
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
        rule.setContent { Screen(vm, enterToSend) }
        waitFor { exists(answerButton) }
        return vm
    }

    /** Waits for the claim that a send would make, in real time: the composer's state after the click. */
    private fun sentOnce(text: String, request: String = "q_a") {
        runBlocking { until { posts("/clarify").isNotEmpty() } }
        val body = answers().single()
        assertTrue(body, body.contains("\"request_id\":\"$request\"") && body.contains("\"answer\":\"$text\""))
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
        waitFor { fieldText() == "Downloads" }
        primary().assertIsEnabled()
        field().performTextClearance()
        waitFor { fieldText() == "" }
        primary().assertIsNotEnabled()
    }

    @Test
    fun stopShownBesideAnswer() {
        hosted()
        field().performTextInput("Downloads")
        tick(400)
        rule.onNodeWithTag(STOP_TAG).performClick()
        runBlocking { until { posts("/stop").isNotEmpty() } }
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
        assertFalse(exists(hasText("Downloads") and hasClickAction()))
        assertFalse(exists(hasText("Desktop") and hasClickAction()))
    }

    @Test
    fun choiceButtonSendsText() {
        hosted(clarify(choices = twoChoices))
        tick(400)
        choice("Desktop").performClick()
        sentOnce("Desktop")
    }

    @Test
    fun imeSendInAnswerModeAnswers() {
        hosted(enterToSend = true)
        field().performTextInput("Downloads")
        tick(400)
        field().performImeAction()
        sentOnce("Downloads")
    }

    @Test
    fun enterBeforeTheIntervalSendsNothing() {
        hosted(enterToSend = true)
        field().performTextInput("Downloads")
        field().performKeyInput { pressKey(Key.Enter) } // inside the interval: judged when it arrives
        nothingSent()
        tick(400)
        field().performKeyInput { pressKey(Key.Enter) }
        sentOnce("Downloads")
    }

    @Test
    fun answerTypedEarlyIsKeptAndSentOnceAfterTheInterval() {
        hosted()
        field().performTextInput("Downloads")
        primary().performClick() // too early: nothing, and nothing used up
        tick(150)
        primary().performClick()
        nothingSent()
        tick(200)
        primary().performClick() // the typed answer is still there
        sentOnce("Downloads")
    }

    @Test
    fun pressBegunInsideTheIntervalReleasedAfterSendsNothing() {
        val vm = hosted()
        vm.type("Downloads")
        waitFor { fieldText() == "Downloads" }
        primary().performTouchInput { down(center) } // begun early
        tick(400)
        primary().performTouchInput { up() } // released late
        tick()
        nothingSent()
        primary().performClick() // a fresh press after the interval
        sentOnce("Downloads")
    }

    @Test
    fun aReplacedRequestWaitsItsOwnInterval() {
        val vm = hosted()
        tick(1_000)
        show(clarify("q_b"))
        waitFor { vm.state.value.answer?.requestId == "q_b" }
        vm.type("Desktop")
        waitFor { fieldText() == "Desktop" }
        primary().performClick() // B was shown a few frames ago: too early
        nothingSent()
        tick(400)
        primary().performClick()
        sentOnce("Desktop", request = "q_b")
    }

    @Test
    @Config(qualifiers = "w640dp-h220dp-xhdpi")
    fun shortWindowAnswerGoesThrough() {
        hosted()
        field().performTextInput("Downloads")
        tick(300)
        primary().performClick()
        sentOnce("Downloads")
    }

    /** A press begun on A, released while B is displayed with an answer typed: nothing; B's own press answers B. */
    @Test
    fun changedPromptDuringPressSendsNothing() = pressAcrossReplacement(clarify("q_b"))

    @Test
    fun newGenerationMidPressSendsNothing() = pressAcrossReplacement(clarify("q_a", generation = 4))

    private fun pressAcrossReplacement(next: String) {
        val vm = hosted()
        field().performTextInput("Downloads")
        tick(400)
        primary().performTouchInput { down(center) }
        show(next)
        waitFor { vm.state.value.answer?.let { it.requestId != "q_a" || it.generation != 3L } == true }
        vm.type("Desktop")
        waitFor { fieldText() == "Desktop" }
        tick(400) // well past the new request's interval: only the press's own start can refuse it
        primary().performTouchInput { up() }
        tick()
        nothingSent()
        primary().performClick()
        runBlocking { until { posts("/clarify").isNotEmpty() } }
        val body = answers().single()
        val replaced = vm.state.value.live!!.pending!!
        assertTrue(body, body.contains("\"request_id\":\"${replaced.requestId}\"") && body.contains("\"execution_generation\":${replaced.executionGeneration}") && body.contains("\"answer\":\"Desktop\""))
    }

    @Test
    fun pressSpanningModalIgnored() {
        val vm = hosted()
        field().performTextInput("Downloads")
        tick(400)
        primary().performTouchInput { down(center) }
        tick()
        vm.openReasoningSheet() // a window opens over the chat while the press is held
        waitFor { exists(hasText("Reasoning for this chat")) }
        vm.closeSheet()
        waitFor { !exists(hasText("Reasoning for this chat")) }
        tick(400)
        primary().performTouchInput { up() }
        tick()
        nothingSent()
        primary().performClick()
        sentOnce("Downloads")
    }

    @Test
    fun pressBegunAsSteerReleasedInAnswerSendsNothing() {
        runBlocking { session(); run() }
        draft("steer this")
        val vm = vm()
        rule.setContent { Screen(vm) }
        waitFor { exists(hasTestTag(SEND_TAG) and hasContentDescription("Send to the running reply")) }
        tick(400)
        primary().performTouchInput { down(center) }
        show(clarify())
        waitFor { vm.state.value.answer != null }
        vm.type("Downloads")
        waitFor { exists(answerButton) && fieldText() == "Downloads" }
        tick(400)
        primary().performTouchInput { up() }
        tick()
        nothingSent()
        assertTrue(posts("/steer").isEmpty())
        assertTrue(runBlocking { rows() }.isEmpty())
        primary().performClick()
        sentOnce("Downloads")
    }

    @Test
    fun pressBegunAsAnswerReleasedAfterSettleSendsNothing() {
        val vm = hosted(before = { draft("keep me") })
        field().performTextInput("Downloads")
        tick(400)
        primary().performTouchInput { down(center) }
        show("")
        waitFor { vm.state.value.answer == null && exists(hasTestTag(SEND_TAG) and hasContentDescription("Send to the running reply")) }
        tick(400)
        primary().performTouchInput { up() }
        tick()
        runBlocking { settled() }
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
        waitFor { vm.state.value.answer?.requestId == "q_b" }
        tick(400)
        choice("Desktop").performTouchInput { up() }
        tick()
        vm.onClarify("q_a", 3, "Desktop") // the view model also re-reads the displayed request
        nothingSent()
        choice("Desktop").performClick() // B's own press
        sentOnce("Desktop", request = "q_b")
    }

    /** New behaviour: after the screen is recreated the field shows the answer and the draft still waits apart. */
    @Test
    fun rotationKeepsAnswerAndDraftApart() {
        runBlocking { session() }
        draft("my draft")
        runBlocking { run(clarify()) }
        val vm = vm()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Screen(vm) }
        waitFor { exists(answerButton) }
        field().performTextInput("Downloads")
        waitFor { fieldText() == "Downloads" }
        rule.mainClock.autoAdvance = true // recreating the screen needs frames
        restoration.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        waitFor { exists(answerButton) }
        assertEquals("Downloads", fieldText())
        assertEquals("my draft", composer().draft)
        show("")
        waitFor { fieldText() == "my draft" }
        assertEquals("Downloads", composer().unsent?.text)
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
