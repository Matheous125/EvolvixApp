package com.example.evolvix.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import com.example.evolvix.notifications.DailySummaryWorker
import com.example.evolvix.notifications.SummaryPreferences
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Represents the three supported app theme modes.
 * Stored as a string key in SharedPreferences.
 */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * ViewModel managing user-facing settings persisted via [SharedPreferences].
 *
 * Stored values:
 *  - [themeMode]            — Light / Dark / Follow system (default: DARK).
 *  - [dailySummaryEnabled]  — Whether the periodic summary notification is active.
 *                             Backed by [SummaryPreferences.isDisabled] (inverted).
 *  - [languageCode]         — "en" or "pl" (default: "en"). Phase 8 localization placeholder.
 *  - [displayName]          — User's display name shown in the profile header.
 *                             Defaults to "Evolvix User" until Phase 9 auth is wired.
 *
 * Pattern: **MVVM + Observer** — each field is a [StateFlow] collected by the UI.
 * Uses [AndroidViewModel] because SharedPreferences requires an application [Context].
 *
 * @param application Application instance used to access [SharedPreferences].
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ── Theme ─────────────────────────────────────────────────────────────────

    private val _themeMode = MutableStateFlow(
        runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.DARK.name) ?: ThemeMode.DARK.name)
        }.getOrDefault(ThemeMode.DARK)  // fall back to DARK if the stored string is invalid
    )
    /** Current theme mode — read by [AppContent] to drive [EvolvixTheme]. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Persists the selected [ThemeMode] and notifies collectors. */
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    // ── Language ──────────────────────────────────────────────────────────────

    // Read the current app-specific locale from AppCompatDelegate so the dialog
    // radio button reflects the live locale (including after process restart).
    private val _languageCode = MutableStateFlow(
        AppCompatDelegate.getApplicationLocales()[0]?.language ?: "en"
    )
    /**
     * BCP-47 language tag driving the language radio button in [SettingsScreen].
     * Source of truth is [AppCompatDelegate.getApplicationLocales]; SharedPreferences
     * is no longer needed for this field.
     */
    val languageCode: StateFlow<String> = _languageCode.asStateFlow()

    /**
     * Switches the app locale immediately via [AppCompatDelegate.setApplicationLocales].
     * Android (API 33+) or AppCompat (older) persists the choice automatically;
     * the call also triggers an Activity recreation so [stringResource] calls
     * throughout the Compose tree pick up translations from the correct locale.
     */
    fun setLanguageCode(code: String) {
        _languageCode.value = code
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))
        // Activity is recreated by the locale change, so the ViewModel will be
        // cleared and re-initialised — no further StateFlow update is needed.
    }

    // ── Daily summary ─────────────────────────────────────────────────────────

    private val _dailySummaryEnabled = MutableStateFlow(
        // SummaryPreferences stores the "disabled" flag, so we invert it here.
        !SummaryPreferences.isDisabled(application)
    )
    /** Whether the daily summary notification is active. */
    val dailySummaryEnabled: StateFlow<Boolean> = _dailySummaryEnabled.asStateFlow()

    /**
     * Listens for external writes to the `summary_disabled` SharedPreferences key.
     *
     * [SummaryDismissReceiver] (a BroadcastReceiver) writes `setDisabled(true)` after
     * the user swipes away the notification 7 times in a row — completely outside the
     * ViewModel lifecycle. Without this listener the [dailySummaryEnabled] StateFlow
     * would stay stale, and the Settings switch would show the wrong position.
     *
     * Pattern: **Observer** — SharedPreferences notifies registered listeners
     * synchronously on the thread that called `apply()` / `commit()`.
     */
    private val summaryDisabledListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SUMMARY_DISABLED) {
                _dailySummaryEnabled.value = !SummaryPreferences.isDisabled(application)
            }
        }

    init {
        // Register after _dailySummaryEnabled is initialised so we never miss a write
        // that occurs between VM construction and the listener being attached.
        prefs.registerOnSharedPreferenceChangeListener(summaryDisabledListener)
    }

    /**
     * Enables or disables the daily summary.
     *
     * When enabled: clears the [SummaryPreferences.isDisabled] flag and re-enqueues
     * [DailySummaryWorker] so it fires on the next scheduled window.
     * When disabled: sets the flag so the worker exits early without posting a notification.
     * No need to cancel the WorkManager request — the flag check inside [DailySummaryWorker.doWork]
     * is the authoritative gate, which keeps the WorkManager state table clean.
     */
    fun setDailySummaryEnabled(enabled: Boolean) {
        SummaryPreferences.setDisabled(getApplication(), !enabled)
        if (enabled) {
            // Re-arm the worker on a coroutine — enqueue is suspend (hits Room for timing).
            // viewModelScope is cancelled automatically when the ViewModel is cleared.
            viewModelScope.launch { DailySummaryWorker.enqueue(getApplication()) }
        }
        // _dailySummaryEnabled is also updated by the SharedPreferences listener above,
        // but we set it directly here too so the switch responds instantly without
        // waiting for the listener callback.
        _dailySummaryEnabled.value = enabled
    }

    // ── Display name ──────────────────────────────────────────────────────────

    private val _displayName = MutableStateFlow(
        prefs.getString(KEY_DISPLAY_NAME, "Evolvix User") ?: "Evolvix User"
    )
    /** User's display name shown in the Settings profile header. */
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    /** Persists the new display name. */
    fun setDisplayName(name: String) {
        val trimmed = name.trim().ifEmpty { "Evolvix User" }
        prefs.edit().putString(KEY_DISPLAY_NAME, trimmed).apply()
        _displayName.value = trimmed
    }

    // ── Developer visibility prefs ────────────────────────────────────────────

    private val _showDebugOnHabits = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_DEBUG_ON_HABITS, true)
    )
    /** Whether the DEBUG bug-report icon button is shown in the Habits screen top bar. */
    val showDebugOnHabits: StateFlow<Boolean> = _showDebugOnHabits.asStateFlow()

    /** Persists the developer toggle for the Habits debug button. */
    fun setShowDebugOnHabits(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_DEBUG_ON_HABITS, enabled).apply()
        _showDebugOnHabits.value = enabled
    }

    private val _showSeederOnStats = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_SEEDER_ON_STATS, true)
    )
    /** Whether the seed-test-data icon button is shown in the Statistics screen top bar. */
    val showSeederOnStats: StateFlow<Boolean> = _showSeederOnStats.asStateFlow()

    /** Persists the developer toggle for the Statistics seeder button. */
    fun setShowSeederOnStats(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SEEDER_ON_STATS, enabled).apply()
        _showSeederOnStats.value = enabled
    }

    companion object {
        private const val PREFS_FILE               = "habit_ui_prefs"
        private const val KEY_THEME                = "settings_theme_mode"
        private const val KEY_LANGUAGE             = "settings_language_code"
        private const val KEY_DISPLAY_NAME         = "settings_display_name"
        private const val KEY_SHOW_DEBUG_ON_HABITS = "dev_show_debug_on_habits"
        private const val KEY_SHOW_SEEDER_ON_STATS = "dev_show_seeder_on_stats"
        // Same key used by SummaryPreferences — referenced here so the listener
        // can filter only the relevant change without importing a private constant.
        private const val KEY_SUMMARY_DISABLED = "summary_disabled"
    }

    override fun onCleared() {
        // Unregister to prevent a memory leak: SharedPreferences holds a strong
        // reference to the listener, which would keep this ViewModel alive after
        // the Activity is destroyed if we forget to remove it.
        prefs.unregisterOnSharedPreferenceChangeListener(summaryDisabledListener)
        super.onCleared()
    }
}
