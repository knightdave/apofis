package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import xyz.rycerz.apofis.connectionEpoch

/** While a stored run is unfinished, follow-up checks come this many seconds after the check before, at most three. */
val RECHECK_DELAYS_S = intArrayOf(10, 30, 90)
const val MAX_RUNS_PER_CHECK = 8

internal const val WAKE_WORK = "apofis-wake"
internal const val CHECK_WORK = "apofis-check"
internal const val EPOCH = "epoch"
internal const val CAUSE = "cause"
internal const val FOLLOW_UP = "follow_up" // follow-ups already in this sequence, 0 for a push or a periodic tick
internal const val CAUSE_PUSH = "push"
internal const val CAUSE_PERIODIC = "periodic"
internal const val CAUSE_RECHECK = "recheck"
internal const val CAUSE_TICK = "tick" // the periodic work itself: it only requests the wake job
internal const val CURSOR_AT = "cursor_at"
internal const val CURSOR_ID = "cursor_id"

internal fun wakePrefs(c: Context): SharedPreferences = c.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)

/**
 * One checked commit of [edit] to [p]. A failed commit puts memory back as it is on disk: Android keeps a failed commit
 * in memory, and a later commit of the file would persist it.
 */
@SuppressLint("UseKtx", "ApplySharedPref") // the KTX edit drops commit()'s result
internal fun commitOrRestore(p: SharedPreferences, edit: SharedPreferences.Editor.() -> Unit): Boolean {
    val before = p.all
    if (p.edit().apply(edit).commit()) return true
    val back = p.edit()
    p.all.keys.filter { it !in before }.forEach { back.remove(it) }
    before.forEach { (k, v) -> if (v is Long) back.putLong(k, v) else if (v is String) back.putString(k, v) }
    back.commit() // writes what the disk already holds, or fails and leaves memory as the disk anyway
    return false
}

/** The epoch a wake is pending for, read under the push lock (so a value whose commit is failing is never seen). */
internal fun pendingWake(c: Context): Long? = PushStore.locked {
    wakePrefs(c).let { if (it.contains(WAKE_PENDING)) it.getLong(WAKE_PENDING, 0) else null }
}

internal enum class Cleared { CLEARED, STALE, FAILED }

/** Under the push lock: [epoch] is still current and no wake mark is left (a mark of an older epoch goes with it). */
internal fun clearPendingWake(c: Context, epoch: Long): Cleared = PushStore.locked {
    val p = wakePrefs(c)
    when {
        epoch != connectionEpoch(c) -> Cleared.STALE
        !p.contains(WAKE_PENDING) || commitOrRestore(p) { remove(WAKE_PENDING) } -> Cleared.CLEARED
        else -> Cleared.FAILED
    }
}

/**
 * The sink of delivered pushes. A push carries nothing this app reads: its one effect is the stored mark "a wake is
 * pending for this epoch" and a request for the one wake job, which checks this phone's own outbox and stored runs.
 */
object Wake : PushSink {
    /** Under the push lock (held by the receiver): the checked commit of the mark for [epoch], nothing else. */
    override fun onPush(context: Context, epoch: Long): Boolean =
        PushStore.locked { commitOrRestore(wakePrefs(context)) { putLong(WAKE_PENDING, epoch) } }

    /** After a push, at process start and when a connection is published: a mark of the current epoch gets the job. */
    override fun recover(context: Context) {
        val epoch = connectionEpoch(context)
        if (pendingWake(context) == epoch) request(context, epoch, CAUSE_PUSH)
    }

    /**
     * The one wake job, replacing any older request (also one waiting in backoff or for the network). A push runs
     * expedited; a follow-up waits [RECHECK_DELAYS_S] for its place in the sequence.
     */
    fun request(context: Context, epoch: Long, cause: String, followUp: Int = 0) {
        val request = OneTimeWorkRequestBuilder<WakeWorker>()
            .apply {
                if (followUp > 0) setInitialDelay(RECHECK_DELAYS_S[followUp - 1].toLong(), TimeUnit.SECONDS)
                else if (cause == CAUSE_PUSH) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .setConstraints(connected)
            .setInputData(workDataOf(EPOCH to epoch, CAUSE to cause, FOLLOW_UP to followUp))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WAKE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** While runs are stored for [epoch]: a tick about every 15 minutes, push on or off; none otherwise. */
    fun reconcilePeriodic(context: Context, epoch: Long, runsStored: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!runsStored) {
            wm.cancelUniqueWork(CHECK_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<WakeWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setInputData(workDataOf(EPOCH to epoch, CAUSE to CAUSE_TICK))
            .build()
        // UPDATE: a tick of another epoch scheduled meanwhile takes this epoch, the running schedule is kept.
        wm.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** A switch, before the epoch moves on: the old connection's wake job and periodic check go. */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(WAKE_WORK)
            cancelUniqueWork(CHECK_WORK)
        }
    }
}

private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
