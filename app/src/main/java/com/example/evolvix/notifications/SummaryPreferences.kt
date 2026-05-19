package com.example.evolvix.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight helper around `SharedPreferences` for daily-summary state (Phase 7.2 v2).
 *
 * **Why SharedPreferences (and not DataStore):** the rest of the codebase already uses
 * `SharedPreferences` (`habit_ui_prefs`) for UI flags such as the sort mode — adding a
 * DataStore dependency just for two ints would be over-engineering. The values stored
 * here are all primitive scalars read at most once per worker invocation, so the
 * synchronous nature of SharedPreferences is harmless.
 *
 * Tracked state:
 *  - `dismissStreak`   — consecutive notification dismissals. Resets to 0 when the
 *                        user taps the notification, opens the inbox, or marks an
 *                        item read. At 7, the worker stops posting (per design).
 *  - `disabled`        — true once the auto-off threshold has fired; user can flip
 *                        it back by re-opening the inbox (future enhancement) or by
 *                        clearing app data.
 *  - `lastReadId`      — id of the most recent summary the user has read (informational).
 */
object SummaryPreferences {
    private const val FILE = "habit_ui_prefs"
    private const val KEY_DISMISS_STREAK = "summary_dismiss_streak"
    private const val KEY_DISABLED = "summary_disabled"
    private const val KEY_LAST_READ_ID = "summary_last_read_id"
    const val MAX_DISMISS_STREAK = 7

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isDisabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DISABLED, false)

    fun setDisabled(ctx: Context, disabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_DISABLED, disabled).apply()
    }

    fun dismissStreak(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DISMISS_STREAK, 0)

    /** Increments the streak and returns the new value. */
    fun incrementDismissStreak(ctx: Context): Int {
        val next = dismissStreak(ctx) + 1
        prefs(ctx).edit().putInt(KEY_DISMISS_STREAK, next).apply()
        return next
    }

    fun resetDismissStreak(ctx: Context) {
        prefs(ctx).edit().putInt(KEY_DISMISS_STREAK, 0).apply()
    }

    fun setLastReadId(ctx: Context, id: Int) {
        prefs(ctx).edit().putInt(KEY_LAST_READ_ID, id).apply()
    }

    fun lastReadId(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LAST_READ_ID, -1)
}
