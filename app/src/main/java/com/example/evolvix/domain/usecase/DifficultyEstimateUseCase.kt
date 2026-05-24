package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.DifficultyFeatures
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.PerceivedDifficultyEstimate
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that estimates the user's perceived difficulty for completing
 * a habit given the current context (Phase 9.4).
 *
 * Responsibility: derive the eight [DifficultyFeatures] from raw Room data, enforce
 * data-sufficiency guards, compute [recentAvgRated] from the user's own star ratings,
 * delegate regression inference to the injected [HabitPredictor] (Strategy + Dependency
 * Inversion), and wrap the raw float in a typed [PerceivedDifficultyEstimate].
 *
 * Feature derivation summary (order matches `perceived_difficulty_scaler.json`):
 * 1. **dayOfWeek**               — `LocalDate.now().dayOfWeek.value` (1=Mon, 7=Sun).
 * 2. **hourOfDay**               — `LocalTime.now().hour` (0–23).
 * 3. **currentStreak**           — pre-computed by the caller ([CalculateStreakUseCase]).
 * 4. **completionRateLast7Days** — fraction of calendar periods in the past 7 days
 *    where `isTargetReached = true`.
 * 5. **completionRateLast30Days**— same computation over past 30 days.
 * 6. **habitAgeDays**            — days since the earliest completion (conservative
 *    underestimate matching the training generator's behaviour).
 * 7. **targetCount**             — `habit.target`.
 * 8. **avgProgressRatio30d**     — mean(completions per calendar date / target) across
 *    all calendar dates in the 30-day window; values > 1.0 indicate over-completion.
 *
 * ⚠ **Observational caveat (thesis):** The model predicts *expected self-reported
 * difficulty* given the current habit state, not an objective task complexity measure.
 * Present as "predicted perceived difficulty" in all thesis documentation.
 *
 * @param predictor Strategy implementation of [HabitPredictor]; injectable so
 *                  [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 */
class DifficultyEstimateUseCase(
    private val predictor: HabitPredictor
) {
    companion object {
        /** Minimum total completions before an estimate is considered meaningful. */
        private const val MIN_COMPLETIONS = 10

        /**
         * Minimum number of user-provided star ratings (non-null
         * [HabitCompletionEntity.perceivedDifficulty] in the last 14 days) required
         * before [PerceivedDifficultyEstimate.recentAvgRated] is populated.
         * Below this threshold [recentAvgRated] is null (cold-start guard).
         */
        const val MIN_RATINGS = 5

        /** Look-back window for user-provided ratings. */
        private const val RATINGS_WINDOW_DAYS = 14L
    }

    /**
     * Computes a [PerceivedDifficultyEstimate] for [habit] given its [completions] history.
     *
     * @param habit         Domain model of the habit to evaluate.
     * @param completions   All historical completion records for this habit.
     * @param currentStreak Pre-computed current streak (from [CalculateStreakUseCase]).
     * @param today         Reference date; defaults to the system clock (injectable for tests).
     * @param now           Reference time; defaults to the system clock (injectable for tests).
     * @return [PerceivedDifficultyEstimate] with prediction, rating tier, user-sourced
     *         average, and data-sufficiency flag.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        today: LocalDate = LocalDate.now(),
        now: LocalTime = LocalTime.now()
    ): PerceivedDifficultyEstimate {
        if (completions.size < MIN_COMPLETIONS) {
            return PerceivedDifficultyEstimate(
                predicted = 3f,
                rounded = 3,
                rating = PerceivedDifficultyEstimate.Rating.MODERATE,
                recentAvgRated = null,
                hasSufficientData = false
            )
        }

        // ── Feature 6: habitAgeDays ──────────────────────────────────────────────────────
        val firstDate = completions.minOf { it.progressUpdate.toLocalDate() }
        val habitAgeDays = ChronoUnit.DAYS.between(firstDate, today).toInt().coerceAtLeast(1)

        // ── Features 4 & 5: completionRateLast7Days / completionRateLast30Days ────────────
        val since7d  = today.minusDays(7)
        val since30d = today.minusDays(30)

        val periodDays = habit.frequency.days.coerceAtLeast(1)
        val periods7   = (7  / periodDays).coerceAtLeast(1)
        val periods30  = (30 / periodDays).coerceAtLeast(1)

        val reachedDates7d = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since7d }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val completions30d = completions.filter { it.progressUpdate.toLocalDate() >= since30d }

        val reachedDates30d = completions30d
            .filter { it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val rate7d  = reachedDates7d.size.toFloat()  / periods7
        val rate30d = reachedDates30d.size.toFloat() / periods30

        // ── Feature 8: avgProgressRatio30d ───────────────────────────────────────────────
        // Mean(count of completion records per calendar date / target) over the 30-day
        // window. Values > 1.0 indicate the user frequently over-completes the target.
        val avgProgressRatio30d: Float = if (completions30d.isEmpty()) {
            rate30d
        } else {
            val countsByDate = completions30d.groupBy { it.progressUpdate.toLocalDate() }
            val ratioSum = countsByDate.values.sumOf { it.size.toDouble() / habit.target }
            (ratioSum / countsByDate.size).toFloat()
        }

        // ── Build feature vector ─────────────────────────────────────────────────────────
        val features = DifficultyFeatures(
            dayOfWeek               = today.dayOfWeek.value,
            hourOfDay               = now.hour,
            currentStreak           = currentStreak,
            completionRateLast7Days = rate7d.coerceIn(0f, 1f),
            completionRateLast30Days = rate30d.coerceIn(0f, 1f),
            habitAgeDays            = habitAgeDays,
            targetCount             = habit.target,
            avgProgressRatio30d     = avgProgressRatio30d.coerceIn(0f, 3f)
        )

        val predicted = predictor.predictPerceivedDifficulty(features)
        val rounded   = predicted.toInt().coerceIn(1, 5)

        // ── recentAvgRated: mean of user-provided star ratings in the last 14 days ──────
        val since14d = today.minusDays(RATINGS_WINDOW_DAYS)
        val recentRatings = completions
            .filter {
                it.perceivedDifficulty != null &&
                it.progressUpdate.toLocalDate() >= since14d
            }
            .mapNotNull { it.perceivedDifficulty }

        val recentAvgRated = if (recentRatings.size >= MIN_RATINGS) {
            recentRatings.average().toFloat()
        } else {
            null
        }

        return PerceivedDifficultyEstimate(
            predicted          = predicted,
            rounded            = rounded,
            rating             = PerceivedDifficultyEstimate.ratingFor(predicted),
            recentAvgRated     = recentAvgRated,
            hasSufficientData  = true
        )
    }
}
