package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.domain.ai.HabitPredictor

/**
 * Factory for creating [StatisticsViewModel] instances with constructor dependencies.
 *
 * Required because [StatisticsViewModel] takes both a [HabitDao] and a [HabitPredictor]
 * — Jetpack's default [ViewModelProvider] cannot inject either without a DI framework.
 *
 * (Pattern: Factory Method — the caller, typically a composable, supplies the [dao]
 *  reference obtained from [AppDatabase] and the [predictor] reference obtained from
 *  [com.example.evolvix.domain.ai.AiContainer].)
 *
 * @property dao       Data Access Object passed through to the ViewModel.
 * @property predictor AI strategy passed through to the ViewModel (Phase 6.5).
 */
class StatisticsViewModelFactory(
    private val dao: HabitDao,
    private val predictor: HabitPredictor
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatisticsViewModel(dao, predictor) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
