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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.MainActivity
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeOutboxEntity
import xyz.rycerz.apofis.db.SessionEntity

private const val A = "apofis-a"
private const val B = "apofis-b"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val prefs = app.getSharedPreferences("notify-test", Context.MODE_PRIVATE)
    @Volatile private var gen = 5L
    @Volatile private var visible: String? = null
    private lateinit var notifier: Notifier

    @Before
    fun setUp() = runTest {
        prefs.edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ensureChannels(app)
        for (k in listOf(A, B, "Aa", "BB")) chat(k)
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.start(): Notifier = Notifier(app, prefs, db, { gen }, { visible }, backgroundScope).also { notifier = it }

    private suspend fun chat(key: String, hidden: Boolean = false) = db.sessionDao().upsert(
        SessionEntity(key, key, "", "Chat $key", pinned = false, archived = false, startedAt = 1.0, lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true, hidden = hidden),
    )

    private fun active(): List<StatusBarNotification> = nm.activeNotifications.toList()
    private fun shown(key: String): Notification? = active().singleOrNull { it.tag == key }?.notification
    private fun text(key: String) = shown(key)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun str(id: Int) = app.getString(id)
    private fun silent(n: Notification) = n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY && n.group != null

    private suspend fun finish(key: String, run: String, started: Long, notice: Notice = Notice.FINISHED, generation: Long = gen, replay: Boolean = false) {
        notifier.notice(key, run, started, notice, "run:$run", generation, replay)
        notifier.idle()
    }

    private suspend fun ask(key: String, run: String, started: Long, request: String, generation: Long = gen) {
        if (db.activeRunDao().get(run) == null) db.activeRunDao().upsert(ActiveRunEntity(run, key, null, "waiting_for_approval", started))
        notifier.notice(key, run, started, Notice.NEEDS_ANSWER, "req:$run:$request:$generation", generation, replay = false)
        notifier.idle()
    }

    private suspend fun clear(key: String, run: String, generation: Long = gen) {
        notifier.clearNeedsAnswer(key, run, generation)
        notifier.idle()
    }

    private suspend fun row(key: String, run: String, started: Long, generation: Long = gen, notice: Notice = Notice.FINISHED) =
        db.noticeOutboxDao().upsert(NoticeOutboxEntity("run:$run", key, run, started, notice.name, generation, 0))

    private suspend fun rows(generation: Long = gen) = db.noticeOutboxDao().all(generation)

    /** Waits in real time for the lane and Room's invalidation thread; a watchdog only. */
    private suspend fun awaitUntil(condition: suspend () -> Boolean) = withContext(Dispatchers.IO) {
        repeat(2000) {
            notifier.idle()
            if (condition()) return@withContext
            Thread.sleep(5)
        }
        fail("condition not met within 10 s")
    }

    @Test
    fun oldFinishAfterNewApproval() = runTest {
        start()
        row(A, "run_a", started = 1) // run A's finish committed; the post never happened
        ask(A, "run_b", started = 2, request = "r1") // run B started later and needs an answer

        notifier.replayOutbox() // A's finish comes back afterwards

        assertEquals(str(R.string.notif_needs_answer), text(A))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun switchBetweenCommitAndPost() = runTest {
        start()
        row(A, "run_a", started = 1, generation = 5)
        gen = 6 // the epoch moved on before the post

        finish(A, "run_a", started = 1, generation = 5)

        assertTrue(active().isEmpty())
        assertTrue(rows(5).isEmpty())
    }

    @Test
    fun staleClearAfterSwitch() = runTest {
        start()
        gen = 6
        ask(A, "run_a", 1, "r1")

        clear(A, "run_a", generation = 5)
        assertEquals(str(R.string.notif_needs_answer), text(A))

        clear(A, "run_b") // another run's clear
        assertEquals(str(R.string.notif_needs_answer), text(A))

        clear(A, "run_a")
        assertNull(shown(A))
    }

    @Test
    fun oncePerEvent() = runTest {
        start()
        finish(A, "run_a", 1)
        assertEquals(str(R.string.notif_reply_finished), text(A))
        nm.cancel(A, active().single().id) // dismissed by the user

        finish(A, "run_a", 1) // a duplicate settlement or read
        ask(B, "run_b", 1, "r1")
        nm.cancel(B, active().single().id)
        ask(B, "run_b", 1, "r1") // the next accepted read derives the same request again

        assertTrue(active().isEmpty())
    }

    @Test
    fun replayIsSilentAndIdempotent() = runTest {
        start()
        row(A, "run_a", 1)
        var dies = true
        notifier.beforeAck = { if (dies) { dies = false; error("process death after the OS post") } }
        finish(A, "run_a", 1)
        assertTrue(!silent(shown(A)!!))
        assertEquals(1, rows().size) // the post happened; its row and order were not written

        notifier.replayOutbox()
        assertTrue(silent(shown(A)!!))
        assertTrue(rows().isEmpty())

        nm.cancelAll()
        notifier.replayOutbox()
        finish(A, "run_a", 1)
        assertTrue(active().isEmpty())
    }

    @Test
    fun replayBetweenCommitAndPost() = runTest {
        start()
        row(A, "run_a", 1, notice = Notice.FAILED)

        notifier.replayOutbox()

        assertEquals(str(R.string.notif_reply_failed), text(A))
        assertTrue(silent(shown(A)!!))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun otherGenerationDropped() = runTest {
        start()
        row(A, "run_a", 1, generation = 4)
        row(B, "run_b", 1, generation = 5)

        notifier.replayOutbox()

        assertNull(shown(A))
        assertEquals(str(R.string.notif_reply_finished), text(B))
        assertTrue(rows(4).isEmpty())
        assertTrue(rows(5).isEmpty())
    }

    @Test
    fun reusedRunIdAcrossEpochs() = runTest {
        start()
        gen = 6
        row(A, "run_x", 1, generation = 6) // the new connection's finish of a run with the same id

        finish(A, "run_x", 1, generation = 5) // the old connection's late hand-off

        assertEquals(1, rows(6).size)
        assertTrue(active().isEmpty())
        notifier.replayOutbox() // the new row needs no live callback
        assertEquals(str(R.string.notif_reply_finished), text(A))
    }

    @Test
    fun olderReaderKeepsNewerRows() = runTest {
        start()
        row(A, "run_a", 1, generation = 6) // committed by a newer connection than this reader knows

        notifier.replayOutbox()

        assertEquals(1, rows(6).size)
    }

    @Test
    fun coldStartFinishesInterruptedSwitch() = runTest {
        start()
        finish(A, "run_a", 1)
        row(B, "run_b", 1)
        gen = 6 // the switch committed the new epoch, then the process died before the cleanup

        start().cleanup() // the next process, connected or not

        assertTrue(active().isEmpty())
        assertTrue(rows(5).isEmpty())
    }

    @Test
    fun switchWaitsForDeliveryInFlight() = runTest {
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Held after the epoch check and the title lookup, right before the post.
        notifier = Notifier(app, prefs, db, { gen }, {
            accepted.countDown()
            release.await(10, TimeUnit.SECONDS)
            null
        }, backgroundScope)
        notifier.notice(A, "run_a", 1, Notice.FINISHED, "run:run_a", gen, replay = false)
        withContext(Dispatchers.IO) { accepted.await(10, TimeUnit.SECONDS) }
        val switch = async(Dispatchers.IO) { notifier.switchEpoch { gen = 6 } }
        release.countDown()
        switch.await()
        notifier.idle()

        assertTrue(active().isEmpty()) // posted before the switch, so the switch removed it
        assertEquals(6, gen)
    }

    @Test
    fun staleApprovalAfterFinishDropped() = runTest {
        start()
        ask(A, "run_a", 1, "r1")
        finish(A, "run_a", 1)
        assertEquals(str(R.string.notif_reply_finished), text(A))

        ask(A, "run_a", 1, "r2") // a read that started before the finish

        assertEquals(str(R.string.notif_reply_finished), text(A))
    }

    @Test
    fun approvalClearedWhenFinishNotShown() = runTest {
        start()
        ask(A, "run_a", 1, "r1")
        block(CHANNEL_REPLIES)

        finish(A, "run_a", 1)

        assertNull(shown(A)) // the obsolete approval does not stay
    }

    @Test
    fun startupRetiresApprovalOfEndedRun() = runTest {
        start()
        db.activeRunDao().upsert(ActiveRunEntity("run_a", A, null, "waiting_for_approval", 1))
        db.activeRunDao().upsert(ActiveRunEntity("run_b", B, null, "waiting_for_approval", 1))
        ask(A, "run_a", 1, "r1")
        ask(B, "run_b", 1, "r1")
        db.activeRunDao().delete("run_a") // ended while the process was dead

        start().replayOutbox()

        assertNull(shown(A))
        assertNotNull(shown(B))
    }

    @Test
    fun failedAckKeepsRow() = runTest {
        start()
        row(A, "run_a", 1)
        notifier.beforeAck = { error("the order could not be written") }
        finish(A, "run_a", 1)
        assertEquals(1, rows().size)

        notifier.beforeAck = {}
        notifier.replayOutbox()
        assertTrue(rows().isEmpty())
        assertTrue(silent(shown(A)!!))
    }

    @Test
    fun visibleChatSuppressed() = runTest {
        start()
        visible = A
        row(A, "run_a", 1)

        finish(A, "run_a", 1)
        ask(A, "run_a2", 2, "r1")
        assertTrue(active().isEmpty())
        assertTrue(rows().isEmpty())

        visible = null
        ask(A, "run_a2", 2, "r1") // the same event again later: it was consumed, not postponed
        assertTrue(active().isEmpty())
    }

    @Test
    fun missingChatSuppressed() = runTest {
        start()
        db.sessionDao().delete(A)
        row(A, "run_a", 1)

        finish(A, "run_a", 1)

        assertTrue(active().isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun deletedOrHiddenChatSwept() = runTest {
        start()
        finish(A, "run_a", 1)
        finish(B, "run_b", 1)

        db.sessionDao().delete(A)
        chat(B, hidden = true)

        awaitUntil { active().isEmpty() }
        assertNull(db.noticeStateDao().get(A)) // its order goes with it
    }

    @Test
    fun laneNeverBlocksTheCaller() = runTest {
        start()
        val release = CountDownLatch(1)
        notifier.beforeAck = { release.await(10, TimeUnit.SECONDS) }
        notifier.notice(A, "run_a", 1, Notice.FINISHED, "run:run_a", gen, replay = false)

        val returned = CountDownLatch(1)
        Thread {
            notifier.cancel(A, gen)
            notifier.clearNeedsAnswer(A, "run_a", gen)
            returned.countDown()
        }.start()
        val handedOver = withContext(Dispatchers.IO) { returned.await(5, TimeUnit.SECONDS) } // a watchdog: these only hand over
        release.countDown()
        assertTrue(handedOver)
        notifier.idle()
        assertNull(shown(A)) // and they ran in order, after the post
    }

    @Test
    fun blockedDeletesRow() = runTest {
        start()
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
    fun channelsAndNotificationsPrivateOnLockScreen() = runTest {
        start()
        for (c in listOf(CHANNEL_REPLIES, CHANNEL_ANSWERS)) assertEquals(Notification.VISIBILITY_PRIVATE, nm.getNotificationChannel(c).lockscreenVisibility)
        finish(A, "run_a", 1)
        finish(B, "run_b", 1, Notice.FAILED)
        ask("Aa", "run_c", 1, "r1")
        for (k in listOf(A, B, "Aa")) {
            val n = shown(k)!!
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals(str(R.string.app_name), n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(str(R.string.notif_public), n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        }
    }

    @Test
    fun contentHasNoMessageText() = runTest {
        start()
        finish(A, "run_a", 1)

        val n = shown(A)!!
        assertEquals(CHANNEL_REPLIES, n.channelId)
        assertEquals("Chat $A", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(str(R.string.notif_reply_finished), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(gen, n.extras.getLong("xyz.rycerz.apofis.epoch"))
        assertNull(n.actions)
        assertEquals(Notification.CATEGORY_MESSAGE, n.category)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)

        ask(B, "run_b", 1, "r1")
        assertEquals(CHANNEL_ANSWERS, shown(B)!!.channelId)
        assertNull(shown(B)!!.actions) // answered only inside the app
    }

    @Test
    fun hashCollisionDistinctIntents() = runTest {
        start()
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
    fun switchEpochClearsEverything() = runTest {
        start()
        finish(A, "run_a", 1)
        ask(B, "run_b", 1, "r1")
        row(A, "run_c", 2)
        assertEquals(2, active().size)

        notifier.switchEpoch { gen = 6 }

        assertTrue(active().isEmpty())
        assertTrue(rows(5).isEmpty())
        assertNull(db.noticeStateDao().get(A))
    }

    @Test
    fun idsWithDelimitersKeepTheirOrder() = runTest {
        start()
        val run = "run\t1\nx"
        ask(A, run, 1, "p\t2\n:y")
        finish(A, run, 1)
        ask(A, run, 1, "p\t3") // after the finish: dropped only if the stored order decoded

        assertEquals(str(R.string.notif_reply_finished), text(A))
    }

    private fun loud(key: String) = shown(key)?.let { !silent(it) } == true

    @Test
    fun approvalsAlertOncePerRequest() = runTest {
        start()
        ask(A, "run_a", 1, "pA")
        assertTrue(loud(A))
        ask(A, "run_a", 1, "pB")
        assertEquals("req:run_a:pB:5", shown(A)!!.extras.getString("xyz.rycerz.apofis.event"))
        ask(A, "run_a", 1, "pA") // an older snapshot accepted last: already alerted
        assertEquals("req:run_a:pB:5", shown(A)!!.extras.getString("xyz.rycerz.apofis.event"))

        start() // a new process: A is no longer in the tray, so it comes back silently, once
        ask(A, "run_a", 1, "pA")
        assertEquals("req:run_a:pA:5", shown(A)!!.extras.getString("xyz.rycerz.apofis.event"))
        assertTrue(!loud(A))

        notifier.switchEpoch { gen = 6 } // the same ids on another connection are new
        ask(A, "run_a", 1, "pA", generation = 6)
        assertTrue(loud(A))
    }

    @Test
    fun approvalDeathBeforePostIsRetriedSilently() = runTest {
        start()
        notifier.beforePost = { error("process death before the OS post") }
        ask(A, "run_a", 1, "p1")
        assertNull(shown(A))

        start().also { it.beforePost = {} }
        ask(A, "run_a", 1, "p1") // the next accepted read derives it again
        assertNotNull(shown(A))
        assertTrue(!loud(A))
    }

    @Test
    fun approvalAfterDeathPastPostIsNotRepeated() = runTest {
        start()
        ask(A, "run_a", 1, "p1")
        val first = shadowOf(nm).getNotification(A, active().single().id)
        start()
        ask(A, "run_a", 1, "p1")
        assertTrue(first === shadowOf(nm).getNotification(A, active().single().id)) // not posted again
    }

    @Test
    fun clearUsesTheDisplayedIdentity() = runTest {
        start()
        ask(A, "run_a", 1, "p1")
        db.noticeStateDao().clear() // its order never written (a death), or lost

        clear(A, "run_a")

        assertNull(shown(A))
    }

    @Test
    fun lateRequestAfterSettlementIsDropped() = runTest {
        start()
        db.activeRunDao().upsert(ActiveRunEntity("run_a", A, null, "running", 1))
        clear(A, "run_a") // a 404 settled the run and retired its request
        db.activeRunDao().delete("run_a")

        notifier.notice(A, "run_a", 1, Notice.NEEDS_ANSWER, "req:run_a:p1:$gen", gen, replay = false) // handed over late
        notifier.idle()

        assertNull(shown(A))
    }

    @Test
    fun suppressedFinishIsConsumedBeforeAnythingElse() = runTest {
        start()
        visible = A
        row(A, "run_a", 1)
        notifier.beforeAck = { error("process death") }
        finish(A, "run_a", 1)

        visible = null
        notifier.beforeAck = {}
        start().replayOutbox() // the next process, chat no longer on screen

        assertTrue(active().isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun startupRetiresASupersededNotification() = runTest {
        start()
        finish(A, "run_a", 1)
        db.noticeStateDao().upsert(xyz.rycerz.apofis.db.NoticeStateEntity(A, gen, 2, "run_b", "FINISHED", "run:run_b")) // decided later, cancel lost

        start().replayOutbox()

        assertNull(shown(A))
    }

    @Test
    fun chatHiddenBeforeThePostIsTakenBack() = runTest {
        start()
        notifier.beforePost = { chat(A, hidden = true) } // the hide commits after the title read
        finish(A, "run_a", 1)

        assertNull(shown(A))
    }

    @Test
    fun laneHoldsOneControlPerChat() = runTest {
        start()
        val release = CountDownLatch(1)
        notifier.beforeAck = { release.await(10, TimeUnit.SECONDS) }
        notifier.notice(B, "run_b", 1, Notice.FINISHED, "run:run_b", gen, replay = false) // holds the lane
        repeat(100) {
            notifier.clearNeedsAnswer(A, "run_a", gen)
            notifier.notice(A, "run_a", 1, Notice.NEEDS_ANSWER, "req:run_a:p$it:$gen", gen, replay = false)
        }
        assertTrue("pending ${notifier.pending}", notifier.pending <= 2)
        release.countDown()
        notifier.idle()
    }

    @Test
    fun chatUriEncodesTheKey() {
        val uri = chatUri(7, "apofis-a/b c")
        assertEquals("apofis", uri.scheme)
        assertEquals("chat", uri.host)
        assertEquals(listOf("7", "apofis-a/b c"), uri.pathSegments)
    }
}
