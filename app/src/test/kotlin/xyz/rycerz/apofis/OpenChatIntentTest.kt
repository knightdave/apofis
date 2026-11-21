package xyz.rycerz.apofis

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeOutboxEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.notify.Notice
import xyz.rycerz.apofis.notify.chatUri
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials

/** A notification tap opens only a chat of this phone's current connection; anything else is ignored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class OpenChatIntentTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var container: AppContainer

    @Before
    fun setUp() = runTest {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container = AppContainer(app, CredentialStore(app) { key }) { db }
        container.notifier.ensureChannels()
        session("apofis-a")
        session("apofis-arch", archived = true)
        session("apofis-hidden", hidden = true)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun session(k: String, archived: Boolean = false, hidden: Boolean = false) = db.sessionDao().upsert(
        SessionEntity(k, k, "", "Title $k", pinned = false, archived = archived, startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true, hidden = hidden),
    )

    private fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri).setClass(app, MainActivity::class.java)
    private fun view(uri: String) = view(Uri.parse(uri))

    private suspend fun post(k: String) =
        container.notifier.notice(k, "run_$k", 1, Notice.FINISHED, "run:run_$k", container.epoch(), replay = false)

    @Test
    fun currentGenerationAndExistingKeySelectsAndCancels() = runTest {
        post("apofis-a")
        assertEquals(1, nm.activeNotifications.size)

        assertTrue(container.openChat(view(chatUri(container.epoch(), "apofis-a"))))

        assertEquals("apofis-a", container.chatToOpen.value)
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(container.openChat(view(chatUri(container.epoch(), "apofis-arch")))) // archived chats open as from the drawer
    }

    @Test
    fun shownChatCancelsAndSuppresses() = runTest {
        post("apofis-a")
        container.chatShown("apofis-a", true)
        assertTrue(nm.activeNotifications.isEmpty())
        container.notifier.notice("apofis-a", "run_2", 2, Notice.FINISHED, "run:run_2", container.epoch(), replay = false)
        assertTrue(nm.activeNotifications.isEmpty())

        container.chatShown("apofis-b", false) // another chat's screen going away changes nothing
        container.notifier.notice("apofis-a", "run_3", 3, Notice.FINISHED, "run:run_3", container.epoch(), replay = false)
        assertTrue(nm.activeNotifications.isEmpty())

        container.chatShown("apofis-a", false) // the activity stopped
        container.notifier.notice("apofis-a", "run_4", 4, Notice.FINISHED, "run:run_4", container.epoch(), replay = false)
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun everythingElseIgnored() = runTest {
        val g = container.epoch()
        post("apofis-a")
        val ignored = listOf(
            view(chatUri(g + 1, "apofis-a")), // another generation
            view(chatUri(g, "apofis-missing")), // deleted, or never here
            view(chatUri(g, "apofis-hidden")),
            view("apofis://chat/$g"),
            view("apofis://chat/$g/apofis-a/x"),
            view("apofis://chat/$g/apofis-a?x=1"),
            view("apofis://chat/$g/apofis-a#x"),
            view("apofis://other/$g/apofis-a"),
            view("https://chat/$g/apofis-a"),
            view("apofis://chat/0$g/apofis-a"),
            view("apofis://chat/x/apofis-a"),
            Intent(Intent.ACTION_MAIN, chatUri(g, "apofis-a")),
            Intent(app, MainActivity::class.java).putExtra("sessionKey", "apofis-a"), // extras only
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        for (intent in ignored) assertFalse(intent.toString(), container.openChat(intent))
        assertFalse(container.openChat(null))

        assertNull(container.chatToOpen.value)
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun previousConnectionTapIgnoredAndSwitchClears() = runTest {
        container.connect(Credentials("https://a.example/", "key-a"))
        session("apofis-a")
        val old = container.epoch()
        post("apofis-a")
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:r", "apofis-a", "r", 1, "FINISHED", old, 0))

        container.connect(Credentials("https://a.example/", "key-b")) // same server, new key: the chats stay

        assertEquals("Title apofis-a", db.sessionDao().get("apofis-a")!!.title)
        assertEquals(old + 1, container.epoch())
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(db.noticeOutboxDao().all().isEmpty())
        assertFalse(container.openChat(view(chatUri(old, "apofis-a"))))
    }
}
