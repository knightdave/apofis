package xyz.rycerz.apofis.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.MainActivity
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeOutboxEntity

private const val A = "apofis-a"
private const val B = "apofis-b"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val prefs = app.getSharedPreferences("notify-test", Context.MODE_PRIVATE)
    private var gen = 5L
    private var visible: String? = null
    private val titles = mutableMapOf(A to "Chat A", B to "Chat B", "Aa" to "Aa", "BB" to "BB")
    private val notifier = Notifier(app, prefs, db, { gen }, { visible }, { titles[it] })

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.ensureChannels()
    }

    @After
    fun tearDown() = db.close()

    private fun active(): List<StatusBarNotification> = nm.activeNotifications.toList()
    private fun shown(key: String): Notification? = active().singleOrNull { it.tag == key }?.notification
    private fun text(key: String) = shown(key)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun str(id: Int) = app.getString(id)
    private fun silent(n: Notification) = n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && n.group != null

    private suspend fun finish(key: String, run: String, started: Long, notice: Notice = Notice.FINISHED, generation: Long = gen, replay: Boolean = false) =
        notifier.notice(key, run, started, notice, "run:$run", generation, replay)

    private suspend fun ask(key: String, run: String, started: Long, request: String, generation: Long = gen) =
        notifier.notice(key, run, started, Notice.NEEDS_ANSWER, "req:$run:$request:$generation", generation, replay = false)

    private suspend fun row(key: String, run: String, started: Long, generation: Long = gen, notice: Notice = Notice.FINISHED) =
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:$run", key, run, started, notice.name, generation, 0))

    private suspend fun rows() = db.noticeOutboxDao().all()

    @Test
    fun oldFinishAfterNewApproval() = runTest {
        row(A, "run_a", started = 1) // run A's finish committed; the post never happened
        ask(A, "run_b", started = 2, request = "r1") // run B started later and needs an answer

        notifier.replayOutbox() // A's finish comes back afterwards

        assertEquals(str(R.string.notif_needs_answer), text(A))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun switchBetweenCommitAndPost() = runTest {
        row(A, "run_a", started = 1, generation = 5)
        gen = 6 // the server switched before the OS post

        finish(A, "run_a", started = 1, generation = 5)
        notifier.replayOutbox()

        assertTrue(active().isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun staleClearAfterSwitch() = runTest {
        gen = 6
        ask(A, "run_a", 1, "r1")

        notifier.clearNeedsAnswer(A, "run_a", "r1", generation = 5)
        assertEquals(str(R.string.notif_needs_answer), text(A))

        notifier.clearNeedsAnswer(A, "run_a", "r1", generation = 6)
        assertNull(shown(A))
    }

    @Test
    fun oncePerEvent() = runTest {
        finish(A, "run_a", 1)
        assertEquals(str(R.string.notif_reply_finished), text(A))
        nm.cancel(A, active().single().id) // dismissed by the user

        finish(A, "run_a", 1) // a duplicate settlement or read
        ask(B, "run_b", 1, "r1")
        nm.cancel(B, active().single().id)
        ask(B, "run_b", 1, "r1") // the next accepted read re-derives the same request

        assertTrue(active().isEmpty())
    }

    @Test
    fun replayIsSilentAndIdempotent() = runTest {
        row(A, "run_a", 1)
        finish(A, "run_a", 1)
        assertTrue(!silent(shown(A)!!))
        row(A, "run_a", 1) // the process died after the OS post, before the row was deleted

        notifier.replayOutbox()
        assertTrue(silent(shown(A)!!))
        assertTrue(rows().isEmpty())

        nm.cancelAll()
        notifier.replayOutbox()
        assertTrue(active().isEmpty())
    }

    @Test
    fun replayBetweenCommitAndPost() = runTest {
        row(A, "run_a", 1, notice = Notice.FAILED)

        notifier.replayOutbox()

        assertEquals(str(R.string.notif_reply_failed), text(A))
        assertTrue(silent(shown(A)!!))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun otherGenerationDropped() = runTest {
        row(A, "run_a", 1, generation = 4)
        row(B, "run_b", 1, generation = 5)

        notifier.replayOutbox()

        assertNull(shown(A))
        assertEquals(str(R.string.notif_reply_finished), text(B))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun staleApprovalAfterFinishDropped() = runTest {
        ask(A, "run_a", 1, "r1")
        finish(A, "run_a", 1)
        assertEquals(str(R.string.notif_reply_finished), text(A))

        ask(A, "run_a", 1, "r2") // a read that started before the finish

        assertEquals(str(R.string.notif_reply_finished), text(A))
    }

    @Test
    fun newApprovalBeforeOldClear() = runTest {
        ask(A, "run_a", 1, "r1")
        ask(A, "run_a", 1, "r2")

        notifier.clearNeedsAnswer(A, "run_a", "r1", gen)
        assertEquals(str(R.string.notif_needs_answer), text(A))

        notifier.clearNeedsAnswer(A, "run_a", "r2", gen)
        assertNull(shown(A))
    }

    @Test
    fun visibleChatSuppressed() = runTest {
        visible = A
        row(A, "run_a", 1)

        finish(A, "run_a", 1)
        ask(A, "run_a2", 2, "r1")

        assertTrue(active().isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun missingChatSuppressed() = runTest {
        titles.remove(A) // deleted, or hidden on the server
        row(A, "run_a", 1)

        finish(A, "run_a", 1)

        assertTrue(active().isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun blockedDeletesRow() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        row(A, "run_1", 1)
        finish(A, "run_1", 1)
        assertTrue(active().isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        shadowOf(nm).setNotificationsEnabled(false)
        row(A, "run_2", 2)
        finish(A, "run_2", 2)
        assertTrue(active().isEmpty())
        shadowOf(nm).setNotificationsEnabled(true)

        block(CHANNEL_REPLIES)
        row(A, "run_3", 3)
        finish(A, "run_3", 3)
        ask(B, "run_b", 1, "r1") // the answers channel is still allowed
        assertNull(shown(A))
        assertEquals(str(R.string.notif_needs_answer), text(B))
        assertTrue(rows().isEmpty())

        unblock(CHANNEL_REPLIES)
        row(A, "run_4", 4)
        finish(A, "run_4", 4)
        assertEquals(str(R.string.notif_reply_finished), text(A))
        assertTrue(rows().isEmpty())
    }

    private fun block(channel: String) {
        nm.deleteNotificationChannel(channel)
        nm.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationManager.IMPORTANCE_NONE, nm.getNotificationChannel(channel).importance)
    }

    private fun unblock(channel: String) {
        nm.getNotificationChannel(channel).importance = NotificationManager.IMPORTANCE_DEFAULT // the user allows it again
        assertNotEquals(NotificationManager.IMPORTANCE_NONE, nm.getNotificationChannel(channel).importance)
    }

    @Test
    fun contentHasNoMessageText() = runTest {
        finish(A, "run_a", 1)

        val n = shown(A)!!
        assertEquals(CHANNEL_REPLIES, n.channelId)
        assertEquals("Chat A", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(str(R.string.notif_reply_finished), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(n.actions)
        assertEquals(Notification.CATEGORY_MESSAGE, n.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(str(R.string.notif_public), n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.takeIf { it.contains("Chat A") })

        ask(B, "run_b", 1, "r1")
        assertEquals(CHANNEL_ANSWERS, shown(B)!!.channelId)
        assertNull(shown(B)!!.actions) // answered only inside the app
    }

    @Test
    fun hashCollisionDistinctIntents() = runTest {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        finish("Aa", "run_1", 1)
        finish("BB", "run_2", 1)

        val a = shown("Aa")!!.contentIntent
        val b = shown("BB")!!.contentIntent
        assertNotEquals(a, b)
        for ((key, pi) in listOf("Aa" to a, "BB" to b)) {
            val s = shadowOf(pi)
            assertTrue(s.isActivity)
            assertTrue(s.flags and PendingIntent.FLAG_IMMUTABLE != 0)
            assertEquals(chatUri(gen, key), s.savedIntent.data)
            assertEquals(MainActivity::class.java.name, s.savedIntent.component!!.className)
        }
    }

    @Test
    fun cancelAllOnSwitch() = runTest {
        finish(A, "run_a", 1)
        ask(B, "run_b", 1, "r1")
        assertEquals(2, active().size)

        notifier.cancelAll()

        assertTrue(active().isEmpty())
    }

    @Test
    fun chatUriEncodesTheKey() {
        val uri = chatUri(7, "apofis-a/b c")
        assertEquals("apofis", uri.scheme)
        assertEquals("chat", uri.host)
        assertEquals(listOf("7", "apofis-a/b c"), uri.pathSegments)
    }
}
