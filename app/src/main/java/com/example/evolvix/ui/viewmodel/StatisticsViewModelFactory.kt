package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.HabitDao

/**
 * Factory for creating [StatisticsViewModel] instances with constructor dependencies.
 *
 * Required because [StatisticsViewModel] takes [HabitDao] as a constructor parameter —
 * Jetpack's default [ViewModelProvider] cannot inject it without a DI framework.
 *
 * (Pattern: Factory Method — the caller, typically NavGraph or a composable, supplies
 *  the [dao] reference obtained from [AppDatabase])
 *
 * @property dao Data Access Object passed through to the ViewModel.
 */
class StatisticsViewModelFactory(
    private val dao: HabitDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatisticsViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
