package xyz.rycerz.apofis.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ComposeError
import xyz.rycerz.apofis.chat.Composer
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.Notice
import xyz.rycerz.apofis.chat.ReasoningStep
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.RetryResult
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.withRepositoryLock

private const val KEY = "apofis-a"
private const val RUN = "run_1"
private const val EVENTS = "/v1/runs/$RUN/events"
private const val STATUS = "/v1/runs/$RUN"
private const val MESSAGES = "/api/sessions/$KEY/messages"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private lateinit var follower: RunFollower
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val online = MutableStateFlow(true)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val latches = mutableListOf<CountDownLatch>()
    private val latchTimeouts = AtomicInteger() // held streams served only because their wait timed out

    /** A gate for a held stream; every gate is opened in tearDown, so a failed test never blocks the next. */
    private fun latch() = CountDownLatch(1).also { latches += it }

    /** When set, a send waits at the start of its store (before anything is stored) until it completes. */
    @Volatile private var storeGate: CompletableDeferred<Unit>? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        lateinit var f: RunFollower
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, { storeGate?.await() })
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
    }

    @After
    fun tearDown() {
        latches.forEach { it.countDown() }
        storeGate?.complete(Unit)
        // Every coroutine must end before the next test: the repository and send locks are process-wide, and a
        // view model coroutine left suspended on the reset main dispatcher would hold them for good.
        // A send or drain still running in a view model would otherwise meet a closed database while holding those locks.
        val jobs = vms.map { it.viewModelScope.coroutineContext.job }
        store.clear()
        runBlocking { withTimeout(10_000) { jobs.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(10_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
        assertEquals("held streams that timed out", 0, latchTimeouts.get())
    }

    private var created = 0

    private val vms = mutableListOf<ChatViewModel>()

    /** A user edit made on the current state, as the screen does after a redraw. */
    private fun ChatViewModel.onDraftChange(text: String) = onDraftChange(text, connected.composer.current(KEY).revision)

    private fun Composer.edit(key: String, text: String) = edit(key, text, current(key).revision)

    private fun vm(): ChatViewModel = ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(KEY, connected, online) } })
        .get("vm${created++}", ChatViewModel::class).also { vms += it }

    private fun json(body: String, code: Int = 200) =
        { _: RecordedRequest -> MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    private fun sse(vararg frames: String) = MockResponse.Builder().body(": open\n\n" + frames.joinToString(""))
        .addHeader("Content-Type", "text/event-stream").build()

    private fun delta(seq: Long, text: String) = "id: $seq\ndata: {\"event\":\"message.delta\",\"delta\":\"$text\",\"seq\":$seq}\n\n"
    private fun terminal(seq: Long) = "id: $seq\ndata: {\"event\":\"run.completed\",\"seq\":$seq}\n\n"
    private fun status(status: String, pending: String = "") = json(
        """{"object":"hermes.run","run_id":"$RUN","status":"$status","session_id":"$KEY","admission_id":"adm_1",""" +
            """"execution_generation":3,"output":"","pending_controls":[$pending]}""",
    )

    private val now = System.currentTimeMillis() / 1000.0 // the follower confirms a send only by a row from the last minute
    private val serverRows =
        """{"session_id":"$KEY","data":[{"id":10,"role":"user","content":"hello","timestamp":$now},""" +
            """{"id":11,"role":"assistant","content":"Hello","timestamp":$now}]}"""
    private val noRows = """{"session_id":"$KEY","data":[]}"""

    private suspend fun addSession(onServer: Boolean = false, stale: Boolean = false) = db.sessionDao().upsert(
        SessionEntity(
            key = KEY, currentId = KEY, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
            lastActiveAt = null, messageCount = 0, preview = null, stale = stale, existsOnServer = onServer,
        ),
    )

    /** A followed run whose stream stays open (no text) until [release]; then it completes. */
    private suspend fun heldRun(
        release: CountDownLatch,
        first: List<String> = emptyList(),
        runStatus: (RecordedRequest) -> MockResponse = status("running"),
    ) {
        routes[STATUS] = runStatus
        val attaches = AtomicInteger()
        routes[EVENTS] = {
            if (attaches.incrementAndGet() == 1 && first.isNotEmpty()) {
                sse(*first.toTypedArray()) // ends without a terminal event: a drop, then a resume
            } else {
                if (!release.await(10, TimeUnit.SECONDS)) latchTimeouts.incrementAndGet()
                sse(terminal(99))
            }
        }
        follower.resumeAll()
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, "running", 1L))
        db.pendingSendDao().upsert(PendingSendEntity("p1", KEY, "hello", "{}", System.currentTimeMillis(), "sending", RUN, null, attempted = true))
        follower.onRunStarted(KEY, RUN)
    }

    private suspend fun awaitTrue(condition: () -> Boolean) = withContext(Dispatchers.IO) {
        repeat(2000) {
            if (condition()) return@withContext
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within 10 s")
    }

    private suspend fun ChatViewModel.await(condition: (ChatUiState) -> Boolean): ChatUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { state.first(condition) } }

    @Test
    fun composerModes() = runTest {
        addSession()
        val vm = vm()
        assertEquals(ComposerMode.SEND_DISABLED, vm.await { it.title == "Plans" }.composer)
        vm.onDraftChange("hi")
        vm.await { it.composer == ComposerMode.SEND }

        heldRun(latch())
        assertEquals(ComposerMode.STEER, vm.await { it.live != null }.composer)
        vm.onDraftChange("")
        vm.await { it.composer == ComposerMode.STOP }
    }

    @Test
    fun liveReplyAppearsThenIsReplaced() = runTest {
        addSession(onServer = true)
        val fetches = AtomicInteger()
        routes[MESSAGES] = { json(if (fetches.incrementAndGet() == 1) noRows else serverRows)(it) }
        val vm = vm()
        awaitTrue { fetches.get() >= 1 }
        val release = latch()
        heldRun(release, first = listOf(delta(0, "Hel"), delta(1, "lo")))

        val streaming = vm.await { (it.items.lastOrNull() as? AssistantReply)?.text == "Hello" }
        assertTrue((streaming.items.last() as AssistantReply).live)
        assertEquals("live", streaming.items.last().key)
        release.countDown()

        val done = vm.await { it.live == null && it.items.any { i -> i.key == "r11" } }
        val last = done.items.last() as AssistantReply
        assertEquals("r11", last.key)
        assertFalse(last.live)
        assertTrue(done.items.none { it.key == "live" })
    }

    @Test
    fun sendClearsDraft() = runTest {
        addSession()
        val vm = vm()
        vm.onDraftChange("What is on my calendar?")
        vm.await { it.draft.isNotEmpty() }
        vm.onSendClick()
        vm.await { it.draft.isEmpty() }
        val sent = vm.await { s -> s.items.any { it is UserMessage } }
        assertEquals("What is on my calendar?", (sent.items.single() as UserMessage).text)
    }

    @Test
    fun steerClearsDraftAndKeepsRunning() = runTest {
        addSession()
        routes["/v1/runs/$RUN/steer"] = json(fixture("steer_response.json"))
        val vm = vm()
        val release = latch()
        heldRun(release)
        vm.await { it.live != null }
        vm.onDraftChange("Also check tomorrow")
        vm.await { it.composer == ComposerMode.STEER }
        vm.onSendClick()
        vm.await { it.draft.isEmpty() }

        val steered = vm.await { s -> s.items.any { it is UserMessage && it.steered && it.state == SendState.SENT && it.text == "Also check tomorrow" } }
        assertNotNull(steered.live)
        assertEquals(ComposerMode.STOP, steered.composer)
        assertTrue(requests.any { it.target == "/v1/runs/$RUN/steer" })
        release.countDown()
    }

    @Test
    fun statusLineFollowsErrors() = runTest {
        addSession(onServer = true, stale = true)
        routes[MESSAGES] = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        val vm = vm()
        assertEquals(StatusLine.UNREACHABLE, vm.await { it.status != StatusLine.IDLE }.status)
        online.value = false
        assertEquals(StatusLine.OFFLINE, vm.await { it.status == StatusLine.OFFLINE }.status)
        online.value = true

        routes[MESSAGES] = json("""{"error":{"code":"invalid_api_key"}}""", 401)
        val rejected = vm()
        assertEquals(StatusLine.KEY_REJECTED, rejected.await { it.status != StatusLine.IDLE }.status)

        routes[MESSAGES] = json(serverRows)
        val idle = vm()
        val synced = idle.await { it.items.isNotEmpty() }
        assertEquals(StatusLine.IDLE, synced.status)
        assertEquals(server.url("/").host, synced.host)
    }

    @Test
    fun statusShowsServerErrorsAndMissingChats() = runTest {
        addSession(onServer = true, stale = true)
        routes[MESSAGES] = json("{}", 500)
        assertEquals(StatusLine.SERVER_ERROR, vm().await { it.status != StatusLine.IDLE }.status)
        routes[MESSAGES] = json("""{"error":{"code":"session_not_found"}}""", 404)
        assertEquals(StatusLine.CHAT_GONE, vm().await { it.status != StatusLine.IDLE }.status)
    }

    @Test
    fun historyIsFetchedEvenWithAPendingBubble() = runTest {
        addSession(onServer = true) // not stale, nothing cached, but an unsent message is shown
        db.pendingSendDao().upsert(PendingSendEntity("q1", KEY, "later", "{}", System.currentTimeMillis(), "queued", null, null))
        routes[MESSAGES] = json(serverRows)
        val vm = vm()
        assertEquals(listOf("u10", "r11", "pq1"), vm.await { it.items.size == 3 }.items.map { it.key })
    }

    private val control = """{"kind":"approval","prompt_id":"ctl_1","execution_generation":2,"command":"rm x","question":null}"""
    private val approvalEvent = "id: 0\ndata: {\"event\":\"approval.request\",\"seq\":0}\n\n"

    @Test
    fun approvalPassesDisplayedRequest() = runTest {
        addSession()
        val release = latch()
        val approvals = CopyOnWriteArrayList<String>()
        routes["/v1/runs/$RUN/approval"] = {
            approvals += it.body!!.utf8()
            routes[STATUS] = status("running")
            json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_1"}""")(it)
        }
        val vm = vm()
        heldRun(release, first = listOf(approvalEvent), runStatus = status("waiting_for_approval", control))
        vm.await { it.live?.pending?.requestId == "ctl_1" }

        vm.onApproval("ctl_1", 2, ApprovalChoice.ONCE)
        vm.await { !it.answering && approvals.isNotEmpty() }
        assertTrue(approvals.single().contains("\"request_id\":\"ctl_1\""))
        assertTrue(vm.state.value.notes.isEmpty())
    }

    @Test
    fun approvalAnswersOnlyTheRequestItWasGiven() = runTest {
        addSession()
        val replacement = control.replace("ctl_1", "ctl_2").replace("rm x", "rm -rf y")
        routes["/v1/runs/$RUN/approval"] = json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_2"}""")
        val vm = vm()
        heldRun(latch(), first = listOf(approvalEvent), runStatus = status("waiting_for_approval", replacement))
        vm.await { it.live?.pending?.requestId == "ctl_2" } // the screen has moved on; the tap was for ctl_1

        vm.onApproval("ctl_1", 2, ApprovalChoice.ONCE)
        val after = vm.await { !it.answering && it.notes.isNotEmpty() }
        assertTrue(requests.none { it.target.endsWith("/approval") })
        assertEquals(mapOf(RequestRef("ctl_1", 2) to AnswerNote(AnswerResult.CHANGED, RequestRef("ctl_2", 2))), after.notes) // shown with the block now waiting

        routes[STATUS] = status("running")
        vm.onApproval("ctl_2", 2, ApprovalChoice.DENY) // settled meanwhile elsewhere
        val gone = vm.await { it.notes[RequestRef("ctl_2", 2)]?.result == AnswerResult.GONE }
        assertTrue(requests.none { it.target.endsWith("/approval") })
        assertEquals(AnswerNote(AnswerResult.GONE), gone.notes[RequestRef("ctl_2", 2)])
    }

    @Test
    fun failedSendGivesTheTextBackWithoutLosingTheNewDraft() = runTest {
        // No session row: send refuses before storing anything.
        val vm = vm()
        val held = CompletableDeferred<Unit>()
        val locked = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); held.await() } }
        locked.await()
        vm.onDraftChange("first")
        vm.await { it.draft == "first" }
        vm.onSendClick()
        vm.await { it.draft.isEmpty() && it.sending }
        vm.onDraftChange("second")
        vm.await { it.draft == "second" }
        held.complete(Unit)
        lock.join()

        val back = vm.await { it.unsent != null && !it.sending }
        assertEquals("second", back.draft) // the text field is never written by a failure
        assertEquals("first", back.unsent?.text)
        assertEquals(ComposeError.NOT_STORED, back.unsent?.error)
        assertEquals(null, back.composerNote)
        assertTrue(db.pendingSendDao().rows(KEY).isEmpty())

        vm.onPutBack()
        val merged = vm.await { it.unsent == null }
        assertEquals("first\nsecond", merged.draft)
    }

    @Test
    fun discardAsksOnceAndRemovesOnlyTheTextShown() = runTest {
        connected.composer.edit(KEY, "first")
        connected.composer.send(KEY) { throw IllegalStateException("not stored") }!!.join()
        val vm = vm()
        assertEquals("first", vm.state.value.unsent?.text) // the first frame already shows it
        vm.onDiscardUnsent()
        assertEquals("first", vm.await { it.confirmDiscard != null }.confirmDiscard?.text)
        vm.onDismissDiscard()
        assertEquals("first", vm.await { it.confirmDiscard == null }.unsent?.text)

        vm.onDiscardUnsent()
        vm.onConfirmDiscard(vm.await { it.confirmDiscard != null }.confirmDiscard!!.id)
        val done = vm.await { it.unsent == null }
        assertEquals(null, done.confirmDiscard)
        assertEquals("", done.draft)
    }

    @Test
    fun aStaleDiscardConfirmationNeverOpensForANewEntryWithTheSameText() = runTest {
        connected.composer.edit(KEY, "same")
        connected.composer.send(KEY) { throw IllegalStateException("not stored") }!!.join()
        val vm = vm()
        vm.onDiscardUnsent()
        vm.await { it.confirmDiscard != null }
        vm.onPutBack() // the entry goes another way while the dialog was open
        assertEquals(null, vm.await { it.unsent == null }.confirmDiscard)

        vm.onSendClick() // "same" fails again: a new entry with identical text
        val again = vm.await { it.unsent != null && !it.sending }
        assertEquals("same", again.unsent?.text)
        assertEquals(null, again.confirmDiscard)
    }

    @Test
    fun unsentNoticeAndCancelWarningAreSeparate() = runTest {
        addSession()
        db.pendingSendDao().upsert(PendingSendEntity("q1", KEY, "later", "{}", System.currentTimeMillis(), "queued", null, null, attempted = true))
        connected.composer.edit(KEY, "first")
        connected.composer.send(KEY) { throw IllegalStateException("not stored") }!!.join()
        val vm = vm()
        vm.await { it.items.isNotEmpty() }
        vm.onCancel("q1")
        val asking = vm.await { it.confirmCancel != null }
        assertEquals("q1", asking.confirmCancel)
        assertTrue((asking.items.single() as UserMessage).attempted) // the dialog shows its stronger warning
        assertEquals("first", asking.unsent?.text)

        vm.onConfirmCancel("q1", mayHaveSentShown = true)
        val done = vm.await { it.composerNote != null }
        assertEquals(ComposerNote.REMOVED_MAY_HAVE_SENT, done.composerNote)
        assertEquals("first", done.unsent?.text)
    }

    @Test
    fun retryNeedingConfirmationAsksFirst() = runTest {
        addSession()
        db.pendingSendDao().upsert(PendingSendEntity("f1", KEY, "hello", "{}", System.currentTimeMillis(), "failed", null, "unconfirmed", attempted = true))
        val vm = vm()
        vm.await { it.items.isNotEmpty() }
        vm.onRetry("f1")
        assertEquals("f1", vm.await { it.confirmRetry != null }.confirmRetry!!.idempotencyKey)
        vm.onDismissRetry("f1")
        vm.await { it.confirmRetry == null }
        assertEquals(RetryResult.NEEDS_CONFIRMATION, connected.repo.retrySend("f1")) // nothing changed
    }

    @Test
    fun retryConfirmationActsOnlyOnItsOwnMessage() = runTest {
        addSession()
        val now = System.currentTimeMillis()
        db.pendingSendDao().upsert(PendingSendEntity("f1", KEY, "first", "{}", now, "failed", null, "unconfirmed", attempted = true))
        db.pendingSendDao().upsert(PendingSendEntity("f2", KEY, "second", "{}", now + 1, "failed", null, "unconfirmed", attempted = true))
        val vm = vm()
        vm.await { it.items.size == 2 }
        vm.onRetry("f1")
        vm.await { it.confirmRetry?.idempotencyKey == "f1" }
        vm.onRetry("f2") // a second dialog replaces the first, visibly: it names its own message
        assertEquals("second", vm.await { it.confirmRetry?.idempotencyKey == "f2" }.confirmRetry!!.text)

        vm.onConfirmRetry("f1") // a confirmation still showing "first" acts on "first" only
        awaitTrue { runBlocking { db.pendingSendDao().get("f1") } == null }
        assertEquals("failed", db.pendingSendDao().get("f2")!!.state)
        assertEquals("f2", vm.state.value.confirmRetry?.idempotencyKey)
    }

    @Test
    fun cancelSaysWhenTheMessageMayAlreadyHaveBeenSent() = runTest {
        addSession()
        db.pendingSendDao().upsert(PendingSendEntity("q1", KEY, "later", "{}", System.currentTimeMillis(), "queued", null, null, attempted = true))
        val vm = vm()
        vm.await { it.items.isNotEmpty() }
        vm.onCancel("q1")
        assertEquals("q1", vm.await { it.confirmCancel != null }.confirmCancel)
        vm.onConfirmCancel("q1", mayHaveSentShown = true)
        assertEquals(ComposerNote.REMOVED_MAY_HAVE_SENT, vm.await { it.composerNote != null }.composerNote)
        assertTrue(vm.await { it.items.isEmpty() }.items.isEmpty())
    }

    @Test
    fun sendSurvivesLeavingTheChat() = runTest {
        addSession()
        val vm = vm()
        val held = CompletableDeferred<Unit>()
        val locked = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); held.await() } }
        locked.await()
        vm.onDraftChange("Remember the milk")
        vm.onSendClick()
        vm.await { it.sending }
        store.clear() // the user switched chats before the message was stored
        held.complete(Unit)
        lock.join()

        awaitTrue { runBlocking { db.pendingSendDao().rows(KEY).isNotEmpty() } }
        assertEquals(listOf("Remember the milk"), db.pendingSendDao().rows(KEY).map { it.text })
        assertEquals("", connected.composer.current(KEY).draft)
    }

    @Test
    fun failedSendAfterLeavingGoesBackToTheDraft() = runTest {
        // No session row: send refuses before storing anything.
        val vm = vm()
        val held = CompletableDeferred<Unit>().also { storeGate = it } // the send waits before storing anything
        vm.onDraftChange("first")
        vm.onSendClick()
        vm.await { it.sending }
        store.clear()
        held.complete(Unit)

        awaitTrue { connected.composer.current(KEY).unsent != null }
        assertEquals("first", connected.composer.current(KEY).unsent?.text)
        assertEquals("first", vm().state.value.unsent?.text) // there when the chat is opened again
    }

    @Test
    fun sendFailingAfterTheChatReopenedReachesTheVisibleComposer() = runTest {
        // No session row: send refuses before storing anything.
        val vm = vm()
        val held = CompletableDeferred<Unit>().also { storeGate = it } // the send waits before storing anything
        vm.onDraftChange("first")
        vm.onSendClick()
        vm.await { it.sending }
        store.clear() // left while the send waits
        val again = vm() // and opened again before it failed
        again.onDraftChange("second")
        held.complete(Unit)

        val failed = again.await { !it.sending && it.unsent != null }
        assertEquals("second", failed.draft)
        again.onDraftChange("second!") // a keystroke from the frame drawn before the failure arrived
        assertEquals("first", connected.composer.current(KEY).unsent?.text)
        val shown = again.state.value.draftRevision
        again.onPutBack()
        assertEquals("first\nsecond!", again.await { it.unsent == null }.draft)
        again.onDraftChange("second!?", shown) // a keystroke from the frame before Put back is dropped
        assertEquals("first\nsecond!", connected.composer.current(KEY).draft)
    }

    @Test
    fun reopenedChatKeepsTheSendInFlight() = runTest {
        addSession()
        val vm = vm()
        val held = CompletableDeferred<Unit>()
        val locked = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); held.await() } }
        locked.await()
        vm.onDraftChange("first")
        vm.onSendClick()
        vm.await { it.sending }
        store.clear()
        val again = vm()
        assertTrue(again.state.value.sending) // the first frame already shows it
        again.onDraftChange("second")
        again.onSendClick() // still in flight: ignored
        assertTrue(again.await { it.draft == "second" }.sending)
        held.complete(Unit)
        lock.join()

        val done = again.await { !it.sending && it.items.isNotEmpty() }
        assertEquals("second", done.draft)
        assertEquals(listOf("first"), db.pendingSendDao().rows(KEY).map { it.text })
    }

    @Test
    fun draftIsReadOnceAndWrittenOnlyByEdits() = runTest {
        addSession()
        connected.composer.edit(KEY, "half a thought")
        val vm = vm()
        assertEquals("half a thought", vm.await { it.title == "Plans" }.draft)
        store.clear() // opened and left at once: the saved draft stays
        assertEquals("half a thought", connected.composer.current(KEY).draft)

        val again = vm()
        again.onDraftChange("half a thought, finished")
        assertEquals("half a thought, finished", connected.composer.current(KEY).draft)
    }

    @Test
    fun openingAgainFetchesAStaleChatAndReportsFailure() = runTest {
        addSession(onServer = true)
        routes[MESSAGES] = json(serverRows)
        val vm = vm()
        vm.await { it.items.isNotEmpty() } // the first transcript is committed (rows and stale = false in one transaction)
        assertEquals(1, requests.count { it.target.startsWith(MESSAGES) })
        routes[MESSAGES] = json("""{"error":{"code":"boom"}}""", 500)
        db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(stale = true)) // a refresh saw it change: fetched at once
        assertEquals(StatusLine.SERVER_ERROR, vm.await { it.status == StatusLine.SERVER_ERROR }.status)
        assertEquals(2, requests.count { it.target.startsWith(MESSAGES) })
        vm.onOpened() // still stale after the failure: opening it again fetches again
        awaitTrue { requests.count { it.target.startsWith(MESSAGES) } == 3 }
        assertEquals(StatusLine.SERVER_ERROR, vm.state.value.status)
    }

    @Test
    fun shownChatMarkedStaleIsFetchedWithoutReopening() = runTest {
        addSession(onServer = true)
        routes[MESSAGES] = json("""{"session_id":"$KEY","data":[{"id":10,"role":"user","content":"hello","timestamp":$now}]}""")
        val vm = vm()
        vm.await { it.items.size == 1 } // the first transcript is committed
        routes[MESSAGES] = json(serverRows) // another client added a turn

        db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(stale = true)) // as a session refresh marks it

        vm.await { it.items.any { i -> i.key == "r11" } }
        assertEquals(2, requests.count { it.target.startsWith(MESSAGES) })
    }

    @Test
    fun sendIsSingleFlightAndTakesTheCurrentDraft() = runTest {
        addSession()
        val vm = vm()
        val held = CompletableDeferred<Unit>()
        val locked = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); held.await() } }
        locked.await()
        vm.onDraftChange("fir")
        vm.onDraftChange("first") // edited after the button was drawn: the send takes what is typed now
        vm.onSendClick()
        vm.onSendClick() // a second tap before the screen redraws
        vm.await { it.sending && it.draft.isEmpty() }
        vm.onDraftChange("second")
        vm.onSendClick() // still in flight: ignored, the new draft stays
        held.complete(Unit)
        lock.join()

        val done = vm.await { !it.sending && it.items.isNotEmpty() }
        assertEquals("second", done.draft)
        assertEquals(listOf("first"), db.pendingSendDao().rows(KEY).map { it.text })
    }

    @Test
    fun goneNoteOutlivesTheRunUntilTheNextSend() = runTest {
        addSession()
        val release = latch()
        routes["/v1/runs/$RUN/approval"] = json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_1"}""")
        val vm = vm()
        heldRun(release, first = listOf(approvalEvent), runStatus = status("waiting_for_approval", control))
        vm.await { it.live?.pending?.requestId == "ctl_1" }
        routes[STATUS] = status("running") // settled elsewhere before the answer
        vm.onApproval("ctl_1", 2, ApprovalChoice.DENY)
        vm.await { it.notes[RequestRef("ctl_1", 2)]?.result == AnswerResult.GONE }
        release.countDown()

        val ended = vm.await { it.live == null }
        assertEquals(AnswerNote(AnswerResult.GONE), ended.notes[RequestRef("ctl_1", 2)])
        vm.onDraftChange("next")
        vm.onSendClick()
        vm.await { it.notes.isEmpty() && !it.sending }
    }

    @Test
    fun cancelAsksAgainWhenTheMessageWasTriedSinceTheDialogOpened() = runTest {
        addSession()
        db.pendingSendDao().upsert(PendingSendEntity("q1", KEY, "later", "{}", System.currentTimeMillis(), "queued", null, null))
        val vm = vm()
        vm.await { it.items.isNotEmpty() }
        vm.onCancel("q1")
        vm.await { it.confirmCancel == "q1" }
        db.pendingSendDao().update(db.pendingSendDao().get("q1")!!.copy(attempted = true)) // a drain tried it meanwhile

        vm.onConfirmCancel("q1", mayHaveSentShown = false) // the dialog still showed the weaker wording
        val asked = vm.await { s -> (s.items.single() as UserMessage).attempted }
        assertEquals("q1", asked.confirmCancel) // still open, now with the stronger wording
        assertNotNull(db.pendingSendDao().get("q1"))

        vm.onConfirmCancel("q1", mayHaveSentShown = true)
        val done = vm.await { it.items.isEmpty() && it.confirmCancel == null }
        assertEquals(ComposerNote.REMOVED_MAY_HAVE_SENT, done.composerNote)
    }

    private fun tool(callId: String?, name: String, result: String) = ToolStep(callId ?: "", name, "", result, false, error = false, running = false)

    @Test
    fun toolSheetShowsItsStepAsItCompletes() {
        val preview = ToolStep("c1", "terminal", "command=ls", null, false, error = false, running = true)
        val full = preview.copy(args = "{\n    \"command\": \"ls\"\n}", result = "a", running = false)
        val subject = StepSubject("r2", 0, "c1", "terminal")
        val index = StepIndex()
        assertEquals(StepIndex.Found(preview, true), index.find(listOf(user("u1"), AssistantReply("r2", listOf(preview), "", 1.0, live = true)), subject))
        assertEquals(StepIndex.Found(full, false), index.find(listOf(user("u1"), AssistantReply("r2", listOf(full), "", 1.0)), subject))
        // The live key was a placeholder with no stored key to map to: the sheet closes at run end.
        assertEquals(null, index.find(listOf(user("u1"), AssistantReply("r2", listOf(full), "", 1.0)), subject.copy(replyKey = "live")))
    }

    @Test
    fun removingTheStepClosesTheSheetEvenIfAnotherReplyReusesItsCallId() {
        val subject = StepSubject("r2", 0, "c1", "terminal")
        val items = listOf(AssistantReply("r2", emptyList(), "", 1.0), AssistantReply("r5", listOf(tool("c1", "terminal", "other")), "", 1.0))
        assertEquals(null, StepIndex().find(items, subject))
    }

    @Test
    fun aStepWithoutCallIdIsOnlyItsPosition() {
        val subject = StepSubject("r2", 0, null, "terminal")
        val index = StepIndex()
        assertEquals("mine", index.find(listOf(AssistantReply("r2", listOf(tool(null, "terminal", "mine")), "", 1.0)), subject)!!.step.result)
        assertEquals(null, index.find(listOf(AssistantReply("r2", listOf(tool("c9", "terminal", "later")), "", 1.0)), subject)) // took an id
        assertEquals(null, index.find(listOf(AssistantReply("r2", listOf(tool(null, "browser", "later")), "", 1.0)), subject)) // another tool
    }

    @Test
    fun toolSheetStaysOnItsStepWhenAStepIsInsertedBeforeIt() {
        val a = tool("c1", "terminal", "A")
        val b = tool("c2", "terminal", "B")
        val subject = StepSubject("r2", 1, "c2", "terminal")
        val index = StepIndex()
        assertEquals("B", index.find(listOf(AssistantReply("r2", listOf(a, b), "", 1.0)), subject)!!.step.result)
        assertEquals("B", index.find(listOf(AssistantReply("r2", listOf(a, ReasoningStep("thinking"), b), "", 1.0)), subject)!!.step.result)
        assertEquals(null, index.find(listOf(AssistantReply("r2", listOf(a), "", 1.0)), subject)) // gone: the sheet closes
    }

    @Test
    fun toolStepsWithoutCallIdsOpenTheirOwnResults() {
        val items = listOf(user("u1"), AssistantReply("r2", listOf(tool(null, "terminal", "first"), tool(null, "terminal", "second")), "", 1.0))
        assertEquals("second", StepIndex().find(items, StepSubject("r2", 1, null, "terminal"))!!.step.result)
        assertEquals("first", StepIndex().find(items, StepSubject("r2", 0, null, "terminal"))!!.step.result)
    }

    /** A list that counts reads of its items. */
    private class Counting(val items: List<ChatItem>) : AbstractList<ChatItem>() {
        var reads = 0
        override val size get() = items.size
        override fun get(index: Int): ChatItem = items[index].also { reads++ }
    }

    @Test
    fun liveUpdatesDoNotReadStoredItems() {
        val stored = Counting(listOf(turn("u0")) + (1..1000).map { user("s$it").copy(steered = true) } +
            AssistantReply("r9", listOf(ToolStep("c1", "terminal", "", "old", false, false, false)), "", 1.0))
        val merger = LiveMerger()
        val rows = ChatRows(java.time.ZoneId.of("UTC"))
        val index = StepIndex()
        val ref = StepSubject("r9", 0, "c2", "terminal") // a call that is not there: a full miss
        rows.rows(merger.merge(stored, liveReply("a")))
        index.find(merger.merge(stored, liveReply("a")), ref)
        stored.reads = 0
        repeat(10) {
            val items = merger.merge(stored, liveReply("a" + "b".repeat(it + 1)))
            rows.rows(items)
            index.find(items, ref)
        }
        assertEquals(0, stored.reads)
    }

    @Test
    fun changedShowsOnlyWithTheRequestThatReplacedTheAnsweredOne() {
        val notes = mapOf(RequestRef("ctl_1", 2) to AnswerNote(AnswerResult.CHANGED, RequestRef("ctl_1", 3)))
        fun control(id: String, generation: Long) = xyz.rycerz.apofis.api.PendingControl("approval", id, generation, "rm x", null)
        assertTrue(showsChanged(notes, control("ctl_1", 3))) // only the generation changed: still another request
        assertFalse(showsChanged(notes, control("ctl_1", 2)))
        assertFalse(showsChanged(notes, control("ctl_9", 3))) // a later request is not labelled
    }

    @Test
    fun stopStaysAvailableWhileASendIsInFlight() {
        assertTrue(primaryEnabled(ComposerMode.STOP, busy = true))
        assertFalse(primaryEnabled(ComposerMode.STEER, busy = true))
        assertFalse(primaryEnabled(ComposerMode.SEND, busy = true))
        assertTrue(primaryEnabled(ComposerMode.SEND, busy = false))
        assertFalse(primaryEnabled(ComposerMode.SEND_DISABLED, busy = false))
    }

    private fun user(key: String, text: String = key) = UserMessage(key, text, false, 1.0)
    private fun reply(key: String, text: String = key) = AssistantReply(key, emptyList(), text, 1.0)
    private fun liveReply(text: String, connection: Connection = Connection.LIVE) = LiveReply("run", "running", text, emptyList(), null, connection)

    private fun turn(key: String, run: String = "run") = UserMessage(key, key, false, 1.0, runId = run)
    private fun notice(key: String) = Notice(key, "info", key)

    @Test
    fun liveReplyStartsAtItsOwnTurn() {
        val transcript = listOf(user("u1"), reply("r2", "old answer"), turn("u3"), reply("r4", "stored part"), notice("n5"),
            user("s6").copy(steered = true), reply("r7", "more"))
        val items = LiveMerger().merge(transcript, liveReply("streamed"))
        assertEquals(listOf("u1", "r2", "u3", "s6", "r4"), items.map { it.key }) // the live item takes the first hidden key
        assertEquals("old answer", (items[1] as AssistantReply).text)
        assertFalse((items[1] as AssistantReply).live)
        assertEquals("streamed", (items.last() as AssistantReply).text)
        assertTrue((items.last() as AssistantReply).live)
    }

    @Test
    fun liveReplyNeverTakesOverAnotherTurn() {
        // The previous question and answer, and a queued message: none of them started this run.
        val transcript = listOf(user("u1"), reply("r2", "old answer"), user("pq", "later"))
        val items = LiveMerger().merge(transcript, liveReply("new"))
        assertEquals(listOf("u1", "r2", "pq", "live"), items.map { it.key })
        assertEquals("old answer", (items[1] as AssistantReply).text)
        assertEquals(listOf("u1", "r2", "live"), LiveMerger().merge(transcript.take(2), liveReply("new")).map { it.key })
        // A turn of another run is not this run's turn either.
        val other = listOf(turn("u1", run = "older"), reply("r2", "old answer"))
        assertEquals(listOf("u1", "r2", "live"), LiveMerger().merge(other, liveReply("new")).map { it.key })
    }

    @Test
    fun pollingShowsStoredItemsAsTheyAre() {
        val transcript = listOf(user("u1"), turn("u3"), reply("r2", "one"), notice("n3"), reply("r4", "two"))
        val items = LiveMerger().merge(transcript, liveReply("streamed", Connection.POLLING))
        assertEquals(transcript, items.dropLast(1))
        val indicator = items.last() as AssistantReply
        assertTrue(indicator.live)
        assertEquals("", indicator.text)
        assertTrue(indicator.steps.isEmpty())
    }
}
