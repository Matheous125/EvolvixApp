package com.example.evolvix.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.sync.SyncController
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Background worker that drives Room ↔ Firestore bidirectional sync (Phase 10.2).
 *
 * Two scheduling modes are used in combination (Pattern: **Command via WorkRequest**):
 *
 * 1. **Periodic** — [enqueuePeriodicSync] schedules a `PeriodicWorkRequest` that
 *    fires every [PERIODIC_INTERVAL_HOURS] hours whenever a network connection is
 *    available. This is the "always-on background sync" path.
 *
 * 2. **On-network-available** — [enqueueOnNetworkSync] schedules a one-shot
 *    `OneTimeWorkRequest` with a [NetworkType.CONNECTED] constraint. WorkManager
 *    holds the request and runs it the moment the device reconnects — this is
 *    the "sync as soon as possible after coming back online" path.
 *
 * Both requests are enqueued idempotently (unique names + KEEP / REPLACE policies)
 * so calling the enqueue functions on every cold start is safe.
 *
 * The worker silently succeeds (returns [Result.success]) when the user is not
 * signed in — sync only makes sense for authenticated sessions.
 *
 * @param context   Application context injected by WorkManager.
 * @param params    WorkManager parameters (not used directly).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /**
     * Performs the sync. Delegates entirely to [SyncController] so the worker
     * remains a thin scheduling shell with no business logic of its own.
     *
     * Returns [Result.failure] on exceptions so WorkManager can retry according
     * to its built-in backoff policy (default: 30-second exponential backoff).
     */
    override suspend fun doWork(): Result {
        // Skip silently when no user is signed in.
        if (FirebaseAuth.getInstance().currentUser == null) return Result.success()

        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            SyncController(db.habitDao(), db.achievementDao()).sync()
            Result.success()
        } catch (e: Exception) {
            // Return failure so WorkManager applies exponential backoff and retries.
            Result.failure()
        }
    }

    companion object {

        /** Unique name for the periodic sync work — prevents duplicates on re-enqueue. */
        private const val PERIODIC_UNIQUE_NAME = "evolvix_periodic_sync"

        /** Unique name for the on-network-available one-shot sync. */
        private const val NETWORK_UNIQUE_NAME = "evolvix_network_sync"

        /** How often the periodic sync fires (WorkManager minimum is 15 minutes). */
        private const val PERIODIC_INTERVAL_HOURS = 1L

        /** Network constraint shared by both request types. */
        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * Enqueues a repeating sync that fires every [PERIODIC_INTERVAL_HOURS] while
         * the device has any network connection.
         *
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so a restart does not reset the timer
         * of an already-scheduled periodic run.
         *
         * Call this once from [com.example.evolvix.MainActivity.onCreate].
         */
        fun enqueuePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(networkConstraint)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Enqueues a one-shot sync that fires as soon as the device connects to any
         * network. WorkManager persists this request across reboots and process deaths,
         * so it will always run — even if the device is currently offline.
         *
         * Uses [ExistingWorkPolicy.REPLACE] so the request is refreshed (and the
         * constraint re-evaluated) every time the user signs in or the app cold-starts.
         *
         * Call this in the same place as [enqueuePeriodicSync].
         */
        fun enqueueOnNetworkSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                NETWORK_UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
