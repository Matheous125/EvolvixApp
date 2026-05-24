package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.AbandonmentFeatures
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.AbandonmentRisk
import com.example.evolvix.domain.model.HabitData
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that assesses the risk of a habit being abandoned (Phase 8.1).
 *
 * Responsibility: extract the seven [AbandonmentFeatures] from raw Room data, check data
 * sufficiency, delegate inference to the injected [HabitPredictor] (Strategy + Dependency
 * Inversion pattern), and map the raw probability to a [AbandonmentRisk.Rating] tier.
 *
 * The "At Risk" ElevatedCard in StatisticsScreen consumes the result:
 * habits with [AbandonmentRisk.Rating.HIGH] or [AbandonmentRisk.Rating.CRITICAL] are listed
 * with their probability so the user can take corrective action before the streak is lost.
 *
 * Note on habit age: [HabitData] and [HabitEntity] carry no explicit `createdAt` timestamp.
 * Habit age is therefore derived from the earliest [HabitCompletionEntity.progressUpdate]
 * in the [completions] list. This is a conservative underestimate (habits with no
 * completions at all are caught by the `< 3 completions` sufficiency guard first).
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class AbandonmentRiskUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /** Minimum completions required before a prediction is meaningful. */
        private const val MIN_COMPLETIONS = 3

        /** Minimum age (days since first completion) for a reliable assessment. */
        private const val MIN_AGE_DAYS = 7

        /** Cap applied to daysSinceLastCompletion to match the training data generator. */
        private const val MAX_DAYS_SINCE_LAST = 30
    }

    /**
     * Computes an [AbandonmentRisk] for [habit] given its [completions] history.
     *
     * Algorithm:
     * 1. Guard: return a safe LOW / `hasSufficientData = false` result when there are
     *    fewer than [MIN_COMPLETIONS] records or the habit is younger than [MIN_AGE_DAYS].
     * 2. Extract the seven [AbandonmentFeatures] from the completions list.
     * 3. Delegate inference to the injected [predictor] (TFLite or math fallback).
     * 4. Map the raw probability to a [AbandonmentRisk.Rating] via [AbandonmentRisk.ratingFor].
     *
     * @param habit          Domain model of the habit to evaluate.
     * @param completions    All historical completion records for this habit.
     * @param currentStreak  Pre-computed current streak (from [CalculateStreakUseCase]);
     *                       accepted as a parameter to avoid duplicating streak logic.
     * @param today          Reference date (defaults to system clock; injectable for testing).
     * @return [AbandonmentRisk] with probability, rating, and data-sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        today: LocalDate = LocalDate.now()
    ): AbandonmentRisk {
        // Guard: insufficient data — return safe placeholder
        if (completions.size < MIN_COMPLETIONS) {
            return AbandonmentRisk(habit.id, 0f, AbandonmentRisk.Rating.LOW, false)
        }

        // Derive habit age from the earliest completion (conservative underestimate)
        val firstDate = completions
            .minOf { it.progressUpdate.toLocalDate() }
        val habitAge = ChronoUnit.DAYS.between(firstDate, today).toInt().coerceAtLeast(1)

        if (habitAge < MIN_AGE_DAYS) {
            return AbandonmentRisk(habit.id, 0f, AbandonmentRisk.Rating.LOW, false)
        }

        // Days since the most recent completion (capped to match training distribution)
        val lastDate = completions.maxOf { it.progressUpdate.toLocalDate() }
        val daysSinceLast = ChronoUnit.DAYS.between(lastDate, today)
            .toInt().coerceIn(0, MAX_DAYS_SINCE_LAST)

        // Completion rates: count distinct reached-dates within each window
        val rate7d = rateInWindow(completions, today, days = 7)
        val rate30d = rateInWindow(completions, today, days = 30)

        // Map HabitFrequency to the ordinal expected by the model (Daily=0, Weekly=1, ≥Monthly=2)
        val frequencyOrdinal = when (habit.frequency) {
            HabitFrequency.Daily  -> 0
            HabitFrequency.Weekly -> 1
            else                  -> 2  // Monthly / Yearly treated as "low frequency" tier
        }

        val features = AbandonmentFeatures(
            habitAge = habitAge,
            daysSinceLastCompletion = daysSinceLast,
            completionRateLast7Days = rate7d,
            completionRateLast30Days = rate30d,
            currentStreak = currentStreak,
            totalCompletions = completions.size,
            frequencyOrdinal = frequencyOrdinal
        )

        val probability = predictor.predictAbandonment(features)
        return AbandonmentRisk(
            habitId = habit.id,
            probability = probability,
            rating = AbandonmentRisk.ratingFor(probability),
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
}
