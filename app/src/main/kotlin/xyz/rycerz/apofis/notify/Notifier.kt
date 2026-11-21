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
import xyz.rycerz.apofis.MainActivity
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.db.AppDatabase

const val CHANNEL_REPLIES = "replies"
const val CHANNEL_ANSWERS = "answers"
private const val ID = 1 // one notification per chat: the tag is the chat key, so no two keys can collide
private const val POSTED = "posted" // the last [POSTED_MAX] event ids decided, newline separated
private const val POSTED_MAX = 64
private const val SILENT_GROUP = "silent"
private const val SHOWN = "shown/" // + chat key: the event the chat's notification stands for (see [Shown])

enum class Notice { FINISHED, FAILED, NEEDS_ANSWER }

/** Where run state hands verified events; [generation] is the connection the event came from. */
fun interface NoticeSink {
    suspend fun notice(sessionKey: String, runId: String, runStartedAt: Long, notice: Notice, eventId: String, generation: Long, replay: Boolean)
}

/** The tap target of a chat's notification; also the PendingIntent's identity, so each chat has its own. */
@SuppressLint("UseKtx") // androidx.core is not a direct dependency
fun chatUri(generation: Long, sessionKey: String): Uri = Uri.parse("apofis://chat/$generation/" + Uri.encode(sessionKey))

/** Creates both channels, or renames them in the current language; the user's settings for them are kept. */
fun ensureChannels(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannels(
        listOf(
            NotificationChannel(CHANNEL_REPLIES, context.getString(R.string.notif_channel_replies), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_ANSWERS, context.getString(R.string.notif_channel_answers), NotificationManager.IMPORTANCE_HIGH),
        ),
    )
}

/** The last event a chat's notification was decided for; [requestId] is empty for a finish. */
private data class Shown(val generation: Long, val runStartedAt: Long, val runId: String, val notice: Notice, val requestId: String, val eventId: String) {
    fun encode() = listOf(generation, runStartedAt, runId, notice, requestId, eventId).joinToString("\t")

    companion object {
        fun decode(s: String?): Shown? = s?.split('\t')?.takeIf { it.size == 6 }?.let {
            Shown(it[0].toLong(), it[1].toLong(), it[2], Notice.valueOf(it[3]), it[4], it[5])
        }
    }
}

/**
 * Posts one generic notification per chat from verified run state, never from a push. Each chat's notification follows
 * the run lifecycle: an event of an older run (by start time, then id) never replaces a newer run's, a finish replaces
 * its own run's request, and nothing of a run is shown after its finish. Events of another connection generation are
 * dropped. One lock serializes these decisions, the persisted per-chat state and every OS call, cancellation included.
 */
