package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.WeeklyForecastUseCase] (Phase 8.3).
 *
 * Wraps the raw regression output from
 * [com.example.evolvix.domain.ai.HabitPredictor.predictWeeklyRate] into a
 * human-readable [Direction] indicator and a data-sufficiency flag, so the View
 * layer never has to compare raw floats directly.
 *
 * This is a **user-level** (not per-habit) prediction: [predictedRate] represents the
 * expected overall completion rate across all active habits for the next 7 days.
 *
 * @property predictedRate   Model output ∈ [0.0, 1.0]; predicted overall completion
 *                           rate for the next 7 days.
 * @property lastWeekRate    Actual completion rate from the trailing 7 days; used by
 *                           the View to render the trend delta alongside the forecast.
 * @property direction       Qualitative trend: UP / FLAT / DOWN based on the delta
 *                           between [predictedRate] and [lastWeekRate] (±5 % dead zone).
 * @property confidence      Data-volume proxy in [0.0, 1.0]. Derived by
 *                           [WeeklyForecastUseCase] as `min(daysOfHistory / 28, 1.0)`.
 *                           Low confidence = the user has fewer than 4 weeks of data;
 *                           the View should soften the forecast copy accordingly.
 * @property hasSufficientData  False when there are fewer than 2 active habits or the
 *                              user has fewer than 7 days of completion history. The View
 *                              shows a "not enough data" placeholder when false.
 */
data class WeeklyForecast(
    val predictedRate: Float,
    val lastWeekRate: Float,
    val direction: Direction,
    val confidence: Float,
    val hasSufficientData: Boolean
) {
    /** Qualitative trend direction shown in the forecast strip on StatisticsScreen. */
    enum class Direction { UP, FLAT, DOWN }

    companion object {
        /** Dead zone: deltas within ±[FLAT_THRESHOLD] are reported as [Direction.FLAT]. */
        private const val FLAT_THRESHOLD = 0.05f

        /**
         * Derives the [Direction] from the delta between predicted and last-week rates.
         * Applied by [com.example.evolvix.domain.usecase.WeeklyForecastUseCase] so the
         * View never thresholds raw floats.
         */
        fun directionFor(predicted: Float, lastWeek: Float): Direction {
            val delta = predicted - lastWeek
            return when {
                delta > FLAT_THRESHOLD  -> Direction.UP
                delta < -FLAT_THRESHOLD -> Direction.DOWN
                else                    -> Direction.FLAT
            }
        }
    }
}
