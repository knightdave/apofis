package xyz.rycerz.apofis.sync

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.db.choice
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.ui.chat.ChatViewModel

private const val KEY = "apofis-a"
private const val RUN = "run_ef57074dc1694db39e37045230b1425c" // run_start.json
private const val RUNS = "/v1/runs"
private const val STATUS = "/v1/runs/$RUN"
private const val EVENTS = "/v1/runs/$RUN/events"
private const val MESSAGES = "/api/sessions/$KEY/messages"
private const val SESSIONS = "/api/sessions"
private val CHOICE = ChatModel("alpha-large", "alpha", "low", true)
private val MODEL_KEYS = setOf("model", "provider", "model_options")

/**
 * The chat's model choice in the run bodies it sends: stored on this phone, read when a send is stored, kept by a
 * refresh, a key change, a steer that falls back to a run and a server that serves another model; a refused or failed
 * send keeps its exact body for Retry (decision 17).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChoiceBodyTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private lateinit var follower: RunFollower
    private lateinit var connected: Connected
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val vms = mutableListOf<ChatViewModel>()

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
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis)
        repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
        // A started run completes at once and the transcript holds nothing, unless a test says otherwise.
        routes[RUNS] = json(fixture("run_start.json"), 202)
        routes[STATUS] = status("completed")
        routes[EVENTS] = { sse(terminal(9, "completed")) }
        routes[MESSAGES] = json(rows())
        runBlocking { follower.resumeAll() }
    }

    @After
    fun tearDown() {
        val jobs = vms.map { it.viewModelScope.coroutineContext.job }
        store.clear()
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun json(body: String, code: Int = 200) =
        { _: RecordedRequest -> MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build() }

    private fun sse(vararg frames: String) =
        MockResponse.Builder().body(": open\n\n" + frames.joinToString("")).addHeader("Content-Type", "text/event-stream").build()

    private fun terminal(seq: Long, status: String) = "id: $seq\ndata: {\"event\":\"run.$status\",\"seq\":$seq}\n\n"

    private fun status(status: String, extra: String = "") = json(
        """{"object":"hermes.run","run_id":"$RUN","status":"$status","session_id":"$KEY","admission_id":"adm_1",""" +
            """"execution_generation":3,"output":"","pending_controls":[]$extra}""",
    )

    private val now get() = System.currentTimeMillis() / 1000.0 // the follower confirms a send only by a row from the last minute

    private fun rows(vararg texts: String) = """{"session_id":"$KEY","data":[""" +
        texts.mapIndexed { i, t -> """{"id":${10 + i},"role":"user","content":"$t","timestamp":$now}""" }.joinToString(",") + "]}"

    // The recorded list row as another client's chat of this app would show it (the probe prefix is reserved).
    private val listed = fixture("sessions_list.json").replace("apofis-probe-", "apofis-other-")

    private suspend fun addSession(choice: ChatModel = ChatModel(), onServer: Boolean = true) = db.sessionDao().upsert(
        SessionEntity(
            key = KEY, currentId = KEY, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
            lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = onServer,
            model = choice.model, provider = choice.provider, reasoning = choice.reasoning, fast = choice.fast,
        ),
    )

    private suspend fun awaitTrue(condition: suspend () -> Boolean) = withContext(Dispatchers.IO) {
        repeat(2000) {
            if (condition()) return@withContext
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within 10 s")
    }

    private fun runBodies() = requests.filter { it.target == RUNS }.map { it.body!!.utf8() }

    private fun keysOf(body: String) = Json.parseToJsonElement(body).jsonObject.keys

    private suspend fun choice(key: String = KEY) = db.sessionDao().get(key)!!.choice()

    private suspend fun settled() = awaitTrue { db.activeRunDao().all().isEmpty() }

    @Test
    fun pickedChoiceInNextBody() = runTest {
        addSession()
        val vm = ViewModelProvider.create(store, viewModelFactory { initializer { ChatViewModel(KEY, connected) } })[ChatViewModel::class].also { vms += it }
        vm.pickModel("alpha", "alpha-large")
        vm.pickReasoning("low")
        assertTrue(vm.toggleFast())
        awaitTrue { choice() == CHOICE }
        assertTrue("a pick makes no request", requests.none { !it.target.startsWith(MESSAGES) }) // the chat's own history load only
        repo.send(KEY, "hi")
        assertEquals(listOf(buildRunBody(KEY, "hi", choice = CHOICE)), runBodies())
        assertTrue(keysOf(runBodies().single()).containsAll(MODEL_KEYS))
        settled()
    }

    @Test
    fun importedChatSendsNoModelKeys() = runTest {
        routes[SESSIONS] = json(listed)
        repo.refreshSessions()
        val key = db.sessionDao().all().single().key
        assertEquals(ChatModel(), choice(key))
        routes[RUNS] = json(fixture("run_start.json"), 202)
        repo.send(key, "hi")
        val body = runBodies().single()
        assertEquals(buildRunBody(key, "hi"), body)
        assertTrue(keysOf(body).intersect(MODEL_KEYS).isEmpty())
    }

    @Test
    fun refreshKeepsLocalChoice() = runTest {
        routes[SESSIONS] = json(listed)
        repo.refreshSessions()
        val key = db.sessionDao().all().single().key
        repo.setChoice(key, CHOICE)
        // The server's row changed meanwhile (a new title, a message count): the refresh rewrites the row, not the choice.
        routes[SESSIONS] = json(listed.replace("\"message_count\": 0", "\"message_count\": 4").replace("renamed", "again"))
        repo.refreshSessions()
        assertEquals(4, db.sessionDao().get(key)!!.messageCount)
        assertEquals(CHOICE, choice(key))
    }

    @Test
    fun newChatStartsWithDefault() = runTest {
        val key = repo.createLocalSession()
        assertEquals(ChatModel(), choice(key))
        connected.sendQueue.enqueue(key, "hi") // stored, not sent: the body as it will go out
        val body = db.pendingSendDao().rows(key).single().requestBody
        assertEquals(buildRunBody(key, "hi"), body)
        assertTrue(keysOf(body).intersect(MODEL_KEYS).isEmpty())
    }

    @Test
    fun modelFailingAfterAcceptShowsFailedRun() = runTest {
        addSession(CHOICE)
        routes[EVENTS] = { sse(terminal(9, "failed")) }
        routes[STATUS] = status("failed")
        routes[MESSAGES] = json(rows("hi")) // the server stored the user turn: the send is delivered
        repo.send(KEY, "hi")
        settled()
        awaitTrue { db.replyOutcomeDao().rows(KEY).isNotEmpty() }
        assertEquals(listOf("FAILED"), db.replyOutcomeDao().rows(KEY).map { it.outcome })
        assertTrue("the pending row is gone, so no Retry", db.pendingSendDao().rows(KEY).isEmpty())
        assertEquals(listOf(buildRunBody(KEY, "hi", choice = CHOICE)), runBodies())
    }

    @Test
    fun unmatchedFailedSendRetriesByteEqual() = runTest {
        addSession(CHOICE)
        routes[EVENTS] = { sse(terminal(9, "failed")) }
        routes[STATUS] = status("failed")
        repo.send(KEY, "hi") // the transcript never shows it
        settled()
        val row = db.pendingSendDao().rows(KEY).single()
        assertEquals("failed", row.state)
        assertEquals(SendError.UNCONFIRMED, row.error)
        repo.setChoice(KEY, ChatModel(model = "other")) // a later pick changes nothing already stored
        assertEquals(RetryResult.NEEDS_CONFIRMATION, repo.retrySend(row.idempotencyKey))
        assertEquals(RetryResult.STARTED, repo.retrySend(row.idempotencyKey, confirmedDuplicateRisk = true))
        awaitTrue { runBodies().size == 2 }
        val (first, second) = runBodies()
        assertEquals(buildRunBody(KEY, "hi", choice = CHOICE), first)
        assertEquals(first, second)
        val keys = requests.filter { it.target == RUNS }.map { it.headers["Idempotency-Key"] }
        assertNotEquals("a send that may be on the server goes again under a new key", keys[0], keys[1])
        settled()
    }

    @Test
    fun routeConflict400FailsNotAttempted() = runTest {
        addSession(ChatModel("alpha-large", "beta"))
        routes[RUNS] = json("""{"error":{"message":"model not available for provider"}}""", 400) // H7: no distinct code
        repo.send(KEY, "hi")
        val row = db.pendingSendDao().rows(KEY).single()
        assertEquals("failed", row.state)
        assertEquals(SendError.HTTP_ERROR, row.error)
        assertFalse(row.attempted)
        val shown = repo.transcript(KEY).first().filterIsInstance<UserMessage>().single()
        assertEquals(SendState.FAILED, shown.state)
        routes[RUNS] = json(fixture("run_start.json"), 202)
        assertEquals(RetryResult.STARTED, repo.retrySend(row.idempotencyKey)) // no confirmation: it never reached a run
        awaitTrue { runBodies().size == 2 }
        val (first, second) = runBodies()
        assertEquals(buildRunBody(KEY, "hi", choice = ChatModel("alpha-large", "beta")), first)
        assertEquals(first, second)
        assertEquals(1, requests.filter { it.target == RUNS }.map { it.headers["Idempotency-Key"] }.distinct().size)
        settled()
    }

    @Test
    fun choiceKeptAcrossKeyChangeAtSameServer() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val own = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val container = AppContainer(app, CredentialStore(app) { aes }) { own }
        val a = Credentials("https://a.example/", "key-a")
        try {
            container.connect(a)
            val old = container.connected()!!
            own.sessionDao().upsert(SessionEntity(KEY, KEY, "", null, false, false, 1.0, null, 0, null, false, true))
            old.repo.setChoice(KEY, CHOICE)
            old.sendQueue.enqueue(KEY, "waiting")
            container.connect(a.copy(apiKey = "key-a2"))
            assertFalse(old.isOpen)
            assertEquals(CHOICE, own.sessionDao().get(KEY)!!.choice())
            assertEquals(buildRunBody(KEY, "waiting", choice = CHOICE), own.pendingSendDao().allQueued().single().requestBody)
        } finally {
            own.close()
        }
    }

    @Test
    fun steerFallbackRunKeepsFrozenChoice() = runTest {
        addSession(CHOICE)
        val running = "run_old"
        db.activeRunDao().upsert(ActiveRunEntity(running, KEY, null, "running", 1L))
        routes["/v1/runs/$running/steer"] = json("""{"error":{"code":"run_not_found"}}""", 404) // the run ended meanwhile
        repo.send(KEY, "late")
        val steer = requests.single { it.target == "/v1/runs/$running/steer" }
        assertEquals(setOf("text"), keysOf(steer.body!!.utf8())) // a steer carries no model fields
        val row = db.pendingSendDao().rows(KEY).single()
        assertEquals("queued", row.state)
        repo.setChoice(KEY, ChatModel(model = "other", fast = false))
        db.activeRunDao().delete(running)
        assertTrue(connected.sendQueue.drain())
        assertEquals(listOf(buildRunBody(KEY, "late", choice = CHOICE)), runBodies())
        settled()
    }

    @Test
    fun fallbackServedLeavesChoice() = runTest {
        addSession(CHOICE)
        // The server ran another model than the one asked for and says so in the run's runtime metadata (H7).
        routes[STATUS] = status("completed", extra = ""","runtime":{"model":"served-model","provider":"served"}""")
        routes[MESSAGES] = json(rows("hi"))
        repo.send(KEY, "hi")
        settled()
        assertTrue(db.pendingSendDao().rows(KEY).isEmpty())
        assertEquals(CHOICE, choice())
        routes[MESSAGES] = json(rows("hi", "again"))
        repo.send(KEY, "again")
        assertEquals(buildRunBody(KEY, "again", choice = CHOICE), runBodies().last())
        settled()
    }
}
