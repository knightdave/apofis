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
import java.util.concurrent.TimeUnit

/** Implemented by the Application class; [sendQueue] is null while the app is not connected (no credentials). */
interface SendQueueProvider {
    val sendQueue: SendQueue?
}

/** Drains the send queue in the background until nothing is left queued. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val queue = (applicationContext as? SendQueueProvider)?.sendQueue ?: return Result.success()
        // Result.retry() takes no delay, so a server Retry-After cannot be honoured here; the exponential backoff applies.
        return if (queue.drain()) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "apofis-send"

        // REPLACE starts the drain at once with a fresh backoff. Cancelling a running drain is safe: an interrupted
        // start stays queued and is retried with the same body and idempotency key.
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SendWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
