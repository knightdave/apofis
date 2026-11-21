package xyz.rycerz.apofis.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NAME = "migrate.db"

/**
 * Upgrades from every released schema, built exactly as exported in app/schemas, through the builder users get. Room
 * validates every table against the current entities when it opens a migrated file, so a migration that leaves the
 * schema different from version 3 fails here.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        app.deleteDatabase(NAME)
    }

    private fun create(version: Int) {
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
        assertTrue(db.noticeOutboxDao().all().isEmpty())
    }

    @Test
    fun version1To3() = runTest {
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
    fun version2To3() = runTest {
        create(2)
        val db = openAppDatabase(app, NAME)
        try {
            assertKept(db)
            assertEquals(listOf("hi"), db.messageDao().rows("apofis-a").map { it.content })
            assertFalse(db.sessionDao().get("apofis-a")!!.stale)
            db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:run_1", "apofis-a", "run_1", 9, "FINISHED", 1, 2)) // the new table works
            assertNotNull(db.noticeOutboxDao().get("run:run_1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun productionBuilderHasFullChain() = runTest {
        create(2)
        val old = openAppDatabase(app, NAME, arrayOf(MIGRATION_1_2)) // the list before version 3
        try {
            old.sessionDao().get("apofis-a")
            fail("opened a version 2 file without a migration to 3")
        } catch (_: IllegalStateException) {
            // Room refuses: no path from 2 to 3 and no destructive fallback
        } finally {
            old.close()
        }
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
        assertTrue(File("schemas/xyz.rycerz.apofis.db.AppDatabase/3.json").isFile)
    }
}
