package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.AchievementDao
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.domain.sync.SyncController

/**
 * Factory for creating [AchievementsViewModel] instances with its DAO dependencies.
 *
 * Required because [AchievementsViewModel] has a non-default constructor.
 * (Pattern: Factory — standard [ViewModelProvider.Factory] contract)
 *
 * @property habitDao       Forwarded to [AchievementsViewModel] for habit + completion queries.
 * @property achievementDao  Forwarded to [AchievementsViewModel] for achievement persistence.
 * @property syncController  Forwarded to [AchievementsViewModel] for real-time Firestore push.
 */
class AchievementsViewModelFactory(
    private val habitDao: HabitDao,
    private val achievementDao: AchievementDao,
    private val syncController: SyncController
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AchievementsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AchievementsViewModel(habitDao, achievementDao, syncController) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
