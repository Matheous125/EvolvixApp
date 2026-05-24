package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.SnoozeDisengagementFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SnoozeDisengagementRisk
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that assesses snooze-driven disengagement risk for a habit (Phase 9.2).
 *
 * Responsibility: derive the seven [SnoozeDisengagementFeatures] from raw Room data,
 * enforce a data-sufficiency guard specific to the snooze signal, delegate inference to the
 * injected [HabitPredictor] (Strategy + Dependency Inversion), and wrap the raw probability
 * in a [SnoozeDisengagementRisk] domain result.
 *
 * **Snooze metrics explained:**
 * - [SnoozeDisengagementFeatures.avgSnoozeCountLast14Days] — mean `snoozeCount` across
 *   reminder-driven completions (`fromReminder = true`, `snoozeCount != null`) in the past
 *   14 days. Only non-null rows contribute so the average ignores non-reminder sessions.
 * - [SnoozeDisengagementFeatures.snoozeFrequencyLast14Days] — fraction of those same rows
 *   where `snoozeCount ≥ 1` (i.e., the user snoozed at least once before completing).
 *
 * **Data sufficiency guard:** a prediction is only meaningful once at least
 * [MIN_REMINDER_COMPLETIONS] reminder-driven completions with a non-null `snoozeCount`
 * are recorded in the past 30 days. Until then the result carries `hasSufficientData = false`
 * and the UI shows a "not enough data" placeholder instead of a potentially misleading score.
 *
 * ⚠ **Thesis note (causality caveat):** The snooze signal is purely observational.
 * A user who consistently snoozes and still completes their habit every day may not be at
 * risk at all — the model learns this nuance from the training distribution, but the math
 * fallback rule chain in [com.example.evolvix.domain.ai.MathHabitPredictor] also incorporates
 * the completion-rate signal as a moderating factor.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [com.example.evolvix.domain.ai.MathHabitPredictor] and
 *                  [com.example.evolvix.domain.ai.TfliteHabitPredictor] are interchangeable.
 */
class SnoozeDisengagementUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /**
         * Minimum number of reminder-driven completions (with non-null `snoozeCount`)
         * required in the past 30 days before a snooze-based prediction is meaningful.
         *
         * Rationale: `snoozeCount` is a new column (Phase 9.2); existing data will have
         * `null` for older rows. This guard prevents noisy early predictions.
         */
        private const val MIN_REMINDER_COMPLETIONS = 5

        /** Minimum overall habit age before any prediction is shown. */
        private const val MIN_AGE_DAYS = 7
    }

    /**
     * Computes a [SnoozeDisengagementRisk] for [habit] given its [completions] history.
     *
     * Algorithm:
     * 1. Guard: return `hasSufficientData = false` when overall data is insufficient.
     * 2. Count reminder-driven completions with non-null `snoozeCount` in the past 30 days.
     *    If fewer than [MIN_REMINDER_COMPLETIONS], return `hasSufficientData = false`.
     * 3. Compute `avgSnoozeCountLast14Days` and `snoozeFrequencyLast14Days` from the
     *    past-14-day reminder-driven subset.
     * 4. Assemble [SnoozeDisengagementFeatures] and delegate to [predictor].
     * 5. Map probability → [SnoozeDisengagementRisk.Rating] via [SnoozeDisengagementRisk.ratingFor].
     *
     * @param habit         Domain model of the habit to evaluate.
     * @param completions   All historical completion records for this habit.
     * @param currentStreak Pre-computed current streak (from [CalculateStreakUseCase]).
     * @param today         Reference date (defaults to system clock; injectable for testing).
     * @return [SnoozeDisengagementRisk] with probability, rating, and sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        today: LocalDate = LocalDate.now()
    ): SnoozeDisengagementRisk {
        // Guard: no completions at all — cannot derive habit age
        if (completions.isEmpty()) {
            return SnoozeDisengagementRisk(habit.id, 0f, SnoozeDisengagementRisk.Rating.LOW, false)
        }

        // Derive habit age from the earliest completion (conservative underestimate)
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val habitAge = ChronoUnit.DAYS.between(firstDate, today).toInt().coerceAtLeast(1)

        if (habitAge < MIN_AGE_DAYS) {
            return SnoozeDisengagementRisk(habit.id, 0f, SnoozeDisengagementRisk.Rating.LOW, false)
        }

        // Reminder-driven completions with a recorded snoozeCount in the past 30 days
        val since30d = today.minusDays(30)
        val reminderCompletions30d = completions.filter { c ->
            c.fromReminder && c.snoozeCount != null &&
                c.progressUpdate.toLocalDate() > since30d
        }

        if (reminderCompletions30d.size < MIN_REMINDER_COMPLETIONS) {
            return SnoozeDisengagementRisk(habit.id, 0f, SnoozeDisengagementRisk.Rating.LOW, false)
        }

        // Subset: reminder completions in the past 14 days for snooze metric computation
        val since14d = today.minusDays(14)
        val reminderCompletions14d = reminderCompletions30d.filter { c ->
            c.progressUpdate.toLocalDate() > since14d
        }

        // avgSnoozeCountLast14Days: mean snoozeCount across the 14-day reminder subset.
        // Falls back to 0.0 when there are no reminder completions in this shorter window.
        val avgSnooze = if (reminderCompletions14d.isEmpty()) {
            0f
        } else {
            reminderCompletions14d.sumOf { it.snoozeCount!!.toDouble() }.toFloat() /
                reminderCompletions14d.size
        }

        // snoozeFrequencyLast14Days: fraction of 14-day reminder completions where snooze ≥ 1.
        val snoozeFreq = if (reminderCompletions14d.isEmpty()) {
            0f
        } else {
            reminderCompletions14d.count { it.snoozeCount!! >= 1 }.toFloat() /
                reminderCompletions14d.size
        }

        val rate7d  = rateInWindow(completions, today, days = 7)
        val rate30d = rateInWindow(completions, today, days = 30)

        // Map HabitFrequency to the ordinal used by the training pipeline
        val frequencyOrdinal = when (habit.frequency) {
            HabitFrequency.Daily  -> 0
            HabitFrequency.Weekly -> 1
            else                  -> 2
        }

        val features = SnoozeDisengagementFeatures(
            habitAge = habitAge,
            completionRateLast7Days = rate7d,
            completionRateLast30Days = rate30d,
            currentStreak = currentStreak,
            avgSnoozeCountLast14Days = avgSnooze,
            snoozeFrequencyLast14Days = snoozeFreq,
            frequencyOrdinal = frequencyOrdinal
        )

        val probability = predictor.predictSnoozeDisengagement(features)
        return SnoozeDisengagementRisk(
            habitId = habit.id,
            probability = probability,
            rating = SnoozeDisengagementRisk.ratingFor(probability),
            hasSufficientData = true
        )
    }

    /**
     * Returns the fraction of days within the last [days] calendar days on which
     * the habit target was reached (distinct reached dates, `isTargetReached = true`).
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
