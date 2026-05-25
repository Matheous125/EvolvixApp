package com.example.evolvix.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Receives the action-button taps fired from a habit reminder notification
 * (Done / Skip / Snooze). It is the **Command receiver** in the *Command pattern*:
 * each [Intent] action is a serialised command sent from the system UI back into the app.
 *
 * Lives outside the MVVM layer because the system delivers it before any
 * ViewModel can exist. Writes go through [AppDatabase] (the single source of truth).
 */
class HabitActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getIntExtra(EXTRA_HABIT_ID, -1)
        if (habitId < 0) return

        // Dismiss the notification immediately so the user sees the action took effect.
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(habitId)

        when (intent.action) {
            ACTION_DONE   -> {
                // Phase 9.2: read the accumulated snooze count and flush it onto the
                // completion row before resetting, so SnoozeDisengagementUseCase can
                // compute avgSnoozeCountLast14Days from persisted HabitCompletionEntity rows.
                val snoozes = SnoozePreferences.getCount(context.applicationContext, habitId)
                SnoozePreferences.reset(context.applicationContext, habitId)
                // Phase 9.6.2: replaced fire-and-forget CoroutineScope write with a
                // WorkManager job so the DB write survives process death.
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    RecordHabitActionWorker.uniqueName(habitId),
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    RecordHabitActionWorker.buildRequest(
                        habitId     = habitId,
                        action      = RecordHabitActionWorker.ACTION_DONE,
                        snoozeCount = snoozes
                    )
                )
            }
            // ACTION_SKIP is intentionally absent: the Skip notification button now uses
            // PendingIntent.getActivity() → SkipReasonPickerActivity directly, bypassing
            // this receiver. The activity handles notification cancellation + snooze reset.
            ACTION_SNOOZE -> snooze(context.applicationContext, habitId)
        }
    }

    /**
     * Re-schedules a one-shot reminder ~15 minutes later via WorkManager. We use a
     * dedicated unique work name (`reminder_snooze_<id>`) so the snooze never collides
     * with the daily slot scheduled by [com.example.evolvix.domain.usecase.ScheduleReminderUseCase].
     */
    private fun snooze(context: Context, habitId: Int) {
        // Phase 9.2: increment the per-habit snooze counter before rescheduling so the
        // final count accurately reflects all snooze taps in this reminder cycle.
        SnoozePreferences.increment(context, habitId)
        // KEY_IS_SNOOZE bypasses the smart gate: the user explicitly asked to be
        // reminded again, so we should never silently swallow the snoozed notification.
        val request = OneTimeWorkRequestBuilder<HabitReminderWorker>()
            .setInitialDelay(15, TimeUnit.MINUTES)
            .setInputData(workDataOf(
                HabitReminderWorker.KEY_HABIT_ID to habitId,
                HabitReminderWorker.KEY_IS_SNOOZE  to true
            ))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    companion object {
        const val ACTION_DONE   = "com.example.evolvix.action.DONE"
        const val ACTION_SKIP   = "com.example.evolvix.action.SKIP"
        const val ACTION_SNOOZE = "com.example.evolvix.action.SNOOZE"
        const val EXTRA_HABIT_ID = "extra_habit_id"
    }
}
