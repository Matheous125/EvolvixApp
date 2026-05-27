package com.example.evolvix.domain.usecase

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.ai.AiContainer
import com.example.evolvix.domain.model.EngagementWindow
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.notifications.HabitReminderWorker
import com.example.evolvix.notifications.SnoozePreferences
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Schedules and cancels per-habit reminder notifications.
 *
 * Wraps WorkManager's `OneTimeWorkRequest` (Pattern: **Command via WorkRequest**) —
 * each scheduled work item is a serialised "post reminder at T+Δ" command. The Worker
 * re-arms itself via [scheduleNext] so reminders behave as a chained daily series
 * without `PeriodicWorkRequest`'s 15-minute floor or drift.
 *
 * **Personalised timing (Phase 7.2 v2):**
 *  - If [HabitEntity.reminderTime] is set, that user-chosen minute-of-day wins.
 *  - Otherwise we look at the user's average target-reaching minute-of-day for this
 *    habit over the last 30 days. We schedule **30 minutes after** that average, so
 *    the notification fires only if the user is later than usual.
 *  - We always floor to the grace deadline (the last 1/5 of the reset period —
 *    19:12 for daily). This guarantees we never nag at 07:00 when the user normally
 *    completes at 22:00.
 *
 * **Conditional firing** is implemented in [HabitReminderWorker]: a scheduled slot
 * may pass without posting a notification if the habit is already complete, not at
 * risk, or before its period-specific grace deadline.
 */
