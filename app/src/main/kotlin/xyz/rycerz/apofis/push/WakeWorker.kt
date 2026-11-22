package xyz.rycerz.apofis.push

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
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

/** Test-only: runs at "cleared" (the mark is gone), "settled <run id>" and "checked". */
internal var wakeStep: suspend (String) -> Unit = {}

/** Debuggable builds only: counts and fixed words, never an id, a body or an exception's text. */
private fun debugLog(c: Context, message: String) {
    if (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.i("ApofisWake", message)
}

/**
 * The periodic tick (`apofis-check`) only requests the wake job for its epoch. The wake job (`apofis-wake`): for its
 * epoch, with a connection of that epoch, it clears the mark and runs one [check]; while stored runs are unfinished it
 * requests one delayed follow-up, at most three in a row. It writes no completion: a push during the check sets the
 * mark again and replaces the job; a process death leaves the persisted job to WorkManager.
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val epoch = inputData.getLong(EPOCH, -1)
        val cause = inputData.getString(CAUSE) ?: CAUSE_PUSH
        if (epoch != connectionEpoch(c)) return Result.success() // another connection's work: no effect
        if (cause == CAUSE_TICK) {
            Wake.request(c, epoch, CAUSE_PERIODIC)
            return Result.success()
        }
        // No connection of this epoch yet (being published, or disconnected): the mark stays for publication.
        val target = (c as? WakeProvider)?.wakeTarget?.takeIf { it.generation == epoch } ?: return Result.success()
        when (clearPendingWake(c, epoch)) {
            Cleared.STALE -> return Result.success()
            Cleared.FAILED -> return Result.retry()
            Cleared.CLEARED -> wakeStep("cleared")
        }
        val outcome = try {
            check(c, target, cause)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            debugLog(c, "check failed")
            Outcome.RETRY // the database or the notifier's lane
        }
        if (outcome == Outcome.RETRY && runAttemptCount < EXECUTIONS - 1) return Result.retry()
        val followUp = inputData.getInt(FOLLOW_UP, 0)
        val unfinished = outcome == Outcome.UNFINISHED || (outcome == Outcome.RETRY && runCatching { target.connected.db.activeRunDao().any() }.getOrDefault(false))
        if (unfinished && followUp < RECHECK_DELAYS_S.size) Wake.request(c, epoch, CAUSE_RECHECK, followUp + 1)
        return Result.success()
    }
}

/**
 * One check, the only effect of a wake: the outbox is replayed for this epoch, then up to [MAX_RUNS_PER_CHECK] stored
 * runs are settled, in start order from after the persisted cursor (wrapping). Runs this phone did not store never
 * appear. A refused key ends the check; one run's failure never stops the others. In the foreground the followers own
 * the runs and their notices, so nothing is done.
 */
@SuppressLint("UseKtx", "ApplySharedPref") // the cursor's commit result is not needed: it only rotates the batches
internal suspend fun check(context: Context, target: WakeTarget, cause: String = CAUSE_PUSH): Outcome {
    val conn = target.connected
    if (conn.follower.inForeground) return Outcome.DONE
    val runs = conn.db.activeRunDao()
    target.notifier.replayOutbox(target.generation)
    val p = wakePrefs(context)
    val sorted = runs.all().sortedWith(compareBy({ it.startedAt }, { it.runId }))
    val cursor = p.getString(CURSOR_ID, null)?.let { p.getLong(CURSOR_AT, 0) to it }
    val start = cursor?.let { (at, id) -> sorted.indexOfFirst { it.startedAt > at || (it.startedAt == at && it.runId > id) } }?.takeIf { it >= 0 } ?: 0
    val batch = (sorted.drop(start) + sorted.take(start)).take(MAX_RUNS_PER_CHECK)
    var retry = false
    for (run in batch) {
        if (!conn.isOpen) return Outcome.DONE // switched meanwhile
        try {
            conn.follower.settle(run.runId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            return Outcome.DONE // nothing more until the key is replaced
        } catch (e: Exception) {
            if (e is ApiError.Network || e is ApiError.Unavailable || e is NoticeNotDelivered) retry = true
            else debugLog(context, "run failed")
        }
        wakeStep("settled ${run.runId}")
    }
    batch.lastOrNull()?.let { p.edit().putLong(CURSOR_AT, it.startedAt).putString(CURSOR_ID, it.runId).commit() }
    debugLog(context, "cause=$cause runs=${batch.size}")
    wakeStep("checked")
    return when {
        retry -> Outcome.RETRY
        runs.any() -> Outcome.UNFINISHED
        else -> Outcome.DONE
    }
}
