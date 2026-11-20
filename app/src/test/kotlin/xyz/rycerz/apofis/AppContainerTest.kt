package xyz.rycerz.apofis

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.first
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
import xyz.rycerz.apofis.sync.SendWorker

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
}
