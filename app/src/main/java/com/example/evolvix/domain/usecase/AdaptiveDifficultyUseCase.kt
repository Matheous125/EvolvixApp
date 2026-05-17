package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.DifficultyAdjustment
import com.example.evolvix.domain.model.HabitData
import java.time.LocalDate

/**
 * Use Case / Interactor that suggests whether to increase or decrease a habit's target
 * based on its rolling 14-day completion rate.
 *
 * Responsibility: compute the completion rate over the rolling window (feature extraction),
 * check data sufficiency, then delegate the directional decision (+1 / 0 / -1) to
 * [HabitPredictor] (Strategy + Dependency Inversion pattern). The result provides
 * the concrete numbers the `✨ Smart Insight` card in StatisticsScreen needs to
 * display actionable advice, e.g. "14-day rate: 94% — consider raising target from 2 → 3."
 *
 * Thresholds (mirrored from [MathHabitPredictor]):
 * - Rate ≥ 90% → delta +1 (habit is too easy; challenge the user).
 * - Rate ≤ 40% → delta -1 (habit is too hard; reduce to rebuild momentum).
 * - Otherwise   → delta  0 (target is well-calibrated; no change).
 *
 * Minimum history: 5 periods within the 14-day window (matches [MathHabitPredictor.MIN_TARGET_SAMPLE]).
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class AdaptiveDifficultyUseCase(
    private val predictor: HabitPredictor
) {

    companion object {
        /** Rolling window length in days — must match the window used in [MathHabitPredictor]. */
        private const val WINDOW_DAYS = 14L

        /**
         * Minimum number of periods that must exist in the rolling window before the
         * advice is meaningful. Mirrors [MathHabitPredictor.MIN_TARGET_SAMPLE].
         */
        private const val MIN_PERIODS = 5
    }

    /**
     * Computes a [DifficultyAdjustment] for [habit] given its [completions] history.
     *
     * Algorithm:
     * 1. Compute the rolling [WINDOW_DAYS]-day completion rate (feature extraction).
     * 2. Check that at least [MIN_PERIODS] periods fall within the window; return a
     *    safe "not enough data" result if not.
     * 3. Delegate the directional delta to the injected predictor (Strategy pattern).
     * 4. Compute the concrete [suggestedTarget] = current target + delta, clamped to ≥ 1.
     *
     * @param habit       Domain model of the habit to evaluate.
     * @param completions All historical completion records for this habit.
     * @param today       Reference date (defaults to system clock; injectable for testing).
     * @return [DifficultyAdjustment] with delta, rolling rate, and suggested target.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        today: LocalDate = LocalDate.now()
    ): DifficultyAdjustment {
        val since = today.minusDays(WINDOW_DAYS)
        val periodDays = habit.frequency.days

        // Feature extraction: distinct calendar dates within the window where the target
        // was reached. Using a Set<LocalDate> ensures over-completions on the same day
        // count as one reached period.
        val reachedDates: Set<LocalDate> = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val totalPeriods = (WINDOW_DAYS / periodDays).toInt().coerceAtLeast(1)

        if (totalPeriods < MIN_PERIODS) {
            return DifficultyAdjustment(
                delta = 0,
                rollingRate = 0f,
                currentTarget = habit.target,
                suggestedTarget = habit.target,
                hasSufficientData = false
            )
        }

        val rollingRate = reachedDates.size.toFloat() / totalPeriods

        // Delegate the directional decision to the injected predictor (Strategy pattern).
        val delta = predictor.suggestTargetDelta(habit, completions)

        // Clamp to ≥ 1: a target of 0 or below is never valid.
        val suggestedTarget = (habit.target + delta).coerceAtLeast(1)

        return DifficultyAdjustment(
            delta = delta,
            rollingRate = rollingRate,
            currentTarget = habit.target,
            suggestedTarget = suggestedTarget,
            hasSufficientData = true
        )
    }
}
