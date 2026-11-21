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
import android.service.notification.StatusBarNotification
import androidx.room.withTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.rycerz.apofis.MainActivity
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.NoticeStateEntity

const val CHANNEL_REPLIES = "replies"
const val CHANNEL_ANSWERS = "answers"
private const val ID = 1 // one notification per chat: the tag is the chat key, so no two keys can collide
private const val SILENT_GROUP = "silent"
private const val MAX_ALERTED = 8
private const val SUPPRESSED = "!" // event ids never start with it ("req:" or "run:")
// What a posted notification stands for, read back for exact cancellation and startup reconciliation.
private const val EXTRA_EPOCH = "xyz.rycerz.apofis.epoch"
private const val EXTRA_RUN = "xyz.rycerz.apofis.run"
private const val EXTRA_KIND = "xyz.rycerz.apofis.kind"
private const val EXTRA_EVENT = "xyz.rycerz.apofis.event"

enum class Notice { FINISHED, FAILED, NEEDS_ANSWER }

/** Where run state hands verified events; [generation] is the connection epoch the event came from. */
fun interface NoticeSink {
    suspend fun notice(sessionKey: String, runId: String, runStartedAt: Long, notice: Notice, eventId: String, generation: Long, replay: Boolean)

    /** Returns once everything handed over before is delivered (posted, or durably decided). */
    suspend fun delivered() {}
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

/** A control transition of one chat waiting in the lane; a later one replaces it (the latest accepted state wins). */
private class Control(@Volatile var op: suspend () -> Unit)

/**
 * Posts one generic notification per chat from verified run state, never from a push.
 *
 * Every operation runs in one ordered lane, off the main thread, in the order it was handed over. Each one reads the
 * current connection epoch when it acts and does nothing on a mismatch but remove its own stale row or notification;
 * a switch bumps the epoch inside the lane, so no delivery can pass the check and post after it. Per chat, an event of
 * an older run (start time, then id) is dropped, a finish replaces its own run's request, and nothing of a run follows
 * its finish; a request alerts once. The lane holds at most one sweep and one control transition per chat.
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
    private val sweepQueued = AtomicBoolean()
    private val queued = AtomicInteger()
    private val controls = ConcurrentHashMap<String, Control>()
    private val postedHere = LinkedHashSet<String>() // request events posted by this process; lane only

    /** Test-only: runs in the lane before a delivery's final transaction, to fail or hold it there. */
    internal var beforeAck: suspend () -> Unit = {}

    /** Test-only: runs in the lane right before a post, after every check. */
    internal var beforePost: suspend () -> Unit = {}

    init {
        scope.launch(Dispatchers.IO) {
            for (op in lane) {
                try {
                    op()
                    queued.decrementAndGet()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    queued.decrementAndGet() // an outbox row whose delivery failed stays for the next replay
                }
            }
        }
        // A chat hidden, deleted or evicted takes its notification and order with it, after any delivery in flight.
        scope.launch { db.invalidationTracker.createFlow("SessionEntity", emitInitialState = false).collect { requestSweep() } }
    }

    private fun enqueue(op: suspend () -> Unit) {
        queued.incrementAndGet()
        lane.trySend(op)
    }

    /** Operations handed over and not yet finished; for tests. */
    internal val pending: Int get() = queued.get()

    private suspend fun <T> inLane(op: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        enqueue { done.completeWith(runCatching { op() }) }
        return done.await()
    }

    /** Returns once every operation handed over before has run. */
    internal suspend fun idle() = inLane {}

    override suspend fun delivered() = idle()

    fun ensureChannels() = ensureChannels(context)

    /** Hands the event to the lane and returns. A finish is queued as is; a request takes the chat's control slot. */
    override suspend fun notice(sessionKey: String, runId: String, runStartedAt: Long, notice: Notice, eventId: String, generation: Long, replay: Boolean) {
        val e = NoticeStateEntity(sessionKey, generation, runStartedAt, runId, notice.name, eventId)
        if (notice == Notice.NEEDS_ANSWER) control(sessionKey) { request(e) } else enqueue { finish(e, replay) }
    }

    /** No answer is pending any more for [runId]: its request notification goes, never another run's notice. */
    fun clearNeedsAnswer(sessionKey: String, runId: String, generation: Long) = control(sessionKey) {
        if (generation == generation()) {
            shownIn(sessionKey).filter { it.extra(EXTRA_KIND) == Notice.NEEDS_ANSWER.name && it.extra(EXTRA_RUN) == runId && it.epoch() == generation }
                .forEach { nm.cancel(sessionKey, it.id) }
        }
    }

    private fun control(key: String, op: suspend () -> Unit) {
        val slot = Control(op)
        val queued = controls.putIfAbsent(key, slot)
        if (queued != null) {
            queued.op = op
            // Taken by the lane meanwhile: this transition needs its own place.
            if (controls[key] === queued) return
            controls[key] = slot
        }
        enqueue {
            controls.remove(key, slot)
            slot.op()
        }
    }

    /** Cleanup that reads nothing about chats: rows and notifications of another epoch. Safe while disconnected. */
    suspend fun cleanup() = inLane {
        val current = generation()
        db.noticeOutboxDao().deleteOlder(current)
        nm.activeNotifications.filter { it.epoch() != current }.forEach { nm.cancel(it.tag, it.id) }
    }

    /**
     * On the connected path, at start and at every background check: stale state removed, notifications no longer
     * matching stored state retired (a request whose run is gone, an event superseded by a later decision), then
     * every outbox row of the current epoch posted silently, oldest run first.
     */
    suspend fun replayOutbox() = inLane {
        sweep()
        val current = generation()
        for (sbn in nm.activeNotifications) {
            val key = sbn.tag ?: continue
            val s = db.noticeStateDao().get(key)?.takeIf { it.generation == current }
            val request = sbn.extra(EXTRA_KIND) == Notice.NEEDS_ANSWER.name
            val runGone = request && db.activeRunDao().get(sbn.extra(EXTRA_RUN).orEmpty()) == null
            val decided = s != null && (s.eventId == sbn.extra(EXTRA_EVENT) || (request && s.notice == Notice.NEEDS_ANSWER.name && s.runId == sbn.extra(EXTRA_RUN)))
            if (runGone || !decided) nm.cancel(key, sbn.id)
        }
        for (row in db.noticeOutboxDao().all(current)) {
            finish(NoticeStateEntity(row.sessionKey, row.generation, row.runStartedAt, row.runId, row.notice, row.eventId), replay = true)
        }
    }

    /** The chat ([generation], [sessionKey]) was opened: its notification goes. */
    fun cancel(sessionKey: String, generation: Long) = enqueue { if (generation == generation()) nm.cancel(sessionKey, ID) }

    /** A server switch: [bump] commits the new epoch inside the lane, then every notification, row and order goes. */
    suspend fun switchEpoch(bump: () -> Unit) = inLane {
        bump()
        nm.cancelAll()
        db.noticeOutboxDao().clear()
        db.noticeStateDao().clear()
        postedHere.clear()
    }

    private fun requestSweep() {
        if (sweepQueued.compareAndSet(false, true)) enqueue {
            sweepQueued.set(false) // a change during this sweep queues the next one
            sweep()
        }
    }

    /** Notifications of another epoch or of a chat that is gone or hidden go, with the order of such chats. */
    private suspend fun sweep() {
        val current = generation()
        db.noticeOutboxDao().deleteOlder(current)
        db.noticeStateDao().deleteStale(current)
        for (sbn in nm.activeNotifications) {
            val key = sbn.tag ?: continue
            if (sbn.epoch() != current || !usable(key)) nm.cancel(key, sbn.id)
        }
    }

    private suspend fun usable(key: String) = db.sessionDao().get(key)?.hidden == false

    /** A chat that may be named: on this phone and not hidden. */
    private suspend fun title(key: String): String? =
        db.sessionDao().get(key)?.takeIf { !it.hidden }?.let { it.title?.takeIf(String::isNotBlank) ?: context.getString(R.string.chat_new_title) }

    private fun shownIn(key: String) = nm.activeNotifications.filter { it.tag == key }
    private fun StatusBarNotification.extra(name: String) = notification.extras.getString(name)
    private fun StatusBarNotification.epoch() = notification.extras.getLong(EXTRA_EPOCH, -1)

    /** The stored order of [e]'s chat in its epoch; null when [e] must be dropped (older run, or its run finished). */
    private suspend fun order(e: NoticeStateEntity): Pair<NoticeStateEntity?, Boolean>? {
        val s = db.noticeStateDao().get(e.sessionKey)?.takeIf { it.generation == e.generation } ?: return null to false
        val order = compareValuesBy(e, s, NoticeStateEntity::runStartedAt, NoticeStateEntity::runId)
        if (order < 0) return null // an older run never replaces a newer run's notice
        if (order == 0 && s.notice != Notice.NEEDS_ANSWER.name) return null // nothing of a run after its finish
        return s to (order == 0)
    }

    private fun showable(e: NoticeStateEntity, title: String?) = title != null && visibleChat() != e.sessionKey && allowed(Notice.valueOf(e.notice))

    /** A finish: posted, then its order written with the removal of its row; a suppressed one is consumed first. */
    private suspend fun finish(e: NoticeStateEntity, replay: Boolean) {
        val outbox = db.noticeOutboxDao()
        if (e.generation != generation()) return outbox.delete(e.generation, e.eventId)
        if (replay && outbox.get(e.generation, e.eventId) == null) return // done meanwhile
        if (order(e) == null) return outbox.delete(e.generation, e.eventId)
        val title = title(e.sessionKey) // read with the other checks, right before the post
        if (!showable(e, title)) {
            consume(e)
            nm.cancel(e.sessionKey, ID) // whatever it showed is superseded; a death before this is reconciled at start
            return
        }
        post(e, title!!, silent = replay)
        beforeAck()
        consume(e)
    }

    private suspend fun consume(e: NoticeStateEntity) = db.withTransaction {
        db.noticeStateDao().upsert(e)
        db.noticeOutboxDao().delete(e.generation, e.eventId)
    }

    /**
     * A request: alerts once per request id. Its order is written before the post, so a death after it never alerts
     * again; after a death before or after the post, a request already decided but not in the tray is re-posted silently.
     */
    private suspend fun request(e: NoticeStateEntity) {
        if (e.generation != generation()) return
        if (db.activeRunDao().get(e.runId) == null) return // settled meanwhile: its clear came first
        val (s, sameRun) = order(e) ?: return
        // Each entry is an event id, prefixed with SUPPRESSED when it was deliberately not shown.
        val alerted = if (sameRun) Json.decodeFromString<List<String>>(s!!.alerted) else emptyList()
        var silent = false
        if (alerted.any { it.removePrefix(SUPPRESSED) == e.eventId }) {
            if (SUPPRESSED + e.eventId in alerted) return // consumed: never shown later
            if (e.eventId in postedHere || shownIn(e.sessionKey).any { it.extra(EXTRA_EVENT) == e.eventId }) return
            silent = true // decided before a death: posted or not, it comes back without a second alert
        }
        val title = title(e.sessionKey)
        val show = showable(e, title)
        val entry = if (show) e.eventId else SUPPRESSED + e.eventId
        val kept = alerted.filter { it.removePrefix(SUPPRESSED) != e.eventId } + entry
        db.noticeStateDao().upsert(e.copy(alerted = Json.encodeToString(kept.takeLast(MAX_ALERTED))))
        if (!show) return nm.cancel(e.sessionKey, ID)
        post(e, title!!, silent)
        postedHere += e.eventId
        if (postedHere.size > 64) postedHere.remove(postedHere.first())
    }

    private fun allowed(notice: Notice): Boolean {
        val a = notificationAccess(context, prefs)
        val runtime = a.runtime == Runtime.GRANTED || a.runtime == Runtime.NOT_NEEDED
        return runtime && a.appEnabled && if (notice == Notice.NEEDS_ANSWER) a.answersEnabled else a.repliesEnabled
    }

    @SuppressLint("MissingPermission") // checked in [allowed], in the same lane step
    private suspend fun post(e: NoticeStateEntity, title: String, silent: Boolean) {
        val notice = Notice.valueOf(e.notice)
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
        val extras = Bundle().apply {
            putLong(EXTRA_EPOCH, e.generation)
            putString(EXTRA_RUN, e.runId)
            putString(EXTRA_KIND, e.notice)
            putString(EXTRA_EVENT, e.eventId)
        }
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.lucide_bell)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setExtras(extras)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // Silent as NotificationCompat.setSilent does it: a group child that defers alerting to a summary that never exists.
        if (silent) n.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        beforePost()
        nm.notify(e.sessionKey, ID, n.build())
        // A chat hidden or deleted after the title read: take it back at once (the instant before is a known limit).
        if (!usable(e.sessionKey)) nm.cancel(e.sessionKey, ID)
    }
}
