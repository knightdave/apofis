package xyz.rycerz.apofis

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 *
 * A hand-over waits only for Room (its IO executor), the main thread and the notifier's lane. Room's executor is counted
 * here, so [drain] returns once all three are quiet: every check runs after the delivered intent's own work has ended.
 */
@SuppressLint("RestrictedApi") // ArchTaskExecutor: Room's default IO executor, replaced for the test
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IntentGateTest {
    private val app = ApplicationProvider.getApplicationContext<ApofisApp>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.databaseBuilder(app, AppDatabase::class.java, "apofis.db").allowMainThreadQueries().build() // the container's file
    private val epoch get() = app.container.epoch()
    private var run = 0L
    private val io = Executors.newCachedThreadPool()
    private val lock = Object()
    private var inFlight = 0 // guarded by lock
    private var handed = 0L // guarded by lock

    private fun uri(k: String): Uri = chatUri(epoch, k)
    private fun tap(k: String) = Intent(Intent.ACTION_VIEW, uri(k)).setClassName(app, OPEN_CHAT)
    private fun explicit(k: String) = Intent(Intent.ACTION_VIEW, uri(k)).setClass(app, MainActivity::class.java)
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(app, MainActivity::class.java)

    @Before
    fun setUp() = runBlocking {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) {
                synchronized(lock) { inFlight++; handed++ }
                io.execute {
                    try {
                        runnable.run()
                    } finally {
                        synchronized(lock) { inFlight--; lock.notifyAll() }
                    }
                }
            }
            override fun postToMainThread(runnable: Runnable) { Handler(Looper.getMainLooper()).post(runnable) }
            override fun isMainThread() = Looper.getMainLooper().thread === Thread.currentThread()
        })
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for (k in listOf(TMP, OTHER, SENTINEL)) {
            db.sessionDao().upsert(SessionEntity(k, k, "", "Title $k", false, false, 1.0, null, 1, null, false, existsOnServer = true))
        }
        db.messageDao().insertAll(listOf(MessageEntity(TMP, 0, 1, "user", null, "hello", null, null, null, null, 1.0, null)))
        app.container.preferences.lastChat = TMP // the open chat, restored by a plain start
    }

    @After
    fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        db.close()
        ArchTaskExecutor.getInstance().setDelegate(null)
        io.shutdown()
    }

    private fun shown(k: String) = nm.activeNotifications.firstOrNull { it.tag == k }

    /** Every row of every table, to compare a start with the state before it. */
    private fun tables(): List<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { names ->
        generateSequence { if (names.moveToNext()) names.getString(0) else null }.toList()
    }.flatMap { t ->
        db.query("SELECT * FROM `$t`", null).use { c ->
            generateSequence { if (c.moveToNext()) t + (0 until c.columnCount).map { c.getString(it) } else null }.toList()
        }
    }

    /** An empty tap slot and a fresh notification for each of [keys]: the next check sees only what the next intent does. */
    private fun arm(vararg keys: String) = runBlocking {
        app.container.chatToOpen.value?.let(app.container::chatOpened) // no connection: nothing else consumes it
        for (k in keys) {
            run++
            app.container.notifier.notice(k, "run_$run", run, Notice.FINISHED, "run:run_$run", epoch, replay = false)
        }
        app.container.notifier.idle()
        for (k in keys) assertNotNull(shown(k))
    }

    /**
     * Until Room has run every query handed to it, the main thread has run what those resumed, and the notifier's lane
     * is empty, with nothing new handed to Room meanwhile. A blocking wait on that condition; 10 s is a watchdog only.
     */
    private fun drain() {
        do {
            val seen = synchronized(lock) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (inFlight > 0) {
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    check(left > 0) { "Room work still running after 10 s" }
                    lock.wait(left)
                }
                handed
            }
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking { app.container.notifier.idle() }
        } while (synchronized(lock) { inFlight > 0 || handed != seen })
    }

    /** [deliver] a tap for [k]: once its work ended, its chat is requested and its notification is gone. */
    private fun assertTapOpens(k: String, deliver: (Intent) -> Unit) {
        arm(k)
        deliver(tap(k))
        drain()
        assertEquals(epoch to k, app.container.chatToOpen.value)
        assertNull(shown(k))
    }

    /** Delivers [intent] after arming [OTHER]; afterwards nothing was requested and every row is as before. */
    private fun assertIgnored(intent: Intent, deliver: (Intent) -> Unit) {
        arm(OTHER)
        val before = tables()
        deliver(intent)
        drain()
        assertNull(app.container.chatToOpen.value)
        assertNotNull(shown(OTHER))
        assertEquals(TMP, app.container.preferences.lastChat)
        assertEquals(before, tables())
    }

    private var activity: ActivityController<MainActivity>? = null
    private fun start(intent: Intent) { activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup() }
    private val warm: (Intent) -> Unit = { activity!!.newIntent(it) }

    @Test
    fun externalChatViewIgnored() {
        assertFalse(gate(explicit(OTHER), fresh = true, app.packageName))
        assertFalse(gate(Intent(Intent.ACTION_VIEW, uri(OTHER)), fresh = true, app.packageName)) // implicit
        assertFalse(gate(Intent(Intent.ACTION_VIEW, uri(OTHER)).setClassName("com.example", OPEN_CHAT), fresh = true, app.packageName))
        assertFalse(gate(Intent(Intent.ACTION_VIEW, uri(OTHER)).setClassName(app, OPEN_CHAT + "X"), fresh = true, app.packageName))

        assertIgnored(explicit(OTHER), ::start) // cold
        assertTapOpens(SENTINEL, warm) // the activity hands taps over
        assertIgnored(explicit(OTHER), warm) // warm: a VIEW aimed at the running activity
    }

    @Test
    fun notificationTapThroughAliasOpensChat() {
        assertTrue(gate(tap(OTHER), fresh = true, app.packageName))

        assertTapOpens(OTHER, ::start)
        assertEquals(launcher().component, activity!!.get().intent.component) // handed over once: a later reader sees a plain start
        assertTapOpens(SENTINEL, warm)
    }

    @Test
    fun tapReplayedFromHistoryIgnored() {
        val replayed = tap(OTHER).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertFalse(gate(replayed, fresh = true, app.packageName))
        assertFalse(gate(tap(OTHER), fresh = false, app.packageName)) // the activity was recreated

        assertIgnored(replayed, ::start)
        assertTapOpens(SENTINEL, warm)
        assertIgnored(replayed, warm)
    }

    @Test
    fun restoredActivityIgnoresItsTap() {
        // A rotation or a restore delivers the old tap again, with saved state.
        assertIgnored(tap(OTHER)) { activity = Robolectric.buildActivity(MainActivity::class.java, it).setup(Bundle()) }
        assertTapOpens(SENTINEL, warm)
    }

    @Test
    fun unknownActionIgnored() { // control: today's chatKey already ignores it
        val odd = Intent("xyz.example.OPEN", uri(OTHER)).setClassName(app, OPEN_CHAT)
        assertFalse(gate(odd, fresh = true, app.packageName))
        assertFalse(gate(launcher(), fresh = true, app.packageName))

        assertIgnored(odd, ::start)
        assertTapOpens(SENTINEL, warm)
        assertIgnored(odd, warm)
    }

    // The two cold starts run as separate tests: each gets its own application and container, never loaded before.

    @Test
    fun coldClearedLauncherStartChangesNothing() {
        val cleared = launcher().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertFalse(gate(cleared, fresh = true, app.packageName))

        assertIgnored(cleared, ::start)
        assertTapOpens(SENTINEL, warm)
    }

    @Test
    fun coldExternalStartChangesNothing() {
        val external = explicit(OTHER).putExtra("sessionKey", OTHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertFalse(gate(external, fresh = true, app.packageName))

        assertIgnored(external, ::start)
        assertTapOpens(SENTINEL, warm)
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
