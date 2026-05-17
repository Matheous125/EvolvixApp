package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.StreakRiskAssessment
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Use Case / Interactor that detects high-risk streak patterns for a habit.
 *
 * Responsibility: extract *which* days of the week are consistently missed
 * (feature extraction), then delegate the overall risk decision to [HabitPredictor]
 * (Strategy + Dependency Inversion pattern). The result drives the `✨ Smart Insight`
 * card in StatisticsScreen, allowing targeted recovery advice such as
 * "You tend to miss Sundays — consider setting a reminder."
 *
 * Split of responsibility vs [HabitPredictor]:
 * - [HabitPredictor.isStreakAtRisk] answers: "is this habit at risk?" (Boolean).
 * - This use case answers: "which specific days cause the risk?" (feature extraction).
 * Both pieces are needed to show actionable advice in the UI.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class StreakRecoveryUseCase(
    private val predictor: HabitPredictor
) {

    companion object {
        /**
         * Minimum number of target-reached completions required before the day-of-week
         * pattern analysis is meaningful. Set to 7 — roughly one full week of data.
         */
        const val MIN_DATA_THRESHOLD = 7

        /**
         * How many recent occurrences of a weekday to look back when classifying that
         * day as high-risk. Mirrors the constant used in [MathHabitPredictor].
         */
        private const val LOOKBACK_WEEKS = 4

        /**
         * How many of the [LOOKBACK_WEEKS] occurrences must be missed for a day to be
         * flagged as a risk day. 3 out of 4 = consistently missing.
         */
        private const val MISS_THRESHOLD = 3
    }

    /**
     * Computes a [StreakRiskAssessment] for [habit] given its [completions] history.
     *
     * Algorithm:
     * 1. Check data sufficiency — return a safe "not enough data" result if below threshold.
     * 2. For daily habits, scan each day of the week over the last [LOOKBACK_WEEKS] weeks.
     *    Any day missed on [MISS_THRESHOLD]+ of those occurrences is added to [riskDays].
     * 3. Delegate the overall `isAtRisk` flag to the injected predictor.
     *
     * @param habit       Domain model of the habit to evaluate.
     * @param completions All historical completion records for this habit.
     * @param today       Reference date (defaults to system clock; injectable for testing).
     * @return [StreakRiskAssessment] with the risk flag, specific risk days, and data flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        today: LocalDate = LocalDate.now()
    ): StreakRiskAssessment {
        val targetReached = completions.filter { it.isTargetReached }

        if (targetReached.size < MIN_DATA_THRESHOLD) {
            return StreakRiskAssessment(
                isAtRisk = false,
                riskDays = emptyList(),
                hasSufficientData = false
            )
        }

        // Overall risk flag — delegate to the predictor (Strategy pattern).
        val isAtRisk = predictor.isStreakAtRisk(habit, completions)

        // Feature extraction: identify which specific days-of-week are consistently missed.
        // This only applies to daily habits — weekly/monthly habits don't have a
        // meaningful day-of-week pattern, so riskDays is left empty for them.
        val riskDays: List<DayOfWeek> = if (habit.frequency == HabitFrequency.Daily) {
            detectRiskDays(targetReached, today)
        } else {
            emptyList()
        }

        return StreakRiskAssessment(
            isAtRisk = isAtRisk,
            riskDays = riskDays,
            hasSufficientData = true
        )
    }

    /**
     * Scans each day of the week (Mon–Sun) over the last [LOOKBACK_WEEKS] occurrences.
     * A day is flagged as a "risk day" when it was missed on [MISS_THRESHOLD]+ of those
     * [LOOKBACK_WEEKS] occurrences.
     *
     * This is the feature-extraction mirror of the private [MathHabitPredictor.isDailyStreakAtRisk]
     * helper — here it returns the individual offending days rather than a single boolean,
     * so the UI can surface specific actionable advice per day.
     */
    private fun detectRiskDays(
        targetReachedCompletions: List<HabitCompletionEntity>,
        today: LocalDate
    ): List<DayOfWeek> {
        val reachedDates: Set<LocalDate> = targetReachedCompletions
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        return DayOfWeek.entries.filter { dow ->
            // Build the last LOOKBACK_WEEKS occurrences of this weekday (excluding today).
            val occurrences = (1..LOOKBACK_WEEKS).map { weeksBack ->
                today.minusWeeks(weeksBack.toLong()).with(dow)
            }
            // Flag the day if MISS_THRESHOLD or more occurrences were not completed.
            occurrences.count { it !in reachedDates } >= MISS_THRESHOLD
        }
    }
}
