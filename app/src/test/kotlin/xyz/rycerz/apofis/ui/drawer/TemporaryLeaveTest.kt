package xyz.rycerz.apofis.ui.drawer

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.push.WakeTarget
import xyz.rycerz.apofis.push.check
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.sync.isBeingDeleted
import xyz.rycerz.apofis.sync.withRepositoryLock
import xyz.rycerz.apofis.sync.isTemporaryKey

internal const val T = "apofis-tmp-t"
internal const val T2 = "apofis-tmp-u"
internal const val A = "apofis-a"
internal const val B = "apofis-b"

/**
 * The drawer's graph for the temporary chat tests: a real repository, queue and follower against a recording server.
 * Main runs eagerly (unconfined) on [leaveClock]: the leave's ten second bound moves only when a test advances it, so
 * a request or a Room query on another thread never makes it fire. (Under runTest the runner skips to any pending
 * virtual timeout, even Long.MAX_VALUE, while the test waits on another thread; the bodies use runBlocking.)
 * Room runs its queries one at a time on [roomThread], in order: see [settled].
 */
abstract class TemporaryFixture {
    protected lateinit var server: MockWebServer
    protected lateinit var db: AppDatabase
    protected lateinit var connected: Connected
    protected lateinit var follower: RunFollower
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * For a test's helpers (held locks, parked calls): not children of the test's runBlocking, so a failing assertion
     * fails the test instead of leaving runBlocking waiting for them; cancelled at teardown. Unconfined: a continuation
     * runs inline where it is resumed (on the Room thread after a query), which keeps [settled]'s ordering.
     */
    protected val side = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    protected val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    protected val requests = CopyOnWriteArrayList<RecordedRequest>()
    protected val store = ViewModelStore()
    protected val clock = AtomicLong(1_800_000_000_000L)
    protected val leaveClock = TestCoroutineScheduler()
    protected val gaveUp = AtomicBoolean(false)
    private val gates = CopyOnWriteArrayList<CountDownLatch>()

    /** A gate the test opens; every gate is also opened at teardown, so a failed assertion never leaves one shut. */
    protected fun gate() = CountDownLatch(1).also { gates += it }

    /** The fixture's SQLite, which can hold or fail a read of one session row (see [SessionReads.arm]). */
    protected val reads = SessionReads()
    private val created = CopyOnWriteArrayList<DrawerViewModel>()
    private val stores = CopyOnWriteArrayList<ViewModelStore>()
    protected val roomThread = Executors.newSingleThreadExecutor()

