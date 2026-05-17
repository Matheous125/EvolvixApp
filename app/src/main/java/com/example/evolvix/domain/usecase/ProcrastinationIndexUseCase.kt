package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.ProcrastinationIndex

/**
 * Use Case / Interactor that measures whether a user tends to complete a habit
 * early or late within each period cycle.
 *
 * Responsibility: delegate the skewness computation to
 * [HabitPredictor.computeProcrastination], then map the raw `Double?` into the
 * richer [ProcrastinationIndex] domain model. Callers (e.g. [StatisticsViewModel])
 * never interact with the moment-based skewness math directly —
 * this is the Use Case / Interactor pattern.
 *
 * The underlying algorithm (in [MathHabitPredictor]) uses the standard third-moment
 * skewness formula over the hour-of-day values of all completions:
 *   skewness = (1/n) * Σ((xᵢ − μ) / σ)³
 *
 * Qualification tiers for [ProcrastinationIndex.Rating]:
 * - **PROCRASTINATOR**      — skewness ≤ −1.0  (strong late-day clustering → negative skew)
 * - **MILD_PROCRASTINATOR** — skewness in (−1.0, −0.5]  (slight late-day bias)
 * - **BALANCED**            — skewness in (−0.5, 0.5)   (no dominant pattern)
 * - **EARLY_COMPLETER**     — skewness ≥ 0.5  (strong front-loading → positive skew)
 *
 * Returns `null` when [HabitPredictor.computeProcrastination] reports insufficient data
 * (fewer than 10 completions), signaling the UI to show a "not enough data" placeholder.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class ProcrastinationIndexUseCase(
    private val predictor: HabitPredictor
) {

    companion object {
        private const val THRESHOLD_MILD = 0.5
        private const val THRESHOLD_STRONG = 1.0
    }

    /**
     * Computes a [ProcrastinationIndex] for [habit] given its [completions] history.
     *
     * @param habit       Domain model of the habit to evaluate.
     * @param completions All historical completion records for this habit.
     * @return [ProcrastinationIndex] with skewness, sample count, and qualitative rating,
     *         or `null` if there is insufficient data (fewer than 10 completions).
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): ProcrastinationIndex? {
        val skewness = predictor.computeProcrastination(habit, completions) ?: return null

        // Late completions (procrastination) produce NEGATIVE skewness in the standard
        // third-moment formula; early completions produce POSITIVE skewness.
        val rating = when {
            skewness <= -THRESHOLD_STRONG -> ProcrastinationIndex.Rating.PROCRASTINATOR
            skewness <= -THRESHOLD_MILD   -> ProcrastinationIndex.Rating.MILD_PROCRASTINATOR
            skewness < THRESHOLD_MILD     -> ProcrastinationIndex.Rating.BALANCED
            else                          -> ProcrastinationIndex.Rating.EARLY_COMPLETER
        }

        return ProcrastinationIndex(
            skewness = skewness,
            sampleCount = completions.size,
            rating = rating
        )
    }
}
