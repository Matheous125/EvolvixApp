package com.example.evolvix.domain.model

/**
 * Output model of [RoutinePrecisionUseCase].
 *
 * Captures how consistent a user's completion timing is for a single habit, expressed
 * as the standard deviation of completion timestamps (in minutes from midnight).
 *
 * A lower [stddevMinutes] indicates a more disciplined, clock-like routine.
 * [rating] translates the raw value into a human-readable consistency label
 * suitable for display on the Statistics screen.
 *
 * @param stddevMinutes Standard deviation of completion times in minutes (≥ 0.0).
 * @param sampleCount   Number of completions used in the computation.
 * @param rating        Qualitative consistency label derived from [stddevMinutes].
 */
data class RoutinePrecision(
    val stddevMinutes: Double,
    val sampleCount: Int,
    val rating: Rating
) {
    /**
     * Qualitative consistency tiers keyed to stddev thresholds.
     *
     * Thresholds (in minutes):
     * - VERY_CONSISTENT : stddev < 30   (~±30 min window)
     * - CONSISTENT      : stddev < 60   (~±1 h window)
     * - VARIABLE        : stddev < 120  (~±2 h window)
     * - ERRATIC         : stddev ≥ 120
     */
    enum class Rating { VERY_CONSISTENT, CONSISTENT, VARIABLE, ERRATIC }
}
