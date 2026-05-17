package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.HabitRecommendation

// Minimum number of target-reached dates the focal habit must have before
// co-occurrence analysis is meaningful. Mirrors the threshold in MathHabitPredictor.
private const val MIN_DATA_THRESHOLD = 5

/**
 * Use Case / Interactor that recommends related habits based on co-occurrence patterns.
 *
 * Responsibility: check data sufficiency, then delegate the co-occurrence ranking to
 * [HabitPredictor] (Strategy + Dependency Inversion pattern). The result drives the
 * `🧠 Behavioral Patterns` card in StatisticsScreen.
 *
 * Co-occurrence rule (implemented in [HabitPredictor.relatedHabits]):
 * Two habits are "related" when they share at least a minimum number of calendar days
 * on which both reached their target, AND their co-occurrence rate (shared days /
 * focal habit's completed days) exceeds a defined threshold. This is a standard
 * support-based association rule, defensible as a simple collaborative-filtering step.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class HabitRecommendationUseCase(
    private val predictor: HabitPredictor
) {

    /**
     * Computes a [HabitRecommendation] for [habit] relative to all habits in [allHabits].
     *
     * @param habit          The focal habit to find co-occurring partners for.
     * @param allHabits      All habits the user has (including [habit] itself — the
     *                       predictor filters it out internally).
     * @param allCompletions Completion records for every habit (used to build the
     *                       co-occurrence matrix across the whole history).
     * @return [HabitRecommendation] with ranked related habit names and a data flag.
     */
    operator fun invoke(
        habit: HabitData,
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>
    ): HabitRecommendation {
        // Count distinct dates where the focal habit reached its target.
        // Below the threshold the co-occurrence signal is too sparse to be trustworthy.
        val focalCompletedDates = allCompletions
            .filter { it.habitId == habit.id && it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val hasSufficientData = focalCompletedDates.size >= MIN_DATA_THRESHOLD

        // Delegate co-occurrence ranking to the injected predictor (Strategy pattern).
        // When hasSufficientData is false the predictor will return an empty list anyway,
        // but the flag lets the UI show the right copy ("not enough data" vs "no matches").
        val relatedNames = predictor.relatedHabits(habit, allHabits, allCompletions)

        return HabitRecommendation(
            relatedHabitNames = relatedNames,
            hasSufficientData = hasSufficientData
        )
    }
}
