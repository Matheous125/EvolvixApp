package com.example.evolvix.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight helper around `SharedPreferences` for per-habit snooze counters (Phase 9.2).
 *
 * **Responsibility:** track how many times the user has tapped "Snooze" on a habit's
 * reminder notification *before* eventually completing or skipping it. The final
 * counter value is flushed onto the [com.example.evolvix.data.model.HabitCompletionEntity]
 * row when the "Done" action fires, then reset to zero. This is the **Observer** for the
 * snooze lifecycle: increment on snooze → read + reset on done/skip.
 *
 * **Why SharedPreferences (not Room):** the counter lives only for the duration of a
 * single notification cycle (at most ~hours). Persisting it in Room would require a
 * schema change just for a transient scalar — over-engineering. The same reasoning
 * applies to [SummaryPreferences] and [OnboardingPreferences] in this package.
 *
 * Key format: `snooze_count_<habitId>` — isolated per habit so concurrent
 * reminders for different habits never collide.
 *
 * Thread-safety: `SharedPreferences.apply()` is fire-and-forget on a background thread;
 * reads via `getInt` are synchronous but cheap for a single integer. This matches how
 * [SummaryPreferences] is used across the rest of the notification layer.
 */
object SnoozePreferences {

    private const val FILE = "evolvix_snooze_prefs"

    private fun key(habitId: Int): String = "snooze_count_$habitId"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Returns the current snooze count for [habitId].
     * Returns 0 if no snooze has been recorded yet (new cycle).
     */
    fun getCount(ctx: Context, habitId: Int): Int =
        prefs(ctx).getInt(key(habitId), 0)

    /**
     * Increments the snooze counter for [habitId] by 1 and returns the new value.
     * Called by [HabitActionReceiver] when the user taps the "Snooze" action button.
     */
    fun increment(ctx: Context, habitId: Int): Int {
        val next = getCount(ctx, habitId) + 1
        prefs(ctx).edit().putInt(key(habitId), next).apply()
        return next
    }

    /**
     * Resets the snooze counter for [habitId] back to zero.
     * Called by [HabitActionReceiver] after the counter has been flushed to
     * the completion row (Done action) or when the user explicitly skips (Skip action).
     */
    fun reset(ctx: Context, habitId: Int) {
        prefs(ctx).edit().remove(key(habitId)).apply()
    }
}
