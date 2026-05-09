package com.example.evolvix.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.HabitDao

/**
 * Factory for creating [HabitViewModel] instances with constructor dependencies.
 * Passes both the [Application] context (for SharedPreferences) and the [HabitDao].
 *
 * @property application Application context used by [AndroidViewModel]
 * @property habitDao Data Access Object for habit operations
 */
class HabitViewModelFactory(
    private val application: Application,
    private val habitDao: HabitDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HabitViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HabitViewModel(application, habitDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}