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
import com.example.evolvix.data.local.TargetHistoryDao
import com.example.evolvix.data.model.HabitTargetHistoryEntity
import com.example.evolvix.domain.model.FormError
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.domain.model.SortMode
import com.example.evolvix.domain.usecase.CalculateStreakUseCase
import com.example.evolvix.domain.usecase.ScheduleReminderUseCase
import com.example.evolvix.domain.usecase.ShouldResetHabitUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
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
class HabitViewModel(
    application: Application,
    private val habitDao: HabitDao,
    // Phase 9.3: audit log for target changes; used by TargetAdjustmentUseCase.
    private val targetHistoryDao: TargetHistoryDao
) : AndroidViewModel(application) {

    // Pure-function interactor — no dependencies beyond its inputs.
    // Instantiated once here and reused on every combine emission.
    // (Pattern: Use Case / Interactor — single-responsibility streak computation)
    private val calculateStreakUseCase = CalculateStreakUseCase()

    // Phase 7.1 — reminder scheduler. Holds an applicationContext-bound WorkManager;
    // safe to keep as a ViewModel field because it is stateless apart from that handle.
    private val scheduleReminderUseCase = ScheduleReminderUseCase(application.applicationContext)

    // SharedPreferences used exclusively for lightweight UI preferences (sort order).
    // Habit data lives in Room; only presentation state is stored here.
    private val prefs = application.getSharedPreferences("habit_ui_prefs", Context.MODE_PRIVATE)

    init {
        // Watches for date rollover while the app is open and in the foreground.
        // Calculates the exact delay until the next midnight, then calls checkAndResetProgress().
        // The coroutine lives in viewModelScope, so it is automatically cancelled when the
        // ViewModel is cleared (app process ends). This complements the ON_RESUME check in
        // MainScreen which handles returns from background.
        viewModelScope.launch {
            while (true) {
                val now = LocalDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
                delay(Duration.between(now, nextMidnight).toMillis())
                checkAndResetProgress()
            }
        }
    }

    
    /**
     * Fires once each time a habit's completion count exactly hits its target.
     * [AppContent] collects this to display [FullScreenConfettiOverlay] over the whole screen.
     * extraBufferCapacity = 1 prevents suspension if the collector is briefly busy.
     * (Pattern: Event Bus via SharedFlow — fire-and-forget, no replay)
     */
    private val _celebrationEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val celebrationEvent: SharedFlow<Unit> = _celebrationEvent.asSharedFlow()

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
        // Wrap in try-catch so that a stale "MANUAL" value stored by an older build
        // (before the MANUAL→CUSTOM rename) does not crash with IllegalArgumentException.
        try {
            SortMode.valueOf(
                prefs.getString("sort_mode", SortMode.DEFAULT.name) ?: SortMode.DEFAULT.name
            )
        } catch (_: IllegalArgumentException) {
            SortMode.DEFAULT
        }
    )
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()

    /** Sets the active sort mode and persists it to SharedPreferences. */
    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        prefs.edit().putString("sort_mode", mode.name).apply()
        // Automatically exit reorder mode when the user switches away from CUSTOM sort.
        if (mode != SortMode.CUSTOM) _reorderMode.value = false
    }

    /**
     * Tracks whether the drag-and-drop reorder mode is active.
     * Lifted into the ViewModel so that [MainActivity] can observe it and hide
     * the FAB — keeping the ViewModel as the single source of truth for all UI state.
     * (Pattern: Observer via StateFlow — Unidirectional Data Flow)
     */
    private val _reorderMode = MutableStateFlow(false)
    val reorderMode: StateFlow<Boolean> = _reorderMode.asStateFlow()

    /** Activates drag-and-drop reorder mode. Only valid when [sortMode] is [SortMode.CUSTOM]. */
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
     * Uses a 4-way [combine]:
     *   1. Sorted entity list — switched by [flatMapLatest] on [sortMode].
     *   2. [activeFilters] — category predicate.
     *   3. [searchQuery] — name predicate.
     *   4. All completion records — re-emits on every insert/update/delete made by
     *      either the main-screen tap or [HistoryViewModel] edits.
     *
     * The fourth arm is what makes streaks reactive: Room invalidates [getAllCompletions]
     * after every write to `habit_completions`, so [CalculateStreakUseCase] re-runs
     * automatically without any manual refresh.
     * (Pattern: Observer via StateFlow + reactive combination)
     */
    val allHabits: StateFlow<List<HabitUiState>> = combine(
        _sortMode.flatMapLatest { mode -> habitDao.getHabitsSorted(mode) },
        _activeFilters,
        _searchQuery,
        habitDao.getAllCompletions()
    ) { entities, filters, query, allCompletions ->
        // Group completions by habitId once per emission — O(n) — so the per-habit
        // streak lookup below is O(1) instead of O(n) per habit.
        val completionsByHabit = allCompletions.groupBy { it.habitId }

        val categoryFiltered = if (filters.isEmpty()) entities
                               else entities.filter { entity ->
                                   entity.categories.any { it in filters }
                               }
        val searchFiltered = if (query.isBlank()) categoryFiltered
                             else categoryFiltered.filter {
                                 it.name.contains(query.trim(), ignoreCase = true)
                             }
        searchFiltered.map { entity ->
            val streakResult = calculateStreakUseCase(
                completions = completionsByHabit[entity.id] ?: emptyList(),
                frequency   = entity.frequency
            )
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
                manualGroup = entity.manualGroup,
                currentStreak = streakResult.current,
                bestStreak = streakResult.best
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
            val entity = HabitEntity(
                id = habit.id,
                name = habit.name,
                currentCount = habit.currentCount,
                target = habit.target,
                frequency = habit.frequency,
                // Persist the "every N" multiplier so FREQ_ASC/FREQ_DESC sorting works
                frequencyN = habit.frequencyN.coerceAtLeast(1),
                colorHex = habit.colorHex,
                totalProgressUpdates = habit.totalProgressUpdates,
                totalTargetReaches = habit.totalTargetReaches,
                lastResetDate = habit.lastResetDate,
                // selectedCategories carries the form's chosen categories on submission
                categories = habit.selectedCategories.toList(),
                reminderEnabled = habit.reminderEnabled,
                reminderTime = habit.reminderTime
            )
            habitDao.insertHabit(entity)
            // Phase 7.1 — schedule the first reminder slot after the row is committed.
            // The use case re-reads the entity by name to obtain the auto-generated id,
            // because Room's insertHabit() does not return it through this DAO.
            val saved = habitDao.findByNameIgnoreCase(habit.name)
            if (saved != null) scheduleReminderUseCase.schedule(saved)
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

                    // Emit celebration event so the full-screen confetti overlay fires
                    // exactly when the user taps the last required completion.
                    if (isTargetReached) _celebrationEvent.tryEmit(Unit)
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
        frequencyN: Int = 1,
        colorHex: String,
        categories: List<String> = emptyList(),
        iconKey: String? = null,
        reminderEnabled: Boolean = false,
        reminderTime: Long? = null,
        onSuccess: () -> Unit,
        onError: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                val existingHabit = habitDao.getHabitById(id) ?: throw Exception("Habit not found")
                // Phase 9.3: detect a target change, bump targetVersion, and log to history.
                val targetChanged = existingHabit.target != target
                val newTargetVersion = if (targetChanged) existingHabit.targetVersion + 1
                                       else existingHabit.targetVersion
                val updatedHabit = existingHabit.copy(
                    name = name,
                    target = target,
                    frequency = frequency,
                    frequencyN = frequencyN.coerceAtLeast(1),
                    colorHex = colorHex,
                    categories = categories,
                    iconKey = iconKey,
                    reminderEnabled = reminderEnabled,
                    reminderTime = reminderTime,
                    targetVersion = newTargetVersion
                )
                habitDao.updateHabit(updatedHabit)
                if (targetChanged) {
                    // Insert an audit row so TargetAdjustmentUseCase can derive
                    // previousDelta and periodsSinceLastChange features.
                    targetHistoryDao.insert(
                        HabitTargetHistoryEntity(
                            habitId = id,
                            oldTarget = existingHabit.target,
                            newTarget = target,
                            changedAt = java.time.LocalDateTime.now(),
                            version = newTargetVersion
                        )
                    )
                }
                // Phase 7.1 — (re)schedule the reminder for the new slot or cancel if disabled.
                scheduleReminderUseCase.schedule(updatedHabit)
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
                scheduleReminderUseCase.cancel(habitId)
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
            // Cancel scheduled reminders while the habit is paused; will be re-armed on resume.
            scheduleReminderUseCase.cancel(id)
        }
    }

    /**
     * Resumes a paused habit immediately by clearing its [pausedUntil] timestamp.
     */
    fun resumeHabit(id: Int) {
        viewModelScope.launch {
            val habit = habitDao.getHabitById(id) ?: return@launch
            val resumed = habit.copy(pausedUntil = null)
            habitDao.updateHabit(resumed)
            scheduleReminderUseCase.schedule(resumed)
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

    /**
     * Deletes a group and all habits inside it.
     *
     * Triggered when the user confirms the delete-group dialog on a non-empty group.
     * Uses an atomic bulk DELETE so the UI reflects the removal in a single DB write.
     *
     * @param groupName The name of the group whose habits should be deleted.
     */
    fun deleteManualGroupWithHabits(groupName: String) {
        viewModelScope.launch {
            habitDao.deleteHabitsByGroup(groupName)
        }
    }

    /**
     * Edits the membership of an existing manual group.
     *
     * Compares [newHabitIds] against [previousHabitIds] to determine what changed:
     * - Habits removed from the selection → [manualGroup] cleared (habit stays, just ungrouped).
     * - Newly added habits → [manualGroup] set to [groupName] and packed at the end of the list.
     *
     * This is the Observer/Command pattern: the UI sends the desired end-state and the ViewModel
     * resolves the delta, keeping the View layer free of DB logic.
     *
     * @param groupName   Name of the group being edited.
     * @param newHabitIds IDs of habits that should be in the group after this operation.
     * @param previousHabitIds IDs of habits that were in the group before editing.
     */
    fun updateManualGroupMembers(
        groupName: String,
        newHabitIds: List<Int>,
        previousHabitIds: List<Int>
    ) {
        viewModelScope.launch {
            // Habits unchecked by the user — remove from group but keep in DB.
            val toUnassign = previousHabitIds.filter { it !in newHabitIds }
            toUnassign.forEach { id ->
                habitDao.unassignHabitFromGroup(id)
            }

            // Habits newly added to the group — assign group name and pack them at the end.
            val toAdd = newHabitIds.filter { it !in previousHabitIds }
            if (toAdd.isNotEmpty()) {
                val allHabits = habitDao.getAllHabitsOnce()
                val maxOrder = allHabits.maxOfOrNull { it.sortOrder } ?: 0
                toAdd.forEachIndexed { index, id ->
                    val entity = habitDao.getHabitById(id) ?: return@forEachIndexed
                    habitDao.updateHabit(entity.copy(
                        manualGroup = groupName,
                        sortOrder = maxOrder + 1 + index
                    ))
                }
            }
        }
    }

    /**
     * Stamps [perceivedDifficulty] on the most recently inserted completion for [habitId].
     *
     * Called by [com.example.evolvix.ui.components.ProgressItem]'s star-chip row immediately
     * after the user logs a new completion tap (Phase 9.4). The most recent completion is
     * the first row from [HabitDao.getCompletionsForHabit], which is ordered DESC.
     * Uses [HabitDao.updateCompletion] to patch the field in-place; Room propagates the
     * change to all active Flows (Observer pattern).
     *
     * A no-op if no completions exist for [habitId].
     *
     * @param habitId The habit whose latest completion should be rated.
     * @param rating  User-selected difficulty value in the range 1–5.
     */
    fun rateLastCompletion(habitId: Int, rating: Int) {
        viewModelScope.launch {
            try {
                val latest = habitDao.getCompletionsForHabit(habitId).first().firstOrNull()
                if (latest != null) {
                    habitDao.updateCompletion(latest.copy(perceivedDifficulty = rating))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun checkAndResetProgress() {
        viewModelScope.launch {
            try {
                // Use a one-shot query so this coroutine completes instead of
                // collecting indefinitely like a Room Flow would.
                val habitList = habitDao.getAllHabitsOnce()
                val now = LocalDateTime.now()
                val nowMillis = System.currentTimeMillis()

                    habitList.forEach { habit ->
                        // Auto-resume: if pausedUntil has passed, clear it so the habit
                        // becomes active again without any manual action from the user.
                        if (habit.pausedUntil != null && habit.pausedUntil != Long.MAX_VALUE
                            && nowMillis >= habit.pausedUntil) {
                            habitDao.updateHabit(habit.copy(pausedUntil = null))
                        }

                        // Delegate reset predicate to ShouldResetHabitUseCase — single source of
                        // truth shared with HabitActionReceiver (notification-tap path).
                        val shouldReset = ShouldResetHabitUseCase()(habit, now)

                        if (shouldReset) {
                            habitDao.updateHabit(habit.copy(
                                currentCount = 0,
                                lastResetDate = now
                            ))
                            Log.d(
                                "HabitViewModel",
                                "Reset progress for habit: ${habit.name} (every ${habit.frequencyN} ${habit.frequency})"
                            )
                        }
                    }
            } catch (e: Exception) {
                Log.e("HabitViewModel", "Error resetting habits: ${e.message}")
            }
        }
    }
}
