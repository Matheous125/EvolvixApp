package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.DifficultyEstimateUseCase] (Phase 9.4).
 *
 * Wraps the raw regression output from
 * [com.example.evolvix.domain.ai.HabitPredictor.predictPerceivedDifficulty]
 * into a rounded integer rating, a qualitative [Rating] tier, and optional
 * user-sourced average — so the View layer never interprets raw floats directly.
 *
 * ⚠ OBSERVATIONAL CAVEAT (thesis): [predicted] reflects expected user-reported
 * difficulty given the current habit state, not an objective task difficulty measure.
 * Present in the thesis as "predicted perceived difficulty" accordingly.
 *
 * @property predicted          Raw regressor output in [1.0, 5.0].
 * @property rounded            [predicted] rounded to the nearest integer (1–5);
 *                              the value shown on screen (e.g. filled stars).
 * @property rating             Qualitative tier derived via [ratingFor]; drives colour
 *                              coding and contextual wording in the UI.
 * @property recentAvgRated     Mean of the user's own star ratings over the last 14 days
 *                              ([HabitCompletionEntity.perceivedDifficulty] values).
 *                              null when fewer than [DifficultyEstimateUseCase.MIN_RATINGS]
 *                              rated completions exist (cold-start guard).
 * @property hasSufficientData  False when the habit has fewer than 10 total completions;
 *                              the StatisticsScreen card shows a placeholder instead of the
 *                              estimate, matching the pattern used by [AbandonmentRisk].
 */
data class PerceivedDifficultyEstimate(
    val predicted: Float,
    val rounded: Int,
    val rating: Rating,
    val recentAvgRated: Float?,
    val hasSufficientData: Boolean
) {
    /**
     * Qualitative difficulty tier displayed in the "Perceived Difficulty" card
     * on [com.example.evolvix.ui.screens.StatisticsScreen].
     *
     * Maps the 1–5 continuous scale to four named tiers:
     *  - EASY      [1.0, 2.0) — habit feels routine; consider raising the target.
     *  - MODERATE  [2.0, 3.5) — healthy challenge level.
     *  - HARD      [3.5, 4.5) — struggling; watch for abandonment signals.
     *  - VERY_HARD [4.5, 5.0] — high burnout risk; prompt target reduction nudge.
     */
    enum class Rating { EASY, MODERATE, HARD, VERY_HARD }

    companion object {
        /**
         * Maps a raw predicted value in [1.0, 5.0] to a [Rating] tier.
         *
         * Thresholds align with the cluster priors in [generate_difficulty_data.py]:
         * "thriving" group clusters near 1.0–2.0; "struggling" group near 4.5–5.0.
         */
        fun ratingFor(x: Float): Rating = when {
            x >= 4.5f -> Rating.VERY_HARD
            x >= 3.5f -> Rating.HARD
            x >= 2.0f -> Rating.MODERATE
            else      -> Rating.EASY
        }
    }
}
