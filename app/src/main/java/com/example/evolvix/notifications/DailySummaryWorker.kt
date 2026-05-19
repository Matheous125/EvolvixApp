package com.example.evolvix.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.evolvix.MainActivity
import com.example.evolvix.R
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.usecase.ComposeDailySummaryUseCase
import com.example.evolvix.domain.usecase.WeeklyOverviewUseCase
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Daily-summary worker (Phase 7.2 v2).
 *
 * Replaces the original `PeriodicWorkRequest(24h)` implementation. We now:
 *  - Use a **chained `OneTimeWorkRequest`** so the fire time can be re-computed each
 *    day from the user's average "last completion minute" (analogous to how
 *    [HabitReminderWorker] re-arms itself).
 *  - Persist each summary as a [com.example.evolvix.data.model.DailySummaryEntity]
 *    so the user can browse history inside the in-app inbox screen — and so the
 *    notification can deep-link to that specific row via an extra.
 *  - Wire a `setDeleteIntent` to [SummaryDismissReceiver] so swipe-aways increment
 *    the dismiss-streak counter. Reaching 7 disables further fires (auto-off).
 *  - Honour the [SummaryPreferences.isDisabled] flag at the top of `doWork` so a
 *    re-enable simply re-enqueues without code changes.
 */
class DailySummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (SummaryPreferences.isDisabled(ctx)) return Result.success()

        NotificationChannels.ensureCreated(ctx)
        val db = AppDatabase.getDatabase(ctx)
        val dao = db.habitDao()
        val summaryDao = db.dailySummaryDao()

        val habits = dao.getActiveHabits(System.currentTimeMillis()).firstOrNull().orEmpty()
        val completions = dao.getAllCompletions().firstOrNull().orEmpty()
        val today = LocalDate.now()

        // Recent achievements unlocked today (one row per achievement key).
        // `unlockedAt` is stored as epoch-millis (nullable), so we filter against
        // today's [startOfDay, startOfNextDay) interval in the device timezone.
        val zone = ZoneId.systemDefault()
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStartMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val achievementsToday = try {
            db.achievementDao().getAllAchievementsOnce()
                .filter { it.unlockedAt != null && it.unlockedAt in todayStartMs until tomorrowStartMs }
        } catch (_: Exception) { emptyList() }

        val overview = WeeklyOverviewUseCase()(habits, completions)
        val summary = ComposeDailySummaryUseCase()(
            today = today,
            activeHabits = habits,
            completionsAll = completions,
            achievementsUnlockedToday = achievementsToday,
            weekRate = overview.weekCompletionRate
        )

        // Insert; if the user already has a row for `today`, IGNORE returns -1 and
        // we silently skip re-posting (avoids duplicate notifications from manual
        // debug triggers + scheduled fires on the same day).
        val newId = summaryDao.insert(summary)
        if (newId <= 0) {
            scheduleNext(ctx)
            return Result.success()
        }

        // Tap → opens MainActivity with extras that route to the inbox + mark read.
        val openInbox = PendingIntent.getActivity(
            ctx, EXTRA_OPEN_INBOX_REQ,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SUMMARY_INBOX, true)
                putExtra(EXTRA_SUMMARY_ROW_ID, newId.toInt())
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Swipe-away → SummaryDismissReceiver increments dismiss streak.
        val deletePi = PendingIntent.getBroadcast(
            ctx, EXTRA_DISMISS_REQ,
            Intent(ctx, SummaryDismissReceiver::class.java).apply {
                action = SummaryDismissReceiver.ACTION_DISMISS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(ctx, NotificationChannels.DAILY_SUMMARY_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(summary.title)
            .setContentText(summary.shortBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.body))
            .setContentIntent(openInbox)
            .setDeleteIntent(deletePi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(SUMMARY_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS missing — silent skip.
        }

        // Always re-arm tomorrow's slot at the end.
        scheduleNext(ctx)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "habit_daily_summary"
        const val EXTRA_OPEN_SUMMARY_INBOX = "open_summary_inbox"
        const val EXTRA_SUMMARY_ROW_ID = "summary_row_id"
        private const val SUMMARY_NOTIFICATION_ID = 9_999_001
        private const val EXTRA_OPEN_INBOX_REQ = 9_999_100
        private const val EXTRA_DISMISS_REQ = 9_999_101

        /** Default fire time when there's not enough history (21:00). */
        private const val DEFAULT_TARGET_MINUTE_OF_DAY: Long = 21L * 60
        /**
         * Earliest the summary is allowed to fire: 16:00.
         * Before this point the user's day is likely not finished, so a summary
         * would be premature even if their historical average last-completion is early.
         */
        private const val EARLIEST_TARGET_MINUTE_OF_DAY: Long = 16L * 60
        /** Hard cap so we never schedule past 23:30. */
        private const val MAX_TARGET_MINUTE_OF_DAY: Long = 23L * 60 + 30

        /**
         * Enqueues (or re-enqueues) the next single summary slot.
         *
         * Uses `ExistingWorkPolicy.REPLACE` so each call re-anchors the schedule
         * with the freshest "user average last completion" minute — important on
         * cold start when the user's habits may have shifted since yesterday.
         *
         * Suspending because the smart-time computation queries Room.
         */
        suspend fun enqueue(context: Context) {
            scheduleNext(context)
        }

        private suspend fun scheduleNext(context: Context) {
            if (SummaryPreferences.isDisabled(context)) return
            val delay = computeNextDelayMillis(context)
            val req = OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req
            )
        }

        /**
         * Personalised minute-of-day = `avgLastCompletion + 30 min`, clamped to
         * [16:00, 23:30]. Falls back to 21:00 when there is no completion history.
         *
         * The 16:00 floor prevents sending a summary mid-afternoon when a user's
         * only habit is a morning run — the rest of their day is still ahead.
         * There is no 21:00 floor: if the user's last habit finishes at 17:00,
         * the summary fires at 17:30 so the recap is timely and relevant.
         */
        private suspend fun computeNextDelayMillis(context: Context): Long {
            val dao = AppDatabase.getDatabase(context).habitDao()
            val since = LocalDateTime.now().minusDays(30)
            val avg = try { dao.avgLastCompletionMinuteOverall(since) } catch (_: Exception) { null }

            val targetMinute = if (avg != null && avg.isFinite() && avg > 0f) {
                (avg + 30).toLong()
                    .coerceIn(EARLIEST_TARGET_MINUTE_OF_DAY, MAX_TARGET_MINUTE_OF_DAY)
            } else DEFAULT_TARGET_MINUTE_OF_DAY

            val now = LocalDateTime.now()
            var target = now.toLocalDate().atStartOfDay().plusMinutes(targetMinute)
            if (!target.isAfter(now)) target = target.plusDays(1)
            return ChronoUnit.MILLIS.between(now, target).coerceAtLeast(1_000L)
        }
    }
}
