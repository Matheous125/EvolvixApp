package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.AppSessionDao
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.local.HabitSkipDao
import com.example.evolvix.data.local.TargetHistoryDao
import com.example.evolvix.domain.ai.HabitPredictor

/**
 * Factory for creating [StatisticsViewModel] instances with constructor dependencies.
 *
 * Required because [StatisticsViewModel] takes [HabitDao], [HabitPredictor], and
 * [TargetHistoryDao] — Jetpack's default [ViewModelProvider] cannot inject any of
 * these without a DI framework.
 *
 * (Pattern: Factory Method — the caller, typically a composable, supplies references
 *  obtained from [AppDatabase] and [com.example.evolvix.domain.ai.AiContainer].)
 *
 * @property dao              Data Access Object passed through to the ViewModel.
 * @property predictor        AI strategy passed through to the ViewModel (Phase 6.5).
 * @property targetHistoryDao DAO for the target-change audit log (Phase 9.3).
 * @property habitSkipDao     DAO for skip records (Phase 9.5).
 * @property appSessionDao    DAO for the app-session log (Phase 9.6).
 */
class StatisticsViewModelFactory(
    private val dao: HabitDao,
    private val predictor: HabitPredictor,
    private val targetHistoryDao: TargetHistoryDao,
    private val habitSkipDao: HabitSkipDao,
    private val appSessionDao: AppSessionDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatisticsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatisticsViewModel(dao, predictor, targetHistoryDao, habitSkipDao, appSessionDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
