package com.example.evolvix.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.domain.usecase.ShouldResetHabitUseCase
import java.time.LocalDateTime

/**
 * WorkManager-backed worker that persists a habit action (Done or Skip) to Room.
 *
 * This Worker replaces the fire-and-forget [CoroutineScope(Dispatchers.IO).launch] pattern
 * that was previously used in [HabitActionReceiver.recordCompletion]. Because WorkManager
 * persists the job to disk before executing it, the write survives even if Android kills
 * the broadcast-receiver process the moment [HabitActionReceiver.onReceive] returns.
 *
 * Acts as the **Command executor** in the Command pattern initiated by [HabitActionReceiver]:
 * the receiver serialises the action into [WorkData], and this Worker deserialises and
 * applies it against the Room database.
 *
 * **Thesis note:** [ShouldResetHabitUseCase] is applied here (same predicate used by
 * [com.example.evolvix.ui.viewmodel.HabitViewModel]) to ensure the reset-race condition
 * described in Phase 9.6.1 cannot recur, regardless of which code path triggers the write.
 */
class RecordHabitActionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val habitId   = inputData.getInt(KEY_HABIT_ID, -1)
        val action    = inputData.getString(KEY_ACTION) ?: return Result.failure()
        if (habitId < 0) return Result.failure()

        return try {
            val db  = AppDatabase.getDatabase(applicationContext)
            val dao = db.habitDao()
            val now = LocalDateTime.now()

            when (action) {
                ACTION_DONE -> {
                    val snoozeCount = inputData.getInt(KEY_SNOOZE_COUNT, 0)
                    val habit = dao.getHabitById(habitId) ?: return Result.failure()

                    // Phase 9.6.1: apply the reset predicate before incrementing so that
                    // lastResetDate is advanced when needed. Without this step,
                    // HabitViewModel.checkAndResetProgress() on the next app launch would
                    // see a stale lastResetDate and zero out the count we are about to write.
                    val baseHabit = if (ShouldResetHabitUseCase()(habit, now)) {
                        val reset = habit.copy(currentCount = 0, lastResetDate = now)
                        dao.updateHabit(reset)
                        reset
                    } else {
                        habit
                    }

                    val newCount  = baseHabit.currentCount + 1
                    val targetHit = newCount == baseHabit.target

                    dao.updateHabit(
                        baseHabit.copy(
                            currentCount         = newCount,
                            totalProgressUpdates = baseHabit.totalProgressUpdates + 1,
                            totalTargetReaches   = if (targetHit) baseHabit.totalTargetReaches + 1
                                                   else baseHabit.totalTargetReaches
                        )
                    )
                    dao.insertCompletion(
                        HabitCompletionEntity(
                            habitId        = habitId,
                            progressUpdate = now,
                            isTargetReached = targetHit,
                            fromReminder   = true,
                            snoozeCount    = snoozeCount,
                            targetVersion  = baseHabit.targetVersion
                        )
                    )
                }

                ACTION_SKIP -> {
                    // SkipReason is stored as a string in WorkData; convert back to enum.
                    // Defaults to NO_REASON if the key is missing or the value is unrecognised.
                    val reasonName = inputData.getString(KEY_SKIP_REASON) ?: SkipReason.NO_REASON.name
                    val reason = runCatching { enumValueOf<SkipReason>(reasonName) }
                        .getOrDefault(SkipReason.NO_REASON)

                    db.habitSkipDao().insert(
                        HabitSkipEntity(
                            habitId   = habitId,
                            skippedAt = now,
                            reason    = reason
                        )
                    )
                }

                else -> return Result.failure()
            }

            Result.success()
        } catch (e: Exception) {
            // Transient failure (e.g. DB locked) — WorkManager will retry.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        /** Serialised key for the habit's primary-key integer. */
        const val KEY_HABIT_ID    = "key_habit_id"
        /** Either [ACTION_DONE] or [ACTION_SKIP]. */
        const val KEY_ACTION      = "key_action"
        /** Number of snooze taps before the Done tap (0 when not snoozed). */
        const val KEY_SNOOZE_COUNT = "key_snooze_count"
        /** [SkipReason.name] string; optional — only present for [ACTION_SKIP]. */
        const val KEY_SKIP_REASON  = "key_skip_reason"

        const val ACTION_DONE = "DONE"
        const val ACTION_SKIP = "SKIP"

        /**
         * Builds an expedited [OneTimeWorkRequest] for this worker.
         *
         * [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] means: attempt to run
         * as an expedited job (API 31+) for near-instant execution; if the system
         * quota is exhausted, fall back to a regular non-expedited request rather than
         * using a foreground service. This avoids needing a persistent notification
         * just to persist a DB row.
         *
         * @param habitId    The habit's primary key.
         * @param action     [ACTION_DONE] or [ACTION_SKIP].
         * @param snoozeCount Number of snooze taps in this reminder cycle (Done path only).
         * @param skipReason  [SkipReason.name] string (Skip path only; null = not provided).
         */
        fun buildRequest(
            habitId: Int,
            action: String,
            snoozeCount: Int = 0,
            skipReason: String? = null
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_HABIT_ID    to habitId,
                KEY_ACTION      to action,
                KEY_SNOOZE_COUNT to snoozeCount,
                KEY_SKIP_REASON  to skipReason
            )
            return OneTimeWorkRequestBuilder<RecordHabitActionWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
        }

        /**
         * Generates a unique work name for [WorkManager.enqueueUniqueWork].
         *
         * The timestamp suffix ensures two rapid taps on the same habit (e.g., double-tap
         * Done) produce separate, non-colliding work entries rather than replacing each
         * other. [ExistingWorkPolicy.APPEND_OR_REPLACE] is the recommended policy to pair
         * with this name.
         *
         * Pattern: `record_action_<habitId>_<System.currentTimeMillis()>`
         */
        fun uniqueName(habitId: Int): String =
            "record_action_${habitId}_${System.currentTimeMillis()}"
    }
}
