package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.model.HabitCompletionEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * ViewModel for the History screen, scoped to a single habit identified by [habitId].
 *
 * Responsibilities:
 * - Collects [HabitCompletionEntity] rows from Room via [HabitDao.getCompletionsForHabit].
 * - Groups and sorts them into a nested map structure ready for the View to render.
 * - Exposes delete, update, and retroactive-insert operations.
 *
 * (Pattern: MVVM + State Holder — the ViewModel owns and transforms UI state;
 *  the View is a stateless consumer of [StateFlow])
 *
 * @property dao The Room DAO used for all completion queries.
 * @property habitId The primary key of the habit whose history is displayed.
 */
class HistoryViewModel(
    private val dao: HabitDao,
    private val habitId: Int
) : ViewModel() {

    /**
     * All completion records for this habit, grouped newest-first.
     *
     * Structure: year (Int) → month-of-year (Int, 1–12) → completions in that month.
     * Both the outer map (years) and inner maps (months) are sorted in descending order
     * so the most-recent entries appear at the top of the History screen without any
     * additional sorting in the View layer.
     *
     * The transformation uses [Flow.map] on the raw DAO [Flow], keeping the data pipeline
     * fully reactive: any insert, update, or delete automatically re-emits a new grouped map.
     *
     * (Pattern: Observer via StateFlow — the View collects this and recomposes on change)
     */
    val groupedByYearMonth: StateFlow<Map<Int, Map<Int, List<HabitCompletionEntity>>>> =
        dao.getCompletionsForHabit(habitId)
            .map { completions -> group(completions) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyMap()
            )

    /**
     * Deletes a single completion record permanently.
     * The Room [Flow] backing [groupedByYearMonth] emits a new value automatically,
     * so the View recomposes without any manual refresh.
     *
     * After deletion the progress bar on the main screen is also updated: if the deleted
     * entry fell inside the current reset cycle (progressUpdate >= lastResetDate) the
     * live [currentCount] on the habit would be one too high. [recalculateCurrentCount]
     * re-counts all cycle completions from the DB and patches [HabitEntity.currentCount].
     *
     * @param completionId Primary key of the [HabitCompletionEntity] to remove.
     */
    fun deleteCompletion(completionId: Int) {
        viewModelScope.launch {
            try {
                dao.deleteCompletion(completionId)
            } catch (_: Exception) { }
            recalculateCurrentCount()
        }
    }

    /**
     * Overwrites an existing completion record with new data.
     * Used by the inline-edit dialog on the History screen.
     *
     * Triggers [recalculateCurrentCount] afterwards so that edits which move a timestamp
     * into or out of the current reset cycle are immediately reflected in the progress bar.
     *
     * @param completion Updated entity — its [id] must match an existing row.
     */
    fun updateCompletion(completion: HabitCompletionEntity) {
        viewModelScope.launch {
            try {
                dao.updateCompletion(completion)
            } catch (_: Exception) { }
            recalculateCurrentCount()
        }
    }

    /**
     * Inserts a retroactive completion entry with a user-supplied past timestamp.
     * The [progressUpdate] date comes from a [DatePicker] + [TimePicker] dialog,
     * not from the current system time.
     *
     * Triggers [recalculateCurrentCount] afterwards so that entries added for the current
     * reset cycle are immediately reflected in the progress bar.
     *
     * @param progressUpdate The user-selected date and time for the retroactive entry.
     * @param isTargetReached Whether this entry should count as a target-reached completion.
     */
    fun addRetroactiveEntry(progressUpdate: LocalDateTime, isTargetReached: Boolean) {
        viewModelScope.launch {
            try {
                dao.insertRetroactive(
                    HabitCompletionEntity(
                        habitId = habitId,
                        progressUpdate = progressUpdate,
                        isTargetReached = isTargetReached
                    )
                )
            } catch (_: Exception) { }
            recalculateCurrentCount()
        }
    }

    /**
     * Recomputes [HabitEntity.currentCount] from the actual completion records in the DB.
     *
     * **Why**: [currentCount] is a cached integer incremented by the main-screen tap.
     * When the user adds, edits, or deletes entries via the History screen the cache
     * becomes stale. This function is the single source of truth re-sync:
     *
     *   1. Fetch the habit to get [HabitEntity.lastResetDate] — the date the current cycle
     *      started (set by [HabitViewModel.checkAndResetProgress]).
     *   2. Floor it to midnight so entries added earlier in the day than the exact reset
     *      timestamp are still counted in the current cycle.
     *   3. Count completion rows with progressUpdate >= cycleStart using Kotlin's
     *      [LocalDateTime] comparator (avoids SQLite ISO-string precision edge cases).
     *   4. Write that count back to [HabitEntity.currentCount].
     *   5. Recompute [HabitCompletionEntity.isTargetReached] for every cycle completion:
     *      sort chronologically — only the entry at index (target - 1) earns the flag.
     *      Only rows where the flag changed are written back (at most one write in the
     *      typical case), so over-completion is handled correctly at no extra cost.
     */
    private suspend fun recalculateCurrentCount() {
        val habit = dao.getHabitById(habitId) ?: return
        val cycleStart = habit.lastResetDate.toLocalDate().atStartOfDay()

        // Filter with Kotlin's LocalDateTime comparator — avoids SQLite ISO-string
        // precision edge cases — then sort chronologically for the target-flag pass.
        val allCompletions = dao.getCompletionsForHabit(habitId).first()
        val cycleCompletions = allCompletions
            .filter { it.progressUpdate >= cycleStart }
            .sortedBy { it.progressUpdate }

        dao.updateHabit(habit.copy(currentCount = cycleCompletions.size))

        // Recompute isTargetReached purely from position in time order.
        // The entry at index (target - 1) is the exact tap that hit the target;
        // all others (including surplus) get false. The guard avoids unnecessary writes.
        cycleCompletions.forEachIndexed { index, completion ->
            val shouldBeReached = (index + 1) == habit.target
            if (completion.isTargetReached != shouldBeReached) {
                dao.updateCompletion(completion.copy(isTargetReached = shouldBeReached))
            }
        }
    }

    /**
     * Groups a flat list of completions into the nested year → month → entries structure.
     * Both levels are sorted in descending order (newest year/month at the top).
     *
     * This is a pure function — it has no side effects and can be tested independently.
     *
     * @param completions Flat list from the DAO, pre-ordered newest-first by the SQL query.
     * @return Nested sorted map ready for the View to iterate.
     */
    private fun group(
        completions: List<HabitCompletionEntity>
    ): Map<Int, Map<Int, List<HabitCompletionEntity>>> =
        completions
            .groupBy { it.progressUpdate.year }
            .mapValues { (_, byYear) ->
                byYear
                    .groupBy { it.progressUpdate.monthValue }
                    .toSortedMap(reverseOrder())
            }
            .toSortedMap(reverseOrder())
}
