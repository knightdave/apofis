package xyz.rycerz.apofis.ui.drawer

import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.TITLE_MAX
import xyz.rycerz.apofis.sync.cleanTitle
import xyz.rycerz.apofis.sync.takeCodePoints

private const val KEY = "apofis-a"
private const val FACE = "😀" // one code point, two UTF-16 units

/** The drawer's rename: counted in code points, cleaned as the server cleans, and short enough for a create suffix. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RenameTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private lateinit var follower: RunFollower
    private lateinit var queue: SendQueue
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val created = CopyOnWriteArrayList<DrawerViewModel>()

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
        queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis)
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
        runBlocking {
            db.sessionDao().upsert(
                SessionEntity(
                    key = KEY, currentId = KEY, lineageIds = "", title = null, pinned = false, archived = false, startedAt = 1.0,
                    lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = false,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        val scopes = created.map { it.viewModelScope.coroutineContext.job }
        runBlocking { withTimeout(60_000) { created.forEach { it.refreshJob?.join() } } }
        store.clear()
        runBlocking { withTimeout(60_000) { scopes.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private fun vm(): DrawerViewModel = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { DrawerViewModel(connected, lastChat = KEY, zone = { ZoneOffset.UTC }) } },
    ).get(DrawerViewModel::class).also { created += it }

    private suspend fun <T> StateFlow<T>.await(condition: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(60_000) { first(condition) } }

    /** Renames the local chat through the drawer's dialog and waits until the prompt has closed (the rename ended). */
    private suspend fun DrawerViewModel.rename(title: String) {
        rows.await { rows -> rows.filterIsInstance<DrawerRow.Chat>().any { it.key == KEY } }
        onRenameRequest(KEY)
        confirmRename(prompt.value!!.token, title)
        prompt.await { it == null }
    }

    private suspend fun title() = db.sessionDao().get(KEY)!!.title

    @Test
    fun renameCountsCodePoints() = runBlocking<Unit> {
        val vm = vm()
        vm.rename(FACE.repeat(120))
        assertEquals("96 faces, not 48 (UTF-16 units) and no half pair", FACE.repeat(TITLE_MAX), title())

        vm.rename(" Plans\tfor​ the\u0007\nweek ")
        assertEquals("Plans for the week", title())

        vm.onRenameRequest(KEY)
        vm.confirmRename(vm.prompt.value!!.token, "​⁠ ­") // nothing visible: not renamed, the dialog stays
        assertEquals("Plans for the week", title())
        assertEquals(PromptKind.RENAME, vm.prompt.value!!.kind)
        assertEquals(0, requests.count { it.method == "PATCH" }) // a local-only chat makes no request

        // The dialog's field cap: code points, never half a pair.
        assertEquals("a" + FACE.repeat(95), ("a" + FACE.repeat(200)).takeCodePoints(TITLE_MAX))
        assertNull(cleanTitle("\uD83D")) // an unpaired surrogate alone is nothing
    }

    /** A local title promoted to the server's title at the first send: with " (9)", the last try, still within 100. */
    @Test
    fun promotedTitleLeavesSuffixRoom() = runBlocking<Unit> {
        val vm = vm()
        vm.rename("x".repeat(40) + FACE.repeat(80))
        val creates = CopyOnWriteArrayList<String>()
        val tries = AtomicInteger()
        routes["POST /api/sessions"] = {
            val title = JSONObject(it.body!!.utf8()).getString("title")
            creates += title
            if (tries.incrementAndGet() < 9) {
                json(400, """{"error":{"code":"invalid_title","message":"Title is already in use"}}""")
            } else {
                json(201, """{"object":"hermes.session","session":{"id":"$KEY","source":"api_server","title":${JSONObject.quote(title)},"pinned":false,"archived":false,"hidden":false,"started_at":1.0,"message_count":0}}""")
            }
        }
        routes["POST /v1/runs"] = { json(202, """{"run_id":"run_1","status":"started","replayed":false}""") }

        queue.enqueue(KEY, "hello")
        queue.drain()

        val base = "x".repeat(40) + FACE.repeat(TITLE_MAX - 40)
        assertEquals(9, creates.size)
        assertEquals(base, creates.first())
        assertEquals("$base (9)", creates.last())
        assertEquals(100, creates.last().codePointCount(0, creates.last().length)) // Hermes' limit, counted as Python's len
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
}
