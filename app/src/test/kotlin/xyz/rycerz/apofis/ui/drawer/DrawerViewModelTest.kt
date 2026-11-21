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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.chat.Composer
import xyz.rycerz.apofis.api.fixture
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
import kotlinx.coroutines.joinAll
import androidx.lifecycle.viewModelScope
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
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.isBeingDeleted
import xyz.rycerz.apofis.sync.withRepositoryLock

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
        assertFalse("a held response timed out", gaveUp.get())
        val scopes = created.map { it.viewModelScope.coroutineContext.job }
        store.clear()
        runBlocking { withTimeout(60_000) { scopes.joinAll() } } // the view models' work ends before Room closes
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
    }

    private val saved = CopyOnWriteArrayList<String>()

    private val gaveUp = AtomicBoolean(false)

    /** Holds a response until the test opens the gate; a timeout never answers normally and fails the test. */
    private fun CountDownLatch.hold() {
        if (!await(10, TimeUnit.SECONDS)) {
            gaveUp.set(true)
            throw IllegalStateException("a held response timed out")
        }
    }

    private val created = CopyOnWriteArrayList<DrawerViewModel>()

    private fun vm(lastChat: String? = null): DrawerViewModel = ViewModelProvider.create(
        store,
        viewModelFactory {
            initializer { DrawerViewModel(connected, lastChat = lastChat, onSelected = { saved += it }, now = clock::get, zone = { ZoneOffset.UTC }) }
        },
    ).get(DrawerViewModel::class).also { created += it }

    private fun ok(body: String = "{}") = { _: RecordedRequest -> MockResponse.Builder().code(200).body(body).addHeader("Content-Type", "application/json").build() }

    private fun listing(vararg keys: String) = ok(
        """{"object":"list","data":[""" + keys.joinToString(",") {
            """{"id":"$it","source":"api_server","title":"Kept","pinned":false,"archived":false,"hidden":false,""" +
                """"started_at":1.0,"last_active":1.0,"message_count":2,"preview":null,"_lineage_root_id":null}"""
        } + """],"has_more":false}""",
    )

    private suspend fun <T> StateFlow<T>.await(condition: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(60_000) { first(condition) } }

    private suspend fun addSession(key: String, title: String, onServer: Boolean = true, active: Double = clock.get() / 1000.0) =
        db.sessionDao().upsert(
            SessionEntity(
                key = key, currentId = key, lineageIds = "", title = title, pinned = false, archived = false, startedAt = active,
                lastActiveAt = active, messageCount = 2, preview = null, stale = false, existsOnServer = onServer,
            ),
        )

    private fun List<DrawerRow>.chats() = filterIsInstance<DrawerRow.Chat>()

    /**
     * A test must not end while the drawer's refresh still runs: tearing down under it made every later test in this
     * class that waits for a database-driven reaction time out (seen in fix round 2). Joins the refresh's own job.
     */
    private suspend fun refreshDone(vm: DrawerViewModel) =
        withContext(Dispatchers.Default) { withTimeout(60_000) { vm.refreshJob?.join() } }

    private fun Composer.edit(key: String, text: String) = edit(key, text, current(key).revision)

    private fun sessionsListed() = requests.count { it.target.startsWith("/api/sessions?") }

    @Test
    fun refreshFailureKeepsCache() = runTest {
        addSession("apofis-a", "Garden plans")
        val vm = vm()
        vm.rows.await { rows -> rows.chats().any { it.key == "apofis-a" } }

        vm.onOpen() // the list request answers 500
        vm.refreshFailed.await { it }
        assertEquals(listOf("Garden plans"), vm.rows.value.chats().map { it.title })
        assertEquals(1, sessionsListed())

        clock.addAndGet(29_999)
        vm.onOpen() // just under 30 s since the last attempt: throttled, decided without any coroutine
        assertEquals(1, sessionsListed())

        clock.addAndGet(1)
        vm.onOpen() // exactly 30 s: refreshes again
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (sessionsListed() < 2) delay(5) } }
        refreshDone(vm)
        assertEquals(2, sessionsListed())
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
    fun rapidNewChatCreatesOneChat() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        val keys = withContext(Dispatchers.Default) {
            List(5) { async { vm.onNewChat() } }.awaitAll()
        }
        assertEquals(1, keys.toSet().size)
        assertEquals(2, db.sessionDao().all().size)
    }

    @Test
    fun deleteOpenChatSwitchesToNew() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        routes["DELETE /api/sessions/apofis-a"] = ok("""{"deleted":true}""")
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        vm.rows.await { it.chats().isNotEmpty() }
        connected.composer.edit("apofis-a", "half a thought")
        vm.onDeleteRequest("apofis-a")
        val prompt = vm.prompt.value!!
        assertEquals(PromptKind.DELETE, prompt.kind)

        vm.confirmDelete(prompt.token)
        val next = vm.selected.await { it != "apofis-a" }!!
        vm.prompt.await { it == null }
        assertNull(db.sessionDao().get("apofis-a"))
        assertFalse(db.sessionDao().get(next)!!.existsOnServer)
        assertEquals("", connected.composer.current("apofis-a").draft)
    }

    @Test
    fun deleteNeedsTheOpenPromptsToken() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        addSession("apofis-b", "Bike repair")
        routes["DELETE /api/sessions/apofis-a"] = ok("""{"deleted":true}""")
        routes["DELETE /api/sessions/apofis-b"] = ok("""{"deleted":true}""")
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }

        vm.confirmDelete(1) // nothing open
        vm.onDeleteRequest("apofis-a")
        val forA = vm.prompt.value!!.token
        vm.dismiss(forA)
        assertNull(vm.prompt.value)
        vm.confirmDelete(forA) // dismissed
        vm.onDeleteRequest("apofis-b")
        val forB = vm.prompt.value!!
        vm.confirmDelete(forA) // a confirmation for A while B is open
        vm.dismiss(forA) // does not close B's dialog either
        assertEquals(forB, vm.prompt.value)
        assertTrue(requests.none { it.method == "DELETE" })

        vm.confirmDelete(forB.token)
        vm.prompt.await { it == null }
        assertEquals(listOf("DELETE /api/sessions/apofis-b"), requests.map { "${it.method} ${it.target}" })
        assertTrue(db.sessionDao().get("apofis-a") != null)
    }

    @Test
    fun runStartedAfterTheDialogOpenedNeedsAnotherConfirm() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        routes["DELETE /api/sessions/apofis-a"] = ok("""{"deleted":true}""")
        routes["POST /v1/runs/run_a/stop"] = ok("""{"run_id":"run_a","status":"stopping"}""")
        routes["POST /v1/runs/run_b/stop"] = ok("""{"run_id":"run_b","status":"stopping"}""")
        val vm = vm()
        vm.rows.await { it.chats().isNotEmpty() }
        vm.onDeleteRequest("apofis-a")
        val prompt = vm.prompt.value!!
        assertFalse(prompt.running)

        db.activeRunDao().upsert(ActiveRunEntity("run_a", "apofis-a", null, "running", 1L))
        db.activeRunDao().upsert(ActiveRunEntity("run_b", "apofis-a", null, "queued", 2L))
        vm.confirmDelete(prompt.token)
        val shown = vm.prompt.await { it?.running == true && !it.busy }!!
        assertTrue(requests.isEmpty()) // nothing stopped or deleted before the user saw the warning
        assertNotEquals(prompt.token, shown.token) // the warning is a new prompt
        vm.confirmDelete(prompt.token) // the confirmation given before the warning counts for nothing
        assertEquals(shown, vm.prompt.value)
        assertTrue(requests.isEmpty())

        vm.confirmDelete(shown.token)
        vm.prompt.await { it == null }
        assertEquals(
            listOf("POST /v1/runs/run_a/stop", "POST /v1/runs/run_b/stop", "DELETE /api/sessions/apofis-a"),
            requests.map { "${it.method} ${it.target}" },
        )
    }

    @Test
    fun queuedSendWaitsForTheDeleteAndGoesOutWhenItFails() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        db.pendingSendDao().upsert(PendingSendEntity("p1", "apofis-a", "hello", "{}", clock.get(), "queued", null, null))
        val gate = CountDownLatch(1)
        routes["DELETE /api/sessions/apofis-a"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        routes["POST /v1/runs"] = ok("""{"run_id":"run_1","status":"started","replayed":false}""")
        val vm = vm()
        vm.rows.await { it.chats().isNotEmpty() }
        vm.onDeleteRequest("apofis-a")
        vm.confirmDelete(vm.prompt.value!!.token)
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (requests.none { it.method == "DELETE" }) delay(5) } }

        val drain = launch(Dispatchers.IO) { connected.sendQueue.drain() } // a send attempt racing the delete
        gate.countDown()
        assertEquals(ActionError.FAILED, vm.prompt.await { it?.error != null }!!.error)
        withContext(Dispatchers.Default) { withTimeout(60_000) { drain.join() } }
        // The racing attempt started nothing; the drain after the failed delete sends the waiting message.
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (requests.none { it.target == "/v1/runs" }) delay(5) } }
        val order = requests.map { "${it.method} ${it.target}" }
        assertTrue(order.indexOf("DELETE /api/sessions/apofis-a") < order.indexOf("POST /v1/runs"))
        assertEquals(1, order.count { it == "POST /v1/runs" })
    }

    @Test
    fun cancelledDeleteWaitingForTheLockLeavesTheChatUsable() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        db.activeRunDao().upsert(ActiveRunEntity("run_a", "apofis-a", null, "running", 1L))
        val gate = CountDownLatch(1)
        routes["POST /v1/runs/run_a/stop"] = { gate.hold(); MockResponse.Builder().code(401).build() }
        routes["POST /v1/runs"] = ok("""{"run_id":"run_1","status":"started","replayed":false}""")
        val vm = vm()
        vm.rows.await { it.chats().any { c -> c.running } }
        vm.onDeleteRequest("apofis-a")
        vm.confirmDelete(vm.prompt.value!!.token)
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (requests.none { it.target == "/v1/runs/run_a/stop" }) delay(5) } }
        assertTrue(isBeingDeleted("apofis-a"))

        val held = CompletableDeferred<Unit>()
        val locked = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); held.await() } }
        locked.await() // the delete's cleanup will have to wait for this
        gate.countDown() // the stop is refused; the delete goes on to its cleanup, which waits for the lock
        follower.credentialsRejected.await { it } // recorded as the refusal returns, just before the cleanup starts
        store.clear() // the drawer goes away while its delete cleans up
        held.complete(Unit)
        lock.join()

        withContext(Dispatchers.Default) { withTimeout(60_000) { while (isBeingDeleted("apofis-a")) delay(5) } }
        assertTrue(db.sessionDao().get("apofis-a") != null)
        // The chat can start runs again.
        db.activeRunDao().delete("run_a")
        db.pendingSendDao().upsert(PendingSendEntity("p1", "apofis-a", "hello", "{}", clock.get(), "queued", null, null))
        connected.sendQueue.drain()
        assertEquals(1, requests.count { it.target == "/v1/runs" })
    }

    @Test
    fun sendFailingAfterTheChatWasDeletedLeavesNoEntry() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        routes["DELETE /api/sessions/apofis-a"] = ok("""{"deleted":true}""")
        val vm = vm()
        vm.rows.await { it.chats().isNotEmpty() }
        val composer = connected.composer
        composer.edit("apofis-a", "hello")
        val stored = CompletableDeferred<Unit>()
        val send = composer.send("apofis-a") { stored.await(); connected.repo.send("apofis-a", it) }!!
        vm.onDeleteRequest("apofis-a")
        vm.confirmDelete(vm.prompt.value!!.token)
        vm.prompt.await { it == null }
        assertNull(db.sessionDao().get("apofis-a"))

        stored.complete(Unit) // the send reaches the repository only now and finds no chat
        withContext(Dispatchers.Default) { withTimeout(60_000) { send.join() } }
        assertFalse("apofis-a" in composer.keys)
    }

    @Test
    fun refreshDropsIdleComposerRecordsOfChatsDeletedOnTheServer() = runTest {
        val gone = List(1000) { "apofis-r$it" }
        gone.forEach { addSession(it, "Old") }
        addSession("apofis-kept", "Kept", active = clock.get() / 1000.0 + 60) // the newest: selected, and still listed
        val composer = connected.composer
        gone.forEach { composer.current(it) } // each was drawn once: it has an idle record
        composer.edit("apofis-r0", "not stored")
        composer.send("apofis-r0") { throw IllegalStateException("not stored") }!!.join()
        routes["GET /api/sessions"] = listing("apofis-kept")
        val vm = vm()
        vm.selected.await { it == "apofis-kept" }

        vm.onOpen()

        withContext(Dispatchers.Default) { withTimeout(60_000) { while (composer.idleKeys.any { it in gone }) delay(5) } }
        assertTrue(composer.idleKeys.none { it in gone })
        assertNull(db.sessionDao().get("apofis-r1"))
        assertEquals("not stored", composer.current("apofis-r0").unsent?.text) // text is never dropped
    }

    @Test
    fun archiveKeepsTheDraft() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        routes["PATCH /api/sessions/apofis-a"] = ok(fixture("session_patch_archived.json"))
        connected.composer.edit("apofis-a", "pack the charger")
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        vm.rows.await { it.chats().isNotEmpty() } // the sheet is built from the loaded list
        vm.onLongPress("apofis-a")
        vm.archive(vm.prompt.value!!.token, true)
        vm.selected.await { it != "apofis-a" }
        assertEquals("pack the charger", connected.composer.current("apofis-a").draft)
    }

    @Test
    fun selectingAGoneOrHiddenChatIsIgnored() = runTest {
        addSession("apofis-a", "Open one")
        addSession("apofis-b", "Evicted", active = clock.get() / 1000.0 - 60)
        addSession("apofis-c", "Hidden", active = clock.get() / 1000.0 - 120)
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        db.sessionDao().deleteRow("apofis-b") // removed while its row was still drawn
        db.sessionDao().upsert(db.sessionDao().get("apofis-c")!!.copy(hidden = true))
        vm.onSelect("apofis-b")
        vm.onSelect("apofis-c")
        vm.onSelect("apofis-a") // serialized after the two above
        vm.reopened.await { it?.key == "apofis-a" }!!.key
        assertEquals("apofis-a", vm.selected.value)
    }

    @Test
    fun hiddenOpenChatIsReplaced() = runTest {
        addSession("apofis-a", "Kept for unsent work")
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        db.sessionDao().upsert(db.sessionDao().get("apofis-a")!!.copy(hidden = true))
        val next = vm.selected.await { it != "apofis-a" }!!
        assertFalse(db.sessionDao().get(next)!!.existsOnServer)
    }

    @Test
    fun tappingTheOpenChatOpensItAgain() = runTest {
        addSession("apofis-a", "Open one")
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        vm.onSelect("apofis-a")
        assertEquals("apofis-a", vm.reopened.await { it?.key == "apofis-a" }!!.key)
    }

    @Test
    fun failedStopKeepsTheChat() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        db.activeRunDao().upsert(ActiveRunEntity("run_a", "apofis-a", null, "running", 1L))
        routes["DELETE /api/sessions/apofis-a"] = ok("""{"deleted":true}""") // the stop answers 500
        val vm = vm()
        vm.rows.await { it.chats().any { c -> c.running } }
        vm.onDeleteRequest("apofis-a")
        val prompt = vm.prompt.value!!
        assertTrue(prompt.running)
        vm.confirmDelete(prompt.token)
        assertEquals(ActionError.FAILED, vm.prompt.await { it?.error != null }!!.error)
        assertTrue(requests.none { it.method == "DELETE" })
        assertTrue(db.sessionDao().get("apofis-a") != null)
    }

    @Test
    fun failedActionShowsInlineError() = runTest {
        addSession("apofis-a", "Trip to Lisbon") // the delete request answers 500
        val vm = vm()
        vm.rows.await { it.chats().isNotEmpty() }
        vm.onDeleteRequest("apofis-a")
        vm.confirmDelete(vm.prompt.value!!.token)
        assertEquals(ActionError.FAILED, vm.prompt.await { it?.error != null }!!.error)
        assertEquals(listOf("apofis-a"), vm.rows.value.chats().map { it.key })
    }

    @Test
    fun lateCompletionLeavesANewerPromptAlone() = runTest {
        addSession("apofis-a", "Trip to Lisbon")
        addSession("apofis-b", "Bike repair")
        val gate = CountDownLatch(1)
        routes["PATCH /api/sessions/apofis-a"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }
        vm.onPin("apofis-a", true) // an accessibility action: runs at once in its own prompt
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (requests.none { it.method == "PATCH" }) delay(5) } }
        vm.onDeleteRequest("apofis-b")
        val forB = vm.prompt.value!!
        assertEquals(1, vm.busyActions()) // the pin is still waiting for its answer
        gate.countDown()
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (vm.busyActions() > 0) delay(5) } }
        assertEquals(forB, vm.prompt.value)
    }

    @Test
    fun selectionFollowsAChatRemovedByRefresh() = runTest {
        addSession("apofis-a", "Gone on the server")
        addSession("apofis-b", "Still there", active = clock.get() / 1000.0 - 60)
        routes["GET /api/sessions"] = listing("apofis-b")
        val vm = vm()
        vm.selected.await { it == "apofis-a" }
        vm.onOpen()
        val next = vm.selected.await { it != "apofis-a" }!!
        assertNotEquals("apofis-b", next)
        assertFalse(db.sessionDao().get(next)!!.existsOnServer)
    }

    @Test
    fun restoresTheLastChatWhenItExists() = runTest {
        addSession("apofis-a", "Newest")
        addSession("apofis-b", "Older", active = clock.get() / 1000.0 - 600)
        assertEquals("apofis-b", vm(lastChat = "apofis-b").selected.await { it != null })
        store.clear()
        assertEquals("apofis-a", vm(lastChat = "apofis-gone").selected.await { it != null })
        // Stored by a collector of the selection, which may run just after the selection was published.
        withContext(Dispatchers.Default) { withTimeout(60_000) { while ("apofis-a" !in saved) delay(5) } }
        assertTrue("apofis-a" in saved)
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

    @Test
    fun anActionsSheetClosesWhenARefreshRemovesItsChat() = runTest {
        addSession("apofis-a", "Gone on the server")
        addSession("apofis-b", "Still there", active = clock.get() / 1000.0 - 60)
        routes["GET /api/sessions"] = listing("apofis-b")
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }
        vm.onLongPress("apofis-a")
        assertEquals("apofis-a", vm.prompt.value!!.key)
        vm.onOpen()
        vm.prompt.await { it == null }
        refreshDone(vm)
    }

    @Test
    fun aRefreshLeavesTheSheetOfAChatThatIsStillThere() = runTest {
        addSession("apofis-a", "Gone on the server")
        addSession("apofis-b", "Still there", active = clock.get() / 1000.0 - 60)
        routes["GET /api/sessions"] = listing("apofis-b")
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }
        vm.onLongPress("apofis-b")
        val forB = vm.prompt.value!!
        vm.onOpen()
        vm.rows.await { it.chats().none { c -> c.key == "apofis-a" } }
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (vm.selected.value == "apofis-a") delay(5) } }
        refreshDone(vm)
        assertEquals(forB, vm.prompt.value)
    }

    @Test
    fun archivingTheChatOfAnOpenSheetDoesNotCloseIt() = runTest {
        addSession("apofis-a", "Kept")
        val vm = vm()
        vm.rows.await { it.chats().size == 1 }
        vm.onLongPress("apofis-a")
        val prompt = vm.prompt.value!!
        db.sessionDao().upsert(db.sessionDao().get("apofis-a")!!.copy(archived = true)) // archived elsewhere: still here
        vm.rows.await { it.chats().isEmpty() }
        assertEquals(prompt, vm.prompt.value)
    }

    // ---- fix round 2: any removal closes an idle sheet, whoever refreshed ----

    @Test
    fun aSheetClosesWhenItsChatIsRemovedWithoutTheDrawerRefreshing() = runTest {
        addSession("apofis-a", "Removed by a refresh elsewhere")
        addSession("apofis-b", "Open", active = clock.get() / 1000.0 - 60)
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }
        vm.onLongPress("apofis-a")
        db.sessionDao().delete("apofis-a") // a run-completion or foreground refresh removed it; onOpen is never called
        vm.prompt.await { it == null }
        // Hidden counts as gone too.
        vm.onLongPress("apofis-b")
        db.sessionDao().upsert(db.sessionDao().get("apofis-b")!!.copy(hidden = true))
        vm.prompt.await { it == null }
    }

    @Test
    fun aNewerSheetIsNotClosedForTheChatOfAnOlderOne() = runTest {
        addSession("apofis-a", "Old sheet")
        addSession("apofis-b", "New sheet", active = clock.get() / 1000.0 - 60)
        val gate = CountDownLatch(1)
        routes["PATCH /api/sessions/apofis-a"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }
        vm.onLongPress("apofis-a")
        vm.pin(vm.prompt.value!!.token, true) // a's action is in flight
        try {
            withContext(Dispatchers.Default) { withTimeout(60_000) { while (requests.none { it.method == "PATCH" }) delay(5) } }
            db.sessionDao().delete("apofis-a")
            vm.prompt.await { it?.key == "apofis-a" && it.gone } // a's watch has delivered its result
            vm.onLongPress("apofis-b") // b's prompt replaces it
        } finally {
            gate.countDown()
        }
        val forB = vm.prompt.value!!
        assertEquals("apofis-b", forB.key)
        // a's late outcome (its failure) arrives for a token that is no longer shown: b's prompt stays as it was.
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (vm.busyActions() > 0) delay(5) } }
        assertEquals(forB, vm.prompt.value)
    }

    @Test
    fun aRefreshLeavesTheNewerSheetOpenAfterClosingTheOld() = runTest {
        addSession("apofis-a", "Gone on the server")
        addSession("apofis-b", "Still there", active = clock.get() / 1000.0 - 60)
        routes["GET /api/sessions"] = listing("apofis-b")
        val vm = vm()
        vm.rows.await { it.chats().size == 2 }
        vm.onLongPress("apofis-a")
        vm.onOpen()
        vm.prompt.await { it == null }
        // A newer prompt for a chat that is still there stays open through the next refresh.
        vm.onLongPress("apofis-b")
        val forB = vm.prompt.value!!
        clock.addAndGet(60_000)
        vm.onOpen()
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (sessionsListed() < 2) delay(5) } }
        vm.rows.await { it.chats().any { c -> c.key == "apofis-b" } }
        refreshDone(vm)
        assertEquals(forB, vm.prompt.value)
    }

    @Test
    fun aBusySheetWhoseChatGoesAwayKeepsItsOutcomeButOffersNothingMore() = runTest {
        addSession("apofis-a", "Pinned while removed")
        val gate = CountDownLatch(1)
        routes["PATCH /api/sessions/apofis-a"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm()
        vm.rows.await { it.chats().size == 1 }
        vm.onLongPress("apofis-a")
        val token = vm.prompt.value!!.token
        vm.pin(token, true)
        try {
            withContext(Dispatchers.Default) { withTimeout(60_000) { while (requests.none { it.method == "PATCH" }) delay(5) } }
            db.sessionDao().delete("apofis-a") // removed while the pin is in flight
            vm.prompt.await { it?.gone == true }
            assertTrue(vm.prompt.value!!.busy)
        } finally {
            gate.countDown()
        }
        val shown = vm.prompt.await { it != null && !it.busy && it.error != null }!!
        assertTrue("the failure stays visible, with nothing more to do", shown.gone)
        vm.archive(token, true) // offers nothing more: ignored
        assertEquals(shown, vm.prompt.value)
    }
}
