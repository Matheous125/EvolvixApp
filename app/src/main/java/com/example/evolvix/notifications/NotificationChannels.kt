package com.example.evolvix.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Centralised notification channel registry for Phase 7.
 *
 * Lives outside the MVVM layer because notification channels are an Android-system
 * concern (a process-wide side effect on first launch). All workers / receivers
 * call [ensureCreated] before posting so the OS never throws "no such channel".
 *
 * (Pattern: **Singleton / utility object** — channels are global by nature.)
 */
object NotificationChannels {

    /**
     * Channel for per-habit reminder notifications scheduled via [HabitReminderWorker].
     * v2 suffix forces a new channel with IMPORTANCE_HIGH (once created, channel
     * importance is immutable — the only way to upgrade is a new channel ID).
     */
    const val REMINDERS_ID = "habit_reminders_v2"

    /** Channel for the once-per-day summary posted by [DailySummaryWorker]. */
    const val DAILY_SUMMARY_ID = "habit_daily_summary"

    /**
     * Idempotently creates both channels. Safe to call from any entry point
     * (Application.onCreate, a Worker, the BroadcastReceiver) — the OS deduplicates
     * by channel id, so repeated calls do nothing after the first.
     */
    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return // pre-O has no channels
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // IMPORTANCE_HIGH → shows as a heads-up (peek) banner and makes a sound.
        // This is intentional: habit reminders are time-sensitive and should
        // interrupt the user visually, not just land silently in the shade.
        val reminders = NotificationChannel(
            REMINDERS_ID,
            "Habit reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Per-habit reminder notifications"
        }
        // IMPORTANCE_DEFAULT → appears in the shade with a sound but no banner.
        // A daily summary is informational, not urgent — we don't want it to
        // interrupt the user the way a time-sensitive reminder would.
        val summary = NotificationChannel(
            DAILY_SUMMARY_ID,
            "Daily summary",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Once-a-day overview of your habits"
        }
        nm.createNotificationChannel(reminders)
        nm.createNotificationChannel(summary)
    }
}
