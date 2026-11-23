package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import xyz.rycerz.apofis.connectionEpoch

/** While a stored run is unfinished, follow-up checks come this many seconds after the check before, at most three. */
val RECHECK_DELAYS_S = intArrayOf(10, 30, 90)
const val MAX_RUNS_PER_CHECK = 8

internal const val WAKE_WORK = "apofis-wake"
internal const val CHECK_WORK = "apofis-check"
internal const val EPOCH = "epoch"
internal const val CAUSE = "cause"
internal const val FOLLOW_UP = "follow_up" // follow-ups already in this sequence, 0 for a fresh one
internal const val CAUSE_PUSH = "push"
internal const val CAUSE_PERIODIC = "periodic"
internal const val CAUSE_RECHECK = "recheck"
internal const val CAUSE_TICK = "tick" // the periodic work itself: it only asks the lane for the wake job

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

/** Under the push lock: the mark for [epoch] is set again (a retrying job that consumed it), while [epoch] is current. */
internal fun restorePendingWake(c: Context, epoch: Long): Boolean = PushStore.locked {
    epoch == connectionEpoch(c) && commitOrRestore(wakePrefs(c)) { putLong(WAKE_PENDING, epoch) }
}

internal enum class Cleared { REMOVED, NONE, STALE, FAILED }

/**
 * Under the push lock: [epoch] is still current and no wake mark is left. Only a mark of [epoch] is a consumed push
 * (REMOVED); an older value goes in the same checked commit and counts as no mark (NONE).
 */
internal fun clearPendingWake(c: Context, epoch: Long): Cleared = PushStore.locked {
    val p = wakePrefs(c)
    if (epoch != connectionEpoch(c)) return@locked Cleared.STALE
    if (!p.contains(WAKE_PENDING)) return@locked Cleared.NONE
    val consumed = p.getLong(WAKE_PENDING, 0) == epoch
    when {
        !commitOrRestore(p) { remove(WAKE_PENDING) } -> Cleared.FAILED
        consumed -> Cleared.REMOVED
        else -> Cleared.NONE
    }
}

/** What a producer asks of the scheduling lane: a cause only, never an epoch or a decision. */
internal sealed interface WakeRequest {
    /** After a push, at process start, at connection publication. */
    data object Recover : WakeRequest
    data object Tick : WakeRequest
    /** Follow-up [n] of the sequence, asked by the job [from] at its end; dropped once [from] was replaced. */
    data class FollowUp(val n: Int, val from: UUID) : WakeRequest
    /** The periodic check, from whether runs are stored when the lane gets to it. */
    class Reconcile(val runsStored: suspend () -> Boolean) : WakeRequest
}

/**
 * The sink of delivered pushes. A push carries nothing this app reads: its one effect is the stored mark "a wake is
 * pending for this epoch" and a request for the one wake job, which checks this phone's own outbox and stored runs.
 *
 * Every scheduling request goes through one serial lane. In one step the lane reads the current epoch, the mark and
 * (for the periodic check) whether runs are stored, and submits for that state, waiting for WorkManager's answer. So a
 * request formed under an old connection, or by a job already replaced, can only submit for the current state. A
 * pending mark always wins: it gets an immediate, expedited job with a fresh follow-up budget, whatever the cause.
 */
object Wake : PushSink {
    private val lane = Executors.newSingleThreadExecutor { Thread(it, "apofis-wake").apply { isDaemon = true } }

    // The periodic decision (epoch, runs stored) the lane applied last; lane only. Nothing else changes that work.
    private var periodicApplied: Pair<Long, Boolean>? = null

    /** Test-only: a new process (the lane's memory of what it applied is gone). */
    internal fun newProcess() = lane.submit { periodicApplied = null }.get(10, TimeUnit.SECONDS)

    /** Test-only: runs in the lane at the start of each request's step. */
    internal var laneStep: (WakeRequest) -> Unit = {}

    /** Test-only: runs before each WorkManager operation of the lane; a throw is a failed operation. */
    internal var beforeInstall: () -> Unit = {}

    /** Under the push lock (held by the receiver): the checked commit of the mark for [epoch], nothing else. */
    override fun onPush(context: Context, epoch: Long): Boolean =
        PushStore.locked { commitOrRestore(wakePrefs(context)) { putLong(WAKE_PENDING, epoch) } }

    /** Posts and returns; nobody waits on the lane. */
    override fun recover(context: Context) = post(context, WakeRequest.Recover)

    internal fun post(context: Context, request: WakeRequest) = lane.execute { runCatching { decide(context, request) } }

    /** Returns once every request posted before has been handled; for tests (bounded, so a stuck test fails). */
    internal fun idle() {
        lane.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun decide(c: Context, r: WakeRequest) {
        laneStep(r)
        val wm = WorkManager.getInstance(c)
        val epoch = connectionEpoch(c)
        when (r) {
            is WakeRequest.Reconcile -> {
                val decision = epoch to runBlocking { r.runsStored() }
                if (decision == periodicApplied) return
                if (reconcile(wm, decision)) periodicApplied = decision
            }
            else -> {
                val job = when {
                    pendingWake(c) == epoch -> wakeJob(workDataOf(EPOCH to epoch, CAUSE to CAUSE_PUSH, FOLLOW_UP to 0), expedited = true)
                    r is WakeRequest.FollowUp -> {
                        val asker = wm.getWorkInfoById(r.from).get()?.state
                        if (asker == null || asker == WorkInfo.State.CANCELLED) return // replaced meanwhile
                        wakeJob(workDataOf(EPOCH to epoch, CAUSE to CAUSE_RECHECK, FOLLOW_UP to r.n), delayS = RECHECK_DELAYS_S[r.n - 1])
                    }
                    r is WakeRequest.Tick -> wakeJob(workDataOf(EPOCH to epoch, CAUSE to CAUSE_PERIODIC, FOLLOW_UP to 0))
                    else -> return // a recover with no mark
                }
                install { wm.enqueueUniqueWork(WAKE_WORK, ExistingWorkPolicy.REPLACE, job) }
            }
        }
    }

    /** Installs (UPDATE: the running schedule is kept and takes the epoch) or cancels the periodic check; true when done. */
    private fun reconcile(wm: WorkManager, decision: Pair<Long, Boolean>): Boolean {
        val (epoch, runsStored) = decision
        if (!runsStored) return install { wm.cancelUniqueWork(CHECK_WORK) }
        val periodic = PeriodicWorkRequestBuilder<WakeWorker>(15, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setInputData(workDataOf(EPOCH to epoch, CAUSE to CAUSE_TICK))
            .build()
        return install { wm.enqueueUniquePeriodicWork(CHECK_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic) }
    }

    private fun wakeJob(input: Data, expedited: Boolean = false, delayS: Int = 0): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WakeWorker>()
            .apply {
                if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                if (delayS > 0) setInitialDelay(delayS.toLong(), TimeUnit.SECONDS)
            }
            .setConstraints(connected)
            .setInputData(input)
            .build()

    /** Waits for WorkManager's answer; one more try on failure, then the mark stays for the next trigger. */
    private fun install(op: () -> Operation): Boolean {
        repeat(2) {
            try {
                beforeInstall()
                op().result.get()
                return true
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (_: Exception) {
            }
        }
        return false
    }
}

private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
