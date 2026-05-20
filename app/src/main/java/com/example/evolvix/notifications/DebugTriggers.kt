package com.example.evolvix.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitFrequency
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Helper triggers for the DEBUG-only menu in the main TopAppBar (Phase 7.2 v2).
 *
 * Schedules a single fire of the relevant worker after a 3-second delay so the
 * thesis defence demo can show notification flow without waiting for the real
 * scheduled slot. Both helpers use `ExistingWorkPolicy.REPLACE` (or APPEND for the
 * summary) to avoid polluting the production schedule.
 *
 * Never include this object in production-only code paths — `BuildConfig.DEBUG`
 * guards the UI entry point, but the methods themselves are debug-callable too.
 */
object DebugTriggers {

    /** Fires [HabitReminderWorker] 3 seconds from now for [habitId], bypassing the smart gate. */
    fun fireReminderSoon(context: Context, habitId: Int) {
        val req = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setInputData(workDataOf(
                HabitReminderWorker.KEY_HABIT_ID to habitId,
                HabitReminderWorker.KEY_IS_DEBUG_TEST to true  // skip shouldFire gate
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "debug_reminder_$habitId",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    /** Fires [DailySummaryWorker] 3 seconds from now. */
    fun fireDailySummarySoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<DailySummaryWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "debug_summary",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    /**
     * Inserts a "___Debug Reminder Test___" habit that satisfies every gate condition
     * naturally (no bypasses), then fires its [HabitReminderWorker] after 3 seconds.
     *
     * Conditions met:
     *  - `reminderEnabled = true`
     *  - `currentCount = 0 < target = 1` → not period-complete
     *  - No completions → `isDailyStreakAtRisk` sees 0/4 reached on every weekday → at risk
     *  - `lastResetDate = yesterday 00:00` → graceDeadline already past (was yesterday at 19:12)
     *
     * The habit is left in the DB so you can inspect it and delete it manually afterwards.
     * If an identical name already exists Room's IGNORE policy just skips the insert.
     */
    suspend fun seedAndFireRealReminder(context: Context) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.habitDao()

        val testName = "___Debug Reminder Test___"
        // Only insert if not already there (IGNORE conflict strategy on the DAO).
        if (dao.findByNameIgnoreCase(testName) == null) {
            val seed = HabitEntity(
                id = 0,
                name = testName,
                currentCount = 0,
                target = 1,
                frequency = HabitFrequency.Daily,
                colorHex = "#FF5722",
                reminderEnabled = true,
                reminderTime = null,          // smart timing from AI
                // Set last reset to yesterday midnight so the grace deadline (19:12
                // yesterday) is already in the past — gate condition 3 passes at any hour.
                lastResetDate = LocalDateTime.now()
                    .minusDays(1)
                    .withHour(0).withMinute(0).withSecond(0).withNano(0)
            )
            dao.insertHabit(seed)
        }

        val habit = dao.findByNameIgnoreCase(testName) ?: return
        // IS_DEBUG_TEST=false → full real gate runs (all conditions satisfied by seed data).
        val req = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS)
            .setInputData(workDataOf(HabitReminderWorker.KEY_HABIT_ID to habit.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "debug_reminder_seed",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    /**
     * Simulates 7 consecutive notification dismissals in one tap.
     *
     * Sets `dismissStreak = MAX_DISMISS_STREAK` and `disabled = true` directly in
     * [SummaryPreferences], then cancels the unique work — exactly what
     * [SummaryDismissReceiver] would do after 7 real swipe-aways.
     * The [SettingsViewModel] SharedPreferences listener fires automatically and
     * flips the Daily Summary switch to OFF without any additional steps.
     */
    fun simulateAutoDisable(context: Context) {
        repeat(SummaryPreferences.MAX_DISMISS_STREAK) {
            SummaryPreferences.incrementDismissStreak(context)
        }
        SummaryPreferences.setDisabled(context, true)
        WorkManager.getInstance(context).cancelUniqueWork(DailySummaryWorker.UNIQUE_NAME)
    }

    /**
     * Resets the auto-disable state — the inverse of [simulateAutoDisable].
     * Clears both the `disabled` flag and the `dismissStreak` counter so the
     * Settings switch returns to ON and the worker can fire again on the next schedule.
     */
    fun resetSummaryDisable(context: Context) {
        SummaryPreferences.setDisabled(context, false)
        SummaryPreferences.resetDismissStreak(context)
    }
}
