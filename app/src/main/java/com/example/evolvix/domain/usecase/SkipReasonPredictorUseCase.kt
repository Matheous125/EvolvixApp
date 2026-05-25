package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.SkipReasonFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SkipReasonPrediction
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that predicts the most likely skip reason for a habit given
 * the current behavioral context (Phase 9.5).
 *
 * Responsibility: derive the eight [SkipReasonFeatures] from raw Room data, enforce
 * data-sufficiency guards, delegate 6-class softmax inference to the injected
 * [HabitPredictor] (Strategy + Dependency Inversion), and wrap the raw distribution
 * in a typed [SkipReasonPrediction] via [SkipReasonPrediction.fromSoftmax].
 *
 * Feature derivation summary (order matches `skip_reason_scaler.json` → `feature_columns`):
 * 1. **habitAge**                — days since first completion (≥ 1).
 * 2. **completionRateLast7Days** — target-reached dates in past 7 days / expected periods.
 * 3. **completionRateLast30Days**— same over past 30 days.
 * 4. **currentStreak**           — pre-computed by the caller ([CalculateStreakUseCase]).
 * 5. **dayOfWeek**               — ISO 8601: 1 = Monday … 7 = Sunday.
 * 6. **hourOfDay**               — device local hour at the moment of evaluation (0–23).
 * 7. **frequencyOrdinal**        — 0 = DAILY, 1 = WEEKLY, 2 = MONTHLY/YEARLY.
 * 8. **recentSkipRate14d**       — skip records in past 14 days / expected opportunities.
 *
 * ⚠ **Observational caveat (thesis):** Features 5 and 6 capture *when* the prediction
 * is made, not why the user intends to skip. All outputs are *predicted associations*
 * from behavioral context, not causal diagnoses. Present as "predicted skip reason
 * given current context" in all thesis documentation.
 *
 * ⚠ **Noise-class caveat:** [SkipReason.SICK] and [SkipReason.TRAVELING] have low
 * per-class F1 by design (illness and travel are behaviorally unpredictable). A flat
 * or low-confidence output for those classes is correct model behaviour, not a bug.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class SkipReasonPredictorUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /**
         * Minimum number of skip records before the prediction is flagged as
         * data-sufficient. Below this threshold [SkipReasonPrediction.hasSufficientData]
         * is false and the View should show all reason chips without pre-selection.
         */
        const val MIN_SKIPS = 3

        /** Look-back window for [SkipReasonFeatures.recentSkipRate14d] derivation. */
        private const val SKIP_RATE_WINDOW_DAYS = 14L
    }

    /**
     * Computes a [SkipReasonPrediction] for [habit] given its completions and skip history.
     *
     * @param habit         Domain model of the habit to evaluate.
     * @param completions   All historical completion records for this habit.
     * @param recentSkips   Skip records returned by [HabitSkipDao.getRecentForHabit] for
     *                      the past [SKIP_RATE_WINDOW_DAYS] days (or longer; older records
     *                      are filtered internally).
     * @param currentStreak Pre-computed current streak (from [CalculateStreakUseCase]).
     * @param today         Reference date; defaults to the system clock (injectable for tests).
     * @param now           Reference time; defaults to the system clock (injectable for tests).
     * @return [SkipReasonPrediction] with full distribution, top reason, confidence,
     *         and data-sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        recentSkips: List<HabitSkipEntity>,
        currentStreak: Int,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): SkipReasonPrediction {
        val hasSufficientData = recentSkips.size >= MIN_SKIPS

        // ── Feature 1: habitAge ──────────────────────────────────────────────
        val habitAge = if (completions.isEmpty()) 1
        else ChronoUnit.DAYS
            .between(completions.minOf { it.progressUpdate.toLocalDate() }, today)
            .toInt().coerceAtLeast(1)

        // ── Features 2 & 3: completionRateLast7Days / completionRateLast30Days ──
        val periodDays = habit.frequency.days.coerceAtLeast(1)
        val periods7   = (7  / periodDays).coerceAtLeast(1)
        val periods30  = (30 / periodDays).coerceAtLeast(1)

        val since7d  = today.minusDays(7)
        val since30d = today.minusDays(30)

        val reachedDates7d = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since7d }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()
        val reachedDates30d = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since30d }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val rate7d  = (reachedDates7d.size.toFloat()  / periods7).coerceIn(0f, 1f)
        val rate30d = (reachedDates30d.size.toFloat() / periods30).coerceIn(0f, 1f)

        // ── Feature 5: dayOfWeek (ISO 8601: 1=Mon … 7=Sun) ──────────────────
        val dayOfWeek = today.dayOfWeek.value

        // ── Feature 6: hourOfDay ─────────────────────────────────────────────
        val hourOfDay = now.hour

        // ── Feature 7: frequencyOrdinal (0=DAILY, 1=WEEKLY, 2=MONTHLY/YEARLY) ──
        val frequencyOrdinal = when (habit.frequency) {
            HabitFrequency.Daily  -> 0
            HabitFrequency.Weekly -> 1
            else                  -> 2  // Monthly and Yearly both map to ordinal 2
        }

        // ── Feature 8: recentSkipRate14d ────────────────────────────────────
        val since14d = today.minusDays(SKIP_RATE_WINDOW_DAYS)
        val skipsInWindow = recentSkips.count { it.skippedAt.toLocalDate() >= since14d }
        val opportunities14d = (14 / periodDays).coerceAtLeast(1)
        val recentSkipRate14d = (skipsInWindow.toFloat() / opportunities14d).coerceIn(0f, 1f)

        val features = SkipReasonFeatures(
            habitAge = habitAge,
            completionRateLast7Days = rate7d,
            completionRateLast30Days = rate30d,
            currentStreak = currentStreak,
            dayOfWeek = dayOfWeek,
            hourOfDay = hourOfDay,
            frequencyOrdinal = frequencyOrdinal,
            recentSkipRate14d = recentSkipRate14d
        )

        // Delegate inference to the injected predictor (TFLite or math fallback).
        val distribution = predictor.predictSkipReason(features)

        // Convert map → ordered FloatArray matching SkipReason.entries declaration order.
        val values = FloatArray(SkipReason.entries.size) { i ->
            distribution[SkipReason.entries[i]] ?: 0f
        }

        return SkipReasonPrediction.fromSoftmax(
            habitId = habit.id,
            values = values,
            hasSufficientData = hasSufficientData
        )
    }
}
