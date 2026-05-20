package com.example.evolvix.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Factory for [SettingsViewModel].
 *
 * Pattern: **Factory Method** — provides the [Application] required by [SettingsViewModel]
 * without requiring a custom DI framework. Follows the same pattern used by
 * [HabitViewModelFactory] and [AchievementsViewModelFactory] throughout the project.
 *
 * @param application Application instance forwarded to [SettingsViewModel].
 */
class SettingsViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return SettingsViewModel(application) as T
    }
}
