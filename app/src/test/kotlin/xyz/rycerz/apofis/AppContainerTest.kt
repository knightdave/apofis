package xyz.rycerz.apofis

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
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
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials

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

    @Test
    fun switchingServerClosesTheOldInstanceThenClearsTheCache() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")
        assertTrue(container.willClearCache(B.baseUrl))
        assertFalse(container.willClearCache(A.baseUrl))

        container.connect(B)

        val now = container.connected()!!
        assertFalse(old.isOpen)
        assertNotSame(old, now)
        assertEquals(B, now.credentials)
        assertEquals(now, container.connection.value)
        assertTrue(db.sessionDao().all().isEmpty())
        assertEquals(B, store.load())

        // The old instance's queue can no longer send, even a row written for the new server.
        addSession("apofis-b")
        val key = now.sendQueue.enqueue("apofis-b", "for server B")
        val row = db.pendingSendDao().get(key)
        assertFalse(old.sendQueue.drain())
        assertEquals(row, db.pendingSendDao().get(key))
    }

    @Test
    fun anyCachedRowAsksForConfirmation() = runTest {
        container.connect(A)
        db.activeRunDao().upsert(ActiveRunEntity("run_1", "apofis-a", null, "running", 1L))
        assertTrue(container.willClearCache(B.baseUrl))
    }

    @Test
    fun newKeyForTheSameServerKeepsTheCache() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")

        container.connect(A.copy(apiKey = "key-a2"))

        assertFalse(old.isOpen)
        assertEquals("key-a2", container.connected()!!.credentials.apiKey)
        assertEquals(1, db.sessionDao().all().size)
    }

    @Test
    fun failureAfterClearingEndsDisconnected() = runTest {
        container.connect(A)
        addSession("apofis-a")
        failNextKeyUse = true

        try {
            container.connect(B)
            fail("a failed write must not report success")
        } catch (_: GeneralSecurityException) {
        }

        assertNull(container.connected())
        assertNull(container.connection.value)
        assertNull(store.load())
        assertTrue(db.sessionDao().all().isEmpty())
        assertTrue(container.willClearCache(A.baseUrl).not())
    }

    @Test
    fun failureBeforeAnythingDestructiveStaysOnTheOldServer() = runTest {
        container.connect(A)
        val old = container.connected()!!
        addSession("apofis-a")
        failNextKeyUse = true

        try {
            container.connect(A.copy(apiKey = "key-a2"))
            fail("a failed write must not report success")
        } catch (_: GeneralSecurityException) {
        }

        val now = container.connected()!!
        assertTrue(now.isOpen)
        assertNotSame(old, now)
        assertEquals(A, now.credentials)
        assertEquals(A, store.load())
        assertEquals(1, db.sessionDao().all().size)
    }
}
