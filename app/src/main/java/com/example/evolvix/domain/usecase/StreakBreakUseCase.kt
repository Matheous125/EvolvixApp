package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.StreakBreakFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.StreakBreakRisk
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that estimates the probability that a habit's active streak
 * will be broken within the next period (Phase 8.2).
 *
 * Responsibility: extract the seven [StreakBreakFeatures] from raw Room data, check data
 * sufficiency (including an active-streak guard), delegate inference to the injected
 * [HabitPredictor] (Strategy + Dependency Inversion), and map the raw probability to a
 * [StreakBreakRisk.Rating] tier.
 *
 * Only habits with an active streak ([currentStreak] ≥ 1) receive a meaningful prediction.
 * Habits with streak = 0 are returned with [StreakBreakRisk.hasSufficientData] = false so
 * the View layer can show a neutral / "no active streak" placeholder.
 *
 * Note on habit age: derived from the earliest [HabitCompletionEntity.progressUpdate],
 * same conservative approach as [AbandonmentRiskUseCase].
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class StreakBreakUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /** Minimum completions required before a prediction is meaningful. */
        private const val MIN_COMPLETIONS = 3

        /** Minimum age (days since first completion) for a reliable assessment. */
        private const val MIN_AGE_DAYS = 7

        /** Window (days) used to compute the average gap between recent completions. */
        private const val GAP_WINDOW_DAYS = 30L
    }

    /**
     * Computes a [StreakBreakRisk] for [habit] given its [completions] history.
     *
     * Algorithm:
     * 1. Guard: return LOW / `hasSufficientData = false` when the streak is inactive,
     *    completions are too few, or the habit is younger than [MIN_AGE_DAYS].
     * 2. Extract the seven [StreakBreakFeatures] from the completions list.
     * 3. Delegate inference to the injected [predictor] (TFLite or math fallback).
     * 4. Map the raw probability to a [StreakBreakRisk.Rating] via [StreakBreakRisk.ratingFor].
     *
     * @param habit          Domain model of the habit to evaluate.
     * @param completions    All historical completion records for this habit.
     * @param currentStreak  Pre-computed current streak (from [CalculateStreakUseCase]);
     *                       must be ≥ 1 for a meaningful prediction.
     * @param today          Reference date (defaults to system clock; injectable for testing).
     * @return [StreakBreakRisk] with probability, rating, and data-sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        today: LocalDate = LocalDate.now()
    ): StreakBreakRisk {
        // Guard: no active streak — prediction is not applicable
        if (currentStreak < 1) {
            return StreakBreakRisk(habit.id, 0f, StreakBreakRisk.Rating.LOW, false)
        }

        // Guard: insufficient history
        if (completions.size < MIN_COMPLETIONS) {
            return StreakBreakRisk(habit.id, 0f, StreakBreakRisk.Rating.LOW, false)
        }

        // Derive habit age from the earliest completion (conservative underestimate)
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val habitAge = ChronoUnit.DAYS.between(firstDate, today).toInt().coerceAtLeast(1)

        if (habitAge < MIN_AGE_DAYS) {
            return StreakBreakRisk(habit.id, 0f, StreakBreakRisk.Rating.LOW, false)
        }

        // Completion rate in the last 7 days (fraction of days with a target-reached record)
        val rate7d = rateInWindow(completions, today, days = 7)

        // Average gap (days) between consecutive target-reached dates in the last 30 days.
        // Falls back to a frequency-based default when fewer than 2 data points exist.
        val recentAvgGapDays = computeAvgGap(completions, today, habit.frequency)

        // Map HabitFrequency to the ordinal expected by the model (Daily=0, Weekly=1, ≥Monthly=2)
        val frequencyOrdinal = when (habit.frequency) {
            HabitFrequency.Daily  -> 0
            HabitFrequency.Weekly -> 1
            else                  -> 2
        }

        val features = StreakBreakFeatures(
            currentStreak = currentStreak,
            habitAge = habitAge,
            completionRateLast7Days = rate7d,
            dayOfWeek = today.dayOfWeek.value,   // 1=Mon..7=Sun, matches training generator
            hourOfDay = LocalTime.now().hour,
            recentAvgGapDays = recentAvgGapDays,
            frequencyOrdinal = frequencyOrdinal
        )

        val probability = predictor.predictStreakBreak(features)
        return StreakBreakRisk(
            habitId = habit.id,
            probability = probability,
            rating = StreakBreakRisk.ratingFor(probability),
            hasSufficientData = true
        )
    }

    /**
     * Returns the fraction of days within the last [days] calendar days on which
     * the habit target was reached (distinct dates, `isTargetReached = true`).
     * Result is in [0.0, 1.0].
     */
    private fun rateInWindow(
        completions: List<HabitCompletionEntity>,
        today: LocalDate,
        days: Int
    ): Float {
        val since = today.minusDays(days.toLong())
        val reachedDates = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() > since }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()
        return reachedDates.size.toFloat() / days
    }

    /**
     * Computes the mean calendar gap (in days) between consecutive target-reached dates
     * within the last [GAP_WINDOW_DAYS] days.
     *
     * When fewer than 2 such dates exist in the window, falls back to a frequency-based
     * default that matches the lognormal base gaps used during training data generation:
     * 1 day for Daily, 7 days for Weekly, 28 days for Monthly/Yearly.
     */
    private fun computeAvgGap(
        completions: List<HabitCompletionEntity>,
        today: LocalDate,
        frequency: HabitFrequency
    ): Float {
        val since = today.minusDays(GAP_WINDOW_DAYS)
        val reachedDates = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() > since }
            .map { it.progressUpdate.toLocalDate() }
            .toSortedSet()
            .toList()

        if (reachedDates.size < 2) {
            // Frequency-based default mirrors the lognormal base gap in the training generator
            return when (frequency) {
                HabitFrequency.Daily  -> 1f
                HabitFrequency.Weekly -> 7f
                else                  -> 28f
            }
        }

        val gaps = reachedDates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toFloat() }
        return gaps.average().toFloat()
    }
}
