package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import java.util.concurrent.TimeUnit

/** Implemented by the Application class; [sendQueue] is null while the app is not connected (no credentials). */
interface SendQueueProvider {
    val sendQueue: SendQueue?
}

/** Drains the send queue in the background until nothing is left to send. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val queue = (applicationContext as? SendQueueProvider)?.sendQueue ?: return Result.success()
        // Result.retry() takes no delay, so a server Retry-After cannot be honoured here; the exponential backoff applies.
        return if (queue.drainSettlingRuns()) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "apofis-send"

        /** Like [schedule], but keeps work that is already enqueued or running: safe to call on every start. */
        fun ensureScheduled(context: Context) =
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request())

        // REPLACE starts the drain at once with a fresh backoff. Cancelling a running drain is safe: a steer in flight
        // is not cancelled and completes, and an interrupted start stays queued and is retried with the same body and
        // idempotency key. Returns once WorkManager stored it.
        suspend fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request()).await()
        }

        private fun request() = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
