package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentLinkedQueue
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.ReplyOutcomeEntity
import xyz.rycerz.apofis.db.SessionEntity

/** The drawer's Delete: one DELETE per recorded server id, newest first and the key last, local rows only after all. */
@RunWith(RobolectricTestRunner::class)
class ChatDeleteTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val answers = ConcurrentLinkedQueue<MockResponse>()
    private var taken = 0

    // Each afterDelete call records how many rows of KEY it saw, read synchronously at the moment of the call.
    private val afterDeleteSaw = mutableListOf<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        // An unexpected request gets a 500 at once instead of waiting for an answer that never comes.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answers.poll() ?: MockResponse.Builder().code(500).build()
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(
            HermesApi(server.url("/").toString(), "secret-key"), db, now = { 1_000L },
            afterDelete = { afterDeleteSaw += rowsOf(KEY) },
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun respond(code: Int, body: String) =
        answers.add(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    private fun deleted(id: String) = respond(200, """{"object":"hermes.session.deleted","id":"$id","deleted":true}""")

    private fun notFound(id: String) = respond(
        404,
        """{"error":{"message":"Session not found: $id","type":"invalid_request_error","param":null,"code":"session_not_found"}}""",
    )

    private fun session(currentId: String, lineageIds: String, existsOnServer: Boolean = true, createStarted: Boolean = false) =
        SessionEntity(
            key = KEY, currentId = currentId, lineageIds = lineageIds, title = "t", pinned = false, archived = false,
            startedAt = 1.0, lastActiveAt = 2.0, messageCount = 1, preview = null, stale = false,
            existsOnServer = existsOnServer, createStarted = createStarted,
        )

    /** The row of [KEY] with one message, one unsent message and one outcome. */
    private suspend fun seed(row: SessionEntity) {
        db.sessionDao().upsert(row)
        db.messageDao().insertAll(
            listOf(MessageEntity(KEY, 0, 1L, "user", null, "hi", null, null, null, null, 1.0, null)),
        )
        db.pendingSendDao().upsert(
            PendingSendEntity(
                idempotencyKey = "p1", sessionKey = KEY, text = "unsent", requestBody = "{}", createdAt = 5L,
                state = "queued", runId = null, error = null,
            ),
        )
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity(KEY, 1L, "failed", null))
    }

    /** Rows of [key] in every table a Delete clears. */
    private fun rowsOf(key: String): Int {
        val sql = "SELECT (SELECT COUNT(*) FROM SessionEntity WHERE `key` = ?) + " +
            "(SELECT COUNT(*) FROM MessageEntity WHERE sessionKey = ?) + " +
            "(SELECT COUNT(*) FROM PendingSendEntity WHERE sessionKey = ?) + " +
            "(SELECT COUNT(*) FROM ActiveRunEntity WHERE sessionKey = ?) + " +
            "(SELECT COUNT(*) FROM ReplyOutcomeEntity WHERE sessionKey = ?)"
        return db.openHelper.readableDatabase.query(sql, arrayOf(key, key, key, key, key)).use { it.moveToFirst(); it.getInt(0) }
    }

    /** The ids of the requests since the last call; each must be a DELETE. */
    private fun deletedPaths(): List<String> = List(server.requestCount - taken) {
        taken++
        server.takeRequest().also { assertEquals("DELETE", it.method) }.url.encodedPath.removePrefix("/api/sessions/")
    }

    @Test
    fun deletesNewestFirstRootLast() = runTest {
        seed(session(currentId = "s2", lineageIds = "s0,s1"))
        assertEquals(listOf("s2", "s1", "s0", KEY), deleteTargets(session(currentId = "s2", lineageIds = "s0,s1")))
        listOf("s2", "s1", "s0", KEY).forEach(::deleted)
        repo.delete(KEY)
        assertEquals(listOf("s2", "s1", "s0", KEY), deletedPaths())
        assertEquals(0, rowsOf(KEY))
        assertEquals(listOf(0), afterDeleteSaw)
    }

    @Test
    fun keyInTheLineageGoesLastOnce() {
        // A seeded or server-sent lineage often holds the key itself (root first): it still goes out once, last.
        assertEquals(listOf("s2", "s1", "s0", KEY), deleteTargets(session(currentId = "s2", lineageIds = "$KEY,s0,s1")))
    }

    @Test
    fun rootAsCurrentWithPriorSegments() = runTest {
        seed(session(currentId = KEY, lineageIds = "s0,s1"))
        listOf("s1", "s0", KEY).forEach(::deleted)
        repo.delete(KEY)
        assertEquals(listOf("s1", "s0", KEY), deletedPaths())
        assertEquals(0, rowsOf(KEY))
    }

    @Test
    fun stopsAtFirstInexactAnswer() = runTest {
        seed(session(currentId = "s2", lineageIds = "s0,s1"))
        val before = rowsOf(KEY)
        deleted("s2")
        deleted("other") // s1 answers an object for another id
        try {
            repo.delete(KEY)
            fail("expected ApiError.Protocol")
        } catch (e: ApiError) {
            assertEquals(ApiError.Protocol, e)
        }
        assertEquals(listOf("s2", "s1"), deletedPaths()) // zero DELETE of s0 and the key
        assertEquals(4, before)
        assertEquals(before, rowsOf(KEY))
        assertNotNull(db.sessionDao().get(KEY))
        assertEquals(emptyList<Int>(), afterDeleteSaw)

        // A second Delete sends every target again; the id the server already deleted answers session_not_found.
        notFound("s2")
        listOf("s1", "s0", KEY).forEach(::deleted)
        repo.delete(KEY)
        assertEquals(listOf("s2", "s1", "s0", KEY), deletedPaths())
        assertEquals(0, rowsOf(KEY))
        assertEquals(listOf(0), afterDeleteSaw)
        assertEquals(0, answers.size)
    }

    @Test
    fun switchingServersMidwayStopsTheSequence() = runTest {
        seed(session(currentId = "s2", lineageIds = "s0,s1"))
        // The app switches servers once the first DELETE went out: no further request, no local change.
        val closing = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), db, active = { server.requestCount == 0 })
        deleted("s2")
        try {
            closing.delete(KEY)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("closed", e.message)
        }
        assertEquals(listOf("s2"), deletedPaths())
        assertEquals(4, rowsOf(KEY))
    }

    @Test
    fun lostCreateAnswerOrdinaryChat() = runTest {
        seed(session(currentId = KEY, lineageIds = "", existsOnServer = false, createStarted = true))
        notFound(KEY)
        repo.delete(KEY)
        assertEquals(listOf(KEY), deletedPaths())
        assertEquals(0, rowsOf(KEY))
    }

    @Test
    fun neverCreatedChatIsLocalOnly() = runTest {
        seed(session(currentId = KEY, lineageIds = "", existsOnServer = false, createStarted = false))
        repo.delete(KEY)
        assertEquals(0, server.requestCount)
        assertNull(db.sessionDao().get(KEY))
        assertEquals(0, rowsOf(KEY))
    }

    @Test
    fun afterDeleteRunsOnlyAfterRemoval() = runTest {
        seed(session(currentId = KEY, lineageIds = ""))
        respond(409, """{"error":{"message":"busy","type":"invalid_request_error","param":null,"code":"session_active_turn"}}""")
        try {
            repo.delete(KEY)
            fail("expected ApiError.Conflict")
        } catch (e: ApiError) {
            assertEquals(ApiError.Conflict("session_active_turn"), e)
        }
        assertEquals(emptyList<Int>(), afterDeleteSaw)
        deleted(KEY)
        repo.delete(KEY)
        assertEquals(listOf(0), afterDeleteSaw)
    }

    private companion object {
        const val KEY = "apofis-r"
    }
}
