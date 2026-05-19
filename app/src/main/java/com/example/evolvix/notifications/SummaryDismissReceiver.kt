package com.example.evolvix.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Receives the `setDeleteIntent` callback when the user swipes away the daily-summary
 * notification without tapping it (Phase 7.2 v2).
 *
 * Each dismissal increments [SummaryPreferences.dismissStreak]. Reaching
 * [SummaryPreferences.MAX_DISMISS_STREAK] in a row signals the user is uninterested,
 * so we set the disabled flag and cancel the unique work — the next
 * `DailySummaryWorker.enqueue` call will short-circuit. This is the "auto-off after
 * 7 ignores" rule from the design.
 *
 * (Pattern: **Observer** — system fires when the notification is dismissed.)
 */
class SummaryDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val streak = SummaryPreferences.incrementDismissStreak(context)
        if (streak >= SummaryPreferences.MAX_DISMISS_STREAK) {
            SummaryPreferences.setDisabled(context, true)
            WorkManager.getInstance(context)
                .cancelUniqueWork(DailySummaryWorker.UNIQUE_NAME)
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.example.evolvix.SUMMARY_DISMISS"
    }
}
