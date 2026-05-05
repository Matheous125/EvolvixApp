package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.domain.model.HabitUiState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.data.model.HabitFrequency
import android.util.Log

//Takes habitDao as dependency, Extends Android ViewModel
/**
 * ViewModel managing habit-related business logic and UI state.
 * Handles CRUD operations, progress tracking, and periodic resets.
 *
 * @property habitDao Data access object for habit operations
 */
class HabitViewModel(private val habitDao: HabitDao) : ViewModel() {

    
    /**
     * Observable flow of all habits transformed into UI state
     */
    val allHabits: StateFlow<List<HabitUiState>> = habitDao.getAllHabits()
        .map { entities ->
            entities.map { entity ->
                HabitUiState(
                    id = entity.id,
                    name = entity.name,
                    currentCount = entity.currentCount,
                    target = entity.target,
                    frequency = entity.frequency,
                    colorScheme = entity.colorScheme,
                    totalProgressUpdates = entity.totalProgressUpdates,
                    totalTargetReaches = entity.totalTargetReaches,
                    lastResetDate = entity.lastResetDate
                )
            }
        }

        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )


    fun addHabit(habit: HabitUiState) {
        viewModelScope.launch {
            habitDao.insertHabit(
                HabitEntity(
                    id = habit.id,
                    name = habit.name,
                    currentCount = habit.currentCount,
                    target = habit.target,
                    frequency = habit.frequency,
                    colorScheme = habit.colorScheme,
                    totalProgressUpdates = habit.totalProgressUpdates,
                    totalTargetReaches = habit.totalTargetReaches,
                    lastResetDate = habit.lastResetDate
                )
            )
        }
    }

    fun incrementHabitCompletion(habitId: Int) {
        viewModelScope.launch {
            try {
                val habitToUpdate = habitDao.getHabitById(habitId)
                if (habitToUpdate != null && habitToUpdate.currentCount < habitToUpdate.target) {
                    // Calculate new count and check if target reached
                    val newCount = habitToUpdate.currentCount + 1
                    val isTargetReached = newCount == habitToUpdate.target

                    // Update habit with new counts
                    val updatedHabitEntity = habitToUpdate.copy(
                        currentCount = newCount,
                        totalProgressUpdates = habitToUpdate.totalProgressUpdates + 1,
                        totalTargetReaches = if (isTargetReached)
                            habitToUpdate.totalTargetReaches + 1
                        else
                            habitToUpdate.totalTargetReaches
                    )
                    habitDao.updateHabit(updatedHabitEntity)

                    // Record progress update
                    val completion = HabitCompletionEntity(
                        habitId = habitId,
                        progressUpdate = LocalDateTime.now(),
                        isTargetReached = isTargetReached
                    )
                    habitDao.insertCompletion(completion)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // For Statistics Screen
    fun getProgressHistory(
        habitId: Int,
        startDate: LocalDateTime = LocalDateTime.now().minusDays(7),
        endDate: LocalDateTime = LocalDateTime.now()
    ): Flow<List<HabitCompletionEntity>> {
        return habitDao.getProgressUpdates(habitId, startDate, endDate)
    }

    suspend fun getHabitById(habitId: Int): HabitEntity? {
        return habitDao.getHabitById(habitId)
    }

    fun updateHabit(
        id: Int,
        name: String,
        target: Int,
        frequency: HabitFrequency,
        colorScheme: HabitColorScheme,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val existingHabit = habitDao.getHabitById(id) ?: throw Exception("Habit not found")
                val updatedHabit = existingHabit.copy(
                    name = name,
                    target = target,
                    frequency = frequency,
                    colorScheme = colorScheme
                )
                habitDao.updateHabit(updatedHabit)
                onSuccess()
            } catch (_: Exception) {
                onError()
            }
        }
    }

    fun deleteHabit(
    habitId: Int,
    onSuccess: () -> Unit,
    onError: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                habitDao.deleteHabit(habitId)
                onSuccess()
            } catch (e: Exception) {
                Log.e("HabitViewModel", "Error deleting habit: ${e.message}")
                onError()
            }
        }
    }

    fun checkAndResetProgress() {
        viewModelScope.launch {
            try {
                val habits = habitDao.getAllHabits()
                val now = LocalDateTime.now()

                habits.collect { habitList ->
                    habitList.forEach { habit ->
                        val lastReset = habit.lastResetDate
                        val shouldReset = when (habit.frequency) {
                            HabitFrequency.Daily -> {
                                now.toLocalDate().isAfter(lastReset.toLocalDate())
                            }

                            HabitFrequency.Weekly -> {
                                val lastResetWeek = lastReset.toLocalDate().get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                                val currentWeek = now.toLocalDate().get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())
                                
                                lastResetWeek < currentWeek || lastReset.year < now.year
                            }

                            HabitFrequency.Monthly -> {
                                now.year > lastReset.year || now.monthValue > lastReset.monthValue
                            }

                            HabitFrequency.Yearly -> {
                                now.year > lastReset.year
                            }
                        }

                        if (shouldReset) {
                            val updatedHabit = habit.copy(
                                currentCount = 0,
                                lastResetDate = now
                            )
                            habitDao.updateHabit(updatedHabit)
                            Log.d(
                                "HabitViewModel",
                                "Reset progress for habit: ${habit.name} (${habit.frequency})"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HabitViewModel", "Error resetting habits: ${e.message}")
            }
        }
    }
}
