package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.HabitDao

/**
 * Factory for creating HabitViewModel instances.
 * Provides dependency injection for the ViewModel by passing required DAO.
 *
 * @property habitDao Data Access Object for habit operations
 */
class HabitViewModelFactory(private val habitDao: HabitDao) : ViewModelProvider.Factory {
      /**
     * Creates a new instance of the requested ViewModel.
     * 
     * @param modelClass The class of the ViewModel to create
     * @return The created ViewModel instance
     * @throws IllegalArgumentException if the ViewModel class is unknown
     */
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HabitViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HabitViewModel(habitDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}