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

/** A run still going is checked again this many seconds after the check that found it; at most once per entry. */
val RECHECK_DELAYS_S = intArrayOf(10, 30, 90)
const val MAX_RUNS_PER_CHECK = 8

internal const val WAKE_WORK = "apofis-wake"
internal const val RECHECK_WORK = "apofis-recheck"
internal const val CHECK_WORK = "apofis-check"
internal const val EPOCH = "epoch"
internal const val CAUSE = "cause"
internal const val CAUSE_PUSH = "push"
internal const val CAUSE_PERIODIC = "periodic"
internal const val CAUSE_RECHECK = "recheck"
internal const val CURSOR_AT = "cursor_at"
internal const val CURSOR_ID = "cursor_id"
internal const val DUE = "due:" // + run id: "<rechecks scheduled>:<due at, 0 when none is left>"

internal fun wakePrefs(c: Context): SharedPreferences = c.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)

/** The wake state counts only for the epoch it was written under: anything of another epoch reads as empty. */
internal fun ofEpoch(p: SharedPreferences, epoch: Long) = p.getLong(WAKE_EPOCH, -1) == epoch
internal fun counter(p: SharedPreferences, key: String, epoch: Long) = if (ofEpoch(p, epoch)) p.getLong(key, 0) else 0

/**
 * Under the push lock: one checked commit to the wake prefs for [epoch], only while it is the current connection epoch.
 * Another epoch's state is dropped in the same commit (the count of lost wakes stays). A failed commit puts memory back
 * as it is on disk: Android keeps a failed commit in memory, and a later commit of the file would persist it.
 */
@SuppressLint("UseKtx", "ApplySharedPref") // the KTX edit drops commit()'s result
internal fun commitWake(c: Context, epoch: Long, edit: SharedPreferences.Editor.(SharedPreferences) -> Unit): Boolean = PushStore.locked {
    if (epoch != connectionEpoch(c)) return@locked false
    val p = wakePrefs(c)
    val before = p.all
    val e = p.edit()
    if (!ofEpoch(p, epoch)) {
        before.keys.filter { it != WAKE_LOST }.forEach { e.remove(it) }
        e.putLong(WAKE_EPOCH, epoch)
    }
    e.edit(p)
    if (e.commit()) return@locked true
    val back = p.edit()
    p.all.keys.filter { it !in before }.forEach { back.remove(it) }
    before.forEach { (k, v) -> if (v is Long) back.putLong(k, v) else if (v is String) back.putString(k, v) }
    back.commit() // writes what the disk already holds, or fails and leaves memory as the disk anyway
    false
}

/**
 * The sink of delivered pushes. A push carries nothing this app reads: its one effect is a wake demand, and a wake job
 * checks this phone's own outbox and stored runs.
 */
object Wake : PushSink {
    /** Under the push lock (held by the receiver): the checked commit of demand + 1 for [epoch], nothing else. */
    override fun onPush(context: Context, epoch: Long): Boolean =
        commitWake(context, epoch) { putLong(WAKE_DEMAND, counter(it, WAKE_DEMAND, epoch) + 1) }

    /** After the hand-off, and at every process start: demand of the current epoch not done yet gets a wake job. */
    override fun recover(context: Context) = WakeScheduler.schedule(context, CAUSE_PUSH)

    /** While runs are stored: a check about every 15 minutes, push on or off. Not tied to an epoch: it checks the current one. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WakeWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setInputData(workDataOf(CAUSE to CAUSE_PERIODIC))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CHECK_WORK)
    }

    /** A switch, before the epoch moves on: wake and recheck jobs of the old connection go. */
    fun cancelAll(context: Context) = WakeScheduler.cancel(context)
}

private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/**
 * Serializes wake scheduling against the durable demand counter. A wake job reads the demand when it claims and again
 * when it decides to end ([complete]), both under [lock]; [schedule] enqueues only when no job of the epoch will read
 * the demand again. So a demand committed before a schedule call is always seen by a job, and at most one job runs and
 * one waits. In-process only: after a process death, [Wake.recover] at start and WorkManager's own persisted job pick
 * up `demand > done`.
 */
