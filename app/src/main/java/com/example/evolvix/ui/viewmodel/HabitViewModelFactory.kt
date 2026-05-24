package com.example.evolvix.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.local.TargetHistoryDao

/**
 * Factory for creating [HabitViewModel] instances with constructor dependencies.
 * Passes the [Application] context (for SharedPreferences), the [HabitDao],
 * and the [TargetHistoryDao] (Phase 9.3).
 *
 * @property application Application context used by [AndroidViewModel]
 * @property habitDao Data Access Object for habit operations
 * @property targetHistoryDao Data Access Object for target-change audit log (Phase 9.3)
 */
class HabitViewModelFactory(
    private val application: Application,
    private val habitDao: HabitDao,
    private val targetHistoryDao: TargetHistoryDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HabitViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HabitViewModel(application, habitDao, targetHistoryDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}