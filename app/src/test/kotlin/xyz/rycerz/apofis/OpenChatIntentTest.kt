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
import org.junit.Assert.assertNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
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

    private suspend fun post(k: String, run: String = "run_$k", started: Long = 1) {
        container.notifier.notice(k, run, started, Notice.FINISHED, "run:$run", container.epoch(), replay = false)
        container.notifier.idle()
    }

    @Test
    fun currentGenerationAndExistingKeySelectsAndCancels() = runTest {
        post("apofis-a")
        assertEquals(1, nm.activeNotifications.size)

        assertTrue(container.openChat(view(chatUri(container.epoch(), "apofis-a"))))
        container.notifier.idle()

        assertEquals(container.epoch() to "apofis-a", container.chatToOpen.value)
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(container.openChat(view(chatUri(container.epoch(), "apofis-arch")))) // archived chats open as from the drawer
    }

    @Test
    fun shownChatCancelsAndSuppresses() = runTest {
        post("apofis-a")
        container.chatShown(container.epoch(), "apofis-a", true)
        container.notifier.idle()
        assertTrue(nm.activeNotifications.isEmpty())
        post("apofis-a", "run_2", started = 2)
        assertTrue(nm.activeNotifications.isEmpty())

        container.chatShown(container.epoch(), "apofis-b", false) // another chat's screen going away changes nothing
        post("apofis-a", "run_3", started = 3)
        assertTrue(nm.activeNotifications.isEmpty())

        container.chatShown(container.epoch(), "apofis-a", false) // the activity stopped
        post("apofis-a", "run_4", started = 4)
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun oldGraphCallbacksLeaveTheNewOneAlone() = runTest {
        val old = container.epoch()
        container.connect(Credentials("https://a.example/", "key-a"))
        session("apofis-a")
        container.chatShown(container.epoch(), "apofis-a", true)
        container.chatShown(old, "apofis-a", false) // the old route disposing late
        post("apofis-a", "run_9", started = 9)
        assertTrue(nm.activeNotifications.isEmpty()) // still suppressed: the new graph's chat is on screen

        container.chatShown(container.epoch(), "apofis-a", false)
        container.chatShown(old, "apofis-a", true) // the old route starting late
        post("apofis-a", "run_10", started = 10)
        assertEquals(1, nm.activeNotifications.size)
    }

    @Test
    fun tapCheckedAgainstTheLiveGraph() = runTest {
        container.connect(Credentials("https://a.example/", "key-a"))
        val conn = container.connected()!!
        val g = container.epoch()
        assertEquals("apofis-a", container.tapTarget(g to "apofis-a", conn))
        assertNull(container.tapTarget(g - 1 to "apofis-a", conn)) // published by an old lookup after the switch

        container.connect(Credentials("https://a.example/", "key-b"))
        assertNull(container.tapTarget(g to "apofis-a", conn)) // the old graph, closed
        assertNull(container.tapTarget(g to "apofis-a", container.connected())) // the new graph: another epoch
        assertNull(container.tapTarget(g + 1 to "apofis-a", null)) // no connection: no screen change
    }

    @Test
    fun tapIsAcknowledgedOnlyAfterItsSelection() = runTest {
        container.connect(Credentials("https://a.example/", "key-a"))
        session("apofis-a")
        val conn = container.connected()!!
        val selecting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var opened = 0
        val consumer = backgroundScope.launch {
            container.consumeTaps(conn, { _, current ->
                selecting.complete(Unit)
                release.await() // the drawer's lock is held meanwhile
                current()
            }) { opened++ }
        }
        assertTrue(container.openChat(view(chatUri(container.epoch(), "apofis-a"))))
        selecting.await()

        assertNotNull(container.chatToOpen.value) // not acknowledged while its selection runs, so nothing cancels it
        release.complete(Unit)
        withContext(Dispatchers.IO) { repeat(2000) { if (opened == 1) return@withContext; Thread.sleep(5) } }

        assertEquals(1, opened)
        assertNull(container.chatToOpen.value)
        consumer.cancel()
    }

    @Test
    fun oldDrawerLeavesANewEpochTap() = runTest {
        container.connect(Credentials("https://a.example/", "key-a"))
        session("apofis-a")
        val old = container.connected()!!
        var oldOpened = 0
        backgroundScope.launch { container.consumeTaps(old, { _, current -> current() }) { oldOpened++ } } // not yet replaced
        container.connect(Credentials("https://a.example/", "key-b")) // same server: the chat stays
        val new = container.connected()!!

        assertTrue(container.openChat(view(chatUri(container.epoch(), "apofis-a")))) // a tap for the new connection
        testScheduler.runCurrent() // the old drawer's collector runs first
        assertEquals(container.epoch() to "apofis-a", container.chatToOpen.value) // left for the new drawer
        assertEquals(0, oldOpened)

        var newOpened = 0
        backgroundScope.launch { container.consumeTaps(new, { _, current -> current() }) { newOpened++ } }
        testScheduler.runCurrent()
        assertEquals(1, newOpened)
        assertNull(container.chatToOpen.value)
    }

    @Test
    fun disconnectedStartPostsNothing() = runTest {
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:r", "apofis-a", "r", 1, "FINISHED", container.epoch(), 0))

        assertNull(container.load()) // no usable credentials
        container.notifier.idle()
        container.notifier.idle() // after the start's cleanup

        assertTrue(nm.activeNotifications.isEmpty())
    }

    @Test
    fun disconnectedStartCancelsOtherEpochNotifications() = runTest {
        post("apofis-a") // posted under the current epoch
        app.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE).edit()
            .putLong("connection_epoch", container.epoch() + 1).commit() // a switch moved on, then the process died

        val next = AppContainer(app, CredentialStore(app) { key }) { db } // no usable credentials
        assertNull(next.load())
        next.notifier.idle()
        next.notifier.idle()

        assertTrue(nm.activeNotifications.isEmpty())
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
        container.notifier.idle()

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
        assertTrue(container.openChat(view(chatUri(old, "apofis-a")))) // a tap not yet consumed by the chat route

        container.connect(Credentials("https://a.example/", "key-b")) // same server, new key: the chats stay

        assertEquals("Title apofis-a", db.sessionDao().get("apofis-a")!!.title)
        assertEquals(old + 1, container.epoch())
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue(db.noticeOutboxDao().all(old).isEmpty())
        assertNull(container.chatToOpen.value) // the pending tap of the old connection is gone
        assertFalse(container.openChat(view(chatUri(old, "apofis-a"))))
        assertEquals(old + 1, container.connected()!!.generation)
    }
}
