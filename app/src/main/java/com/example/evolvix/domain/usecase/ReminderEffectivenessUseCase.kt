package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.ReminderLiftFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.ReminderLift
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Use Case / Interactor that decides whether sending a push reminder is worth it
 * for a given habit at a specific time slot (Phase 9.1).
 *
 * Responsibility: extract the eight [ReminderLiftFeatures] from raw Room data, call the
 * injected [HabitPredictor.predictReminderCompletion] **twice** (once with `reminderSent=0`,
 * once with `reminderSent=1`), compute predicted lift, and return a [ReminderLift]
 * wrapping the recommendation.
 *
 * Consumed by:
 * - [ScheduleReminderUseCase] — suppresses reminder enqueue when `!recommendSend`.
 * - `StatisticsScreen` "Smart Reminders" ElevatedCard — shows per-habit lift values.
 *
 * ⚠ **Thesis note:** The lift estimate is observational, not causal. Present it as
 * "predicted lift" in thesis documents, not "causal treatment effect."
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class ReminderEffectivenessUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /**
         * Minimum number of completions required before suppression is active.
         * Below this threshold [ReminderLift.hasSufficientData] is false and
         * reminders are always sent (safe-default for new habits).
         */
        const val MIN_COMPLETIONS = 5

        /**
         * Minimum predicted lift below which a reminder is suppressed.
         * If P(sent=1) − P(sent=0) < [SUPPRESS_THRESHOLD] the reminder is skipped.
         */
        const val SUPPRESS_THRESHOLD = 0.05f
    }

    /**
     * Computes a [ReminderLift] for [habit] at the given [reminderTime].
     *
     * Algorithm:
     * 1. Guard: fewer than [MIN_COMPLETIONS] → return with `hasSufficientData = false`
     *    (reminder will be sent unconditionally by [ScheduleReminderUseCase]).
     * 2. Build [ReminderLiftFeatures] with `reminderSent=0` and `reminderSent=1`.
     * 3. Call [predictor.predictReminderCompletion] twice to get baseline and with-reminder
     *    probabilities.
     * 4. Compute lift and decide [ReminderLift.recommendSend].
     *
     * @param habit         Domain model of the habit to evaluate.
     * @param completions   All historical completion records for this habit.
     * @param currentStreak Pre-computed current streak (from [CalculateStreakUseCase]).
     * @param reminderTime  The candidate reminder slot (defaults to now).
     * @param today         Reference date (injectable for testing).
     * @return [ReminderLift] with probabilities, lift, recommendation, and sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        reminderTime: LocalDateTime = LocalDateTime.now(),
        today: LocalDate = LocalDate.now()
    ): ReminderLift {
        // Guard: not enough history — safe default is to always send
        if (completions.size < MIN_COMPLETIONS) {
            return ReminderLift(
                habitId = habit.id,
                baselineProb = 0f,
                withReminderProb = 0f,
                lift = 0f,
                recommendSend = true,
                hasSufficientData = false
            )
        }

        val rate7d  = rateInWindow(completions, today, days = 7)
        val rate30d = rateInWindow(completions, today, days = 30)

        // Derive habit age from the earliest completion (conservative underestimate)
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val habitAge = java.time.temporal.ChronoUnit.DAYS
            .between(firstDate, today).toInt().coerceAtLeast(1)

        val frequencyOrdinal = when (habit.frequency) {
            HabitFrequency.Daily  -> 0
            HabitFrequency.Weekly -> 1
            else                  -> 2
        }

        // dayOfWeek: 0 = Monday … 6 = Sunday (matches Python training ordinal)
        val dayOfWeekOrdinal = reminderTime.dayOfWeek.value - 1

        val baseFeatures = ReminderLiftFeatures(
            habitAge               = habitAge,
            completionRateLast7Days  = rate7d,
            completionRateLast30Days = rate30d,
            currentStreak          = currentStreak,
            hourOfDay              = reminderTime.hour,
            dayOfWeekOrdinal       = dayOfWeekOrdinal,
            frequencyOrdinal       = frequencyOrdinal,
            reminderSent           = 0
        )

        val baselineProb     = predictor.predictReminderCompletion(baseFeatures)
        val withReminderProb = predictor.predictReminderCompletion(baseFeatures.copy(reminderSent = 1))
        val lift             = withReminderProb - baselineProb

        return ReminderLift(
            habitId          = habit.id,
            baselineProb     = baselineProb,
            withReminderProb = withReminderProb,
            lift             = lift,
            recommendSend    = lift >= SUPPRESS_THRESHOLD,
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
