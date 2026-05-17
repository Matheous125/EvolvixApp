package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.OptimalTimePrediction

/**
 * Use Case / Interactor that identifies the optimal hours of day for completing a habit.
 *
 * Responsibility: bin historical completions into a 24-bucket hour histogram (feature
 * extraction), then delegate ranking of the top slots to [HabitPredictor]
 * (Strategy + Dependency Inversion pattern). The ViewModel receives a rich
 * [OptimalTimePrediction] that drives both the "best time" label and the bar chart on
 * the `🕒 Optimal Timing` card in StatisticsScreen.
 *
 * Separation of concerns:
 * - This class owns the feature extraction (binning completions by hour).
 * - [HabitPredictor] owns the ranking logic (today it is histogram rank; in Phase 6.5
 *   a TFLite model may produce a learned ranking without changing this class).
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class OptimalTimeUseCase(
    private val predictor: HabitPredictor
) {

    /**
     * Computes an [OptimalTimePrediction] for [habit] given its [completions] history.
     *
     * Algorithm:
     * 1. Filter completions to target-reached records and check the data threshold.
     * 2. Build a 24-bucket histogram by tallying the hour of each target-reached timestamp.
     * 3. Delegate ranked-hour selection to [predictor] (reuses its fallback logic when
     *    data is insufficient, e.g. defaults to morning hours).
     *
     * @param habit       Domain model of the habit to evaluate.
     * @param completions All historical completion records for this habit.
     * @param topN        Number of top hours to rank and return (default 3).
     * @return [OptimalTimePrediction] with ranked hours, full histogram, and a data-
     *         sufficiency flag for the UI to decide whether to show a placeholder.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        topN: Int = 3
    ): OptimalTimePrediction {
        val targetCompletions = completions.filter { it.isTargetReached }

        // The predictor requires at least 5 target-reached records before its histogram
        // rank is meaningful. Below this threshold it falls back to morning defaults —
        // the flag lets the UI show "not enough history yet" instead of those defaults.
        val hasEnoughData = targetCompletions.size >= 5

        // Feature extraction: bin each target-reached completion by its hour of day.
        // This produces the raw counts the UI uses for the bar chart — kept here (not
        // inside the predictor) because it is feature data, not prediction logic.
        val hourlyBins = IntArray(24)
        for (c in targetCompletions) {
            hourlyBins[c.progressUpdate.hour]++
        }

        // Delegate ranking to the injected predictor (Strategy pattern). The predictor
        // applies the same binning internally, but we expose the histogram separately
        // so the Statistics card can render it without re-computing.
        val rankedHours = predictor.optimalHours(habit, completions, topN)

        return OptimalTimePrediction(
            rankedHours = rankedHours,
            hourlyBins = hourlyBins.toList(),
            hasEnoughData = hasEnoughData
        )
    }
}