class ScheduleReminderUseCase(private val context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    /** Cancels any pending reminder for [habitId]. */
    fun cancel(habitId: Int) {
        workManager.cancelUniqueWork(uniqueName(habitId))
    }

    /**
     * Public entry point used by ViewModels / Activity on cold start.
     *
     * Suspending because the smart-time computation hits Room. Callers already run
     * inside a coroutine (viewModelScope / lifecycleScope / CoroutineWorker).
     */
    suspend fun schedule(habit: HabitEntity) {
        if (!habit.reminderEnabled) {
            cancel(habit.id); return
        }
        val pausedUntil = habit.pausedUntil
        if (pausedUntil != null && pausedUntil > System.currentTimeMillis()) {
            cancel(habit.id); return
        }
        scheduleNext(habit)
    }

    /**
     * Schedules the next single reminder slot. Public so [HabitReminderWorker] can
     * re-arm after firing.
     *
     * **Phase 9.1 — Smart suppression:** Before enqueuing, [ReminderEffectivenessUseCase]
     * is called with recent completion history. If the predicted lift is below
     * [ReminderEffectivenessUseCase.SUPPRESS_THRESHOLD] and the habit has sufficient
     * data, the reminder is silently skipped (cancelled) for this slot.
     */
    suspend fun scheduleNext(habit: HabitEntity) {
        // Phase 9.1: suppress reminder if predicted lift is too low
        if (shouldSuppressReminder(habit)) {
            Log.d(TAG, "Reminder suppressed for habit ${habit.id} (low predicted lift)")
            cancel(habit.id)
            return
        }

        val targetMinuteOfDay = habit.reminderTime?.toLong() ?: smartMinuteOfDay(habit)
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atStartOfDay().plusMinutes(targetMinuteOfDay)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delayMs = ChronoUnit.MILLIS.between(now, target).coerceAtLeast(1_000L)

        val request = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(HabitReminderWorker.KEY_HABIT_ID to habit.id))
            .build()
        workManager.enqueueUniqueWork(
            uniqueName(habit.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Picks a personalised minute-of-day from the user's history.
     *
     * If the user has reached their target at least once in the last 30 days, the
     * reminder is placed 30 minutes after their average target-reach time — meaning
     * "fire only when the user is running later than usual." The result is clamped to
     * [08:00, 23:00] so we never remind in the middle of the night or before breakfast.
     *
     * If there is no history yet, we fall back to [FALLBACK_MINUTE] (19:12 = 80% of
     * a daily period) — the grace-period concept applies only as a cold-start default,
     * not as a permanent floor.
     */
    private suspend fun smartMinuteOfDay(habit: HabitEntity): Long {
        // Phase 9.6 — Engagement Window override: when the regressor has sufficient
        // session history AND confidence ≥ CONFIDENCE_THRESHOLD, prefer the user's
        // predicted active hour over the 30-day average target-reach time. The double
        // guard (sufficiency + confidence) prevents cold-start or noisy predictions
        // from overriding the data-driven timing for established habits.
        // Only applies to non-user-set reminders (this method is not called when
        // habit.reminderTime is non-null).
        try {
            val sessionDao = AppDatabase.getDatabase(context).appSessionDao()
            val predictor = AiContainer.predictor(context)
            val window = EngagementWindowUseCase(sessionDao, predictor).execute()
            if (window.hasSufficientData && window.confidence >= EngagementWindow.CONFIDENCE_THRESHOLD) {
                Log.d(TAG, "Engagement-window override for habit ${habit.id}: hour=${window.predictedHour}")
                return (window.predictedHour * 60L)
                    .coerceIn(SCHEDULE_EARLIEST_MINUTE, SCHEDULE_LATEST_MINUTE)
            }
        } catch (_: Exception) {
            // Fall through to the existing completion-history logic on any error.
        }

        val dao = AppDatabase.getDatabase(context).habitDao()
        val since = LocalDateTime.now().minusDays(30)
        val avg = try {
            dao.avgTargetReachMinuteForHabit(habit.id, since)
        } catch (_: Exception) { null }

        return if (avg != null && avg.isFinite() && avg > 0f) {
            // Trust the data: remind 30 min after the average completion time.
            (avg + 30).toLong().coerceIn(SCHEDULE_EARLIEST_MINUTE, SCHEDULE_LATEST_MINUTE)
        } else {
            // No history yet — use the cold-start fallback (19:12 for daily habits).
            FALLBACK_MINUTE
        }
    }

    companion object {
        /**
         * Cold-start fallback minute-of-day used when there is no completion history.
         * 19:12 = 80% of 1440 minutes — the "last fifth of the day" heuristic that
         * ensures new users still receive a reminder in the evening before midnight.
         * Once the user has real history, [smartMinuteOfDay] uses their actual average
         * and this constant plays no role.
         */
        private const val FALLBACK_MINUTE: Long = 19L * 60 + 12

        /** Earliest allowed reminder minute-of-day: 08:00. */
        private const val SCHEDULE_EARLIEST_MINUTE: Long = 8L * 60

        /** Latest allowed reminder minute-of-day: 23:00. */
        private const val SCHEDULE_LATEST_MINUTE: Long = 23L * 60

        private const val TAG = "ScheduleReminderUseCase"
    }

    /**
     * Returns true when the [ReminderEffectivenessUseCase] has sufficient data AND
     * predicts that sending a reminder yields lift below [ReminderEffectivenessUseCase.SUPPRESS_THRESHOLD].
     *
     * New habits (< [ReminderEffectivenessUseCase.MIN_COMPLETIONS] completions) always
     * return false here — their reminders are sent unconditionally as a safe default.
     */
    private suspend fun shouldSuppressReminder(habit: HabitEntity): Boolean {
        return try {
            val dao = AppDatabase.getDatabase(context).habitDao()
            val completions = dao.getCompletionsForHabit(habit.id).firstOrNull() ?: emptyList()
            val predictor = AiContainer.predictor(context)
            val useCase = ReminderEffectivenessUseCase(predictor)
            // Build a minimal HabitData — only id and frequency are used by the use case
            val habitData = HabitData(
                id = habit.id,
                name = habit.name,
                currentCount = habit.currentCount,
                frequency = habit.frequency,
                target = habit.target
            )
            // Compute a simple current streak: count consecutive days with isTargetReached
            val today = LocalDate.now()
            var streak = 0
            var checkDate = today
            val reachedDates = completions
                .filter { it.isTargetReached }
                .map { it.progressUpdate.toLocalDate() }
                .toSet()
            while (checkDate in reachedDates) {
                streak++
                checkDate = checkDate.minusDays(1)
            }
            val snoozeCountToday = SnoozePreferences.getCount(context, habit.id)
            val lift = useCase(habitData, completions, streak, snoozeCountToday = snoozeCountToday)
            !lift.recommendSend && lift.hasSufficientData
        } catch (t: Throwable) {
            Log.w(TAG, "shouldSuppressReminder failed; defaulting to send", t)
            false  // safe default: never suppress on error
        }
    }

    private fun uniqueName(habitId: Int) = HabitReminderWorker.UNIQUE_NAME_PREFIX + habitId
}
