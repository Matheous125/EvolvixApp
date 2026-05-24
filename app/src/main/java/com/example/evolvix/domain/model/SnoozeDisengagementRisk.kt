package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.SnoozeDisengagementUseCase] (Phase 9.2).
 *
 * Wraps the raw disengagement probability from
 * [com.example.evolvix.domain.ai.HabitPredictor.predictSnoozeDisengagement]
 * into a human-readable [Rating] tier and a data-sufficiency flag, so the View
 * layer never has to threshold a raw float directly.
 *
 * This is an **early-warning** signal (7-day horizon) complementing the broader
 * [AbandonmentRisk] (14-day horizon, Phase 8.1). It fires sooner and is driven
 * by snooze behaviour, giving the app a chance to intervene before the long-horizon
 * abandonment model triggers.
 *
 * ⚠ **Thesis note:** The risk estimate is observational, not causal. Snooze frequency
 * and abandonment may share common confounders (e.g. a busy week causes both).
 * Present this in thesis documents as "predicted disengagement risk given snooze pattern."
 *
 * @property habitId             Room primary key of the assessed habit.
 * @property probability         Raw model output in [0.0, 1.0]; probability that the
 *                               habit will receive zero completions in the next 7 days.
 * @property rating              Qualitative risk tier derived from [probability] via [ratingFor].
 * @property hasSufficientData   False when fewer than [com.example.evolvix.domain.usecase.SnoozeDisengagementUseCase.MIN_REMINDER_COMPLETIONS]
 *                               reminder-driven completions with non-null snoozeCount exist in
 *                               the past 30 days. The View shows a "not enough data" placeholder
 *                               instead of the probability when this is false.
 */
data class SnoozeDisengagementRisk(
    val habitId: Int,
    val probability: Float,
    val rating: Rating,
    val hasSufficientData: Boolean
) {
    /** Qualitative risk tier displayed in the "Snooze Drift" card on StatisticsScreen. */
    enum class Rating { LOW, MEDIUM, HIGH, CRITICAL }

    companion object {
        /**
         * Maps a raw probability to a [Rating] tier.
         *
         * Thresholds are symmetric with [AbandonmentRisk.ratingFor] and [StreakBreakRisk.ratingFor]
         * for UI consistency (same color scheme, same card layout across all risk cards):
         *  - CRITICAL ≥ 0.75: heavy snooze + low engagement — strong disengagement signal
         *  - HIGH     ≥ 0.50: moderate snooze drift — worth a proactive softer reminder
         *  - MEDIUM   ≥ 0.25: mild snooze pattern — monitor but no action yet
         *  - LOW      < 0.25: healthy snooze behaviour — user is engaged
         */
        fun ratingFor(p: Float): Rating = when {
            p >= 0.75f -> Rating.CRITICAL
            p >= 0.50f -> Rating.HIGH
            p >= 0.25f -> Rating.MEDIUM
            else       -> Rating.LOW
        }
    }
}
