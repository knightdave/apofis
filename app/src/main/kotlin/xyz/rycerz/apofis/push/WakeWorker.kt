package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.connectionEpoch
import xyz.rycerz.apofis.notify.NoticeNotDelivered
import xyz.rycerz.apofis.notify.Notifier

/** Implemented by the Application class; null while not connected or while a connection is being published. */
interface WakeProvider {
    val wakeTarget: WakeTarget?
}

/** What a wake job checks: the graph of connection epoch [generation] and its notifier. */
class WakeTarget(val connected: Connected, val generation: Long, val notifier: Notifier)

internal enum class Outcome { DONE, UNFINISHED, RETRY }

private const val EXECUTIONS = 3 // a network or unavailable error: three executions in total

// The fairness cursor: its own file, with the epoch it belongs to; written only by the running check.
internal const val CURSOR_PREFS = "wake_cursor"
internal const val CURSOR_EPOCH = "epoch"
internal const val CURSOR_AT = "at"
internal const val CURSOR_ID = "id"

/** Test-only: runs at "cleared" (the mark is gone), "entered" (the check holds its lock), "settled <run id>", "checked". */
internal var wakeStep: suspend (String) -> Unit = {}

/**
 * One check body at a time, process wide. A replaced check keeps it until it has left its non-cancellable work (a
 * finish commit together with its hand-over to the notifier), so its successor cannot replay that finish silently.
 * Installing a successor never waits for it; it is never held under the push lock or the switch lock.
 */
private val checking = Mutex()

/**
 * The periodic tick (`apofis-check`) only asks the lane for the wake job. The wake job (`apofis-wake`): for its epoch,
 * with a connection of that epoch, it clears the mark and runs one [check]; while stored runs are unfinished it asks
 * for one delayed follow-up, at most three in a row. A job that clears a mark starts a fresh sequence. It writes no
 * completion: a push during the check sets the mark again and replaces the job; a process death leaves the persisted
 * job to WorkManager.
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val epoch = inputData.getLong(EPOCH, -1)
        val cause = inputData.getString(CAUSE) ?: CAUSE_PUSH
        if (epoch != connectionEpoch(c)) return Result.success() // another connection's work: no effect
        if (cause == CAUSE_TICK) {
            Wake.post(c, WakeRequest.Tick)
            return Result.success()
        }
        // No connection of this epoch yet (being published, or disconnected): the mark stays for publication.
        val target = (c as? WakeProvider)?.wakeTarget?.takeIf { it.generation == epoch } ?: return Result.success()
        val cleared = clearPendingWake(c, epoch)
        when (cleared) {
            Cleared.STALE -> return Result.success()
            Cleared.FAILED -> return Result.retry()
            else -> wakeStep("cleared")
        }
        val outcome = try {
            check(c, target, cause)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.RETRY // the database or the notifier's lane (the check's own summary line says so)
        }
        if (outcome == Outcome.RETRY && runAttemptCount < EXECUTIONS - 1) {
            // The fresh budget of a push this job consumed must outlast this execution: its mark goes back.
            if (cleared == Cleared.REMOVED) restorePendingWake(c, epoch)
            return Result.retry()
        }
        val followUp = if (cleared == Cleared.REMOVED) 0 else inputData.getInt(FOLLOW_UP, 0)
        val unfinished = outcome == Outcome.UNFINISHED || (outcome == Outcome.RETRY && runCatching { target.connected.db.activeRunDao().any() }.getOrDefault(false))
        if (unfinished && followUp < RECHECK_DELAYS_S.size) Wake.post(c, WakeRequest.FollowUp(followUp + 1, id))
        return Result.success()
    }
}

/**
 * One check, the only effect of a wake: the outbox is replayed for this epoch, then up to [MAX_RUNS_PER_CHECK] stored
 * runs are settled, in start order from after this epoch's cursor (wrapping). Runs this phone did not store never
 * appear. A refused key ends the check; one run's failure never stops the others. In debuggable builds exactly one
 * summary line is written per check, on every path.
 */
@SuppressLint("UseKtx", "ApplySharedPref") // the cursor's commit result is not needed: it only rotates the batches
internal suspend fun check(context: Context, target: WakeTarget, cause: String = CAUSE_PUSH): Outcome = checking.withLock {
    wakeStep("entered")
    var runs = 0
    var settled = 0
    var failed = 0
    var result: Outcome? = null
    try {
        result = scan(context, target, onTaken = { runs = it }, onSettled = { ok -> if (ok) settled++ else failed++ })
        result
    } finally {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.i("ApofisWake", "cause=$cause runs=$runs settled=$settled failed=$failed outcome=${result?.name ?: "none"}")
        }
    }
}

@SuppressLint("UseKtx", "ApplySharedPref")
private suspend fun scan(context: Context, target: WakeTarget, onTaken: (Int) -> Unit, onSettled: (Boolean) -> Unit): Outcome {
    val conn: Connected = target.connected
    val dao = conn.db.activeRunDao()
    target.notifier.replayOutbox(target.generation)
    val cursorPrefs = context.getSharedPreferences(CURSOR_PREFS, Context.MODE_PRIVATE)
    val sorted = dao.all().sortedWith(compareBy({ it.startedAt }, { it.runId }))
    val cursor = cursorPrefs.takeIf { it.getLong(CURSOR_EPOCH, -1) == target.generation }?.getString(CURSOR_ID, null)?.let { cursorPrefs.getLong(CURSOR_AT, 0) to it }
    val start = cursor?.let { (at, id) -> sorted.indexOfFirst { it.startedAt > at || (it.startedAt == at && it.runId > id) } }?.takeIf { it >= 0 } ?: 0
    val batch = (sorted.drop(start) + sorted.take(start)).take(MAX_RUNS_PER_CHECK)
    onTaken(batch.size)
    var retry = false
    for (run in batch) {
        if (!conn.isOpen) return Outcome.DONE // switched meanwhile
        try {
            conn.follower.settle(run.runId)
            onSettled(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            onSettled(false)
            return Outcome.DONE // nothing more until the key is replaced
        } catch (e: Exception) {
            onSettled(false)
            if (e is ApiError.Network || e is ApiError.Unavailable || e is NoticeNotDelivered) retry = true
        }
        wakeStep("settled ${run.runId}")
    }
    batch.lastOrNull()?.let {
        cursorPrefs.edit().putLong(CURSOR_EPOCH, target.generation).putLong(CURSOR_AT, it.startedAt).putString(CURSOR_ID, it.runId).commit()
    }
    wakeStep("checked")
    return when {
        retry -> Outcome.RETRY
        dao.any() -> Outcome.UNFINISHED
        else -> Outcome.DONE
    }
}
