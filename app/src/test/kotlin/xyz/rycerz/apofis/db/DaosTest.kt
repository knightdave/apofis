package xyz.rycerz.apofis.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaosTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun session(
        key: String,
        currentId: String = key,
        lineageIds: String = "",
        pinned: Boolean = false,
        archived: Boolean = false,
        lastActiveAt: Double? = null,
        existsOnServer: Boolean = true,
        title: String? = null,
    ) = SessionEntity(
        key = key, currentId = currentId, lineageIds = lineageIds, title = title, pinned = pinned,
        archived = archived, startedAt = 1.0, lastActiveAt = lastActiveAt, messageCount = 0,
        preview = null, stale = false, existsOnServer = existsOnServer,
    )

    private fun message(sessionKey: String, position: Int, id: Long = position.toLong(), content: String = "m$position") =
        MessageEntity(
            sessionKey = sessionKey, position = position, id = id, role = "user", displayKind = null,
            content = content, reasoning = null, toolCallsJson = null, toolCallId = null, toolName = null,
            timestamp = position.toDouble(), finishReason = null,
        )

    @Test
    fun replaceSessionIsAtomicAndOrdered() = runTest {
        val dao = db.messageDao()
        dao.replaceSession("s", listOf(message("s", 2), message("s", 0), message("s", 1)))
        dao.replaceSession("other", listOf(message("other", 0, id = 99)))

        assertEquals(listOf(0, 1, 2), dao.observe("s").first().map { it.position })
        assertEquals(2L, dao.maxId("s"))
        assertNull(dao.maxId("missing"))

        // A failing insert (duplicate primary key) must roll back the delete too.
        val failed = runCatching { dao.replaceSession("s", listOf(message("s", 5, content = "new"), message("s", 5))) }
        assertTrue(failed.exceptionOrNull() is SQLiteConstraintException)
        assertEquals(listOf("m0", "m1", "m2"), dao.observe("s").first().map { it.content })

        dao.replaceSession("s", listOf(message("s", 0, id = 10, content = "fresh")))
        assertEquals(listOf("fresh"), dao.observe("s").first().map { it.content })
        assertEquals(listOf(99L), dao.observe("other").first().map { it.id })
    }

    @Test
    fun replaceServerSessionsKeepsLocalOnlyChats() = runTest {
        val dao = db.sessionDao()
        dao.upsert(session("gone"))
        dao.upsert(session("kept", title = "old"))
        dao.upsert(session("local", existsOnServer = false))
        db.messageDao().replaceSession("gone", listOf(message("gone", 0)))

        dao.replaceServerSessions(listOf(session("kept", title = "new"), session("archived", archived = true), session("added")))

        assertNull(dao.get("gone"))
        assertEquals("new", dao.get("kept")?.title)
        assertEquals(false, dao.get("local")?.existsOnServer)
        assertEquals(true, dao.get("archived")?.archived)
        assertEquals("added", dao.get("added")?.key)
        assertEquals(emptyList<MessageEntity>(), db.messageDao().observe("gone").first())
    }

    @Test
    fun replaceServerSessionsKeepsSessionsWithPendingSends() = runTest {
        val dao = db.sessionDao()
        val queued = PendingSendEntity("p1", "q", "hello there", """{"input":"hello there"}""", 7L, "queued", null, null)
        val failed = PendingSendEntity("p2", "f", "retry me", """{"input":"retry me"}""", 8L, "failed", "run-x", "boom")
        dao.upsert(session("q"))
        dao.upsert(session("f"))
        db.pendingSendDao().upsert(queued)
        db.pendingSendDao().upsert(failed)

        dao.replaceServerSessions(emptyList())

        assertEquals("q", dao.get("q")?.key)
        assertEquals("f", dao.get("f")?.key)
        assertEquals(listOf(queued), db.pendingSendDao().observe("q").first())
        assertEquals(listOf(failed), db.pendingSendDao().observe("f").first())
    }

    @Test
    fun replaceServerSessionsKeepsSessionsWithActiveRuns() = runTest {
        val dao = db.sessionDao()
        val run = ActiveRunEntity("r1", "running", 4L, "running", 1L)
        dao.upsert(session("running"))
        dao.upsert(session("idle"))
        db.activeRunDao().upsert(run)
        db.messageDao().replaceSession("idle", listOf(message("idle", 0)))
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("idle", 0L, "interrupted", null))

        dao.replaceServerSessions(emptyList())

        assertEquals("running", dao.get("running")?.key)
        assertEquals(run, db.activeRunDao().forSession("running"))
        assertNull(dao.get("idle"))
        assertEquals(emptyList<MessageEntity>(), db.messageDao().observe("idle").first())
        assertEquals(emptyList<ReplyOutcomeEntity>(), db.replyOutcomeDao().observe("idle").first())
    }

    @Test
    fun replaceSessionRejectsForeignRows() = runTest {
        val dao = db.messageDao()
        dao.replaceSession("s", listOf(message("s", 0)))

        val result = runCatching { dao.replaceSession("s", listOf(message("s", 1), message("t", 0))) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(0), dao.observe("s").first().map { it.position })
        assertEquals(emptyList<MessageEntity>(), dao.observe("t").first())
    }

    @Test
    fun findByAnyIdIgnoresEmptyId() = runTest {
        db.sessionDao().upsert(session("k", currentId = "c", lineageIds = ""))
        db.sessionDao().upsert(session("k2", currentId = "d", lineageIds = "a,,b"))

        assertNull(db.sessionDao().findByAnyId(""))
    }

    @Test
    fun findByAnyIdMatchesLineage() = runTest {
        val dao = db.sessionDao()
        dao.upsert(session("k1", currentId = "c_3", lineageIds = "a_1,b_2,c_3"))
        dao.upsert(session("k2", currentId = "x_1", lineageIds = "x_1"))

        assertEquals("k1", dao.findByAnyId("k1")?.key)
        assertEquals("k1", dao.findByAnyId("c_3")?.key)
        assertEquals("k1", dao.findByAnyId("a_1")?.key)
        assertEquals("k1", dao.findByAnyId("b_2")?.key)
        assertEquals("k2", dao.findByAnyId("x_1")?.key)
        assertNull(dao.findByAnyId("b"))
        assertNull(dao.findByAnyId("a_1,b_2"))
        assertNull(dao.findByAnyId("a?1"))
        assertNull(dao.findByAnyId("%"))
    }

    @Test
    fun deleteSessionCascades() = runTest {
        db.sessionDao().upsert(session("s"))
        db.sessionDao().upsert(session("t"))
        for (key in listOf("s", "t")) {
            db.messageDao().replaceSession(key, listOf(message(key, 0)))
            db.pendingSendDao().upsert(PendingSendEntity("p-$key", key, "hi", "{}", 1L, "queued", null, null))
            db.activeRunDao().upsert(ActiveRunEntity("r-$key", key, null, "running", 1L))
            db.replyOutcomeDao().upsert(ReplyOutcomeEntity(key, 0L, "interrupted", null))
        }

        db.sessionDao().delete("s")

        assertNull(db.sessionDao().get("s"))
        assertEquals(emptyList<MessageEntity>(), db.messageDao().observe("s").first())
        assertEquals(emptyList<PendingSendEntity>(), db.pendingSendDao().observe("s").first())
        assertNull(db.activeRunDao().forSession("s"))
        assertEquals(emptyList<ReplyOutcomeEntity>(), db.replyOutcomeDao().observe("s").first())

        assertEquals(1, db.messageDao().observe("t").first().size)
        assertEquals(listOf("p-t"), db.pendingSendDao().allQueued().map { it.idempotencyKey })
        assertEquals(listOf("r-t"), db.activeRunDao().all().map { it.runId })
        assertEquals(1, db.replyOutcomeDao().observe("t").first().size)
    }

    @Test
    fun observeSessionsFiltersArchived() = runTest {
        val dao = db.sessionDao()
        dao.upsert(session("old", lastActiveAt = 10.0))
        dao.upsert(session("new", lastActiveAt = 30.0))
        dao.upsert(session("pinnedOld", pinned = true, lastActiveAt = 5.0))
        dao.upsert(session("mid", lastActiveAt = 20.0))
        dao.upsert(session("arch", archived = true, lastActiveAt = 50.0))

        assertEquals(listOf("pinnedOld", "new", "mid", "old"), dao.observe(archived = false).first().map { it.key })
        assertEquals(listOf("arch"), dao.observe(archived = true).first().map { it.key })
    }

    @Test
    fun forRunListsOnlyStartedAndSteeredRows() = runTest {
        val dao = db.pendingSendDao()
        fun send(key: String, state: String, runId: String?) = PendingSendEntity(key, "s", "t", "{}", 1L, state, runId, null)
        dao.upsert(send("started", "sending", "run_1"))
        dao.upsert(send("steered", "steered", "run_1"))
        dao.upsert(send("failedSteer", "failed", null))
        dao.upsert(send("otherRun", "sending", "run_2"))
        dao.upsert(send("cutOffSteer", "steering", "run_1"))

        assertEquals(listOf("started", "steered"), dao.forRun("run_1").map { it.idempotencyKey }.sorted())
    }

    @Test
    fun userRowsListsOnlyUserMessages() = runTest {
        val dao = db.messageDao()
        dao.replaceSession("s", listOf(message("s", 0), message("s", 1).copy(role = "assistant"), message("s", 2)))
        dao.replaceSession("t", listOf(message("t", 0)))
        assertEquals(listOf(0, 2), dao.userRows("s").map { it.position })
    }

    @Test
    fun activeRunUpdatesNeverRecreateOrRewind() = runTest {
        val dao = db.activeRunDao()
        dao.updateLastSeq("gone", 5)
        dao.setStatus("gone", "stopping")
        dao.setStopRequested("gone")
        dao.setPolling("gone")
        assertTrue(dao.all().isEmpty())

        dao.upsert(ActiveRunEntity("run_1", "s", null, "running", 1L))
        dao.updateLastSeq("run_1", 7)
        dao.updateLastSeq("run_1", 3)
        dao.setStatus("run_1", "stopping")
        dao.setStatus("run_1", "running") // the status mirrors the server, also back from stopping
        dao.setStopRequested("run_1")
        dao.setPolling("run_1")
        val row = dao.all().single()
        assertEquals(7L, row.lastSeq)
        assertEquals("running", row.status)
        assertTrue(row.polling)
        assertTrue(row.stopRequested)
        assertEquals(row, dao.get("run_1"))

        dao.upsert(ActiveRunEntity("run_2", "t", null, "running", 1L))
        dao.setStatus("run_2", "queued")
        assertEquals("queued", dao.forSession("t")!!.status)
        assertEquals(false, dao.forSession("t")!!.polling)
        assertEquals(false, dao.forSession("t")!!.stopRequested)

        dao.setStopping("run_2")
        assertEquals("stopping", dao.forSession("t")!!.status)
        dao.setStatus("run_2", "completed")
        dao.setStopping("run_2") // a terminal status is final
        assertEquals("completed", dao.forSession("t")!!.status)
        dao.setStopping("gone")
        assertNull(dao.get("gone"))
    }
}
