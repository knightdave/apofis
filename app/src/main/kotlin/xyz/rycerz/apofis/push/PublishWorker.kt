package xyz.rycerz.apofis.push

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs [PushRegistrar.publishPending] until it reports nothing left; retries (with backoff) whenever it could not act. */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val registrar = (applicationContext as? PushSinkProvider)?.pushRegistrar ?: return Result.retry()
        return if (registrar.publishPending()) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "apofis-push-publish"

        /** Replaces any queued run; returns at once (WorkManager stores it asynchronously). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request())
        }

        /** At process start and after a switch: schedules when any persisted intent is left. */
        fun recover(context: Context, prefs: SharedPreferences = pushPrefs(context)) {
            if (needsWork(prefs, pushPolicy.distributors(context))) schedule(context)
        }

        private fun request() = OneTimeWorkRequestBuilder<PublishWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
