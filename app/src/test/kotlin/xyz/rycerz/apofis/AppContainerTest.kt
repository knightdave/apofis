package xyz.rycerz.apofis

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import xyz.rycerz.apofis.chat.Composer
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.db.ActiveRunEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.RetryResult
import xyz.rycerz.apofis.sync.SendWorker
import xyz.rycerz.apofis.sync.withRepositoryLock

private val A = Credentials("https://a.example/", "key-a")
private val B = Credentials("https://b.example/", "key-b")

@RunWith(RobolectricTestRunner::class)
class AppContainerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var failNextKeyUse = false
    private val store = CredentialStore(app) {
        if (failNextKeyUse) {
            failNextKeyUse = false
            throw GeneralSecurityException("keystore unavailable")
        }
        key
    }
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        container = AppContainer(app, store) { db }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addSession(key: String) = db.sessionDao().upsert(
        SessionEntity(
            key = key, currentId = key, lineageIds = "", title = null, pinned = false, archived = false,
            startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false,
            existsOnServer = true, hidden = false,
        ),
    )

    private fun assertClosed(block: () -> Unit) {
        try {
            block()
            fail("a closed instance must refuse use")
        } catch (e: IllegalStateException) {
            assertEquals("closed", e.message)
        }
    }

    private fun Composer.edit(key: String, text: String) = edit(key, text, current(key).revision)

    private suspend fun expectFailure(c: Credentials) {
        try {
            container.connect(c)
            fail("a failed switch must not report success")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun switchingServerClosesTheOldInstanceThenClearsTheCache() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")

        container.connect(B)

        val now = container.connected()!!
        assertFalse(old.isOpen)
        assertNotSame(old, now)
        assertEquals(B, now.credentials)
        assertEquals(now, container.connection.value)
        assertTrue(db.sessionDao().all().isEmpty())
        assertEquals(B, store.load())

        // A retained old instance can neither drain nor write, even into a session key the new server also has.
        addSession("apofis-b")
        val key = now.sendQueue.enqueue("apofis-b", "for server B")
        val rows = db.pendingSendDao().observe("apofis-b")
        val before = db.pendingSendDao().get(key)
        assertFalse(old.sendQueue.drain())
        try {
            old.sendQueue.enqueue("apofis-b", "from server A")
            fail("a closed queue must refuse writes")
        } catch (e: IllegalStateException) {
            assertEquals("closed", e.message)
        }
        assertEquals(listOf(before), rows.first())
        assertClosed { old.repo }
        assertClosed { old.follower }
        assertClosed { old.api }
    }

    @Test
    fun anotherUrlThanTheSavedOneAlwaysAsks() = runTest {
        assertFalse(container.willClearCache(B.baseUrl)) // nothing saved yet
        container.connect(A)
        assertTrue(container.willClearCache(B.baseUrl)) // asked even with an empty cache
        assertFalse(container.willClearCache(A.baseUrl))
    }

    @Test
    fun newKeyForTheSameServerKeepsTheCacheAndReschedulesQueuedSends() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")
        old.sendQueue.enqueue("apofis-a", "waiting")
        val work = WorkManager.getInstance(app)
        work.cancelAllWork().result.get()

        container.connect(A.copy(apiKey = "key-a2"))

        assertFalse(old.isOpen)
        assertEquals("key-a2", container.connected()!!.credentials.apiKey)
        assertEquals(1, db.sessionDao().all().size)
        assertEquals(1, db.pendingSendDao().allQueued().size)
        val infos = work.getWorkInfosForUniqueWork(SendWorker.WORK_NAME).get()
        assertTrue(infos.any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun newKeyForTheSameServerQuarantinesSendsTheOldKeyMayHaveStarted() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")
        val tried = old.sendQueue.enqueue("apofis-a", "accepted under key A, response lost")
        db.pendingSendDao().update(db.pendingSendDao().get(tried)!!.copy(attempted = true))
        val fresh = old.sendQueue.enqueue("apofis-a", "never sent")

        container.connect(A.copy(apiKey = "key-a2"))

        // Hermes scopes idempotency keys by API key: the same key under the new one could start a second run.
        val quarantined = db.pendingSendDao().get(tried)!!
        assertEquals("failed", quarantined.state)
        assertEquals("unconfirmed", quarantined.error)
        assertEquals(RetryResult.NEEDS_CONFIRMATION, container.connected()!!.sendQueue.retry(tried))
        assertEquals("queued", db.pendingSendDao().get(fresh)!!.state)
    }

    @Test
    fun retryInFlightAcrossAKeyChangeEndsQuarantined() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")
        val tried = old.sendQueue.enqueue("apofis-a", "accepted under key A, then refused")
        db.pendingSendDao().update(db.pendingSendDao().get(tried)!!.copy(state = "failed", error = "unauthorized", attempted = true))
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        old.sendQueue.beforeRetryWrite = {
            reading.complete(Unit)
            release.await()
        }
        val retry = async(Dispatchers.IO) { old.sendQueue.retry(tried) } // no confirmation needed under key A
        reading.await() // read and decided, not yet written
        val switch = async(Dispatchers.IO) { container.connect(A.copy(apiKey = "key-a2")) }
        val prefs = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        eventually("switch marked") { prefs.contains(SWITCH_MARKER) } // the quarantine step is next
        release.complete(Unit)
        retry.await()
        switch.await()

        // The old retry's write can never land after the quarantine: the new key never resends it unasked.
        val row = db.pendingSendDao().get(tried)!!
        assertEquals("failed", row.state)
        assertEquals("unconfirmed", row.error)
        assertEquals(RetryResult.NEEDS_CONFIRMATION, container.connected()!!.sendQueue.retry(tried))
    }

    @Test
    fun sameKeyAgainKeepsAttemptedSendsQueued() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")
        val tried = old.sendQueue.enqueue("apofis-a", "tried")
        db.pendingSendDao().update(db.pendingSendDao().get(tried)!!.copy(attempted = true))

        container.connect(A)

        assertEquals("queued", db.pendingSendDao().get(tried)!!.state)
    }

    @Test
    fun reconnectAfterAnInterruptedSwitchQuarantinesAttemptedSends() = runTest {
        container.connect(A)
        addSession("apofis-a")
        val tried = container.connected()!!.sendQueue.enqueue("apofis-a", "tried")
        db.pendingSendDao().update(db.pendingSendDao().get(tried)!!.copy(attempted = true))
        app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(SWITCH_MARKER, true).commit()
        val restarted = AppContainer(app, store) { db } // the old key is gone: it cannot be shown to be unchanged
        assertNull(restarted.connected())

        restarted.connect(A)

        assertEquals("unconfirmed", db.pendingSendDao().get(tried)!!.error)
    }

    @Test
    fun returningToTheForegroundRefreshesTheChatList() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = recordingServer(requests)
        try {
            container.connect(Credentials(server.url("/").toString(), "k1"))
            container.onStart()
            container.awaitLifecycle()
            // Another client may have added turns while the phone slept: the list (and with it stale flags) is read again.
            eventually("session refresh") { synchronized(requests) { "GET /api/sessions" in requests } }
        } finally {
            container.onStop()
            container.awaitLifecycle()
            server.close()
        }
    }

    @Test
    fun newKeyForTheSameServerKeepsDraftsAndTheSendBeingStored() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = recordingServer(requests)
        try {
            val first = Credentials(server.url("/").toString(), "k1")
            container.connect(first)
            val old = container.connected()!!
            addSession("apofis-a")
            addSession("apofis-b")
            old.composer.edit("apofis-b", "half a thought")
            old.composer.edit("apofis-a", "hello")
            val held = CompletableDeferred<Unit>()
            val locked = CompletableDeferred<Unit>()
            val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); held.await() } }
            locked.await()
            val repo = old.repo
            old.composer.send("apofis-a") { repo.send("apofis-a", it) }!! // waits for the lock: not stored yet
            val switching = CompletableDeferred<Unit>()
            container.beforeSwitch = { switching.complete(Unit) }
            val connect = async(Dispatchers.IO) { container.connect(first.copy(apiKey = "k2")) }
            switching.await()
            held.complete(Unit)
            lock.join()
            connect.await()

            val now = container.connected()!!
            assertNotSame(old, now)
            assertEquals("half a thought", now.composer.current("apofis-b").draft)
            assertEquals("", now.composer.current("apofis-a").draft)
            assertEquals(listOf("hello"), db.pendingSendDao().rows("apofis-a").map { it.text })
            now.sendQueue.drain()
            assertEquals(1, synchronized(requests) { requests.count { it == "POST /v1/runs" } })
        } finally {
            server.close()
        }
    }

    @Test
    fun failureAfterTheSwitchBeganEndsDisconnected() = runTest {
        container.connect(A)
        addSession("apofis-a")
        failNextKeyUse = true

        expectFailure(A.copy(apiKey = "key-a2")) // even for the same server: nothing half-saved survives

        assertNull(container.connected())
        assertNull(container.connection.value)
        assertNull(store.load())
        assertFalse(app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).contains(SWITCH_MARKER))
    }

    @Test
    fun failedSameServerSaveThenRetryKeepsDraftsAndUnsentText() = runTest {
        container.connect(A)
        val first = container.connected()!!
        first.composer.edit("apofis-a", "half a thought")
        first.composer.edit("apofis-b", "not stored")
        first.composer.send("apofis-b") { throw IllegalStateException("not stored") }!!.join()
        failNextKeyUse = true

        expectFailure(A.copy(apiKey = "key-a2"))
        assertNull(container.connected())
        container.connect(A.copy(apiKey = "key-a3")) // the user tries again

        val now = container.connected()!!
        assertEquals("half a thought", now.composer.current("apofis-a").draft)
        assertEquals("not stored", now.composer.current("apofis-b").unsent?.text)
    }

    /** A switch to B whose commit of [failPut] or removal of [failRemove] fails, after the tables were cleared. */
    private suspend fun failAfterTheClear(failPut: String? = null, failRemove: String? = null) {
        val flaky = FlakyCacheApp(app)
        container = AppContainer(flaky, store) { db }
        container.connect(A)
        val first = container.connected()!!
        addSession("apofis-a")
        first.composer.edit("apofis-a", "half a thought")
        first.composer.edit("apofis-b", "not stored")
        first.composer.send("apofis-b") { throw IllegalStateException("not stored") }!!.join()
        val release = CompletableDeferred<Unit>()
        first.composer.edit("apofis-c", "being stored")
        first.composer.send("apofis-c") { release.await() }!!
        container.beforeSwitch = { release.complete(Unit) }
        val before = container.scope.coroutineContext.job.children.filter { it.isActive }.toSet()
        flaky.failPutOf = failPut
        flaky.failRemoveOf = failRemove

        try {
            container.connect(B)
            fail("a failed switch must not report success")
        } catch (_: IllegalStateException) {
        }

        assertTrue(db.sessionDao().all().isEmpty())
        assertTrue(container.composer.keys.isEmpty()) // cleared with the tables, in the same step
        assertFalse(container.composer.job.children.any { it.isActive })
        val left = container.scope.coroutineContext.job.children.filter { it.isActive }.toSet()
        assertTrue("jobs left by the failed switch: ${left - before}", (left - before).isEmpty())
        flaky.failPutOf = null
        flaky.failRemoveOf = null
    }

    @Test
    fun ownerCommitFailingAfterTheClearLeavesAnEmptyComposer() = runTest {
        failAfterTheClear(failPut = "cache_base_url")
    }

    @Test
    fun markerCommitFailingAfterTheClearLeavesAnEmptyComposer() = runTest {
        failAfterTheClear(failRemove = SWITCH_MARKER)
    }

    @Test
    fun switchFailingBeforeTheClearKeepsTextUntilAConfirmedSwitch() = runTest {
        container.connect(A)
        val first = container.connected()!!
        addSession("apofis-a")
        first.composer.edit("apofis-a", "half a thought")
        first.composer.edit("apofis-b", "not stored")
        first.composer.send("apofis-b") { throw IllegalStateException("not stored") }!!.join()
        container.beforeSwitch = { throw IllegalStateException("setup failed") }
        try {
            container.connect(B)
            fail("a failed switch must not report success")
        } catch (_: IllegalStateException) {
        }
        container.beforeSwitch = {}
        assertEquals("half a thought", container.composer.current("apofis-a").draft)
        assertEquals("not stored", container.composer.current("apofis-b").unsent?.text)

        val c = Credentials("https://c.example/", "key-c")
        assertTrue(container.willClearCache(c.baseUrl)) // the confirmation is shown for C
        assertEquals("half a thought", container.composer.current("apofis-a").draft) // and nothing changed before it
        container.connect(c) // confirmed

        assertTrue(container.composer.keys.isEmpty())
        assertTrue(db.sessionDao().all().isEmpty())
    }

    /** A container on a database that fails the statements [faults] is told to; A's text is in its composer. */
    private suspend fun withTextOfA(faults: FaultyStatements): AppDatabase {
        val faulty = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).openHelperFactory(faults).allowMainThreadQueries().build()
        container = AppContainer(app, store) { faulty }
        container.connect(A)
        val first = container.connected()!!
        faulty.sessionDao().upsert(SessionEntity(
            key = "apofis-a", currentId = "apofis-a", lineageIds = "", title = null, pinned = false, archived = false,
            startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false,
            existsOnServer = false, hidden = false, // a local-only chat: its row exists only here
        ))
        first.composer.edit("apofis-a", "half a thought")
        first.composer.edit("apofis-b", "not stored")
        first.composer.send("apofis-b") { throw IllegalStateException("not stored") }!!.join()
        return faulty
    }

    @Test
    fun vacuumFailingAfterTheDeletesCommittedStillClearsTheComposer() = runTest {
        val faults = FaultyStatements()
        val faulty = withTextOfA(faults)
        faults.failVacuum = true

        container.connect(B) // VACUUM is best effort: the switch continues

        assertEquals(B, container.connected()!!.credentials)
        assertTrue(faulty.sessionDao().all().isEmpty())
        assertTrue(container.composer.keys.isEmpty())
        assertEquals("", container.composer.current("apofis-a").draft)
        assertNull(container.composer.current("apofis-b").unsent)

        val c = Credentials("https://c.example/", "key-c")
        assertTrue(container.willClearCache(c.baseUrl)) // C still asks, and there is nothing left to discard
        container.connect(c)
        assertEquals(c, container.connected()!!.credentials)
        assertTrue(container.composer.keys.isEmpty())
        faulty.close()
    }

    @Test
    fun deletesFailingLeaveTablesAndComposerIntact() = runTest {
        val faults = FaultyStatements()
        val faulty = withTextOfA(faults)
        faults.failDeletes = true

        try {
            container.connect(B)
            fail("a failed switch must not report success")
        } catch (_: SQLiteException) {
        }

        assertNull(container.connected()) // the marker rule: disconnected
        assertEquals(listOf("apofis-a"), faulty.sessionDao().all().map { it.key })
        assertEquals("half a thought", container.composer.current("apofis-a").draft)
        assertEquals("not stored", container.composer.current("apofis-b").unsent?.text)
        assertTrue(container.willClearCache(B.baseUrl)) // the next switch asks before anything is deleted
        faulty.close()
    }

    @Test
    fun failureWhileSwitchingServerEndsDisconnected() = runTest {
        container.connect(A)
        addSession("apofis-a")
        failNextKeyUse = true

        expectFailure(B)

        assertNull(container.connected())
        assertNull(store.load())
        assertTrue(db.sessionDao().all().isEmpty())
    }

    @Test
    fun interruptedSwitchForcesDisconnectedOnStart() = runTest {
        store.save(A)
        app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(SWITCH_MARKER, true).commit()

        val restarted = AppContainer(app, store) { db }

        assertNull(restarted.connected())
        assertNull(store.load())
        assertTrue(restarted.consumeSwitchFailure())
        assertFalse(restarted.consumeSwitchFailure())
        assertFalse(app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).contains(SWITCH_MARKER))
    }

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        repeat(200) {
            if (check()) return
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        fail("timed out waiting for $what")
    }

    private fun recordingServer(requests: MutableList<String>) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.url.encodedPath}"
                return if (request.method == "POST" && request.url.encodedPath == "/v1/runs") {
                    MockResponse.Builder().code(202).body(fixture("run_start.json")).addHeader("Content-Type", "application/json").build()
                } else {
                    MockResponse.Builder().code(404).build()
                }
            }
        }
        start()
    }

    @Test
    fun stopReturnsWithTheGateClosedWhileAResumeIsInFlight() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = recordingServer(requests)
        try {
            container.connect(Credentials(server.url("/").toString(), "k"))
            val c = container.connected()!!
            val inResume = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            container.afterResume = { // resumeAll has opened the gate; the resume has not returned yet
                inResume.complete(Unit)
                release.await()
            }
            container.onStart()
            inResume.await()
            addSession("apofis-a")
            db.activeRunDao().upsert(ActiveRunEntity("run_late", "apofis-a", null, "running", 1L))

            container.onStop()
            c.follower.onRunStarted("apofis-a", "run_late") // the first thing that can happen after ON_STOP returns

            assertTrue(c.follower.following.toString(), c.follower.following.isEmpty())
            release.complete(Unit)
            container.awaitLifecycle()
            withContext(Dispatchers.IO) { Thread.sleep(1_500) } // past the follower's 1 s first status read
            val seen = synchronized(requests) { requests.toList() }
            assertTrue(seen.toString(), seen.none { it.contains("run_late") })
        } finally {
            server.close()
        }
    }

    @Test
    fun aResumeThatOutlivesTheStopClosesTheGateAgain() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = recordingServer(requests)
        try {
            container.connect(Credentials(server.url("/").toString(), "k"))
            val c = container.connected()!!
            addSession("apofis-a")
            db.activeRunDao().upsert(ActiveRunEntity("run_stored", "apofis-a", null, "running", 1L))
            val inResume = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            container.beforeResume = {
                inResume.complete(Unit)
                release.await()
            }

            container.onStart()
            inResume.await()
            container.onStop() // after the resume checked it is started, before it opens the gate
            release.complete(Unit)
            container.awaitLifecycle()
            assertTrue(c.follower.following.toString(), c.follower.following.isEmpty()) // closed again

            // A run the send queue starts after the stop is stored but gets no stream.
            addSession("apofis-b")
            c.sendQueue.enqueue("apofis-b", "sent in the background")
            c.sendQueue.drain()
            withContext(Dispatchers.IO) { Thread.sleep(1_500) } // past the follower's 1 s first status read

            val seen = synchronized(requests) { requests.toList() }
            assertTrue(seen.toString(), "POST /v1/runs" in seen)
            assertTrue(c.follower.following.toString(), c.follower.following.isEmpty())
            // The stored run may be read in the moment the late resume had the gate open; the started one never is.
            assertTrue(seen.toString(), seen.none { it.contains("/events") || it.contains("run_ef57074dc1694db39e37045230b1425c") })
        } finally {
            server.close()
        }
    }

    @Test
    fun startWithAConnectionReschedulesQueuedSends() = runTest {
        container.connect(A)
        addSession("apofis-a")
        container.connected()!!.sendQueue.enqueue("apofis-a", "waiting")
        val work = WorkManager.getInstance(app)
        work.cancelAllWork().result.get()

        val restarted = AppContainer(app, store) { db }
        restarted.connected()

        eventually("send work") {
            work.getWorkInfosForUniqueWork(SendWorker.WORK_NAME).get().any { it.state == WorkInfo.State.ENQUEUED }
        }
    }

    @Test
    fun rowsOfAnUnknownOwnerAlwaysAsk() = runTest {
        addSession("apofis-left-behind") // no saved connection and no cache owner, as after an interrupted switch
        assertTrue(container.willClearCache(A.baseUrl))
    }

    @Test
    fun failingSetupStepEndsDisconnectedAndNotStuck() = runTest {
        container.connect(A)
        addSession("apofis-a")
        container.beforeSwitch = { throw IllegalStateException("setup failed") }

        try {
            container.connect(B)
            fail("a failed switch must not report success")
        } catch (_: IllegalStateException) {
        }

        assertNull(container.connected())
        assertNull(container.connection.value)
        assertNull(store.load())
        container.beforeSwitch = {}
        container.connect(B) // not stuck switching
        assertEquals(B, container.connected()!!.credentials)
    }

    @Test
    fun throwingCleanupStillEndsDisconnected() = runTest {
        val flaky = FlakyCacheApp(app)
        container = AppContainer(flaky, store) { db }
        container.connect(A)
        flaky.failCommits = true // the marker write fails, and so does removing it during cleanup

        try {
            container.connect(B)
            fail("a failed switch must not report success")
        } catch (_: IOException) {
        } catch (_: IllegalStateException) {
        }

        assertNull(container.connection.value) // the Connect screen shows
        assertNull(container.connected())
        assertNull(store.load())
        flaky.failCommits = false
        container.connect(B)
        assertEquals(B, container.connected()!!.credentials)
    }

    // Issue 16 row 8: a stored steer not yet sent (deferred by a delete the process did not survive) re-arms the worker.
    @Test
    fun deferredSteerRearmedAfterRestart() = runTest {
        container.connect(A)
        addSession("apofis-a")
        db.pendingSendDao().upsert(
            xyz.rycerz.apofis.db.PendingSendEntity( // qualified: this test is the only change to the file
                idempotencyKey = "k", sessionKey = "apofis-a", text = "also do this", requestBody = "{}", createdAt = 1L,
                state = "steering", runId = "run_1", error = null, attempted = false,
            ),
        )
        container.scope.coroutineContext.job.let { it.cancel(); it.join() } // the first process ends: none of its jobs outlives it
        val work = WorkManager.getInstance(app)
        work.cancelAllWork().result.get()

        val restarted = AppContainer(app, store) { db }
        try {
            restarted.connected()
            // WorkManager's own signal that the work is enqueued; the timeout is only a watchdog that fails.
            withContext(Dispatchers.Default) {
                kotlinx.coroutines.withTimeout(10_000) {
                    work.getWorkInfosForUniqueWorkFlow(SendWorker.WORK_NAME).first { infos -> infos.any { it.state == WorkInfo.State.ENQUEUED } }
                }
            }
        } finally {
            restarted.scope.coroutineContext.job.let { it.cancel(); it.join() } // before the database closes
        }
    }
}

