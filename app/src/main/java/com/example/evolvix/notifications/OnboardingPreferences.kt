package com.example.evolvix.notifications

import android.content.Context
import android.content.SharedPreferences

/**
 * Lightweight SharedPreferences wrapper that tracks whether the user has completed
 * the onboarding flow (i.e., tapped "Get Started" at least once).
 *
 * **Pattern: Preferences as Repository** — the UI layer never touches SharedPreferences
 * directly; it delegates to this object, keeping the storage mechanism swappable.
 *
 * **Why SharedPreferences (not DataStore):** consistent with the rest of the codebase
 * ([SummaryPreferences], [SettingsViewModel]). Adding Datastore for a single boolean
 * would be over-engineering and would force a coroutine read before the first NavHost
 * composition — unnecessarily complex for a one-shot flag.
 *
 * Uses the same `habit_ui_prefs` file as [SummaryPreferences] to keep preference
 * files consolidated.
 */
object OnboardingPreferences {

    /** SharedPreferences file shared with other UI-flag helpers. */
    private const val FILE = "habit_ui_prefs"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_FAB_HINT_SHOWN = "fab_hint_shown"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Returns `true` if the user has already gone through the onboarding screen.
     * Read synchronously — safe because SharedPreferences keeps a cached in-memory
     * copy after the first load; no disk I/O on subsequent calls.
     */
    fun isCompleted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_COMPLETED, false)

    /**
     * Persists the completed state. Called by [OnboardingScreen] when the user
     * taps "Get Started". The `apply()` write is asynchronous (non-blocking).
     */
    fun setCompleted(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /**
     * Returns `true` if the pulsing FAB hint has already been acknowledged
     * (i.e., the user tapped the FAB at least once). Used to stop the
     * [infiniteTransition] pulse after first use.
     */
    fun fabHintShown(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FAB_HINT_SHOWN, false)

    /**
     * Persists the FAB hint acknowledged state. Called in [AppContent] when
     * the user taps the FAB for the first time.
     */
    fun markFabHintShown(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_FAB_HINT_SHOWN, true).apply()
    }
}
