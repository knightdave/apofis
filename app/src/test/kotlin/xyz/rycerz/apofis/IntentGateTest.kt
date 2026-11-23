package xyz.rycerz.apofis

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.notify.Notice
import xyz.rycerz.apofis.notify.chatUri

private const val TMP = "apofis-tmp-1" // a temporary chat: only its key prefix marks it until Task 6b
private const val OTHER = "apofis-b"
private const val SENTINEL = "apofis-c"

/**
 * MainActivity acts on the launcher intent and on notification taps through the non-exported alias only. The decision
 * is [gate]; the activity cases run the real container (no connection: Robolectric has no keystore) and observe the one
 * channel from an intent to the selection, [AppContainer.chatToOpen], and the tapped chat's notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IntentGateTest {
    private val app = ApplicationProvider.getApplicationContext<ApofisApp>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").allowMainThreadQueries().build() // the container's file
    private val epoch get() = app.container.epoch()

    private fun uri(k: String): Uri = chatUri(epoch, k)
    private fun tap(k: String) = Intent(Intent.ACTION_VIEW, uri(k)).setClassName(app, OPEN_CHAT)
    private fun explicit(k: String) = Intent(Intent.ACTION_VIEW, uri(k)).setClass(app, MainActivity::class.java)
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity::class.java)

    @Before
    fun setUp() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for (k in listOf(TMP, OTHER, SENTINEL)) {
            db.sessionDao().upsert(SessionEntity(k, k, "", "Title $k", false, false, 1.0, null, 1, null, false, existsOnServer = true))
        }
        db.messageDao().insertAll(listOf(MessageEntity(TMP, 0, 1, "user", null, "hello", null, null, null, null, 1.0, null)))
        app.container.preferences.lastChat = TMP // the open chat, restored by a plain start
        for (k in listOf(OTHER, SENTINEL)) app.container.notifier.notice(k, "run_$k", 1, Notice.FINISHED, "run:run_$k", epoch, replay = false)
        app.container.notifier.idle()
        assertNotNull(shown(OTHER))
    }

    @After
    fun tearDown() = db.close()

    private fun shown(k: String) = nm.activeNotifications.firstOrNull { it.tag == k }

    /** Every row of every table, to compare a start with the state before it. */
    private fun tables(): List<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { names ->
        generateSequence { if (names.moveToNext()) names.getString(0) else null }.toList()
    }.flatMap { t ->
        db.query("SELECT * FROM `$t`", null).use { c ->
            generateSequence { if (c.moveToNext()) t + (0 until c.columnCount).map { c.getString(it) } else null }.toList()
        }
    }

    private fun until(what: String, check: () -> Boolean) {
        repeat(1000) { // a watchdog of 10 s, not a synchronisation: it ends as soon as the check holds
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for $what")
    }

    private fun start(intent: Intent): ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java, intent).setup()

    /** A real tap for [SENTINEL], waited for: a hand-over of an earlier intent would have run before it. */
    private fun settle(c: ActivityController<MainActivity>) {
        c.newIntent(tap(SENTINEL))
        until("the sentinel tap") { app.container.chatToOpen.value == epoch to SENTINEL && shown(SENTINEL) == null }
    }

    /** Nothing but the sentinel was opened: [OTHER] keeps its notification and the open chat stays [TMP]. */
    private fun assertOnlySentinelOpened(before: List<String>) {
        assertNotNull(shown(OTHER))
        assertEquals(TMP, app.container.preferences.lastChat)
        assertEquals(before, tables())
    }

    @Test
    fun externalChatViewIgnored() {
        assertFalse(gate(explicit(OTHER), fresh = true, app.packageName))
        assertFalse(gate(Intent(Intent.ACTION_VIEW, uri(OTHER)), fresh = true, app.packageName)) // implicit
        assertFalse(gate(Intent(Intent.ACTION_VIEW, uri(OTHER)).setClassName("com.example", OPEN_CHAT), fresh = true, app.packageName))
        assertFalse(gate(Intent(Intent.ACTION_VIEW, uri(OTHER)).setClassName(app, OPEN_CHAT + "X"), fresh = true, app.packageName))

        val before = tables()
        val c = start(explicit(OTHER))
        settle(c)
        assertOnlySentinelOpened(before)
        c.newIntent(explicit(OTHER)) // warm: a VIEW aimed at the running activity
        settle(c)
        assertOnlySentinelOpened(before)
        c.pause().stop().destroy()
    }

    @Test
    fun notificationTapThroughAliasOpensChat() {
        assertTrue(gate(tap(OTHER), fresh = true, app.packageName))

        val c = start(tap(OTHER))
        until("the tap") { app.container.chatToOpen.value == epoch to OTHER && shown(OTHER) == null }
        assertEquals(launcher().component, c.get().intent.component) // handed over once: a later reader sees a plain start
        settle(c) // and through onNewIntent
        c.pause().stop().destroy()
    }

    @Test
    fun tapReplayedFromHistoryIgnored() {
        val replayed = tap(OTHER).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertFalse(gate(replayed, fresh = true, app.packageName))
        assertFalse(gate(tap(OTHER), fresh = false, app.packageName)) // the activity was recreated

        val before = tables()
        val c = start(replayed)
        settle(c)
        assertOnlySentinelOpened(before)
        c.pause().stop().destroy()
    }

    @Test
    fun unknownActionIgnored() { // control: today's chatKey already ignores it
        val odd = Intent("xyz.example.OPEN", uri(OTHER)).setClassName(app, OPEN_CHAT)
        assertFalse(gate(odd, fresh = true, app.packageName))
        assertFalse(gate(launcher(), fresh = true, app.packageName))

        val before = tables()
        val c = start(odd)
        settle(c)
        assertOnlySentinelOpened(before)
        c.pause().stop().destroy()
    }

    @Test
    fun coldExternalStartChangesNothing() {
        val cleared = launcher().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertFalse(gate(cleared, fresh = true, app.packageName))
        val extras = explicit(OTHER).putExtra("sessionKey", OTHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertFalse(gate(extras, fresh = true, app.packageName))

        val before = tables()
        for (intent in listOf(cleared, extras)) {
            val c = start(intent) // the container is fresh, as after Android reclaimed the process
            settle(c)
            assertOnlySentinelOpened(before)
            c.pause().stop().destroy()
        }
    }

    @Test
    fun aliasNotExported() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, OPEN_CHAT), 0)
        assertFalse(info.exported)
        assertEquals(MainActivity::class.java.name, info.targetActivity)
        // No activity takes a chat VIEW from outside: the alias has no filter and MainActivity only the launcher's.
        assertTrue(app.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri(OTHER)).setPackage(app.packageName), 0).isEmpty())
        assertNull(app.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, uri(OTHER)), 0))
    }
}
