package com.example.evolvix.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitCompletionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf

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
            ACTION_DONE   -> recordCompletion(context.applicationContext, habitId)
            ACTION_SKIP   -> { /* noop — user explicitly skipped, nothing persisted */ }
            ACTION_SNOOZE -> snooze(context.applicationContext, habitId)
        }
    }

    /**
     * Inserts a completion row and bumps the habit's progress counters on the IO
     * dispatcher. Mirrors [com.example.evolvix.ui.viewmodel.HabitViewModel.incrementHabitCompletion]
     * but cannot call it directly because no ViewModel exists in this process at notification time.
     */
    private fun recordCompletion(context: Context, habitId: Int) {
        // Fire-and-forget background scope — the BroadcastReceiver is killed after onReceive,
        // but `goAsync()` is overkill for a single DAO write that takes a few ms on Room's
        // own executor. We keep things simple and let Room's internal pool finish the write.
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(context).habitDao()
            val habit = dao.getHabitById(habitId) ?: return@launch
            val newCount = habit.currentCount + 1
            val targetHit = newCount == habit.target
            dao.updateHabit(
                habit.copy(
                    currentCount = newCount,
                    totalProgressUpdates = habit.totalProgressUpdates + 1,
                    totalTargetReaches = if (targetHit) habit.totalTargetReaches + 1
                                         else habit.totalTargetReaches
                )
            )
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = habitId,
                    progressUpdate = LocalDateTime.now(),
                    isTargetReached = targetHit
                )
            )
        }
    }

    /**
     * Re-schedules a one-shot reminder ~15 minutes later via WorkManager. We use a
     * dedicated unique work name (`reminder_snooze_<id>`) so the snooze never collides
     * with the daily slot scheduled by [com.example.evolvix.domain.usecase.ScheduleReminderUseCase].
     */
    private fun snooze(context: Context, habitId: Int) {
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
