package com.example.evolvix.domain.model

/**
 * Output model of [ProcrastinationIndexUseCase].
 *
 * Captures whether a user tends to complete a habit early or late within each period,
 * expressed as the skewness of completion hour-of-day values.
 *
 * - **Positive skew** → completions cluster toward the end of the day (procrastination).
 * - **Negative skew** → completions are front-loaded (early completer).
 * - **Near zero**     → completions are evenly distributed throughout the day.
 *
 * [rating] translates the raw skewness into a human-readable tendency label
 * suitable for display on the Statistics screen.
 *
 * @param skewness    Moment-based skewness of completion hour-of-day distribution.
 * @param sampleCount Number of completions used in the computation.
 * @param rating      Qualitative tendency label derived from [skewness].
 */
data class ProcrastinationIndex(
    val skewness: Double,
    val sampleCount: Int,
    val rating: Rating
) {
    /**
     * Qualitative tendency tiers keyed to skewness thresholds.
     *
     * Standard third-moment skewness produces **negative** values when completions
     * cluster at high hours (late = procrastination), because the few early outliers
     * form a long left tail. Conversely, mostly-early completions produce positive
     * skewness (long right tail of late outliers).
     *
     * Thresholds:
     * - PROCRASTINATOR      : skewness ≤ −1.0  (strong late-day clustering)
     * - MILD_PROCRASTINATOR : skewness in (−1.0, −0.5]  (slight late-day bias)
     * - BALANCED            : skewness in (−0.5, 0.5)  (no dominant pattern)
     * - EARLY_COMPLETER     : skewness ≥ 0.5  (strong front-loading)
     */
    enum class Rating { PROCRASTINATOR, MILD_PROCRASTINATOR, BALANCED, EARLY_COMPLETER }
}
