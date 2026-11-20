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
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertSame
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
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.RetryResult
import xyz.rycerz.apofis.sync.SendQueue

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

    /** A gate for a held stream; every gate is opened in tearDown, so a failed test never blocks the next. */
    private fun latch() = CountDownLatch(1).also { latches += it }

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
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, {})
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
    }

    @After
    fun tearDown() {
        latches.forEach { it.countDown() }
        // Every coroutine must end before the next test: the repository and send locks are process-wide, and a
        // view model coroutine left suspended on the reset main dispatcher would hold them for good.
        store.clear()
        follower.pauseAll()
        runBlocking { withTimeout(10_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private var created = 0

    private fun vm(): ChatViewModel = ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(KEY, connected, online) } })
        .get("vm${created++}", ChatViewModel::class)

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
                release.await(10, TimeUnit.SECONDS)
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
    fun hugeReplyStaysResponsive() = runTest {
        addSession(onServer = true)
        routes[MESSAGES] = json(serverRows)
        val vm = vm()
        val before = vm.await { it.items.size == 2 }
        val paragraph = "word ".repeat(19).trimEnd() + "!!!!\n\n" // 100 characters
        val huge = paragraph.repeat(1000)
        assertEquals(100_000, huge.length)
        val release = latch()
        heldRun(release, first = listOf(delta(0, huge.replace("\n", "\\n"))))

        withContext(Dispatchers.Default) { withTimeout(10_000) { follower.live(KEY).first { it?.text?.length == 100_000 } } }
        val elapsed = measureTime { vm.await { (it.items.last() as AssistantReply).text.length == 100_000 } }
        assertTrue("state took $elapsed", elapsed.inWholeMilliseconds < 200)
        val after = vm.state.value
        // The stored items are the same instances: nothing was mapped again for the streamed reply.
        assertSame(before.items[0], after.items[0])
        assertSame(before.items[1], after.items[1])

        // Splitting into lazy rows: once in full, then only the growing tail for each streamed token.
        val rows = LiveBlocks()
        val time = measureTime {
            var text = huge
            rows.update(text)
            repeat(1000) {
                text += "more "
                rows.update(text)
            }
        }
        assertTrue("blocks took $time", time.inWholeMilliseconds < 200)
        assertEquals(1001, rows.blocks.size) // the appended words start a new paragraph
        release.countDown()
    }

    @Test
    fun approvalPassesDisplayedRequest() = runTest {
        addSession()
        val release = latch()
        val control = """{"kind":"approval","prompt_id":"ctl_1","execution_generation":2,"command":"rm x","question":null}"""
        val approvals = CopyOnWriteArrayList<String>()
        routes["/v1/runs/$RUN/approval"] = {
            approvals += it.body!!.utf8()
            routes[STATUS] = status("running")
            json("""{"run_id":"$RUN","status":"resolved","prompt_id":"ctl_1"}""")(it)
        }
        val event = "id: 0\ndata: {\"event\":\"approval.request\",\"seq\":0}\n\n"
        val vm = vm()
        heldRun(release, first = listOf(event), runStatus = status("waiting_for_approval", control))
        vm.await { it.live?.pending?.requestId == "ctl_1" }

        vm.onApproval(ApprovalChoice.ONCE)
        vm.await { !it.answering && approvals.isNotEmpty() }
        assertTrue(approvals.single().contains("\"request_id\":\"ctl_1\""))
        assertEquals(null, vm.state.value.answerNote)
        release.countDown()
    }

    @Test
    fun retryNeedingConfirmationAsksFirst() = runTest {
        addSession()
        db.pendingSendDao().upsert(PendingSendEntity("f1", KEY, "hello", "{}", System.currentTimeMillis(), "failed", null, "unconfirmed", attempted = true))
        val vm = vm()
        vm.await { it.items.isNotEmpty() }
        vm.onRetry("f1")
        assertEquals("f1", vm.await { it.confirmRetry != null }.confirmRetry)
        vm.onDismissRetry()
        vm.await { it.confirmRetry == null }
        assertEquals(RetryResult.NEEDS_CONFIRMATION, connected.repo.retrySend("f1")) // nothing changed
    }
}
