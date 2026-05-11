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
     * @param completionId Primary key of the [HabitCompletionEntity] to remove.
     */
    fun deleteCompletion(completionId: Int) {
        viewModelScope.launch {
            dao.deleteCompletion(completionId)
        }
    }

    /**
     * Overwrites an existing completion record with new data.
     * Used by the inline-edit dialog on the History screen.
     *
     * @param completion Updated entity — its [id] must match an existing row.
     */
    fun updateCompletion(completion: HabitCompletionEntity) {
        viewModelScope.launch {
            dao.updateCompletion(completion)
        }
    }

    /**
     * Inserts a retroactive completion entry with a user-supplied past timestamp.
     * The [progressUpdate] date comes from a [DatePicker] + [TimePicker] dialog,
     * not from the current system time.
     *
     * @param progressUpdate The user-selected date and time for the retroactive entry.
     * @param isTargetReached Whether this entry should count as a target-reached completion.
     */
    fun addRetroactiveEntry(progressUpdate: LocalDateTime, isTargetReached: Boolean) {
        viewModelScope.launch {
            dao.insertRetroactive(
                HabitCompletionEntity(
                    habitId = habitId,
                    progressUpdate = progressUpdate,
                    isTargetReached = isTargetReached
                )
            )
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
