package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.RoutinePrecision

/**
 * Use Case / Interactor that measures how clock-consistent a user is when completing a habit.
 *
 * Responsibility: delegate the standard deviation computation (in minutes from midnight)
 * to [HabitPredictor.computeRoutinePrecision], then map the raw `Double?` into the
 * richer [RoutinePrecision] domain model. Callers (e.g. [StatisticsViewModel]) never
 * interact with the math directly — this is the Use Case / Interactor pattern.
 *
 * Qualification tiers for [RoutinePrecision.Rating]:
 * - **VERY_CONSISTENT** — stddev < 30 min  (habit always done at nearly the same time)
 * - **CONSISTENT**      — stddev < 60 min  (within a typical one-hour window)
 * - **VARIABLE**        — stddev < 120 min (loose but recognizable pattern)
 * - **ERRATIC**         — stddev ≥ 120 min (no discernible time pattern)
 *
 * Returns `null` when [HabitPredictor.computeRoutinePrecision] reports insufficient data
 * (fewer than 5 completions), signaling the UI to show a "not enough data" placeholder.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class RoutinePrecisionUseCase(
    private val predictor: HabitPredictor
) {

    companion object {
        private const val THRESHOLD_VERY_CONSISTENT = 30.0  // minutes
        private const val THRESHOLD_CONSISTENT = 60.0       // minutes
        private const val THRESHOLD_VARIABLE = 120.0        // minutes
    }

    /**
     * Computes a [RoutinePrecision] result for a habit given its [completions] history.
     *
     * @param completions All historical completion records for the habit being evaluated.
     * @return [RoutinePrecision] with stddev, sample count, and qualitative rating,
     *         or `null` if there is insufficient data.
     */
    operator fun invoke(
        completions: List<HabitCompletionEntity>
    ): RoutinePrecision? {
        val stddev = predictor.computeRoutinePrecision(completions) ?: return null

        val rating = when {
            stddev < THRESHOLD_VERY_CONSISTENT -> RoutinePrecision.Rating.VERY_CONSISTENT
            stddev < THRESHOLD_CONSISTENT      -> RoutinePrecision.Rating.CONSISTENT
            stddev < THRESHOLD_VARIABLE        -> RoutinePrecision.Rating.VARIABLE
            else                               -> RoutinePrecision.Rating.ERRATIC
        }

        return RoutinePrecision(
            stddevMinutes = stddev,
            sampleCount = completions.size,
            rating = rating
        )
    }
}
