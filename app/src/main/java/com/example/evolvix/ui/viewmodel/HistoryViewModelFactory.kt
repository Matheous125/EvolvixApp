package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.HabitDao

/**
 * Factory for creating [HistoryViewModel] instances with constructor dependencies.
 *
 * A custom factory is required because [HistoryViewModel] takes [HabitDao] and [habitId]
 * as constructor parameters — Jetpack's default [ViewModelProvider] cannot inject these
 * automatically without a DI framework.
 *
 * (Pattern: Factory Method — the NavGraph creates one factory per habit navigation event,
 *  passing the correct [habitId] extracted from the back-stack entry's arguments)
 *
 * @property dao Data Access Object passed through to the ViewModel.
 * @property habitId The habit whose history this ViewModel will manage.
 */
class HistoryViewModelFactory(
    private val dao: HabitDao,
    private val habitId: Int
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(dao, habitId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