@SuppressLint("UseKtx") // commit()'s result matters: the decision is persisted before the post
class Notifier(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val db: AppDatabase,
    private val generation: () -> Long,
    private val visibleChat: () -> String?,
    private val chatTitle: suspend (String) -> String?,
) : NoticeSink {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val lock = Any()

    fun ensureChannels() = ensureChannels(context)

    /**
     * Decides and posts [eventId]; afterwards its outbox row (if any) is deleted whatever the decision, so a blocked or
     * dropped notice leaves no backlog. A [replay] posts silently: the OS post may already have happened before a death.
     */
    override suspend fun notice(sessionKey: String, runId: String, runStartedAt: Long, notice: Notice, eventId: String, generation: Long, replay: Boolean) {
        val outbox = db.noticeOutboxDao()
        if (replay && outbox.get(eventId) == null) return // posted and deleted meanwhile
        val title = chatTitle(sessionKey) // a missing or hidden chat: null
        val requestId = if (notice == Notice.NEEDS_ANSWER) eventId.removePrefix("req:$runId:").substringBeforeLast(':') else ""
        val event = Shown(generation, runStartedAt, runId, notice, requestId, eventId)
        synchronized(lock) {
            if (accepted(sessionKey, event, replay) && remember(sessionKey, event)) {
                if (title != null && visibleChat() != sessionKey && allowed(notice)) post(sessionKey, event, title, replay)
            }
        }
        outbox.delete(eventId)
    }

    /** Every outbox row of the current generation, oldest run first, posted silently; rows of other generations dropped. */
    suspend fun replayOutbox() {
        val current = generation()
        val outbox = db.noticeOutboxDao()
        outbox.deleteOtherGenerations(current)
        for (row in outbox.all()) {
            val notice = runCatching { Notice.valueOf(row.notice) }.getOrNull()
            if (row.generation != current || notice == null) {
                outbox.delete(row.eventId)
                continue
            }
            notice(row.sessionKey, row.runId, row.runStartedAt, notice, row.eventId, row.generation, replay = true)
        }
    }

    fun cancel(sessionKey: String) = synchronized(lock) { nm.cancel(sessionKey, ID) }

    /** The request [requestId] is no longer pending: its notification goes, but only if it still stands for exactly that request. */
    fun clearNeedsAnswer(sessionKey: String, runId: String, requestId: String, generation: Long) = synchronized(lock) {
        val s = shown(sessionKey) ?: return@synchronized
        if (generation == generation() && s.generation == generation && s.notice == Notice.NEEDS_ANSWER && s.runId == runId && s.requestId == requestId) {
            nm.cancel(sessionKey, ID)
        }
    }

    /** A server switch: every notification goes, with the per-chat state of the old connection. */
    fun cancelAll() = synchronized(lock) {
        nm.cancelAll()
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(SHOWN) }.forEach(edit::remove)
        edit.commit()
    }

    private fun shown(key: String) = Shown.decode(prefs.getString(SHOWN + key, null))

    private fun accepted(key: String, e: Shown, replay: Boolean): Boolean {
        if (e.generation != generation()) return false
        val s = shown(key)?.takeIf { it.generation == e.generation }
        if (s != null) {
            val order = compareValuesBy(e, s, Shown::runStartedAt, Shown::runId)
            if (order < 0) return false // an older run never replaces a newer run's notice
            if (order == 0) {
                if (s.eventId == e.eventId) return replay // a duplicate; a replay may re-post it, silently
                if (s.notice != Notice.NEEDS_ANSWER) return false // nothing of a run after its finish
            }
        }
        return replay || e.eventId !in posted()
    }

    private fun posted() = prefs.getString(POSTED, null)?.split('\n').orEmpty()

    /** Persists the decision before the post, so the post is never repeated loudly. False: not stored, nothing posted. */
    private fun remember(key: String, e: Shown): Boolean {
        val ids = (posted() - e.eventId + e.eventId).takeLast(POSTED_MAX)
        return prefs.edit().putString(SHOWN + key, e.encode()).putString(POSTED, ids.joinToString("\n")).commit()
    }

    private fun allowed(notice: Notice): Boolean {
        val a = notificationAccess(context, prefs)
        val runtime = a.runtime == Runtime.GRANTED || a.runtime == Runtime.NOT_NEEDED
        return runtime && a.appEnabled && if (notice == Notice.NEEDS_ANSWER) a.answersEnabled else a.repliesEnabled
    }

    @SuppressLint("MissingPermission") // checked in [allowed], under the same lock
    private fun post(key: String, e: Shown, title: String, silent: Boolean) {
        val channel = if (e.notice == Notice.NEEDS_ANSWER) CHANNEL_ANSWERS else CHANNEL_REPLIES
        val text = context.getString(
            when (e.notice) {
                Notice.FINISHED -> R.string.notif_reply_finished
                Notice.FAILED -> R.string.notif_reply_failed
                Notice.NEEDS_ANSWER -> R.string.notif_needs_answer
            },
        )
        val open = Intent(Intent.ACTION_VIEW, chatUri(e.generation, key)).setClass(context, MainActivity::class.java)
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.lucide_bell)
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
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        // Silent as NotificationCompat.setSilent does it: a group child that defers alerting to a summary that never exists.
        if (silent) n.setGroup(SILENT_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        nm.notify(key, ID, n.build())
    }
}
