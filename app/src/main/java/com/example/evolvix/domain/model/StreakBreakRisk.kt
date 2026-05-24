package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.StreakBreakUseCase] (Phase 8.2).
 *
 * Wraps the raw streak-break probability from [com.example.evolvix.domain.ai.HabitPredictor.predictStreakBreak]
 * into a human-readable [Rating] tier and a data-sufficiency flag, so the View
 * layer never has to threshold a raw float directly.
 *
 * @property habitId            Room primary key of the assessed habit.
 * @property probability        Raw model output in [0.0, 1.0]; probability that the
 *                              active streak will end within the next N periods
 *                              (N = 3 for daily habits, N = 2 for weekly).
 * @property rating             Qualitative risk tier derived from [probability] via [ratingFor].
 * @property hasSufficientData  False when the habit has no active streak, is too young
 *                              (< 3 completions), or lacks enough history for a reliable
 *                              assessment. The View should show a "not enough data"
 *                              placeholder instead of the probability bar when false.
 */
data class StreakBreakRisk(
    val habitId: Int,
    val probability: Float,
    val rating: Rating,
    val hasSufficientData: Boolean
) {
    /** Qualitative risk tier displayed in the streak risk card on StatisticsScreen. */
    enum class Rating { LOW, MEDIUM, HIGH, CRITICAL }

    companion object {
        /**
         * Maps a raw probability to a [Rating] tier.
         *
         * Thresholds mirror the logit priors in the training data and are symmetric
         * with [com.example.evolvix.domain.model.AbandonmentRisk.ratingFor] for UI
         * consistency (same color scheme, same card layout):
         *  - CRITICAL ≥ 0.75: strongly-at-risk signal (short streak + low rate)
         *  - HIGH     ≥ 0.50: moderate break risk
         *  - MEDIUM   ≥ 0.25: mild concern — worth a nudge reminder
         *  - LOW      < 0.25: streak is healthy
         */
        fun ratingFor(p: Float): Rating = when {
            p >= 0.75f -> Rating.CRITICAL
            p >= 0.50f -> Rating.HIGH
            p >= 0.25f -> Rating.MEDIUM
            else       -> Rating.LOW
        }
    }
}