/** The real app, except that commits to the cache preferences throw while [failCommits] is set. */
private class FlakyCacheApp(base: Application) : Application() {
    @Volatile var failCommits = false
    @Volatile var failPutOf: String? = null // a commit that writes this key throws
    @Volatile var failRemoveOf: String? = null // a commit that removes this key throws

    init {
        attachBaseContext(base)
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val real = super.getSharedPreferences(name, mode)
        if (name != CACHE_PREFS) return real
        return object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val e = real.edit()
                var put: String? = null
                var removed: String? = null
                return object : SharedPreferences.Editor by e {
                    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = also { e.putBoolean(key, value) }
                    override fun putString(key: String, value: String?): SharedPreferences.Editor = also { put = key; e.putString(key, value) }
                    override fun remove(key: String): SharedPreferences.Editor = also { removed = key; e.remove(key) }
                    override fun commit(): Boolean =
                        if (failCommits || (put != null && put == failPutOf) || (removed != null && removed == failRemoveOf)) {
                            throw IllegalStateException("disk")
                        } else {
                            e.commit()
                        }
                }
            }
        }
    }
}

/** Fails VACUUM or the table deletes while asked to, the way a storage error would. */
private class FaultyStatements : SupportSQLiteOpenHelper.Factory {
    @Volatile var failVacuum = false
    @Volatile var failDeletes = false
    private val real = FrameworkSQLiteOpenHelperFactory()

    private fun check(sql: String) {
        val s = sql.trimStart().uppercase()
        if ((failVacuum && s.startsWith("VACUUM")) || (failDeletes && s.startsWith("DELETE FROM"))) throw SQLiteException("disk I/O error")
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val h = real.create(configuration)
        return object : SupportSQLiteOpenHelper by h {
            override val writableDatabase: SupportSQLiteDatabase get() = wrap(h.writableDatabase)
            override val readableDatabase: SupportSQLiteDatabase get() = wrap(h.readableDatabase)
        }
    }

    private fun wrap(d: SupportSQLiteDatabase) = object : SupportSQLiteDatabase by d {
        override fun execSQL(sql: String) = check(sql).also { d.execSQL(sql) }
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) = check(sql).also { d.execSQL(sql, bindArgs) }
        override fun compileStatement(sql: String): SupportSQLiteStatement = check(sql).let { d.compileStatement(sql) }
    }
}
