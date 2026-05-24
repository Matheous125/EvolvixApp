package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.SpilloverFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SpilloverPair
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Training-data median for typicalGapHours (≈ 3 h from generate_spillover_data.py,
// exponential scale=3.0). Used when fewer than MIN_SHARED_DAYS shared completions exist.
private const val GAP_MEDIAN_FALLBACK = 3.0f

// Minimum shared A+B completed days required to compute a reliable typicalGapHours.
// Below this threshold the fallback median is used instead.
private const val MIN_SHARED_DAYS = 3

// Look-back window for 30-day rates and co-occurrence calculation.
private const val WINDOW_DAYS = 30L

// Maximum number of SpilloverPairs returned to the ViewModel (top by |liftDelta|).
private const val MAX_PAIRS = 3

/**
 * Use Case / Interactor that identifies which habit completions today are likely to
 * have a positive or negative spillover effect on the user's other habits (Phase 8.5).
 *
 * Responsibility:
 * 1. Guard: fewer than 2 active habits → return empty list.
 * 2. Identify habit(s) A that were completed today (target reached on [today]).
 * 3. For each ordered pair (A, B) where A ≠ B and A was completed today:
 *    a. Compute 5-field [SpilloverFeatures] from 30-day history.
 *    b. Delegate to [HabitPredictor.predictSpillover] (Strategy + DI pattern).
 *    c. Wrap raw lift in [SpilloverPair], attaching [SpilloverPair.Direction].
 * 4. Filter out NEUTRAL pairs, sort by `|liftDelta|` descending, return top [MAX_PAIRS].
 *
 * ⚠ **Causal caveat:** The output reflects observational co-occurrence lift, not a
 * causal effect. The View should use hedged language (e.g. "tends to boost").
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class SpilloverUseCase(
    private val predictor: HabitPredictor
) {

    /**
     * Computes a ranked list of [SpilloverPair]s for today.
     *
     * @param habits         All active (non-paused) habits.
     * @param completions    All historical completion records across every habit.
     * @param today          Reference date (defaults to system clock; injectable for testing).
     * @return Up to [MAX_PAIRS] non-NEUTRAL [SpilloverPair]s sorted by |liftDelta| descending,
     *         or an empty list when fewer than 2 habits exist or nothing was completed today.
     */
    operator fun invoke(
        habits: List<HabitData>,
        completions: List<HabitCompletionEntity>,
        today: LocalDate = LocalDate.now()
    ): List<SpilloverPair> {
        if (habits.size < 2) return emptyList()

        // Build a set of habitIds whose target was reached on [today].
        val completedTodayIds: Set<Int> = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() == today }
            .map { it.habitId }
            .toSet()

        if (completedTodayIds.isEmpty()) return emptyList()

        // Index completions by habitId for O(1) lookup inside the pair loop.
        val byHabit: Map<Int, List<HabitCompletionEntity>> = completions.groupBy { it.habitId }

        val windowStart = today.minusDays(WINDOW_DAYS)

        val results = mutableListOf<SpilloverPair>()

        for (habitA in habits) {
            if (habitA.id !in completedTodayIds) continue

            // Hour at which A was completed today (first target-reached record of today).
            val hourACompleted: Int = completions
                .filter { it.habitId == habitA.id && it.isTargetReached && it.progressUpdate.toLocalDate() == today }
                .minByOrNull { it.progressUpdate }
                ?.progressUpdate?.hour ?: 12   // noon fallback (shouldn't happen)

            val completionsA = byHabit[habitA.id] ?: emptyList()
            // Dates on which A reached its target within the 30-day window.
            val aDates: Set<LocalDate> = completionsA
                .filter { it.isTargetReached && !it.progressUpdate.toLocalDate().isBefore(windowStart) }
                .map { it.progressUpdate.toLocalDate() }
                .toSet()

            val rateA = aDates.size.toFloat() / WINDOW_DAYS

            for (habitB in habits) {
                if (habitB.id == habitA.id) continue

                val completionsB = byHabit[habitB.id] ?: emptyList()
                val bDates: Set<LocalDate> = completionsB
                    .filter { it.isTargetReached && !it.progressUpdate.toLocalDate().isBefore(windowStart) }
                    .map { it.progressUpdate.toLocalDate() }
                    .toSet()

                val rateB = bDates.size.toFloat() / WINDOW_DAYS

                // co-occurrence: fraction of A-completed days on which B was also completed.
                val coOccurrenceRate = if (aDates.isEmpty()) 0f else
                    aDates.count { it in bDates }.toFloat() / aDates.size

                // typicalGapHours: median |t_B - t_A| in hours on shared days.
                val sharedDates = aDates.intersect(bDates)
                val typicalGapHours = if (sharedDates.size >= MIN_SHARED_DAYS) {
                    computeMedianGapHours(sharedDates, completionsA, completionsB)
                } else {
                    GAP_MEDIAN_FALLBACK
                }

                val features = SpilloverFeatures(
                    rateA = rateA.coerceIn(0f, 1f),
                    rateB = rateB.coerceIn(0f, 1f),
                    hourACompleted = hourACompleted,
                    coOccurrenceRate = coOccurrenceRate.coerceIn(0f, 1f),
                    typicalGapHours = typicalGapHours.coerceIn(0f, 24f)
                )

                val liftDelta = predictor.predictSpillover(features)
                val direction = SpilloverPair.directionFor(liftDelta)

                if (direction != SpilloverPair.Direction.NEUTRAL) {
                    results += SpilloverPair(
                        habitAName = habitA.name,
                        habitBName = habitB.name,
                        liftDelta = liftDelta,
                        direction = direction
                    )
                }
            }
        }

        // Return the top MAX_PAIRS pairs ranked by absolute lift magnitude.
        return results
            .sortedByDescending { abs(it.liftDelta) }
            .take(MAX_PAIRS)
    }

    /**
     * Computes the median absolute gap in hours between habit A's and habit B's
     * first target-reached timestamps on [sharedDates].
     *
     * Falls back to [GAP_MEDIAN_FALLBACK] if either habit has no matching record
     * for a shared date (defensive; should not happen given [sharedDates] definition).
     */
    private fun computeMedianGapHours(
        sharedDates: Set<LocalDate>,
        completionsA: List<HabitCompletionEntity>,
        completionsB: List<HabitCompletionEntity>
    ): Float {
        // Index first target-reached timestamp per day for each habit.
        val aByDate = completionsA
            .filter { it.isTargetReached }
            .groupBy { it.progressUpdate.toLocalDate() }
            .mapValues { (_, list) -> list.minByOrNull { it.progressUpdate }!!.progressUpdate }

        val bByDate = completionsB
            .filter { it.isTargetReached }
            .groupBy { it.progressUpdate.toLocalDate() }
            .mapValues { (_, list) -> list.minByOrNull { it.progressUpdate }!!.progressUpdate }

        val gaps: List<Float> = sharedDates.mapNotNull { date ->
            val tA = aByDate[date] ?: return@mapNotNull null
            val tB = bByDate[date] ?: return@mapNotNull null
            abs(ChronoUnit.MINUTES.between(tA, tB)).toFloat() / 60f
        }

        if (gaps.isEmpty()) return GAP_MEDIAN_FALLBACK
        val sorted = gaps.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        }
    }
}
