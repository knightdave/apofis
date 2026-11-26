package xyz.rycerz.apofis.db

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.notify.Notifier
import xyz.rycerz.apofis.notify.ensureChannels

private const val NAME = "migrate.db"

/**
 * Upgrades from every released schema, built exactly as exported in app/schemas, through the builder users get. Room
 * validates every table against the current entities when it opens a migrated file, so a migration that leaves the
 * schema different from the current version fails here.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        app.deleteDatabase(NAME)
    }

    private fun create(version: Int, seed: (SQLiteDatabase) -> Unit = {}) {
        app.deleteDatabase(NAME)
        val schema = JSONObject(File("schemas/xyz.rycerz.apofis.db.AppDatabase/$version.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(app.getDatabasePath(NAME).apply { parentFile!!.mkdirs() }, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL(
                "INSERT INTO SessionEntity (`key`, currentId, lineageIds, title, pinned, archived, startedAt, messageCount, stale, existsOnServer, hidden) " +
                    "VALUES ('apofis-a', 'apofis-a', '', 'Chat', 0, 0, 1.0, 1, 0, 1, 0)",
            )
            db.execSQL("INSERT INTO MessageEntity (sessionKey, position, id, role, content, timestamp) VALUES ('apofis-a', 0, 1, 'user', 'hi', 1.0)")
            db.execSQL(
                "INSERT INTO PendingSendEntity (idempotencyKey, sessionKey, text, requestBody, createdAt, state, runId, attempted) " +
                    "VALUES ('k', 'apofis-a', 'unsent', '{}', 1, 'sending', 'run_1', 1)",
            )
            db.execSQL(
                "INSERT INTO ActiveRunEntity (runId, sessionKey, lastSeq, status, startedAt, polling, stopRequested) " +
                    "VALUES ('run_1', 'apofis-a', 4, 'running', 9, 0, 1)",
            )
            seed(db)
            db.version = version
        }
    }

    private suspend fun assertKept(db: AppDatabase) {
        assertEquals("Chat", db.sessionDao().get("apofis-a")!!.title)
        val send = db.pendingSendDao().get("k")!!
        assertEquals("unsent", send.text)
        assertEquals("run_1", send.runId)
        assertTrue(send.attempted)
        assertEquals(ActiveRunEntity("run_1", "apofis-a", 4, "running", 9, polling = false, stopRequested = true), db.activeRunDao().get("run_1"))
        assertTrue(db.noticeOutboxDao().all(0).isEmpty())
        assertNull(db.noticeStateDao().get("apofis-a"))
    }

    @Test
    fun version1To4() = runTest {
        create(1)
        val db = openAppDatabase(app, NAME)
        try {
            assertKept(db)
            assertTrue(db.messageDao().rows("apofis-a").isEmpty()) // 1 to 2 drops the uncut transcripts
            assertTrue(db.sessionDao().get("apofis-a")!!.stale)
        } finally {
            db.close()
        }
    }

    @Test
    fun version2To4() = runTest {
        create(2)
        val db = openAppDatabase(app, NAME)
        try {
            assertKept(db)
            assertEquals(listOf("hi"), db.messageDao().rows("apofis-a").map { it.content })
            assertFalse(db.sessionDao().get("apofis-a")!!.stale)
            db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_1", "apofis-a", "run_1", 9, "FINISHED", 1, 2)) // the new table works
            assertNotNull(db.noticeOutboxDao().get(1, "run:run_1"))
            db.noticeStateDao().upsert(NoticeStateEntity("apofis-a", 1, 9, "run_1", "FINISHED", "run:run_1"))
            assertNotNull(db.noticeStateDao().get("apofis-a"))
        } finally {
            db.close()
        }
    }

    @Test
    fun productionBuilderHasFullChain() = runTest {
        val chains = listOf(
            2 to arrayOf(MIGRATION_1_2),
            3 to arrayOf(MIGRATION_1_2, MIGRATION_2_3),
            4 to arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4),
        )
        for ((version, chain) in chains) {
            create(version)
            val old = openAppDatabase(app, NAME, chain) // the list before the next version
            try {
                old.sessionDao().get("apofis-a")
                fail("opened a version $version file without a migration to ${version + 1}")
            } catch (_: IllegalStateException) {
                // Room refuses: no path to the current version and no destructive fallback
            } finally {
                old.close()
            }
        }
        create(2)
        val db = openAppDatabase(app, NAME)
        try {
            assertKept(db)
        } finally {
            db.close()
        }
        val container = File("src/main/kotlin/xyz/rycerz/apofis/AppContainer.kt").readText()
        assertTrue("AppContainer opens the database through openAppDatabase", container.contains("openAppDatabase(app)"))
    }

    @Test
    fun noDestructiveFallback() {
        val hits = File("src/main").walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("fallbackToDestructiveMigration") }.toList()
        assertTrue("destructive fallback in $hits", hits.isEmpty())
        assertTrue(File("schemas/xyz.rycerz.apofis.db.AppDatabase/4.json").isFile)
        assertTrue(File("schemas/xyz.rycerz.apofis.db.AppDatabase/5.json").isFile)
    }

    /** Version 3 rows in every table, as a user upgrading from Milestone 3 has them. */
    private fun seedVersion3(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO SessionEntity (`key`, currentId, lineageIds, title, pinned, archived, startedAt, lastActiveAt, messageCount, preview, stale, existsOnServer, hidden) VALUES " +
                "('apofis-h', 'apofis-h', '', 'Hidden', 0, 0, 2.0, NULL, 0, NULL, 0, 1, 1), " +
                "('apofis-r', 'apofis-r', '', NULL, 0, 1, 3.0, NULL, 0, NULL, 0, 0, 0), " +
                "('apofis-p', 'x3', 'x1,x2', 'Pinned', 1, 0, 4.0, 5.5, 3, 'pv', 1, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO PendingSendEntity (idempotencyKey, sessionKey, text, requestBody, createdAt, state, runId, error, attempted) " +
                "VALUES ('k2', 'apofis-r', 'q \"x\"', '{\"session_id\":\"apofis-r\",\"input\":\"q \\\"x\\\"\"}', 77, 'failed', NULL, 'expired_uncertain', 1)",
        )
        db.execSQL("INSERT INTO ReplyOutcomeEntity (sessionKey, afterMessageId, outcome, detail) VALUES ('apofis-a', 1, 'stopped', 'why')")
        db.execSQL(
            "INSERT INTO NoticeOutboxEntity (eventId, sessionKey, runId, runStartedAt, notice, generation, createdAt) " +
                "VALUES ('run:run_1', 'apofis-a', 'run_1', 9, 'FINISHED', 3, 11)",
        )
        db.execSQL(
            "INSERT INTO NoticeStateEntity (sessionKey, generation, runStartedAt, runId, notice, eventId, alerted) " +
                "VALUES ('apofis-p', 3, 8, 'run_0', 'REQUEST', 'req:1', '[\"e1\"]')",
        )
    }

    private fun session(key: String, currentId: String = key) =
        SessionEntity(key, currentId, "", null, false, false, 1.0, null, 0, null, false, true)

    @Test
    @Config(sdk = [37])
    fun version3To4() = runTest {
        create(3, ::seedVersion3)
        val db = openAppDatabase(app, NAME)
        try {
            val dao = db.sessionDao()
            assertEquals(session("apofis-a").copy(title = "Chat", messageCount = 1, createStarted = true), dao.get("apofis-a"))
            assertEquals(session("apofis-h").copy(title = "Hidden", startedAt = 2.0, hidden = true, createStarted = true), dao.get("apofis-h"))
            assertEquals(session("apofis-r").copy(archived = true, startedAt = 3.0, existsOnServer = false), dao.get("apofis-r"))
            assertEquals(
                SessionEntity("apofis-p", "x3", "x1,x2", "Pinned", true, false, 4.0, 5.5, 3, "pv", true, true, createStarted = true),
                dao.get("apofis-p"),
            )
            assertEquals(4, dao.all().size)
            assertEquals(listOf(MessageEntity("apofis-a", 0, 1, "user", null, "hi", null, null, null, null, 1.0, null)), db.messageDao().rows("apofis-a"))
            assertEquals(PendingSendEntity("k", "apofis-a", "unsent", "{}", 1, "sending", "run_1", null, attempted = true), db.pendingSendDao().get("k"))
            assertEquals(
                PendingSendEntity("k2", "apofis-r", "q \"x\"", """{"session_id":"apofis-r","input":"q \"x\""}""", 77, "failed", null, "expired_uncertain", attempted = true),
                db.pendingSendDao().get("k2"),
            )
            assertEquals(ActiveRunEntity("run_1", "apofis-a", 4, "running", 9, polling = false, stopRequested = true), db.activeRunDao().get("run_1"))
            assertEquals(listOf(ReplyOutcomeEntity("apofis-a", 1, "stopped", "why")), db.replyOutcomeDao().rows("apofis-a"))
            // The outbox replays on the next start, and the notice order keeps its alerted requests.
            assertEquals(listOf(NoticeOutboxEntity("run:run_1", "apofis-a", "run_1", 9, "FINISHED", 3, 11)), db.noticeOutboxDao().all(3))
            assertEquals(NoticeStateEntity("apofis-p", 3, 8, "run_0", "REQUEST", "req:1", """["e1"]"""), db.noticeStateDao().get("apofis-p"))
            assertEquals(emptyList<String>(), db.imageBindingDao().allDigests())
            assertEquals(emptyList<String>(), db.pendingSendDao().allImages().filter { it.isNotEmpty() })
            // The new table works.
            db.imageBindingDao().upsert(ImageBindingEntity("apofis-a", 1, "d1,d2"))
            assertEquals(listOf(ImageBindingEntity("apofis-a", 1, "d1,d2")), db.imageBindingDao().forSession("apofis-a"))

            // The production notifier replays the migrated outbox: the finish is posted once, silently, and consumed.
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            ensureChannels(app)
            val nm = app.getSystemService(NotificationManager::class.java)
            val notifier = Notifier(app, app.getSharedPreferences("migrate-notify", Context.MODE_PRIVATE), db, { 3L }, { null }, backgroundScope)
            notifier.replayOutbox(3)
            val shown = nm.activeNotifications.single()
            assertEquals("apofis-a", shown.tag)
            assertEquals(app.getString(R.string.notif_reply_finished), shown.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(shown.notification.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && shown.notification.group != null)
            assertTrue(db.noticeOutboxDao().all(3).isEmpty())
            assertEquals("run:run_1", db.noticeStateDao().get("apofis-a")!!.eventId)
            nm.cancelAll()
            notifier.replayOutbox(3)
            assertTrue(nm.activeNotifications.isEmpty()) // nothing left to post
        } finally {
            db.close()
        }
    }

    @Test
    fun createStartedFollowsExistsOnServerFromEveryVersion() = runTest {
        for (version in 1..3) {
            create(version) { it.execSQL("INSERT INTO SessionEntity (`key`, currentId, lineageIds, pinned, archived, startedAt, messageCount, stale, existsOnServer, hidden) VALUES ('apofis-l', 'apofis-l', '', 0, 0, 1.0, 0, 0, 0, 0)") }
            val db = openAppDatabase(app, NAME)
            try {
                assertTrue(db.sessionDao().get("apofis-a")!!.createStarted)
                assertFalse(db.sessionDao().get("apofis-l")!!.createStarted)
                assertEquals(PendingSendEntity("k", "apofis-a", "unsent", "{}", 1, "sending", "run_1", null, attempted = true), db.pendingSendDao().get("k"))
            } finally {
                db.close()
            }
        }
    }

    /** The eight tables of version 4, each with its primary key columns. */
    private val v4Tables = mapOf(
        "SessionEntity" to "`key`",
        "MessageEntity" to "sessionKey, position",
        "PendingSendEntity" to "idempotencyKey",
        "ActiveRunEntity" to "runId",
        "ReplyOutcomeEntity" to "sessionKey, afterMessageId",
        "NoticeOutboxEntity" to "generation, eventId",
        "NoticeStateEntity" to "sessionKey",
        "ImageBindingEntity" to "sessionKey, messageId",
    )

    /** Every row of every version 4 table, each column by name with its stored value. */
    private fun v4Rows(query: (String) -> Cursor) = v4Tables.mapValues { (table, key) ->
        query("SELECT * FROM $table ORDER BY $key").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        (0 until c.columnCount).map { i ->
                            c.getColumnName(i) to when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).toList()
                                else -> c.getString(i)
                            }
                        },
                    )
                }
            }
        }
    }

    /** The first emission, bounded in real time: Room emits from its own threads, so a virtual clock would not do. */
    private suspend fun <T> Flow<T>.firstWithin(): T = withContext(Dispatchers.Default) { withTimeout(10_000) { first() } }

    private val d1 = "a".repeat(64)
    private val d2 = "b".repeat(64)

    /**
     * Version 4 rows in every table: every column with a default holds another value in some row, and createStarted
     * differs from existsOnServer in both directions, so a migration that rewrites any of them is seen.
     */
    private fun seedVersion4(db: SQLiteDatabase) {
        db.execSQL("UPDATE SessionEntity SET createStarted = 0, model = 'm1', provider = 'p1', reasoning = 'high', fast = 1 WHERE `key` = 'apofis-a'")
        db.execSQL("UPDATE MessageEntity SET droppedToolCalls = 3, shortened = 1 WHERE sessionKey = 'apofis-a'")
        db.execSQL("UPDATE PendingSendEntity SET images = '$d1,$d2', steerOnly = 1 WHERE idempotencyKey = 'k'")
        db.execSQL(
            "INSERT INTO SessionEntity (`key`, currentId, lineageIds, title, pinned, archived, startedAt, messageCount, stale, existsOnServer, hidden, createStarted) " +
                "VALUES ('apofis-n', 'apofis-n', '', NULL, 0, 0, 2.0, 0, 0, 0, 0, 1)",
        )
        db.execSQL("INSERT INTO ReplyOutcomeEntity (sessionKey, afterMessageId, outcome, detail) VALUES ('apofis-a', 1, 'stopped', 'why')")
        db.execSQL(
            "INSERT INTO NoticeOutboxEntity (eventId, sessionKey, runId, runStartedAt, notice, generation, createdAt) " +
                "VALUES ('run:run_1', 'apofis-a', 'run_1', 9, 'FINISHED', 3, 11)",
        )
        db.execSQL(
            "INSERT INTO NoticeStateEntity (sessionKey, generation, runStartedAt, runId, notice, eventId, alerted) " +
                "VALUES ('apofis-a', 3, 8, 'run_0', 'REQUEST', 'req:1', '[\"e1\"]')",
        )
        db.execSQL("INSERT INTO ImageBindingEntity (sessionKey, messageId, digests) VALUES ('apofis-a', 1, '$d1,$d2')")
    }

    @Test
    fun version4To5() = runTest {
        var before: Map<String, List<List<Pair<String, Any?>>>> = emptyMap()
        create(4) {
            seedVersion4(it)
            before = v4Rows { sql -> it.rawQuery(sql, null) }
        }
        assertEquals(listOf(2, 1, 1, 1, 1, 1, 1, 1), before.values.map { it.size }) // the seed reached every table
        val db = openAppDatabase(app, NAME)
        try {
            assertEquals(before, v4Rows { sql -> db.openHelper.readableDatabase.query(sql) })
            val dao = db.sessionDao()
            assertEquals(session("apofis-a").copy(title = "Chat", messageCount = 1, model = "m1", provider = "p1", reasoning = "high", fast = true), dao.get("apofis-a"))
            assertEquals(session("apofis-n").copy(startedAt = 2.0, existsOnServer = false, createStarted = true), dao.get("apofis-n"))
            assertEquals(emptyList<AgentFileEntity>(), db.agentFileDao().observe("apofis-a").firstWithin()) // the new table exists, empty
            assertEquals(emptyList<String>(), db.agentFileDao().digests())
        } finally {
            db.close()
        }
    }

    @Test
    fun agentFileBindingsRoundTrip() = runTest {
        create(4)
        val db = openAppDatabase(app, NAME)
        try {
            val dao = db.agentFileDao()
            val path = "/srv/out/chart.png"
            val one = AgentFileEntity("apofis-a", 1, path, d1)
            val two = AgentFileEntity("apofis-a", 2, path, d1) // another reply naming the same picture, the same bytes
            dao.insert(one)
            dao.insert(two)
            assertEquals(listOf(one, two), dao.observe("apofis-a").firstWithin())
            assertEquals(one, dao.get("apofis-a", 1, path))
            assertEquals(two, dao.get("apofis-a", 2, path))
            dao.insert(one.copy(digest = "c".repeat(64)))
            assertEquals(one, dao.get("apofis-a", 1, path)) // an existing binding wins
            assertEquals(0, dao.deleteIf("apofis-a", 1, path, "other"))
            assertEquals(one, dao.get("apofis-a", 1, path))
            assertEquals(1, dao.deleteIf("apofis-a", 1, path, d1))
            assertNull(dao.get("apofis-a", 1, path))
            assertEquals(two, dao.get("apofis-a", 2, path)) // only the named reply's binding went
            assertEquals(listOf(d1), dao.digests())
            assertEquals(listOf(two), dao.observe("apofis-a").firstWithin())
            dao.insert(AgentFileEntity("apofis-gone", 1, path, d2)) // no chat row
            dao.deleteOrphans()
            assertEquals(listOf(d1), dao.digests())
            assertEquals(listOf(two), dao.observe("apofis-a").firstWithin())
        } finally {
            db.close()
        }
    }
}