    @Before
    fun setUpGraph() {
        Dispatchers.setMain(UnconfinedTestDispatcher(leaveClock))
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes["${request.method} ${request.target.substringBefore('?')}"]?.invoke(request)
                    ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(roomThread.asCoroutineDispatcher()).openHelperFactory(reads).build()
        val api = HermesApi(server.url("/").toString(), "secret-key")
        lateinit var f: RunFollower
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, clock::get, {})
        val open = AtomicBoolean(true)
        val repo = ChatRepository(api, db, clock::get, queue, active = open::get) // closed with the connection, as in the app
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, open, scope)
    }

    @After
    fun tearDownGraph() {
        gates.forEach { it.countDown() }
        assertFalse("a held response timed out", gaveUp.get())
        val scopes = created.map { it.viewModelScope.coroutineContext.job }
        store.clear()
        stores.forEach { it.clear() }
        runBlocking { withTimeout(60_000) { scopes.joinAll() } }
        side.coroutineContext.job.cancel()
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        roomThread.shutdown()
        server.close()
        Dispatchers.resetMain()
    }

    /**
     * Every Room query queued before this one has run, with the work it resumed (Main is eager) up to its next query:
     * a leave started earlier has by now set its deleting mark ([isBeingDeleted]).
     */
    protected suspend fun settled() {
        db.sessionDao().get("")
    }

    /** Exceptions that no coroutine handled, as the threads' default handler saw them while [block] ran. */
    protected suspend fun uncaughtDuring(block: suspend () -> Unit): List<Throwable> {
        val seen = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
        try {
            block()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        return seen
    }

    /**
     * Wraps the fixture's SQLite: once armed, it counts the reads of one session row (`SELECT * FROM SessionEntity WHERE
     * key = ?`, the DAO's `get`) and acts on one of them, on the Room thread: it holds it until [release] opens, or fails
     * it as a storage error would.
     */
    protected inner class SessionReads : SupportSQLiteOpenHelper.Factory {
        private val real = FrameworkSQLiteOpenHelperFactory()
        @Volatile private var pass = -1
        @Volatile private var fail = false
        val reached = CompletableDeferred<Unit>()
        val release = gate()

        /** The read that comes after [pass] more such reads is held (or, with [fail], throws). */
        fun arm(pass: Int, fail: Boolean = false) {
            this.fail = fail
            this.pass = pass
        }

        private fun seen(sql: String) {
            if (pass < 0 || !sql.trimStart().startsWith("SELECT * FROM SessionEntity WHERE `key` = ?")) return
            if (pass-- > 0) return
            pass = -1
            reached.complete(Unit)
            if (fail) throw SQLiteException("disk I/O error")
            release.hold()
        }

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val h = real.create(configuration)
            return object : SupportSQLiteOpenHelper by h {
                override val writableDatabase: SupportSQLiteDatabase get() = wrap(h.writableDatabase)
                override val readableDatabase: SupportSQLiteDatabase get() = wrap(h.readableDatabase)
            }
        }

        private fun wrap(d: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by d {
            override fun query(query: String): Cursor = seen(query).let { d.query(query) }
            override fun query(query: String, bindArgs: Array<out Any?>): Cursor = seen(query).let { d.query(query, bindArgs) }
            override fun query(query: SupportSQLiteQuery): Cursor = seen(query.sql).let { d.query(query) }
            override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                seen(query.sql).let { d.query(query, cancellationSignal) }
        }
    }

    protected fun vm(lastChat: String? = null, on: Connected = connected, into: ViewModelStore = store): DrawerViewModel = ViewModelProvider.create(
        into,
        viewModelFactory { initializer { DrawerViewModel(on, lastChat = lastChat, now = clock::get, zone = { ZoneOffset.UTC }) } },
    ).get(DrawerViewModel::class).also {
        created += it
        stores += into
    }

    /** Holds a response until the test opens the gate; a timeout never answers normally and fails the test. */
    protected fun CountDownLatch.hold() {
        if (!await(10, TimeUnit.SECONDS)) {
            gaveUp.set(true)
            throw IllegalStateException("a held response timed out")
        }
    }

    protected fun json(code: Int, body: String) = MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()

    protected fun deleted(id: String) {
        routes["DELETE /api/sessions/$id"] = { json(200, """{"object":"hermes.session.deleted","id":"$id","deleted":true}""") }
    }

    protected fun stopOk(run: String) {
        routes["POST /v1/runs/$run/stop"] = { json(200, """{"run_id":"$run","status":"stopping"}""") }
    }

    protected suspend fun session(
        key: String,
        title: String = "Title $key",
        onServer: Boolean = true,
        createStarted: Boolean = false,
        currentId: String = key,
        lineage: String = "",
        hidden: Boolean = false,
        active: Double = clock.get() / 1000.0,
    ) = db.sessionDao().upsert(
        SessionEntity(
            key = key, currentId = currentId, lineageIds = lineage, title = title, pinned = false, archived = false, startedAt = active,
            lastActiveAt = active, messageCount = 2, preview = null, stale = false, existsOnServer = onServer, hidden = hidden,
            createStarted = createStarted,
        ),
    )

    protected suspend fun message(key: String, text: String = "hello") = db.messageDao().insertAll(
        listOf(MessageEntity(key, 0, 1, "user", null, text, null, null, null, null, clock.get() / 1000.0, null)),
    )

    protected suspend fun pending(key: String, id: String = "p-$key") =
        db.pendingSendDao().upsert(PendingSendEntity(id, key, "queued text", "{}", clock.get(), "queued", null, null))

    protected suspend fun run(key: String, run: String) = db.activeRunDao().upsert(ActiveRunEntity(run, key, null, "running", 1L))

    protected fun lines() = requests.map { "${it.method} ${it.target}" }
    protected fun deletes() = lines().filter { it.startsWith("DELETE ") }
    protected fun stops() = lines().filter { it.endsWith("/stop") }

    protected suspend fun <V> StateFlow<V>.await(condition: (V) -> Boolean): V =
        withContext(Dispatchers.Default) { withTimeout(60_000) { first(condition) } }

    /** A watchdog only: waits in real time for work on other threads (the server, Room). */
    protected suspend fun until(condition: suspend () -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(60_000) { while (!condition()) delay(5) } }

    protected fun List<DrawerRow>.chats() = filterIsInstance<DrawerRow.Chat>().map { it.key }

    protected fun note(key: String) = connected.composer.current(key).note

    /** The bar's button for the open chat, once it shows [state]. */
    protected suspend fun DrawerViewModel.shown(state: NewChatState): NewChat =
        newChatButton.await { it.key != null && it.key == selected.value && it.state == state }

    /** A tap on the bar's toggle as it shows now for the open chat. */
    protected suspend fun DrawerViewModel.toggle(): String? =
        toggleTemporary(newChatButton.await { it.key != null && it.key == selected.value && it.state != NewChatState.NEW })

    protected val notDeleted = ComposerNote(NoteKind.TEMPORARY_NOT_DELETED)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TemporaryLeaveTest : TemporaryFixture() {

    @Test
    fun hiddenOnlyWhileOpen() = runBlocking<Unit> {
        session(T, title = "Secret plan")
        session(A)
        routes["DELETE /api/sessions/$T"] = { MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.rows.await { A in it.chats() && T !in it.chats() }
        vm.onQuery("Secret")
        vm.rows.await { it.chats().isEmpty() } // search leaves it out too while it is open

        vm.onQuery("")
        vm.onSelect(A)
        until { note(A) == notDeleted } // the leave failed
        assertTrue(T in vm.rows.await { T in it.chats() }.chats()) // listed (its row draws the fixed title)
        vm.onQuery("Secret")
        assertEquals(listOf(T), vm.rows.await { T in it.chats() }.chats()) // found by its stored title

        vm.onQuery("")
        vm.onSelect(T)
        vm.selected.await { it == T }
        vm.rows.await { T !in it.chats() }
        vm.onQuery("Secret")
        vm.rows.await { it.chats().isEmpty() }
        assertEquals(1, deletes().size)
    }

    @Test
    fun failedLeaveKeepsAndListsIt() = runBlocking<Unit> {
        session(T)
        session(A)
        message(T)
        pending(T)
        routes["DELETE /api/sessions/$T"] = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() } // offline
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }

        vm.onSelect(A)
        until { note(A) == notDeleted }
        assertNotNull(db.sessionDao().get(T))
        assertTrue(db.messageDao().hasRows(T))
        assertEquals(1, db.pendingSendDao().rows(T).size)
        vm.rows.await { T in it.chats() }

        deleted(T) // online again: the drawer's Delete removes it
        vm.onDeleteRequest(T)
        vm.confirmDelete(vm.prompt.value!!.token)
        vm.prompt.await { it == null }
        assertNull(db.sessionDao().get(T))
        assertEquals(A, vm.selected.value)
    }

    @Test
    fun leaveDeletesNewestFirstRootLast() = runBlocking<Unit> {
        val r = "apofis-tmp-r"
        session(r, currentId = "s2", lineage = "s1")
        session(A)
        listOf("s2", "s1", r).forEach(::deleted)
        val vm = vm(lastChat = r)
        vm.selected.await { it == r }

        vm.onSelect(A)
        until { db.sessionDao().get(r) == null }
        assertEquals(listOf("DELETE /api/sessions/s2", "DELETE /api/sessions/s1", "DELETE /api/sessions/$r"), deletes())
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertNull(note(A))
    }

    @Test
    fun leaveStopsAtFirstInexactAnswer() = runBlocking<Unit> {
        val r = "apofis-tmp-r"
        session(r, currentId = "s2", lineage = "s1")
        session(A)
        deleted("s2")
        routes["DELETE /api/sessions/s1"] = { json(200, """{"deleted":true}""") }
        deleted(r)
        val vm = vm(lastChat = r)
        vm.selected.await { it == r }

        vm.onSelect(A)
        until { note(A) == notDeleted }
        assertEquals(listOf("DELETE /api/sessions/s2", "DELETE /api/sessions/s1"), deletes())
        assertNotNull(db.sessionDao().get(r))
        vm.rows.await { r in it.chats() }
    }

    @Test
    fun leaveWaitsForStoppedRunThenDeletes() = runBlocking<Unit> {
        session(T)
        session(A)
        run(T, "run_t")
        stopOk("run_t")
        // A DELETE that arrives while the run is still stored meets the server's 409, as a running turn would.
        val removed = AtomicBoolean(false)
        routes["DELETE /api/sessions/$T"] = {
            if (removed.get()) json(200, """{"object":"hermes.session.deleted","id":"$T","deleted":true}""")
            else json(409, """{"error":{"code":"session_active_turn","message":"busy"}}""")
        }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }

        vm.onSelect(A)
        until { stops().isNotEmpty() }
        leaveClock.advanceTimeBy(9_000) // the manual clock: nine seconds pass while the run is still stored
        assertTrue(deletes().isEmpty())
        assertNull(note(A)) // not yet: the leave still waits for the run

        removed.set(true)
        db.activeRunDao().delete("run_t") // the follower removes it when the run ends
        until { db.sessionDao().get(T) == null }
        assertEquals(listOf("POST /v1/runs/run_t/stop"), stops())
        assertEquals(listOf("DELETE /api/sessions/$T"), deletes())
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertNull(note(A))
    }

    @Test
    fun leaveGivesUpAfterTenSeconds() = runBlocking<Unit> {
        // The run never ends.
        session(T)
        session(T2)
        session(A)
        session(B)
        run(T, "run_t")
        stopOk("run_t")
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { stops().isNotEmpty() }
        leaveClock.advanceTimeBy(9_999)
        assertNull(note(A)) // not yet: one millisecond is left
        leaveClock.advanceTimeBy(1)
        leaveClock.runCurrent()
        until { note(A) == notDeleted }
        assertTrue(deletes().isEmpty())
        assertNotNull(db.sessionDao().get(T))
        vm.rows.await { T in it.chats() }

        // The server never answers; its late 409 changes nothing more.
        val gate = gate()
        routes["DELETE /api/sessions/$T2"] = { gate.hold(); json(409, """{"error":{"code":"session_active_turn","message":"busy"}}""") }
        vm.onSelect(T2)
        vm.selected.await { it == T2 }
        vm.onSelect(B)
        until { deletes().contains("DELETE /api/sessions/$T2") }
        leaveClock.advanceTimeBy(10_000)
        leaveClock.runCurrent()
        until { note(B) == notDeleted }
        gate.countDown()
        until { !isBeingDeleted(T2) }
        assertNotNull(db.sessionDao().get(T2))
        vm.rows.await { T2 in it.chats() }
        assertEquals(B, vm.selected.value)
        assertEquals(notDeleted, note(B))
        assertEquals(1, deletes().count { it == "DELETE /api/sessions/$T2" })
    }

    @Test
    fun leaveDoesNotBlockNextSelection() = runBlocking<Unit> {
        session(T)
        session(A)
        session(B)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); json(200, """{"object":"hermes.session.deleted","id":"$T","deleted":true}""") }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { deletes().isNotEmpty() }

        vm.onSelect(B) // takes only the selection lock: done while the DELETE is held
        vm.selected.await { it == B }
        assertFalse("selected only after the held DELETE gave up", gaveUp.get())
        assertEquals(1L, gate.count)

        // A new chat needs the repository lock, which the held DELETE keeps: it completes once that answers.
        val fresh = side.async(start = CoroutineStart.UNDISPATCHED) { vm.onNewChat() } // runs until its first Room read
        settled() // that read has answered, and its continuation, queued first, has reached the repository lock
        assertFalse(fresh.isCompleted)
        assertEquals(setOf(T, A, B), db.sessionDao().all().map { it.key }.toSet()) // no new row yet
        gate.countDown()
        val key = withTimeout(60_000) { fresh.await() }
        assertEquals(key, vm.selected.value)
        until { db.sessionDao().get(T) == null }
    }

    @Test
    fun listedAfterRestartWithAnotherChatSelected() = runBlocking<Unit> {
        session(T)
        session(A)
        val vm = vm(lastChat = A) // the selection was stored, the process died before the Delete
        vm.selected.await { it == A }
        vm.rows.await { T in it.chats() && A in it.chats() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun storedTemporaryStaysOpenAtStart() = runBlocking<Unit> {
        session(T)
        session(A)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.rows.await { A in it.chats() && T !in it.chats() }
        assertTrue(requests.isEmpty())
        assertNotNull(db.sessionDao().get(T))
    }

    @Test
    fun newChatLeaves() = runBlocking<Unit> {
        val vm = vm()
        vm.selected.await { it != null }
        val local = vm.toggle()!! // an empty, never-created temporary chat
        vm.newChat() // the drawer's New chat row
        val ordinary = vm.selected.await { it != local }!!
        assertFalse(isTemporaryKey(ordinary))
        until { db.sessionDao().get(local) == null }
        assertTrue(requests.isEmpty())

        session(T)
        message(T)
        deleted(T)
        vm.onSelect(T)
        vm.selected.await { it == T }
        vm.newChat()
        val next = vm.selected.await { it != T }!!
        assertFalse(isTemporaryKey(next))
        until { db.sessionDao().get(T) == null }
        assertEquals(listOf("DELETE /api/sessions/$T"), deletes())
    }

    @Test
    fun notificationTapForAnotherChatLeaves() = runBlocking<Unit> {
        session(T)
        session(A)
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        assertTrue(vm.selectTapped(A) { true })
        assertEquals(A, vm.selected.value)
        until { db.sessionDao().get(T) == null }
        assertEquals(listOf("DELETE /api/sessions/$T"), deletes())
    }

    @Test
    fun callerCancelledAfterSelectionStillDeletes() = runBlocking<Unit> {
        session(T)
        session(T2)
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); json(200, """{"object":"hermes.session.deleted","id":"$T","deleted":true}""") }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        val tapped = CompletableDeferred<Unit>()
        val caller = launch(Dispatchers.Default) {
            assertTrue(vm.selectTapped(A) { true })
            tapped.complete(Unit)
            awaitCancellation()
        }
        tapped.await()
        until { deletes().isNotEmpty() }
        assertTrue(caller.isActive)
        caller.cancelAndJoin() // a recreated activity, while the DELETE is held
        gate.countDown()
        until { db.sessionDao().get(T) == null }

        // A share accepted from a temporary chat, its caller cancelled while it fills the new chat; the DELETE fails.
        val failGate = gate()
        routes["DELETE /api/sessions/$T2"] = { failGate.hold(); MockResponse.Builder().code(500).build() }
        vm.onSelect(T2)
        vm.selected.await { it == T2 }
        val filling = CompletableDeferred<String>()
        val sharer = launch(Dispatchers.Default) { vm.openFreshChatIf(T2, { true }) { filling.complete(it); awaitCancellation() } }
        val fresh = filling.await()
        sharer.cancelAndJoin()
        until { deletes().contains("DELETE /api/sessions/$T2") }
        failGate.countDown()
        until { note(fresh) == notDeleted }
        assertEquals(fresh, vm.selected.value)
        assertNotNull(db.sessionDao().get(T2))
        vm.rows.await { T2 in it.chats() }
    }

    @Test
    fun neverCreatedTemporaryLeavesLocally() = runBlocking<Unit> {
        val vm = vm()
        vm.selected.await { it != null }
        val local = vm.toggle()!!
        session(A)
        vm.onSelect(A)
        vm.selected.await { it == A }
        until { db.sessionDao().get(local) == null }
        assertTrue(requests.isEmpty())
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertNull(note(A))
    }

    @Test
    fun openFreshChatIfLostClaimLeavesNothing() = runBlocking<Unit> {
        session(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        var filled = false
        assertNull(vm.openFreshChatIf(T, { false }) { filled = true })
        assertNull(vm.openFreshChatIf(A, { true }) { filled = true }) // not the selected chat
        assertFalse(filled)
        assertEquals(T, vm.selected.value)
        assertEquals(listOf(T), db.sessionDao().all().map { it.key })
        assertTrue(requests.isEmpty())
    }

    // ---- a chat being left is set aside ----

    /** Not listed, not selectable from the drawer or a notification, no prompt and no Delete for it. */
    private suspend fun assertSetAside(vm: DrawerViewModel, key: String, open: String) {
        vm.rows.await { open in it.chats() }
        assertFalse(key in vm.rows.value.chats())
        assertFalse(key in vm.archived.value.map { it.key })
        vm.onSelect(key)
        assertFalse(vm.selectTapped(key) { true })
        vm.onLongPress(key)
        assertNull(vm.prompt.value)
        settled()
        assertEquals(open, vm.selected.value)
        assertFalse(key in connected.composer.keys) // nothing was typed or sent into it
    }

    @Test
    fun chatBeingLeftIsSetAsideWhileItsDeleteIsHeld() = runBlocking<Unit> {
        session(T, title = "Secret plan")
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        vm.selected.await { it == A }
        until { deletes().isNotEmpty() }
        assertSetAside(vm, T, open = A)
        vm.onQuery("Secret")
        vm.rows.await { it.chats().isEmpty() }
        vm.onQuery("")

        gate.countDown() // the leave fails: listed again, with the note, and selectable
        until { note(A) == notDeleted }
        vm.rows.await { T in it.chats() }
        vm.onSelect(T)
        vm.selected.await { it == T }
    }

    @Test
    fun chatBeingLeftIsSetAsideWhileItWaitsForItsRun() = runBlocking<Unit> {
        session(T)
        session(A)
        run(T, "run_t")
        stopOk("run_t")
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { stops().isNotEmpty() }
        assertSetAside(vm, T, open = A)
        assertTrue(deletes().isEmpty())

        db.activeRunDao().delete("run_t")
        until { db.sessionDao().get(T) == null }
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertNull(note(A))
    }

    @Test
    fun chatIsSetAsideBeforeTheLeaveFirstSuspends() = runBlocking<Unit> {
        session(T)
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        val roomHeld = gate()
        try {
            // A share: the claim holds Room just before the selection changes, so the leave's first read cannot answer.
            val fresh = vm.openFreshChatIf(T, { roomThread.execute { roomHeld.hold() }; true }) {}
            assertEquals(fresh, vm.selected.value)
            vm.onLongPress(T) // synchronous: neither the selection lock nor Room
            assertNull(vm.prompt.value)
            val tapped = side.async(start = CoroutineStart.UNDISPATCHED) { vm.selectTapped(T) { true } }
            roomHeld.countDown()
            assertFalse(withTimeout(60_000) { tapped.await() })
            assertEquals(fresh, vm.selected.value)
        } finally {
            roomHeld.countDown()
        }
        until { db.sessionDao().get(T) == null }
    }

    @Test
    fun twoLeavesInFlightEachEndOnTheirOwn() = runBlocking<Unit> {
        session(T)
        session(T2)
        session(A)
        val first = gate()
        routes["DELETE /api/sessions/$T"] = { first.hold(); MockResponse.Builder().code(500).build() }
        deleted(T2)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(T2) // leave T for T2
        vm.selected.await { it == T2 }
        until { deletes().contains("DELETE /api/sessions/$T") }
        vm.onSelect(A) // and at once T2 for A
        vm.selected.await { it == A }
        assertSetAside(vm, T, open = A)
        assertSetAside(vm, T2, open = A)

        first.countDown() // T's leave fails; T2's, waiting behind it for the repository lock, then succeeds
        until { db.sessionDao().get(T2) == null }
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        vm.rows.await { T in it.chats() && T2 !in it.chats() }
        assertNotNull(db.sessionDao().get(T))
        assertFalse(T2 in connected.composer.keys) // the note for the gone T2 left no entry behind
        vm.onSelect(T)
        vm.selected.await { it == T }
    }

    // ---- the note follows the row ----

    @Test
    fun deleteCommittedBeforeTheTimeoutSetsNoNote() = runBlocking<Unit> {
        session(T)
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); json(200, """{"object":"hermes.session.deleted","id":"$T","deleted":true}""") }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        // Queued behind the Delete for the repository lock: it gets the lock between the commit and the cleanup.
        val cleanupHeld = CompletableDeferred<Unit>()
        val locked = CompletableDeferred<Unit>()
        val holder = side.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { withRepositoryLock { locked.complete(Unit); cleanupHeld.await() } }
        gate.countDown()
        locked.await()
        assertNull(db.sessionDao().get(T)) // committed
        leaveClock.advanceTimeBy(10_000) // the ten seconds pass while the cleanup waits for the lock
        leaveClock.runCurrent()
        cleanupHeld.complete(Unit)
        holder.join()
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertNull(note(A))
    }

    @Test
    fun closedConnectionSetsNoNote() = runBlocking<Unit> {
        session(T)
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        connected.close() // a switch closes the graph before its drawer is cleared
        gate.countDown()
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertFalse(A in connected.composer.keys)
        assertNotNull(db.sessionDao().get(T))
    }

    @Test
    fun goneDestinationGetsNoNote() = runBlocking<Unit> {
        session(T)
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        db.sessionDao().delete(A) // removed meanwhile (a refresh, another phone)
        gate.countDown()
        withTimeout(60_000) { vm.leavesEnded() } // the note decision is made
        assertFalse(A in connected.composer.keys)
        assertNotNull(db.sessionDao().get(T))
    }

    @Test
    fun failingReadAfterTheAttemptThrowsNothing() = runBlocking<Unit> {
        session(T)
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        reads.arm(pass = 0, fail = true) // the leave's first read after the attempt fails, as a storage error would
        val uncaught = uncaughtDuring {
            gate.countDown()
            withTimeout(60_000) { vm.leavesEnded() } // the key has left the set
        }
        assertTrue(reads.reached.isCompleted)
        assertEquals(emptyList<Throwable>(), uncaught)
        assertFalse(A in connected.composer.keys) // eligibility unknown: no note
        assertNotNull(db.sessionDao().get(T))
    }

    @Test
    fun connectionClosedDuringTheLastReadSetsNoNote() = runBlocking<Unit> {
        session(T)
        session(A)
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        reads.arm(pass = 1) // the second read after the attempt (the chat the note would go to) is held
        gate.countDown()
        withTimeout(60_000) { reads.reached.await() }
        connected.close() // a switch closes the graph while that read is held
        reads.release.countDown()
        withTimeout(60_000) { vm.leavesEnded() }
        assertFalse(A in connected.composer.keys)
    }

    @Test
    fun deleteDialogOpenedBeforeTheLeaveIsRefused() = runBlocking<Unit> {
        session(T)
        session(A)
        db.sessionDao().upsert(db.sessionDao().get(T)!!.copy(archived = true)) // an open archived chat stays in the archive
        val first = gate()
        val answered = java.util.concurrent.atomic.AtomicInteger()
        routes["DELETE /api/sessions/$T"] = { if (answered.getAndIncrement() == 0) first.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.archived.await { rows -> rows.any { it.key == T } } // loaded: the dialog can be opened for it
        vm.onDeleteRequest(T) // before the leave starts
        val prompt = vm.prompt.value!!
        assertEquals(PromptKind.DELETE, prompt.kind)
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        vm.confirmDelete(prompt.token) // while the leave's DELETE is held
        first.countDown()
        withTimeout(60_000) { vm.leavesEnded() }
        until { vm.busyActions() == 0 }
        assertEquals(ActionError.FAILED, vm.prompt.value?.error)
        assertEquals(listOf("DELETE /api/sessions/$T"), deletes())
    }

    @Test
    fun archivedChatBeingLeftIsNotInTheArchive() = runBlocking<Unit> {
        session(T)
        session(A)
        db.sessionDao().upsert(db.sessionDao().get(T)!!.copy(archived = true))
        val gate = gate()
        routes["DELETE /api/sessions/$T"] = { gate.hold(); MockResponse.Builder().code(500).build() }
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.archived.await { rows -> rows.any { it.key == T } } // listed in the archive while open
        vm.onSelect(A)
        until { deletes().isNotEmpty() }
        vm.archived.await { rows -> rows.none { it.key == T } }
        gate.countDown()
        withTimeout(60_000) { vm.leavesEnded() }
        vm.archived.await { rows -> rows.any { it.key == T } } // the leave failed: back in the archive
    }

    // ---- a share's fresh chat is never orphaned ----

    @Test
    fun shareCancelledAfterTheInsertLeavesNoRow() = runBlocking<Unit> {
        session(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        val caller = StandardTestDispatcher(TestCoroutineScheduler()) // runs only when the test says so
        val share = side.launch(caller) { vm.openFreshChatIf(T, { false }) {} }
        // Driven step by step until the fresh row is inserted, checked before each step: its return then waits on the
        // caller, and the cancellation lands before it.
        until {
            db.sessionDao().all().size == 2 || run {
                caller.scheduler.runCurrent()
                false
            }
        }
        share.cancel()
        until {
            caller.scheduler.runCurrent()
            share.isCompleted
        }
        assertEquals(listOf(T), db.sessionDao().all().map { it.key })
        assertEquals(T, vm.selected.value)
    }

    // ---- controls: nothing but leaving deletes ----

    @Test
    fun drawerSelectionOfTheSameChatKeepsIt() = runBlocking<Unit> {
        session(T)
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect(T)
        vm.reopened.await { it?.key == T }
        assertTrue(vm.selectTapped(T) { true })
        settled()
        assertFalse(isBeingDeleted(T))
        assertEquals(T, vm.selected.value)
        assertNotNull(db.sessionDao().get(T))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun staleTargetLeavesNothing() = runBlocking<Unit> {
        session(T)
        session(A)
        session("apofis-h", hidden = true)
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        vm.onSelect("apofis-gone")
        vm.onSelect("apofis-h")
        assertFalse(vm.selectTapped(A) { false }) // a tap of another graph
        assertFalse(vm.selectTapped("apofis-gone") { true })
        settled()
        assertFalse(isBeingDeleted(T))
        assertEquals(T, vm.selected.value)
        assertNotNull(db.sessionDao().get(T))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun viewModelTeardownLeavesNothing() = runBlocking<Unit> {
        session(T)
        run(T, "run_t")
        stopOk("run_t")
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        store.clear()
        assertNotNull(db.sessionDao().get(T))
        assertTrue(stops().isEmpty())
        assertTrue(deletes().isEmpty())
    }

    // ---- controls through the app's container: a switch, a key change and the background lanes ----

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private suspend fun container(): AppContainer {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        return AppContainer(app, CredentialStore(app) { aes }) { db }
    }

    @Test
    fun serverSwitchDeletesNothing() = runBlocking<Unit> {
        val other = MockWebServer()
        val otherRequests = CopyOnWriteArrayList<RecordedRequest>()
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                otherRequests += request
                return MockResponse.Builder().code(500).build()
            }
        }
        other.start()
        try {
            val c = container()
            c.connect(Credentials(server.url("/").toString(), "key-1"))
            session(T)
            run(T, "run_t")
            stopOk("run_t")
            deleted(T)
            c.preferences.lastChat = T
            val graph = ViewModelStore()
            val first = vm(lastChat = T, on = c.connected()!!, into = graph)
            first.selected.await { it == T }

            c.connect(Credentials(other.url("/").toString(), "key-1")) // another server: the cached tables go
            graph.clear()
            val second = vm(lastChat = c.preferences.lastChat, on = c.connected()!!, into = ViewModelStore())
            second.selected.await { it != null }
            assertTrue(deletes().isEmpty() && stops().isEmpty())
            assertTrue(otherRequests.none { it.method == "DELETE" || it.target.endsWith("/stop") })
        } finally {
            other.close()
        }
    }

    @Test
    fun keyChangeAtSameServerKeepsTemporary() = runBlocking<Unit> {
        val c = container()
        c.connect(Credentials(server.url("/").toString(), "key-1"))
        session(T)
        session(A)
        deleted(T)
        c.preferences.lastChat = T
        val graph = ViewModelStore()
        vm(lastChat = T, on = c.connected()!!, into = graph).selected.await { it == T }

        c.connect(Credentials(server.url("/").toString(), "key-2")) // the same server: the cache stays
        graph.clear()
        val next = vm(lastChat = c.preferences.lastChat, on = c.connected()!!, into = ViewModelStore())
        next.selected.await { it == T }
        next.rows.await { A in it.chats() && T !in it.chats() }
        assertNotNull(db.sessionDao().get(T))
        assertTrue(deletes().isEmpty())
    }

    @Test
    fun workerAndPushStartsLeaveNothing() = runBlocking<Unit> {
        val c = container()
        c.connect(Credentials(server.url("/").toString(), "key-1"))
        session(T)
        run(T, "run_t")
        pending(T)
        stopOk("run_t")
        deleted(T)
        c.preferences.lastChat = T

        // A process started by the send worker or a push wake: the container only, no drawer.
        val cold = container()
        val conn = cold.load()!!
        runCatching { conn.sendQueue.drainSettlingRuns() }
        runCatching { check(app, WakeTarget(conn, cold.epoch(), cold.notifier)) }
        assertNotNull(db.sessionDao().get(T))
        assertTrue(stops().isEmpty())
        assertTrue(deletes().isEmpty())
        assertEquals(T, cold.preferences.lastChat)
        assertNotEquals(0, requests.size) // the lanes did run against the server
    }
}
