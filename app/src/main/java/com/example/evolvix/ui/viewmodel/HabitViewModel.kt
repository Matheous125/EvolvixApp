package com.example.evolvix.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitTemplate
import com.example.evolvix.data.model.defaultHabitTemplates
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.domain.model.FormError
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.domain.model.SortMode
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import com.example.evolvix.data.model.HabitFrequency
import android.util.Log

/**
 * ViewModel managing habit-related business logic and UI state.
 * Extends [AndroidViewModel] to access [Application] context for SharedPreferences,
 * which persists [SortMode] across process restarts without a full database.
 *
 * @property habitDao Data access object for habit operations
 */
class HabitViewModel(application: Application, private val habitDao: HabitDao) : AndroidViewModel(application) {

    // SharedPreferences used exclusively for lightweight UI preferences (sort order).
    // Habit data lives in Room; only presentation state is stored here.
    private val prefs = application.getSharedPreferences("habit_ui_prefs", Context.MODE_PRIVATE)

    
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

    // ── Sort & Filter state ──────────────────────────────────────────────────

    /**
     * Controls the active sort order for the habit list.
     * Initialized from SharedPreferences so the user's choice survives process restarts.
     * Changing this value triggers [allHabits] to switch to a different DAO query
     * reactively via [flatMapLatest] — no manual refresh needed.
     * (Pattern: Observer via StateFlow — Unidirectional Data Flow)
     */
    private val _sortMode = MutableStateFlow(
        SortMode.valueOf(
            prefs.getString("sort_mode", SortMode.MANUAL.name) ?: SortMode.MANUAL.name
        )
    )
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** Sets the active sort mode and persists it to SharedPreferences. */
    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        prefs.edit().putString("sort_mode", mode.name).apply()
        // Automatically exit reorder mode when the user switches away from MANUAL sort.
        if (mode != SortMode.MANUAL) _reorderMode.value = false
    }

    /**
     * Tracks whether the drag-and-drop reorder mode is active.
     * Lifted into the ViewModel so that [MainActivity] can observe it and hide
     * the FAB — keeping the ViewModel as the single source of truth for all UI state.
     * (Pattern: Observer via StateFlow — Unidirectional Data Flow)
     */
    private val _reorderMode = MutableStateFlow(false)
    val reorderMode: StateFlow<Boolean> = _reorderMode.asStateFlow()

    /** Activates drag-and-drop reorder mode. Only valid when [sortMode] is [SortMode.MANUAL]. */
    fun enterReorderMode() { _reorderMode.value = true }

    /** Deactivates drag-and-drop reorder mode and commits no additional changes. */
    fun exitReorderMode() { _reorderMode.value = false }

    /**
     * Holds the set of category labels currently used as filters.
     * An empty set means "show all habits". The View observes this to render
     * active/inactive filter chips in the top bar.
     * (Pattern: Observer via StateFlow)
     */
    private val _activeFilters = MutableStateFlow<Set<String>>(emptySet())
    val activeFilters: StateFlow<Set<String>> = _activeFilters.asStateFlow()

    /** Toggles [category] in/out of the active filter set. */
    fun toggleFilter(category: String) {
        _activeFilters.update { current ->
            if (category in current) current - category else current + category
        }
    }

    /** Clears all active category filters, restoring the full habit list. */
    fun clearFilters() {
        _activeFilters.value = emptySet()
    }

    // ── Search state ─────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Updates the name search query; [allHabits] applies it reactively. */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /**
     * All unique category labels across every habit, derived from the unfiltered list.
     * Used by the View to populate the filter chip row regardless of active filters.
     * (Pattern: Observer via StateFlow)
     */
    val availableCategories: StateFlow<Set<String>> = habitDao.getAllHabits()
        .map { entities -> entities.flatMap { it.categories }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // ── Habits list ──────────────────────────────────────────────────────────

    /**
     * Observable list of habits in UI-ready form.
     * Uses a 3-way [combine]: the sorted entity list (switched by [flatMapLatest] on
     * [sortMode]), [activeFilters] (category predicate), and [searchQuery] (name predicate).
     * All three dimensions update the list reactively with no manual refresh.
     * (Pattern: Observer via StateFlow + reactive combination)
     */
    val allHabits: StateFlow<List<HabitUiState>> = combine(
        _sortMode.flatMapLatest { mode -> habitDao.getHabitsSorted(mode) },
        _activeFilters,
        _searchQuery
    ) { entities, filters, query ->
        val categoryFiltered = if (filters.isEmpty()) entities
                               else entities.filter { entity ->
                                   entity.categories.any { it in filters }
                               }
        val searchFiltered = if (query.isBlank()) categoryFiltered
                             else categoryFiltered.filter {
                                 it.name.contains(query.trim(), ignoreCase = true)
                             }
        searchFiltered.map { entity ->
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
                isOverCompleted = entity.currentCount > entity.target,
                pausedUntil = entity.pausedUntil,
                categories = entity.categories,
                categoryGroup = entity.categoryGroup,
                manualGroup = entity.manualGroup
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

    /**
     * Pauses a habit until the given timestamp.
     * Pass [Long.MAX_VALUE] for an indefinite pause, or a future epoch-millis for a timed pause.
     * The DAO's [getActiveHabits] query will automatically exclude this habit until [until] passes.
     * (Pattern: Command — encapsulates a state-mutation operation)
     */
    fun pauseHabit(id: Int, until: Long) {
        viewModelScope.launch {
            val habit = habitDao.getHabitById(id) ?: return@launch
            habitDao.updateHabit(habit.copy(pausedUntil = until))
        }
    }

    /**
     * Resumes a paused habit immediately by clearing its [pausedUntil] timestamp.
     */
    fun resumeHabit(id: Int) {
        viewModelScope.launch {
            val habit = habitDao.getHabitById(id) ?: return@launch
            habitDao.updateHabit(habit.copy(pausedUntil = null))
        }
    }

    /**
     * Persists a new display order for all habits after a drag-and-drop gesture.
     *
     * Receives [orderedIds] — the full list of habit IDs in the desired order —
     * and writes [HabitEntity.sortOrder] = list index for each one.
     * Using the complete ordered list (instead of a from/to swap) guarantees
     * that the database order always exactly matches what the user saw on screen.
     *
     * (Pattern: Command — the full reorder is one encapsulated, atomic write operation)
     *
     * @param orderedIds Habit IDs in the desired display order (index 0 = top of list).
     */
    fun applyNewOrder(orderedIds: List<Int>) {
        viewModelScope.launch {
            orderedIds.forEachIndexed { index, id ->
                val entity = habitDao.getHabitById(id) ?: return@forEachIndexed
                if (entity.sortOrder != index) {
                    habitDao.updateHabit(entity.copy(sortOrder = index))
                }
            }
        }
    }

    /**
     * Persists a reorder that may include [HabitUiState.manualGroup] changes.
     *
     * Used after cross-group drags where an ungrouped habit is dropped into a group.
     * For each habit, both [HabitEntity.sortOrder] and [HabitEntity.manualGroup] are
     * updated if they differ from the current DB value — a single pass covers both
     * pure reorder (group drag) and cross-group assignment (ungrouped → group drag).
     *
     * (Pattern: Command — encapsulates the full post-drag persistence in one operation)
     *
     * @param orderedHabits Full list of [HabitUiState] in the desired display order.
     */
    fun applyNewOrderWithGroups(orderedHabits: List<HabitUiState>) {
        viewModelScope.launch {
            orderedHabits.forEachIndexed { index, uiState ->
                val entity = habitDao.getHabitById(uiState.id) ?: return@forEachIndexed
                if (entity.sortOrder != index || entity.manualGroup != uiState.manualGroup) {
                    habitDao.updateHabit(entity.copy(
                        sortOrder = index,
                        manualGroup = uiState.manualGroup
                    ))
                }
            }
        }
    }

    /**
     * Creates a new manual group and assigns the selected habits to it.
     *
     * The selected habits are packed at the bottom of the list by assigning them
     * [sortOrder] values beyond the current maximum. This guarantees they appear
     * consecutively in the MANUAL sorted query, which the View relies on to build
     * contiguous group sections.
     *
     * @param groupName Display name of the new group.
     * @param habitIds IDs of habits to assign to the group.
     */
    fun createManualGroup(groupName: String, habitIds: List<Int>) {
        viewModelScope.launch {
            val allHabits = habitDao.getAllHabitsOnce()
            val maxOrder = allHabits.maxOfOrNull { it.sortOrder } ?: 0
            habitIds.forEachIndexed { index, id ->
                val entity = habitDao.getHabitById(id) ?: return@forEachIndexed
                habitDao.updateHabit(entity.copy(
                    manualGroup = groupName,
                    sortOrder = maxOrder + 1 + index
                ))
            }
        }
    }

    /**
     * Renames a manual group by updating all member habits in one DAO call.
     *
     * @param oldName Current group name.
     * @param newName Desired new group name.
     */
    fun renameManualGroup(oldName: String, newName: String) {
        viewModelScope.launch {
            habitDao.renameManualGroup(oldName, newName.trim())
        }
    }

    fun checkAndResetProgress() {
        viewModelScope.launch {
            try {
                val habits = habitDao.getAllHabits()
                val now = LocalDateTime.now()
                val nowMillis = System.currentTimeMillis()

                habits.collect { habitList ->
                    habitList.forEach { habit ->
                        // Auto-resume: if pausedUntil has passed, clear it so the habit
                        // becomes active again without any manual action from the user.
                        if (habit.pausedUntil != null && habit.pausedUntil != Long.MAX_VALUE
                            && nowMillis >= habit.pausedUntil) {
                            habitDao.updateHabit(habit.copy(pausedUntil = null))
                        }

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
