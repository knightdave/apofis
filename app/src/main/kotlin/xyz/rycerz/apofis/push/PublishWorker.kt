package xyz.rycerz.apofis.push

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

/** Runs [PushRegistrar.publishPending] after a receiver event or at a process start that found work left. */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val registrar = (applicationContext as? PushSinkProvider)?.pushRegistrar ?: return Result.success()
        // Bounded: after the last retry the next process start (or the hourly verify) picks the rest up.
        return if (registrar.publishPending() || runAttemptCount >= MAX_RETRIES) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "apofis-push-publish"
        private const val MAX_RETRIES = 5

        /** From the receiver: replaces any queued run and waits at most 5 s for WorkManager to store it. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request()).result.get(5, TimeUnit.SECONDS)
        }

        /** At process start: an event whose enqueue never completed, a revocation or debt left. */
        fun recover(context: Context) {
            if (needsWork(pushPrefs(context))) WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request())
        }

        private fun request() = OneTimeWorkRequestBuilder<PublishWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
