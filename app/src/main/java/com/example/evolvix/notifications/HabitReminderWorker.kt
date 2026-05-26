package com.example.evolvix.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.evolvix.MainActivity
import com.example.evolvix.R
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.ai.AiContainer
import com.example.evolvix.domain.ai.ReminderContext
import com.example.evolvix.domain.model.AbandonmentRisk
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.usecase.AbandonmentRiskUseCase
import com.example.evolvix.domain.usecase.CalculateStreakUseCase
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * One-shot worker that posts a habit reminder notification.
 *
 * Invoked by [com.example.evolvix.domain.usecase.ScheduleReminderUseCase] which builds
 * a `OneTimeWorkRequest` with an `initialDelay`. The Worker re-schedules itself for
 * the next day at the end of [doWork] so reminders behave as a daily series without
 * relying on `PeriodicWorkRequest` (which has a 15-minute minimum interval and drifts).
 *
 * Notification text is selected by [com.example.evolvix.domain.ai.HabitPredictor.selectReminderTemplate];
 * the returned key resolves to a `<string>` in `strings.xml` so the device locale
 * (PL/EN) controls user-visible copy. (Pattern: **Command via WorkRequest + Strategy via AI**.)
 */
class HabitReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val habitId = inputData.getInt(KEY_HABIT_ID, -1)
        if (habitId < 0) return Result.failure()

        val isDebugTest = inputData.getBoolean(KEY_IS_DEBUG_TEST, false)
        // Snooze = explicit user request to be reminded again; always bypass the smart gate.
        val isSnoozed  = inputData.getBoolean(KEY_IS_SNOOZE, false)

        val ctx = applicationContext
        NotificationChannels.ensureCreated(ctx)

        val dao = AppDatabase.getDatabase(ctx).habitDao()
        val habit = dao.getHabitById(habitId) ?: return Result.success() // habit deleted
        // In debug-test mode we skip config guards so the test button always fires
        // regardless of whether the habit has reminders switched on or is paused.
        if (!isDebugTest) {
            if (!habit.reminderEnabled) return Result.success()
            val pausedUntil = habit.pausedUntil
            if (pausedUntil != null && pausedUntil > System.currentTimeMillis()) {
                return Result.success()
            }
        }

        // ── Build the AI ReminderContext so notification text adapts to user behaviour ──
        val completions = try {
            dao.getCompletionsForHabit(habitId).firstOrNull() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val last7 = completions.count { ChronoUnit.DAYS.between(it.progressUpdate.toLocalDate(), today) in 0..6 }
        val rate7 = (last7.toFloat() / 7f).coerceIn(0f, 1f)
        val lastTs = completions.maxByOrNull { it.progressUpdate }?.progressUpdate
        val daysSince = if (lastTs == null) 99 else ChronoUnit.DAYS.between(lastTs.toLocalDate(), today).toInt()
        val predictor = AiContainer.predictor(ctx)
        val habitData = HabitData(
            id = habit.id,
            name = habit.name,
            currentCount = habit.currentCount,
            frequency = habit.frequency,
            target = habit.target,
            lastResetDate = habit.lastResetDate
        )
        val streak = CalculateStreakUseCase()(completions, habit.frequency).current
        // Phase 8.1 — abandonment risk informs the notification gate and template choice.
        val abandonmentRisk = AbandonmentRiskUseCase(predictor)(habitData, completions, streak)
        val targetReachedToday = completions.any {
            it.isTargetReached && it.progressUpdate.toLocalDate() == today
        }
        // R1: read the live snooze counter for this habit so Model 3 can bias
        // toward gentler templates when the user has already snoozed multiple times.
        val snoozeCountToday = SnoozePreferences.getCount(ctx, habitId)
        val ctxFeatures = ReminderContext(
            currentStreak = streak,
            completionRateLast7Days = rate7,
            daysSinceLastCompletion = daysSince,
            dayOfWeek = today.dayOfWeek.value,
            hourOfDay = now.hour,
            // R3: pass continuous probability directly from Model 8.1 output.
            abandonmentProbability = abandonmentRisk.probability,
            targetReachedToday = targetReachedToday,
            snoozeCountToday = snoozeCountToday
        )
        val templateKey = predictor.selectReminderTemplate(ctxFeatures)
        val messageText = resolveTemplateText(ctx, templateKey, habit.name)

        // ── Phase 7.2v2 gate ────────────────────────────────────────────────
        // The schedule already places this slot at the right behavioral time
        // (avg target-reach + 30 min). The gate only needs to answer:
        //   - Has the habit already been completed this period?  (→ skip)
        //   - Is the ML predictor saying the streak is at risk?  (→ skip if not)
        // There is no separate time-of-day guard here: that responsibility belongs
        // entirely to ScheduleReminderUseCase. Snooze bypasses both checks because
        // the user explicitly asked to be reminded again.
        val periodTargetReached = habit.currentCount >= habit.target
        // Debug-test mode bypasses the smart gate entirely so the notification
        // always fires regardless of streak-at-risk state or period completion.
        // R3: gate uses continuous probability threshold (≥0.6) OR the classic streak-at-risk rule.
        val shouldFire = isDebugTest || isSnoozed ||
            (!periodTargetReached && (ctxFeatures.abandonmentProbability >= 0.6f || predictor.isStreakAtRisk(habitData, completions)))

        // ── Build PendingIntents for the three action buttons ──
        val doneIntent = PendingIntent.getBroadcast(
            ctx, habitId * 10 + 1,
            Intent(ctx, HabitActionReceiver::class.java).apply {
                action = HabitActionReceiver.ACTION_DONE
                putExtra(HabitActionReceiver.EXTRA_HABIT_ID, habitId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipIntent = PendingIntent.getActivity(
            ctx, habitId * 10 + 2,
            // Phase 9.5 fix: use getActivity() so Android's notification-action exemption
            // allows the picker to launch even when the app is fully killed.
            // getActivity() from a PendingIntent tapped by the user is always allowed;
            // startActivity() called from BroadcastReceiver.onReceive() is not (API 29+).
            Intent(ctx, SkipReasonPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(HabitActionReceiver.EXTRA_HABIT_ID, habitId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            ctx, habitId * 10 + 3,
            Intent(ctx, HabitActionReceiver::class.java).apply {
                action = HabitActionReceiver.ACTION_SNOOZE
                putExtra(HabitActionReceiver.EXTRA_HABIT_ID, habitId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Tapping the notification body opens the app's main screen.
        val openAppIntent = PendingIntent.getActivity(
            ctx, habitId,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, NotificationChannels.REMINDERS_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(habit.name)
            .setContentText(messageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(messageText))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.reminder_action_done), doneIntent)
            .addAction(0, ctx.getString(R.string.reminder_action_skip), skipIntent)
            .addAction(0, ctx.getString(R.string.reminder_action_snooze), snoozeIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)  // heads-up banner on API < 26
            .build()

        try {
            if (shouldFire) {
                NotificationManagerCompat.from(ctx).notify(habitId, notification)
            }
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted on API 33+; silently skip rather than crash.
        }

        // Re-arm the next daily reminder so the chain continues without PeriodicWorkRequest.
        com.example.evolvix.domain.usecase.ScheduleReminderUseCase(ctx)
            .scheduleNext(habit)

        return Result.success()
    }

    /**
     * Resolves a template key (returned by the AI predictor) to a localised string.
     * Falls back to a generic motivation message if the key is unknown so we never
     * post an empty notification.
     */
    private fun resolveTemplateText(ctx: Context, key: String, habitName: String): String {
        val resId = when (key) {
            "cheer_streak_milestone"   -> R.string.reminder_streak_milestone
            "gentle_nudge_at_risk"     -> R.string.reminder_gentle_nudge
            "celebrate_consistency"    -> R.string.reminder_celebrate_consistency
            "recovery_encouragement"   -> R.string.reminder_recovery
            "morning_optimistic"       -> R.string.reminder_morning
            "evening_reflection"       -> R.string.reminder_evening
            "comeback_after_break"     -> R.string.reminder_comeback
            "weekend_warrior"          -> R.string.reminder_weekend
            "first_week_support"       -> R.string.reminder_first_week
            "cold_start"               -> R.string.reminder_cold_start
            "streak_save"              -> R.string.reminder_streak_save
            "target_smashed"           -> R.string.reminder_target_smashed
            "category_balance"         -> R.string.reminder_category_balance
            "pace_yourself"            -> R.string.reminder_pace
            else                       -> R.string.reminder_quiet
        }
        return ctx.getString(resId, habitName)
    }

    companion object {
        const val KEY_HABIT_ID = "habit_id"
        /** Set to `true` by [DebugTriggers] to bypass the smart gate during debug testing. */
        const val KEY_IS_DEBUG_TEST = "is_debug_test"
        /**
         * Set to `true` when this work was enqueued by a Snooze action tap.
         * A user-initiated snooze is an explicit request to be re-notified, so the
         * smart gate is always bypassed — we should never silently swallow a snooze.
         */
        const val KEY_IS_SNOOZE = "is_snooze"
        /** Unique work name prefix used by ScheduleReminderUseCase. */
        const val UNIQUE_NAME_PREFIX = "habit_reminder_"
    }
}
