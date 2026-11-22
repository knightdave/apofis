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
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * The network job verifies (hourly: record present, current and renewed, distributor installed) and then publishes; the
 * local job only reconciles. Each retries (with backoff) whenever it could not act.
 */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val registrar = (applicationContext as? PushSinkProvider)?.pushRegistrar ?: return Result.retry()
        val done = if (inputData.getBoolean(LOCAL, false)) {
            registrar.reconcileLocal()
        } else {
            // A failed verification keeps its error (no false On) and retries; it is not covered by a local fast path.
            registrar.verifyNow(force = false) && registrar.publishPending()
        }
        return if (done) Result.success() else Result.retry()
    }

    companion object {
        const val NAME = "apofis-push-publish"
        const val LOCAL_NAME = "apofis-push-revoke"
        private const val LOCAL = "local"

        /** Replaces any queued run; returns at once (WorkManager stores it asynchronously). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request())
        }

        /**
         * At process start and after a switch: schedules when any persisted intent is left, and whenever push is on, so the
         * registration is confirmed again once per process (an endpoint update lost to a failed commit is resent).
         */
        fun recover(context: Context, prefs: SharedPreferences = pushPrefs(context)) {
            // Any persisted push state schedules the job, which decides the rest (it reads the connection epoch, not this).
            if (prefs.getBoolean(Keys.ENABLED, false) || prefs.contains(Keys.REVOKE) || prefs.contains(Keys.UNREGISTERED_INSTANCE) ||
                prefs.contains(Keys.PENDING_ID)
            ) {
                schedule(context)
                // Revocation and registration are local broadcasts: they run without waiting for a network.
                WorkManager.getInstance(context).enqueueUniqueWork(LOCAL_NAME, ExistingWorkPolicy.REPLACE, request(local = true))
            }
        }

        private fun request(local: Boolean = false) = OneTimeWorkRequestBuilder<PublishWorker>()
            .apply { if (local) setInputData(workDataOf(LOCAL to true)) else setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
    }
}
