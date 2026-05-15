package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.AchievementDao
import com.example.evolvix.data.local.HabitDao

/**
 * Factory for creating [AchievementsViewModel] instances with its DAO dependencies.
 *
 * Required because [AchievementsViewModel] has a non-default constructor.
 * (Pattern: Factory — standard [ViewModelProvider.Factory] contract)
 *
 * @property habitDao      Forwarded to [AchievementsViewModel] for habit + completion queries.
 * @property achievementDao Forwarded to [AchievementsViewModel] for achievement persistence.
 */
class AchievementsViewModelFactory(
    private val habitDao: HabitDao,
    private val achievementDao: AchievementDao
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AchievementsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AchievementsViewModel(habitDao, achievementDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