internal object WakeScheduler {
    val lock = Any()
    // The epoch of a wake job that will still read the demand; guarded by [lock]. Tests clear it to model a new process.
    internal var reading: Long? = null

    fun schedule(c: Context, cause: String): Unit = synchronized(lock) {
        val epoch = connectionEpoch(c)
        val p = wakePrefs(c)
        if (counter(p, WAKE_DEMAND, epoch) <= counter(p, WAKE_DONE, epoch) || reading == epoch) return
        enqueue(c, epoch, cause)
        reading = epoch
    }

    /** The demand version this job's pass covers. */
    fun claim(c: Context, epoch: Long): Long = synchronized(lock) {
        reading = epoch
        counter(wakePrefs(c), WAKE_DEMAND, epoch)
    }

    /**
     * True: demand arrived since [seen], run another pass. Otherwise [seen] is done; on the [last] pass newer demand
     * gets one trailing job, queued behind this one.
     */
    fun complete(c: Context, epoch: Long, seen: Long, last: Boolean, cause: String): Boolean = synchronized(lock) {
        val more = epoch == connectionEpoch(c) && counter(wakePrefs(c), WAKE_DEMAND, epoch) > seen
        if (more && !last) return true
        commitWake(c, epoch) { putLong(WAKE_DONE, seen) }
        if (more) enqueue(c, epoch, cause) else if (reading == epoch) reading = null
        false
    }

    fun cancel(c: Context): Unit = synchronized(lock) {
        reading = null
        WorkManager.getInstance(c).run {
            cancelUniqueWork(WAKE_WORK)
            cancelUniqueWork(RECHECK_WORK)
        }
    }

    /**
     * After a check of [epoch]: dues of runs gone are pruned; each checked run still stored whose due has come gets its
     * next recheck while it has one left (an earlier due that has not come stays as it is); the cursor moves to [last].
     * Then the recheck job is set to the earliest due left.
     */
    fun afterCheck(c: Context, epoch: Long, checked: List<String>, stored: Set<String>, last: Pair<Long, String>?, now: Long): Unit = synchronized(lock) {
        val p = wakePrefs(c)
        val old = if (ofEpoch(p, epoch)) p.all.keys.filter { it.startsWith(DUE) }.associate { it.removePrefix(DUE) to due(p.getString(it, null)) } else emptyMap()
        val next = old.filterKeys { it in stored }.toMutableMap()
        for (id in checked) {
            if (id !in stored) continue
            val (n, at) = next[id] ?: (0 to 0L)
            if (at > now) continue
            next[id] = if (n < RECHECK_DELAYS_S.size) n + 1 to now + RECHECK_DELAYS_S[n] * 1000L else n to 0L
        }
        val written = commitWake(c, epoch) {
            (old.keys - next.keys).forEach { remove(DUE + it) }
            next.forEach { (id, d) -> putString(DUE + id, "${d.first}:${d.second}") }
            if (last != null) putLong(CURSOR_AT, last.first).putString(CURSOR_ID, last.second)
        }
        val earliest = next.values.map { it.second }.filter { it > 0 }.minOrNull() ?: return
        if (!written) return
        val request = OneTimeWorkRequestBuilder<WakeWorker>()
            .setInitialDelay((earliest - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(CAUSE to CAUSE_RECHECK))
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(RECHECK_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun due(s: String?): Pair<Int, Long> = s?.split(':')?.let { (it[0].toIntOrNull() ?: 0) to (it.getOrNull(1)?.toLongOrNull() ?: 0) } ?: (0 to 0L)

    // APPEND_OR_REPLACE: a job that has decided to end may still be running; the new one runs after it.
    private fun enqueue(c: Context, epoch: Long, cause: String) {
        val request = OneTimeWorkRequestBuilder<WakeWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(connected)
            .setInputData(workDataOf(EPOCH to epoch, CAUSE to cause))
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WAKE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
