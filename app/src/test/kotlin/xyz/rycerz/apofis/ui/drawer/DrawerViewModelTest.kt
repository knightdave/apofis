package xyz.rycerz.apofis.ui.drawer

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DrawerViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private lateinit var follower: RunFollower
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val clock = AtomicLong(1_800_000_000_000L)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes["${request.method} ${request.target.substringBefore('?')}"]?.invoke(request)
                    ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val api = HermesApi(server.url("/").toString(), "secret-key")
        lateinit var f: RunFollower
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, clock::get, {})
        val repo = ChatRepository(api, db, clock::get, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
    }

    @After
    fun tearDown() {
        store.clear()
        follower.pauseAll()
        runBlocking { withTimeout(10_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun vm(): DrawerViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { DrawerViewModel(connected, now = clock::get, zone = { ZoneOffset.UTC }) } },
    ).get(DrawerViewModel::class)

    private suspend fun <T> StateFlow<T>.await(condition: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(10_000) { first(condition) } }

    private suspend fun addSession(key: String, title: String, onServer: Boolean = true, active: Double = clock.get() / 1000.0) =
        db.sessionDao().upsert(
            SessionEntity(
                key = key, currentId = key, lineageIds = "", title = title, pinned = false, archived = false, startedAt = active,
                lastActiveAt = active, messageCount = 2, preview = null, stale = false, existsOnServer = onServer,
            ),
        )

    private fun List<DrawerRow>.chats() = filterIsInstance<DrawerRow.Chat>()

    @Test
    fun refreshFailureKeepsCache() = runTest {
        addSession("apofis-a", "Garden plans")
        val vm = vm()
        vm.rows.await { rows -> rows.chats().any { it.key == "apofis-a" } }

        vm.onOpen() // the list request answers 500
        vm.refreshFailed.await { it }
        assertEquals(listOf("Garden plans"), vm.rows.value.chats().map { it.title })
        val listed = requests.count { it.target.startsWith("/api/sessions?") }
        assertEquals(1, listed)

        clock.addAndGet(29_000)
        vm.onOpen() // within 30 s of the last attempt: no request
        clock.addAndGet(1_000)
        vm.onOpen()
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (requests.count { it.target.startsWith("/api/sessions?") } < 2) delay(5) }
        }
        assertEquals(2, requests.count { it.target.startsWith("/api/sessions?") })
    }

    @Test
    fun newChatReusesEmptyLocalChat() = runTest {
        val vm = vm()
        val first = vm.selected.await { it != null }!! // no chats: a new local one is opened
        assertEquals(first, vm.onNewChat())
        assertFalse(db.sessionDao().get(first)!!.existsOnServer)

        db.pendingSendDao().upsert(PendingSendEntity("p1", first, "hello", "{}", clock.get(), "queued", null, null))
        val second = vm.onNewChat()
        assertNotEquals(first, second)
        assertEquals(second, vm.selected.value)
        assertTrue(requests.isEmpty()) // a local chat makes no request
    }

    @Test
    fun deleteOpenChatSwitchesToNew() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        routes["DELETE /api/sessions/apofis-a"] = { MockResponse.Builder().code(200).body(fixture("session_delete.json")).build() }
        val vm = vm()
        vm.selected.await { it != null }
        vm.onSelect("apofis-a")
        vm.onDeleteRequest("apofis-a")
        assertEquals(PromptKind.DELETE, vm.prompt.value?.kind)

        vm.onDelete("apofis-a")
        val next = vm.selected.await { it != "apofis-a" }!!
        vm.prompt.await { it == null }
        assertNull(db.sessionDao().get("apofis-a"))
        assertFalse(db.sessionDao().get(next)!!.existsOnServer)
    }

    @Test
    fun failedActionShowsInlineError() = runTest {
        addSession("apofis-a", "Trip to Lisbon") // the delete request answers 500
        val vm = vm()
        vm.rows.await { it.chats().isNotEmpty() }
        vm.onDeleteRequest("apofis-a")
        vm.onDelete("apofis-a")
        assertEquals(ActionError.FAILED, vm.prompt.await { it?.error != null }!!.error)
        assertEquals(listOf("apofis-a"), vm.rows.value.chats().map { it.key })
    }

    @Test
    fun runningAndNeedsInputFlags() = runTest {
        addSession("apofis-a", "Running one")
        addSession("apofis-b", "Asking one")
        addSession("apofis-c", "Idle one")
        db.activeRunDao().upsert(ActiveRunEntity("run_a", "apofis-a", null, "running", 1L))
        db.activeRunDao().upsert(ActiveRunEntity("run_b", "apofis-b", null, "waiting_for_approval", 2L))
        val vm = vm()
        val chats = vm.rows.await { it.chats().size == 3 && it.chats().any { c -> c.needsInput } }.chats().associateBy { it.key }
        assertTrue(chats.getValue("apofis-a").running)
        assertFalse(chats.getValue("apofis-a").needsInput)
        assertTrue(chats.getValue("apofis-b").needsInput)
        assertFalse(chats.getValue("apofis-b").running)
        assertFalse(chats.getValue("apofis-c").running || chats.getValue("apofis-c").needsInput)
    }
}
