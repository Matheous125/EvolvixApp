package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.model.StreakResult
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Use Case / Interactor responsible for computing streak metrics from a flat list of completion records.
 *
 * Single-responsibility: given a list of [HabitCompletionEntity] and the habit's [HabitFrequency],
 * it returns a [StreakResult] with [StreakResult.current] and [StreakResult.best] streak counts.
 *
 * This is a pure-function invocable class — no side effects, no Room or ViewModel dependencies.
 * It is invoked via the `operator fun invoke(...)` convention (Interactor pattern).
 */
class CalculateStreakUseCase {

    /**
     * Computes [StreakResult] from raw completion records.
     *
     * Algorithm:
     * 1. Filter records where [HabitCompletionEntity.isTargetReached] is true (target was met).
     * 2. Map each record's date to a monotonic period key — consecutive periods always differ by 1,
     *    which makes the streak check a simple integer adjacency test.
     * 3. Scan the sorted key list once to find the longest consecutive run ([StreakResult.best]).
     * 4. Walk backwards from today (or yesterday if today is not yet completed) to count the
     *    unbroken tail run ([StreakResult.current]).
     *
     * @param completions All [HabitCompletionEntity] records for a single habit.
     * @param frequency The [HabitFrequency] of the habit — determines the granularity of one "period".
     * @param today Reference date for the current streak (defaults to [LocalDate.now]; injectable for testing).
     * @return [StreakResult] with current and best streak values measured in periods (days/weeks/months/years).
     */
    operator fun invoke(
        completions: List<HabitCompletionEntity>,
        frequency: HabitFrequency,
        today: LocalDate = LocalDate.now()
    ): StreakResult {
        if (completions.isEmpty()) return StreakResult(current = 0, best = 0)

        // Step 1: collect distinct periods where the target was reached at least once.
        val completedPeriods: Set<Long> = completions
            .filter { it.isTargetReached }
            .map { toPeriodKey(it.progressUpdate.toLocalDate(), frequency) }
            .toSet()

        if (completedPeriods.isEmpty()) return StreakResult(current = 0, best = 0)

        // Step 2: sort period keys ascending for the best-streak scan.
        val sorted = completedPeriods.sorted()

        // Step 3: compute best streak — one linear scan over the sorted period keys.
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            // Consecutive periods produced by toPeriodKey() always differ by exactly 1.
            if (sorted[i] - sorted[i - 1] == 1L) {
                run++
                if (run > best) best = run
            } else {
                run = 1
            }
        }

        // Step 4: compute current streak — walk backwards from today (or yesterday).
        // If today has been completed, the streak includes today.
        // If today has not been completed yet, the streak is still alive as long as
        // yesterday (the previous period) was completed.
        val todayKey = toPeriodKey(today, frequency)
        val startKey = if (todayKey in completedPeriods) todayKey else todayKey - 1
        var current = 0
        var checkKey = startKey
        while (checkKey in completedPeriods) {
            current++
            checkKey--
        }

        return StreakResult(current = current, best = best)
    }

    /**
     * Maps a [LocalDate] to a monotonically increasing Long key whose consecutive values
     * always differ by exactly 1 for the given [frequency]. This invariant is required by
     * the streak adjacency check above.
     *
     * - **Daily** → epoch day (built-in; consecutive days differ by 1).
     * - **Weekly**  → epoch day of the ISO week's Monday / 7. Snapping to Monday via
     *   [TemporalAdjusters.previousOrSame] ensures every date in the same Mon–Sun week
     *   maps to the same key. Consecutive Mondays are always exactly 7 epoch days apart,
     *   so the consecutive-difference-of-1 invariant is preserved.
     * - **Monthly** → year × 12 + monthValue (adjacent months differ by 1 across year boundaries).
     * - **Yearly**  → calendar year (adjacent years differ by 1).
     */
    private fun toPeriodKey(date: LocalDate, frequency: HabitFrequency): Long = when (frequency) {
        HabitFrequency.Daily   -> date.toEpochDay()
        HabitFrequency.Weekly  -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay() / 7
        HabitFrequency.Monthly -> date.year * 12L + date.monthValue
        HabitFrequency.Yearly  -> date.year.toLong()
    }
}
