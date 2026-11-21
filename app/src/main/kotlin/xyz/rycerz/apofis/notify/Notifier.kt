package xyz.rycerz.apofis.notify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.MainActivity
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeStateEntity

const val CHANNEL_REPLIES = "replies"
const val CHANNEL_ANSWERS = "answers"
private const val ID = 1 // one notification per chat: the tag is the chat key, so no two keys can collide
private const val SILENT_GROUP = "silent"
private const val EXTRA_EPOCH = "xyz.rycerz.apofis.epoch" // the connection epoch a posted notification belongs to

enum class Notice { FINISHED, FAILED, NEEDS_ANSWER }

/** Where run state hands verified events; [generation] is the connection epoch the event came from. */
fun interface NoticeSink {
    suspend fun notice(sessionKey: String, runId: String, runStartedAt: Long, notice: Notice, eventId: String, generation: Long, replay: Boolean)
}

/** The tap target of a chat's notification; also the PendingIntent's identity, so each chat has its own. */
@SuppressLint("UseKtx") // androidx.core is not a direct dependency
fun chatUri(generation: Long, sessionKey: String): Uri = Uri.parse("apofis://chat/$generation/" + Uri.encode(sessionKey))

/** Creates both channels (private on the lock screen), or renames them in the current language; user settings are kept. */
fun ensureChannels(context: Context) {
    fun channel(id: String, name: Int, importance: Int) = NotificationChannel(id, context.getString(name), importance).apply {
        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannels(
        listOf(
            channel(CHANNEL_REPLIES, R.string.notif_channel_replies, NotificationManager.IMPORTANCE_DEFAULT),
            channel(CHANNEL_ANSWERS, R.string.notif_channel_answers, NotificationManager.IMPORTANCE_HIGH),
        ),
    )
}

/**
 * Posts one generic notification per chat from verified run state, never from a push.
 *
 * Every operation runs in one ordered lane, off the main thread, in the order it was handed over. Each one reads the
 * current connection epoch when it acts and does nothing on a mismatch but remove its own stale row or notification;
 * a switch bumps the epoch inside the lane, so no delivery can pass the check and post after it. Per chat, an event of
 * an older run (start time, then id) is dropped, a finish replaces its own run's request, and nothing of a run follows
 * its finish. The per-chat order and the outbox row change together, only after the post (or the decision not to
 * show it) is done.
 */
class Notifier(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val db: AppDatabase,
    private val generation: () -> Long,
    private val visibleChat: () -> String?,
    scope: CoroutineScope,
) : NoticeSink {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val lane = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Test-only: runs in the lane before a delivery's final transaction, to fail or hold it there. */
    internal var beforeAck: suspend () -> Unit = {}

    init {
        scope.launch(Dispatchers.IO) {
            for (op in lane) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // an outbox row whose delivery failed stays for the next replay
                }
            }
        }
        // A chat hidden, deleted or evicted takes its notification and order with it, after any delivery in flight.
        scope.launch { db.invalidationTracker.createFlow("SessionEntity", emitInitialState = false).collect { enqueue { sweep() } } }
    }

    private fun enqueue(op: suspend () -> Unit) {
        lane.trySend(op)
    }

    private suspend fun <T> inLane(op: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        enqueue { done.completeWith(runCatching { op() }) }
        return done.await()
    }

    /** Returns once every operation handed over before has run; for tests. */
    internal suspend fun idle() = inLane {}

    fun ensureChannels() = ensureChannels(context)

    /** Hands the event to the lane and returns; delivered in hand-over order. A [replay] posts silently. */
    override suspend fun notice(sessionKey: String, runId: String, runStartedAt: Long, notice: Notice, eventId: String, generation: Long, replay: Boolean) =
        enqueue { deliver(NoticeStateEntity(sessionKey, generation, runStartedAt, runId, notice.name, eventId), replay) }

    /**
     * At start and at every background check: stale state removed, a request whose run is no longer stored retired
     * (answered, stopped or gone while no follower watched), then every outbox row of the current epoch posted
     * silently, oldest run first.
     */
    suspend fun replayOutbox() = inLane {
        sweep { s -> s?.notice == Notice.NEEDS_ANSWER.name && db.activeRunDao().get(s.runId) == null }
        for (row in db.noticeOutboxDao().all(generation())) {
            deliver(NoticeStateEntity(row.sessionKey, row.generation, row.runStartedAt, row.runId, row.notice, row.eventId), replay = true)
        }
    }

    /** The chat was opened: its notification of epoch [generation] goes. */
    fun cancel(sessionKey: String, generation: Long) = enqueue { if (generation == generation()) nm.cancel(sessionKey, ID) }

    /** No answer is pending any more for [runId]: its request notification goes, never another run's notice. */
    fun clearNeedsAnswer(sessionKey: String, runId: String, generation: Long) = enqueue {
        val s = db.noticeStateDao().get(sessionKey)
        if (generation == generation() && s != null && s.generation == generation && s.runId == runId && s.notice == Notice.NEEDS_ANSWER.name) {
            nm.cancel(sessionKey, ID)
        }
    }

    /** A server switch: [bump] commits the new epoch inside the lane, then every notification, row and order goes. */
    suspend fun switchEpoch(bump: () -> Unit) = inLane {
        bump()
        nm.cancelAll()
        db.noticeOutboxDao().clear()
        db.noticeStateDao().clear()
    }

    /**
     * In the lane: removes what no longer belongs to the current epoch or to a chat that may be named, and what
     * [retired] says is over. Idempotent; also finishes a switch a process death interrupted.
     */
    private suspend fun sweep(retired: suspend (NoticeStateEntity?) -> Boolean = { false }) {
        val current = generation()
        db.noticeOutboxDao().deleteOlder(current)
        db.noticeStateDao().deleteStale(current)
        for (sbn in nm.activeNotifications) {
            val key = sbn.tag ?: continue
            val s = db.noticeStateDao().get(key)
            val stale = sbn.notification.extras.getLong(EXTRA_EPOCH, -1) != current || title(key) == null || retired(s)
            if (stale) nm.cancel(key, sbn.id)
        }
    }

    /** A chat that may be named: on this phone and not hidden. */
    private suspend fun title(key: String): String? =
        db.sessionDao().get(key)?.takeIf { !it.hidden }?.let { it.title?.takeIf(String::isNotBlank) ?: context.getString(R.string.chat_new_title) }

    private suspend fun deliver(e: NoticeStateEntity, replay: Boolean) {
        val outbox = db.noticeOutboxDao()
        val states = db.noticeStateDao()
        if (e.generation != generation()) return outbox.delete(e.generation, e.eventId)
        if (replay && outbox.get(e.generation, e.eventId) == null) return // done meanwhile
        val s = states.get(e.sessionKey)?.takeIf { it.generation == e.generation }
        if (s != null) {
            val order = compareValuesBy(e, s, NoticeStateEntity::runStartedAt, NoticeStateEntity::runId)
            // An older run, the same event again, or anything of a run after its finish.
            if (order < 0 || (order == 0 && (s.eventId == e.eventId || s.notice != Notice.NEEDS_ANSWER.name))) {
                return outbox.delete(e.generation, e.eventId)
            }
        }
        val notice = Notice.valueOf(e.notice)
        val title = title(e.sessionKey) // read together with the other checks, right before the post
        if (title != null && visibleChat() != e.sessionKey && allowed(notice)) {
            post(e, notice, title, silent = replay)
        } else {
            nm.cancel(e.sessionKey, ID) // whatever it showed is superseded by an event that is not shown
        }
        beforeAck()
        db.withTransaction {
            states.upsert(e)
            outbox.delete(e.generation, e.eventId)
        }
    }

    private fun allowed(notice: Notice): Boolean {
        val a = notificationAccess(context, prefs)
        val runtime = a.runtime == Runtime.GRANTED || a.runtime == Runtime.NOT_NEEDED
        return runtime && a.appEnabled && if (notice == Notice.NEEDS_ANSWER) a.answersEnabled else a.repliesEnabled
    }

    @SuppressLint("MissingPermission") // checked in [allowed], in the same lane step
    private fun post(e: NoticeStateEntity, notice: Notice, title: String, silent: Boolean) {
        val channel = if (notice == Notice.NEEDS_ANSWER) CHANNEL_ANSWERS else CHANNEL_REPLIES
        val text = context.getString(
            when (notice) {
                Notice.FINISHED -> R.string.notif_reply_finished
                Notice.FAILED -> R.string.notif_reply_failed
                Notice.NEEDS_ANSWER -> R.string.notif_needs_answer
            },
        )
        val open = Intent(Intent.ACTION_VIEW, chatUri(e.generation, e.sessionKey)).setClass(context, MainActivity::class.java)
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.lucide_bell)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_public))
            .build()
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.lucide_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setExtras(Bundle().apply { putLong(EXTRA_EPOCH, e.generation) })
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // Silent as NotificationCompat.setSilent does it: a group child that defers alerting to a summary that never exists.
        if (silent) n.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        nm.notify(e.sessionKey, ID, n.build())
    }
}
