package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.model.DaySummary
import com.example.evolvix.domain.model.WeeklyOverview
import java.time.LocalDate

/**
 * Use Case / Interactor that aggregates habit completion data into a [WeeklyOverview].
 *
 * For each of the 7 days in the rolling window [today − 6 .. today], it counts how many
 * habits had at least one [HabitCompletionEntity] record where [isTargetReached] is true.
 * The result drives the "Global Overview" card in StatisticsScreen (Pattern: Use Case per query).
 *
 * This is a pure-function interactor — no side effects, no Room or ViewModel dependencies.
 * Invoked via the `operator fun invoke(...)` convention (Interactor pattern).
 */
class WeeklyOverviewUseCase {

    /**
     * Computes a [WeeklyOverview] for the 7-day window ending on [today].
     *
     * Algorithm:
     * 1. Build a map of `LocalDate → Set<habitId>` for all target-reached completions.
     * 2. For each of the 7 window days, look up the set and count distinct habit IDs.
     * 3. Compute [WeeklyOverview.weekCompletionRate] as completed (habit, day) pairs
     *    divided by total (habit, day) pairs in the window.
     *
     * @param habits All active (non-paused) [HabitEntity] records to include.
     * @param completions All [HabitCompletionEntity] records relevant to these habits.
     * @param today Reference date for the window end (injectable for testing).
     * @return [WeeklyOverview] containing 7 [DaySummary] entries and aggregate metrics.
     */
    operator fun invoke(
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>,
        today: LocalDate = LocalDate.now()
    ): WeeklyOverview {
        val totalHabits = habits.size

        // Map each date to the set of habitIds that reached their target that day.
        // Using a Set<Int> per date ensures each habit is counted once per day even if
        // the user logged multiple over-completions.
        val reachedByDate: Map<LocalDate, Set<Int>> = completions
            .filter { it.isTargetReached }
            .groupBy { it.progressUpdate.toLocalDate() }
            .mapValues { (_, records) -> records.map { it.habitId }.toSet() }

        // Build the 7-day window: oldest day first, today last.
        val window: List<LocalDate> = (6 downTo 0).map { today.minusDays(it.toLong()) }

        val dailySummaries: List<DaySummary> = window.map { date ->
            val completedOnDate = reachedByDate[date]?.size ?: 0
            DaySummary(date = date, completedHabits = completedOnDate, totalHabits = totalHabits)
        }

        val todayCompleted = reachedByDate[today]?.size ?: 0

        // Week completion rate: ratio of completed (habit × day) pairs to total possible pairs.
        val weekCompletionRate = if (totalHabits == 0) {
            0f
        } else {
            val totalPairs = totalHabits * window.size
            val completedPairs = dailySummaries.sumOf { it.completedHabits }
            completedPairs.toFloat() / totalPairs
        }

        return WeeklyOverview(
            dailySummaries = dailySummaries,
            totalActiveHabits = totalHabits,
            todayCompletedHabits = todayCompleted,
            weekCompletionRate = weekCompletionRate
        )
    }
}
