package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.model.SparklinePoint
import java.time.LocalDate

/**
 * Use Case / Interactor that produces a chronological list of [SparklinePoint] entries
 * for a single habit over a caller-specified date range.
 *
 * Each point marks whether [HabitCompletionEntity.isTargetReached] was true on that day.
 * The output is suitable for rendering a sparkline or bar chart in the per-habit section
 * of StatisticsScreen (7D / 30D / 3M / ALL range tabs) (Pattern: Use Case per query).
 *
 * This is a pure-function interactor — no side effects, no Room or ViewModel dependencies.
 */
class SparklineUseCase {

    /**
     * Builds a [List<SparklinePoint>] from [from] to [to], one entry per calendar day.
     *
     * Algorithm:
     * 1. Collect all dates in [completions] where [isTargetReached] is true into a [Set]
     *    for O(1) per-day lookup.
     * 2. Iterate every calendar day in [from..to] and emit a [SparklinePoint] flagging
     *    whether that date appears in the reached set.
     *
     * @param completions All [HabitCompletionEntity] records for a single habit.
     * @param from Start date of the range (inclusive).
     * @param to End date of the range (inclusive); defaults to [LocalDate.now].
     * @return Chronological list of [SparklinePoint] covering every day in [from..to].
     */
    operator fun invoke(
        completions: List<HabitCompletionEntity>,
        from: LocalDate,
        to: LocalDate = LocalDate.now()
    ): List<SparklinePoint> {
        // Set of dates where the target was reached — O(1) lookup per day in the range.
        val reachedDates: Set<LocalDate> = completions
            .filter { it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        // Emit one SparklinePoint per calendar day in [from..to].
        val points = mutableListOf<SparklinePoint>()
        var cursor = from
        while (!cursor.isAfter(to)) {
            points.add(SparklinePoint(date = cursor, reached = cursor in reachedDates))
            cursor = cursor.plusDays(1)
        }
        return points
    }
}
