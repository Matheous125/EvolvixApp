package com.example.evolvix.domain.model

/**
 * Output of [AbandonmentRiskUseCase] (Phase 8.1).
 *
 * Wraps the raw abandonment probability from [HabitPredictor.predictAbandonment]
 * into a human-readable [Rating] tier and a data-sufficiency flag, so the View
 * layer never has to threshold a raw float directly.
 *
 * @property habitId             Room primary key of the assessed habit.
 * @property probability         Raw model output in [0.0, 1.0]; probability that
 *                               the habit will receive zero completions in the next 14 days.
 * @property rating              Qualitative risk tier derived from [probability] via [ratingFor].
 * @property hasSufficientData   False when the habit is too young (< 7 days) or has
 *                               too few completions (< 3) for a reliable assessment.
 *                               The View should show a "not enough data" placeholder
 *                               instead of the probability when this is false.
 */
data class AbandonmentRisk(
    val habitId: Int,
    val probability: Float,
    val rating: Rating,
    val hasSufficientData: Boolean
) {
    /** Qualitative risk tier displayed in the "At Risk" card on StatisticsScreen. */
    enum class Rating { LOW, MEDIUM, HIGH, CRITICAL }

    companion object {
        /**
         * Maps a raw probability to a [Rating] tier.
         *
         * Thresholds chosen to match the logit priors in the training data:
         *  - CRITICAL ≥ 0.75: strongly-abandoned prior (gap ≥ 7 AND rate < 0.2)
         *  - HIGH     ≥ 0.50: moderate abandonment signal
         *  - MEDIUM   ≥ 0.25: mild concern
         *  - LOW      < 0.25: user is actively engaging
         */
        fun ratingFor(p: Float): Rating = when {
            p >= 0.75f -> Rating.CRITICAL
            p >= 0.50f -> Rating.HIGH
            p >= 0.25f -> Rating.MEDIUM
            else       -> Rating.LOW
        }
    }
}
