package xyz.rycerz.apofis.push

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

/** Implemented by the Application class; null while not connected. */
interface WakeProvider {
    val wakeTarget: WakeTarget?
}

/** What a wake job checks: the graph of connection epoch [generation], its notifier, and the wall clock for dues. */
class WakeTarget(val connected: Connected, val generation: Long, val notifier: Notifier, val clock: () -> Long)

internal enum class Outcome { DONE, RECHECK, RETRY }

private const val PASSES = 3
private const val ATTEMPTS = 3
private val NOTICED = setOf("completed", "failed", "interrupted")

/**
 * A wake job (`apofis-wake`): up to [PASSES] passes of [check] while demand keeps arriving, then one trailing job. A
 * periodic (`apofis-check`) or recheck (`apofis-recheck`) tick only adds demand and schedules the wake job, so every
 * check runs in that one serialized job.
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val cause = inputData.getString(CAUSE) ?: CAUSE_PUSH
        if (!inputData.keyValueMap.containsKey(EPOCH)) { // a tick
            if (!Wake.onPush(c, connectionEpoch(c))) return Result.retry()
            WakeScheduler.schedule(c, cause)
            return Result.success()
        }
        val epoch = inputData.getLong(EPOCH, -1)
        if (epoch != connectionEpoch(c)) return Result.success() // another connection's job: no request, no change
        val target = (c as? WakeProvider)?.wakeTarget?.takeIf { it.generation == epoch }
        for (pass in 1..PASSES) {
            val seen = WakeScheduler.claim(c, epoch)
            val due = seen > counter(wakePrefs(c), WAKE_DONE, epoch)
            val outcome = try {
                if (target != null && due) check(c, target, cause) else Outcome.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Apofis", "Wake check failed: ${e.javaClass.name}")
                Outcome.RETRY // the database or the notifier's lane: try again
            }
            if (outcome == Outcome.RETRY && runAttemptCount < ATTEMPTS) return Result.retry()
            if (!WakeScheduler.complete(c, epoch, seen, pass == PASSES, cause)) break
        }
        return Result.success()
    }
}

/** Test-only: runs after each settle of a check, with the run id. */
internal var afterSettle: suspend (String) -> Unit = {}

/**
 * One check, the only effect of a wake: the outbox is replayed, then up to [MAX_RUNS_PER_CHECK] stored runs of this
 * connection are settled, in start order from after the persisted cursor (wrapping). A run whose row is still stored
 * afterwards (still going, or its finish did not commit) is due a recheck. Runs this phone did not store never appear.
 * A refused key ends the check; one run's failure never stops the others.
 */
internal suspend fun check(context: Context, target: WakeTarget, cause: String = CAUSE_PUSH): Outcome {
    val conn = target.connected
    val runs = conn.db.activeRunDao()
    target.notifier.replayOutbox()
    val p = wakePrefs(context)
    val sorted = runs.all().sortedWith(compareBy({ it.startedAt }, { it.runId }))
    val cursor = if (ofEpoch(p, target.generation)) p.getString(CURSOR_ID, null)?.let { p.getLong(CURSOR_AT, 0) to it } else null
    val start = cursor?.let { (at, id) -> sorted.indexOfFirst { it.startedAt > at || (it.startedAt == at && it.runId > id) } }?.takeIf { it >= 0 } ?: 0
    val batch = (sorted.drop(start) + sorted.take(start)).take(MAX_RUNS_PER_CHECK)
    var retry = false
    var notices = 0
    for (run in batch) {
        if (!conn.isOpen) return Outcome.DONE // switched meanwhile
        try {
            val status = conn.follower.settle(run.runId)
            if (status in NOTICED && runs.get(run.runId) == null) notices++
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiError.Unauthorized) {
            return Outcome.DONE // nothing more until the key is replaced
        } catch (e: Exception) {
            if (e is ApiError.Network || e is ApiError.Unavailable || e is NoticeNotDelivered) retry = true
            else Log.w("Apofis", "Wake check: run failed: ${e.javaClass.name}")
        }
        afterSettle(run.runId)
    }
    target.notifier.idle() // what the check handed over is posted before the job ends
    val stored = runs.all().map { it.runId }.toSet()
    WakeScheduler.afterCheck(context, target.generation, batch.map { it.runId }, stored, batch.lastOrNull()?.let { it.startedAt to it.runId }, target.clock())
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.i("ApofisWake", "cause=$cause runs=${batch.size} notices=$notices")
    return when {
        retry -> Outcome.RETRY
        batch.any { it.runId in stored } -> Outcome.RECHECK
        else -> Outcome.DONE
    }
}
