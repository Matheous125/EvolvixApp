package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitClash
import com.example.evolvix.domain.model.HabitData

/**
 * Use Case / Interactor that detects pairs of habits that negatively interfere with
 * each other based on Pearson correlation of their daily completion vectors.
 *
 * Responsibility: pass the full habit and completion lists to
 * [HabitPredictor.detectClashes], then map the raw `List<Pair<String, String>>`
 * into typed [HabitClash] domain objects. Callers (e.g. [StatisticsViewModel])
 * never interact with the Pearson math directly — this is the Use Case / Interactor pattern.
 *
 * The underlying algorithm (in [MathHabitPredictor]):
 * 1. Builds a binary daily completion vector (1 = target reached, 0 = not) for each habit.
 * 2. Computes Pearson r for every pair of habits over their shared observation dates.
 * 3. Reports pairs where r < [clashThreshold] (default −0.4) as clashing, provided
 *    both habits have at least [MathHabitPredictor.MIN_CLASH_SAMPLES] completed days.
 *
 * Returns an empty list when no clashes are detected or data is insufficient —
 * the UI should hide the clash section rather than show an empty state in that case.
 *
 * @param predictor      Strategy implementation of [HabitPredictor]; injectable so
 *                       [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 * @param clashThreshold Pearson r below which a pair is considered clashing (default −0.4).
 *                       Exposed for testing; the default matches [MathHabitPredictor].
 */
class HabitClashingUseCase(
    private val predictor: HabitPredictor,
    private val clashThreshold: Double = DEFAULT_THRESHOLD
) {

    companion object {
        /** Mirrors the default threshold in [MathHabitPredictor.detectClashes]. */
        const val DEFAULT_THRESHOLD = -0.4
    }

    /**
     * Computes a list of [HabitClash] pairs for the given habit collection.
     *
     * @param allHabits       All habits owned by the user (provides names and ids).
     * @param allCompletions  All completion records across every habit.
     * @return List of [HabitClash] pairs ordered by detection (may be empty, never null).
     */
    operator fun invoke(
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>
    ): List<HabitClash> =
        predictor
            .detectClashes(allHabits, allCompletions, clashThreshold)
            .map { (nameA, nameB) -> HabitClash(nameA, nameB) }
}
