package com.example.evolvix.domain.model

/**
 * Output model of [ResilienceScoreUseCase].
 *
 * Measures how quickly a user bounces back after missing a habit period.
 * [avgMissedPeriods] is the average number of consecutive missed periods observed
 * between two "target reached" periods — lower means faster recovery.
 *
 * [rating] translates the raw value into a human-readable resilience label
 * suitable for display on the Statistics screen.
 *
 * @param avgMissedPeriods Average count of missed periods per recovery event (≥ 0.0).
 * @param recoveryEventCount Number of distinct recovery events observed.
 * @param rating             Qualitative resilience label derived from [avgMissedPeriods].
 */
data class ResilienceScore(
    val avgMissedPeriods: Double,
    val recoveryEventCount: Int,
    val rating: Rating
) {
    /**
     * Qualitative resilience tiers keyed to average missed-periods thresholds.
     *
     * Thresholds (in missed periods):
     * - EXCELLENT : avgMissedPeriods < 1.5  (resumes almost immediately)
     * - GOOD      : avgMissedPeriods < 3.0  (recovers within a few periods)
     * - MODERATE  : avgMissedPeriods < 7.0  (takes about a week to bounce back)
     * - LOW       : avgMissedPeriods ≥ 7.0  (prolonged gaps before resuming)
     */
    enum class Rating { EXCELLENT, GOOD, MODERATE, LOW }
}
