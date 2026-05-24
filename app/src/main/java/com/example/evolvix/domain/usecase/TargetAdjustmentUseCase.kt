package com.example.evolvix.domain.usecase

import com.example.evolvix.data.local.TargetHistoryDao
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.TargetChangeFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.TargetAdjustment
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that recommends an optimal target delta for a habit (Phase 9.3).
 *
 * Responsibility: derive the eight [TargetChangeFeatures] from raw Room data, enforce
 * a data-sufficiency guard, delegate inference to the injected [HabitPredictor]
 * (Strategy + Dependency Inversion), and wrap the raw regression output in a typed
 * [TargetAdjustment] domain result.
 *
 * Feature derivation summary:
 * 1. **currentTarget** — `habit.target` (passed in directly).
 * 2. **rate30d** — fraction of calendar periods in the past 30 days where the target
 *    was reached (`isTargetReached = true`). Denominator is `30 / periodDays`.
 * 3. **rate7d** — same computation over the past 7 days.
 * 4. **avgProgressRatio30d** — mean(completions per period / target) over 30 days;
 *    values > 1.0 indicate consistent over-completion. Serves as a proxy for
 *    `perceivedDifficulty` until Phase 9.4 adds that column to the completion entity.
 * 5. **currentStreak** — pre-computed by the caller ([CalculateStreakUseCase]).
 * 6. **habitAgeDays** — days since the earliest completion (conservative underestimate).
 * 7. **previousDelta** — `newTarget − oldTarget` from the most recent entry in
 *    `habit_target_history`; 0 if the target has never been changed.
 * 8. **periodsSinceLastChange** — calendar periods since `changedAt` on the latest
 *    history entry; sentinel 999 when the target has never changed.
 *
 * ⚠ **Thesis note — observational model:** [TargetAdjustment] is a recommendation
 * based on correlational patterns in historical data. It must be framed as
 * "what target tends to sustain high performance given the current state" rather
 * than a causal effect estimate.
 *
 * @param predictor       Strategy implementation of [HabitPredictor]; injectable so
 *                        [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 * @param targetHistoryDao DAO for reading the target-change audit log.
 */
class TargetAdjustmentUseCase(
    private val predictor: HabitPredictor,
    private val targetHistoryDao: TargetHistoryDao
) {
    companion object {
        /**
         * Minimum number of completion records required before a recommendation is
         * meaningful. Prevents premature advice on brand-new habits.
         */
        private const val MIN_COMPLETIONS = 5

        /** Sentinel value for [TargetChangeFeatures.periodsSinceLastChange] when the
         *  target has never been changed. Matches the Python training generator. */
        private const val NEVER_CHANGED_SENTINEL = 999
    }

    /**
     * Computes a [TargetAdjustment] for [habit] given its [completions] history.
     *
     * Suspends to read the latest target-change record from [TargetHistoryDao].
     *
     * @param habit         Domain model of the habit to evaluate.
     * @param completions   All historical completion records for this habit.
     * @param currentStreak Pre-computed current streak (from [CalculateStreakUseCase]).
     * @param today         Reference date; defaults to the system clock (injectable for tests).
     * @return [TargetAdjustment] with rounded delta, suggested target, confidence, and
     *         a data-sufficiency flag.
     */
    suspend operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        today: LocalDate = LocalDate.now()
    ): TargetAdjustment {
        if (completions.size < MIN_COMPLETIONS) {
            return TargetAdjustment.insufficientData(habit.target)
        }

        // ── Feature 6: habitAgeDays (conservative underestimate from first completion) ──
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val habitAgeDays = ChronoUnit.DAYS.between(firstDate, today).toInt().coerceAtLeast(1)

        // ── Features 2 & 3: rate30d / rate7d ────────────────────────────────────────────
        val since30d = today.minusDays(30)
        val since7d  = today.minusDays(7)

        val completions30d = completions.filter {
            it.progressUpdate.toLocalDate() >= since30d
        }
        val completions7d  = completions.filter {
            it.progressUpdate.toLocalDate() >= since7d
        }

        val reachedDates30d = completions30d.filter { it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }.toSet()
        val reachedDates7d  = completions7d.filter { it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }.toSet()

        val periodDays = habit.frequency.days.coerceAtLeast(1)
        val periods30  = (30 / periodDays).coerceAtLeast(1)
        val periods7   = (7  / periodDays).coerceAtLeast(1)

        val rate30d = reachedDates30d.size.toFloat() / periods30
        val rate7d  = reachedDates7d.size.toFloat()  / periods7

        // ── Feature 4: avgProgressRatio30d ───────────────────────────────────────────────
        // Group all completions in the 30-day window by calendar date; count updates per
        // date and divide by target. Mean across all dates gives a ratio proxy for
        // perceived effort (> 1.0 = user is over-completing; < 1.0 = under-completing).
        val avgProgressRatio30d: Float = if (completions30d.isEmpty()) {
            rate30d   // fallback: treat rate as proxy when no completions in window
        } else {
            val countsByDate = completions30d.groupBy { it.progressUpdate.toLocalDate() }
            val ratioSum = countsByDate.values.sumOf { it.size.toDouble() / habit.target }
            (ratioSum / countsByDate.size).toFloat()
        }

        // ── Features 7 & 8: previousDelta / periodsSinceLastChange ──────────────────────
        val latestHistory = targetHistoryDao.getLatestForHabit(habit.id)
        val previousDelta: Int
        val periodsSinceLastChange: Int
        if (latestHistory == null) {
            previousDelta = 0
            periodsSinceLastChange = NEVER_CHANGED_SENTINEL
        } else {
            previousDelta = latestHistory.newTarget - latestHistory.oldTarget
            val daysSinceChange = ChronoUnit.DAYS.between(
                latestHistory.changedAt.toLocalDate(), today
            ).toInt().coerceAtLeast(0)
            periodsSinceLastChange = (daysSinceChange / periodDays).coerceAtLeast(0)
        }

        // ── Assemble feature vector and run the model ───────────────────────────────────
        val features = TargetChangeFeatures(
            currentTarget           = habit.target,
            rate30d                 = rate30d.coerceIn(0f, 1f),
            rate7d                  = rate7d.coerceIn(0f, 1f),
            avgProgressRatio30d     = avgProgressRatio30d.coerceAtLeast(0f),
            currentStreak           = currentStreak,
            habitAgeDays            = habitAgeDays,
            previousDelta           = previousDelta,
            periodsSinceLastChange  = periodsSinceLastChange
        )

        val rawDelta = predictor.predictTargetDelta(features)

        // Round to nearest integer in {-2, -1, 0, +1, +2}, then clamp
        val delta = rawDelta.toInt().let {
            if (rawDelta - it >= 0.5f) it + 1 else it
        }.coerceIn(-2, 2)

        val suggestedTarget = (habit.target + delta).coerceAtLeast(1)
        val confidence = TargetAdjustment.confidenceFrom(rawDelta, delta)

        return TargetAdjustment(
            delta               = delta,
            rawDelta            = rawDelta,
            currentTarget       = habit.target,
            suggestedTarget     = suggestedTarget,
            confidence          = confidence,
            hasSufficientData   = true
        )
    }
}
