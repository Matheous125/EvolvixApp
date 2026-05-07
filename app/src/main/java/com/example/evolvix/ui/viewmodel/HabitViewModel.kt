package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitTemplate
import com.example.evolvix.data.model.defaultHabitTemplates
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.domain.model.FormError
import com.example.evolvix.domain.model.HabitUiState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime
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
     * Exposes form validation errors to the View.
     * Null means no error. The View collects this flow to show inline messages.
     * (Pattern: Observer via StateFlow — Unidirectional Data Flow)
     */
    private val _formError = MutableStateFlow<FormError?>(null)
    val formError: StateFlow<FormError?> = _formError.asStateFlow()

    /** Clears any active form error — called when the user starts editing the name field. */
    fun clearFormError() {
        _formError.value = null
    }

    /**
     * Validates [name] for uniqueness before insertion or update.
     * Queries the DAO using a case-insensitive match. If a duplicate is found,
     * emits [FormError.DuplicateName] on [formError] and returns [Result.failure].
     * On success, clears any previous error and returns [Result.success].
     *
     * @param excludeId Optionally exclude a habit by ID (used during edits so a
     *   habit does not conflict with its own current name).
     */
    suspend fun validateName(name: String, excludeId: Int = -1): Result<Unit> {
        val existing = habitDao.findByNameIgnoreCase(name.trim())
        return if (existing != null && existing.id != excludeId) {
            _formError.value = FormError.DuplicateName
            Result.failure(IllegalArgumentException("Duplicate name"))
        } else {
            _formError.value = null
            Result.success(Unit)
        }
    }

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
                    colorHex = entity.colorHex,
                    totalProgressUpdates = entity.totalProgressUpdates,
                    totalTargetReaches = entity.totalTargetReaches,
                    lastResetDate = entity.lastResetDate,
                    // Computed here so the View never does arithmetic — pure UDF
                    isOverCompleted = entity.currentCount > entity.target
                )
            }
        }

        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )


    // ── Add-habit form state (State Holder / Unidirectional Data Flow) ──────────

    /**
     * Holds the transient form state for the Add New Habit screen.
     * The View observes this [StateFlow] and dispatches user actions back through
     * the functions below — classic UDF: State flows down, Events flow up.
     */
    private val _addHabitFormState = MutableStateFlow(initialFormState())
    val addHabitFormState: StateFlow<HabitUiState> = _addHabitFormState.asStateFlow()

    /** Returns the blank initial state for the Add form with templates pre-loaded. */
    private fun initialFormState() = HabitUiState(
        name = "",
        currentCount = 0,
        target = 1,
        templates = defaultHabitTemplates,
        selectedColor = "#4CAF50",
        frequencyN = 1,
        frequencyUnit = HabitFrequency.Daily,
        targetCount = 1
    )

    /**
     * Applies a [HabitTemplate] to the form, pre-filling name, frequency, target, and color.
     * Called when the user taps a template chip in the Templates row.
     */
    fun selectTemplate(template: HabitTemplate) {
        _addHabitFormState.update { current ->
            current.copy(
                name = template.name,
                frequencyUnit = template.frequency,
                targetCount = template.target,
                selectedColor = template.colorHex,
                // Replace (not merge) categories so the selection always matches the template
                selectedCategories = setOf(template.category)
            )
        }
    }

    /**
     * Toggles [category] in/out of [HabitUiState.selectedCategories].
     * Called when the user taps a [FilterChip] in the Categories section.
     */
    fun toggleCategory(category: String) {
        _addHabitFormState.update { current ->
            val updated = if (category in current.selectedCategories)
                current.selectedCategories - category
            else
                current.selectedCategories + category
            current.copy(selectedCategories = updated)
        }
    }

    /**
     * Updates the pending color in the form state.
     * Called when the user picks a color from the color picker.
     */
    fun selectColor(colorHex: String) {
        _addHabitFormState.update { it.copy(selectedColor = colorHex) }
    }

    /**
     * Updates the frequency builder fields in the form state.
     * [n] is the repetition count; [unit] is the time period.
     */
    fun setFrequency(n: Int, unit: HabitFrequency) {
        _addHabitFormState.update { it.copy(frequencyN = n, frequencyUnit = unit) }
    }

    /**
     * Updates the target count in the form state.
     * Called on every keystroke in the Target input field.
     */
    fun setTargetCount(count: Int) {
        _addHabitFormState.update { it.copy(targetCount = count) }
    }

    /**
     * Resets the form to its blank initial state.
     * Call this when the Add screen opens or after a successful submission.
     */
    fun resetFormState() {
        _addHabitFormState.value = initialFormState()
    }

    // ─────────────────────────────────────────────────────────────────────────

    fun addHabit(habit: HabitUiState) {        viewModelScope.launch {
            habitDao.insertHabit(
                HabitEntity(
                    id = habit.id,
                    name = habit.name,
                    currentCount = habit.currentCount,
                    target = habit.target,
                    frequency = habit.frequency,
                    colorHex = habit.colorHex,
                    totalProgressUpdates = habit.totalProgressUpdates,
                    totalTargetReaches = habit.totalTargetReaches,
                    lastResetDate = habit.lastResetDate,
                    // selectedCategories carries the form's chosen categories on submission
                    categories = habit.selectedCategories.toList(),
                    reminderEnabled = habit.reminderEnabled
                )
            )
        }
    }

    fun incrementHabitCompletion(habitId: Int) {
        viewModelScope.launch {
            try {
                val habitToUpdate = habitDao.getHabitById(habitId)
                // No upper clamp — over-completion is explicitly supported (Phase 1.1)
                if (habitToUpdate != null) {
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
        colorHex: String,
        categories: List<String> = emptyList(),
        iconKey: String? = null,
        reminderEnabled: Boolean = false,
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
                    colorHex = colorHex,
                    categories = categories,
                    iconKey = iconKey,
                    reminderEnabled = reminderEnabled
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
