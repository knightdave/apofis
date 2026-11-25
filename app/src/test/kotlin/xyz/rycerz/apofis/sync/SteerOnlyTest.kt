package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.buildRunBody
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity

private const val KEY = "apofis-a"

/**
 * A /steer row ([xyz.rycerz.apofis.db.PendingSendEntity.steerOnly]) is never sent as a run, on the paths submit does
 * not take itself: another drain's recovery of a stored steer, and a restart after its run ended. Its Retry is a message.
 */
@RunWith(RobolectricTestRunner::class)
class SteerOnlyTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var api: HermesApi
    private lateinit var queue: SendQueue

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        api = HermesApi(server.url("/").toString(), "secret-key")
        queue = SendQueue(api, db, { _, _ -> }, { 10_000_000L })
        runBlocking {
            db.sessionDao().upsert(
                SessionEntity(
                    key = KEY, currentId = KEY, lineageIds = "", title = null, pinned = false, archived = false, startedAt = 1.0,
                    lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true,
                ),
            )
            db.activeRunDao().upsert(ActiveRunEntity("run_1", KEY, null, "running", 1L))
        }
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun json(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    private suspend fun targets() = withContext(Dispatchers.IO) { List(server.requestCount) { server.takeRequest(5, TimeUnit.SECONDS)!!.target } }

    private suspend fun row() = db.pendingSendDao().observe(KEY).first().single()

    /** Stores a /steer of run_1 from a queue whose connection closes right after the commit (its own delivery never runs). */
    private suspend fun storedByADyingSubmit() {
        var checks = 0
        SendQueue(api, db, { _, _ -> }, { 10_000_000L }, active = { checks++ < 1 }).submit(KEY, "go left", steerRun = "run_1", steerOnly = true)
        val stored = row()
        assertEquals(PendingState.STEERING, stored.state)
        assertTrue(stored.steerOnly)
        assertFalse(stored.attempted)
    }

    @Test
    fun deliveredByAnotherDrainNeverQueues() = runTest {
        storedByADyingSubmit()
        json(409, """{"error":{"code":"run_not_accepting_steer","message":"Run is not accepting steer"}}""")

        assertTrue(queue.drain()) // another drain (the worker) recovers the stored steer

        val failed = row()
        assertEquals(PendingState.FAILED, failed.state)
        assertEquals(SendError.STEER_NOT_DELIVERED, failed.error)
        assertFalse("refused: not attempted", failed.attempted)
        assertNull(failed.runId)
        db.activeRunDao().delete("run_1") // the run ends: a queued row would start now
        assertTrue(queue.drain())
        assertEquals(listOf("/v1/runs/run_1/steer"), targets())

        // Retry sends it as an ordinary message: its stored run body, as a new turn, no confirmation.
        json(202, """{"run_id":"run_2","status":"started","replayed":false}""")
        assertEquals(RetryResult.STARTED, queue.retry(failed.idempotencyKey))
        assertFalse(row().steerOnly)
        assertTrue(queue.drain())
        val run = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS)!! }
        assertEquals("/v1/runs", run.target)
        assertEquals(buildRunBody(KEY, "go left"), run.body!!.utf8())
        assertEquals(PendingState.SENDING, row().state)
    }

    @Test
    fun deliveredByAnotherDrainNotFoundNeverQueues() = runTest {
        storedByADyingSubmit()
        json(404, """{"error":{"code":"run_not_found","message":"no such run"}}""")
        assertTrue(queue.drain())
        db.activeRunDao().delete("run_1")
        assertTrue(queue.drain())
        assertEquals(SendError.STEER_NOT_DELIVERED, row().error)
        assertEquals(listOf("/v1/runs/run_1/steer"), targets())
    }

    @Test
    fun afterRestartStartsNoRun() = runTest {
        storedByADyingSubmit()
        db.activeRunDao().delete("run_1") // the run ended before the restart

        assertTrue(queue.drain()) // the restarted app (or its worker)
        assertTrue(queue.drain())

        val failed = row()
        assertEquals(PendingState.FAILED, failed.state)
        assertEquals(SendError.STEER_NOT_DELIVERED, failed.error)
        assertFalse(failed.attempted)
        assertEquals(0, server.requestCount)
    }
}
