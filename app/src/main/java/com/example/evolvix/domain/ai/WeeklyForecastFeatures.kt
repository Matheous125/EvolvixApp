package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **WeeklyForecastRegressor** TFLite model (Phase 8.3).
 *
 * This is a **user-level** (not per-habit) feature vector: it aggregates behaviour
 * across all active habits to predict the overall completion rate for the next 7 days.
 *
 * The field order, types, and units MUST exactly mirror the Python training script
 * (`ml-training/generate_weekly_forecast_data.py` and
 * `ml-training/train_weekly_forecast_model.py`): any discrepancy breaks inference
 * because the `weekly_forecast_scaler.json` mean/scale arrays are indexed positionally.
 *
 * Field order (matches `weekly_forecast_scaler.json` → `feature_columns`):
 *   1.  [lastWeekRate]       — overall completion rate for the trailing 7 days (0..1).
 *   2.  [avgCurrentStreak]   — mean of current streaks across all active habits (0..200).
 *   3.  [habitCount]         — total number of active (non-paused) habits (1..30).
 *   4.  [rateMon]            — fraction of Mondays hit in the last 4 weeks (0..1).
 *   5.  [rateTue]            — same for Tuesdays.
 *   6.  [rateWed]            — same for Wednesdays.
 *   7.  [rateThu]            — same for Thursdays.
 *   8.  [rateFri]            — same for Fridays.
 *   9.  [rateSat]            — same for Saturdays.
 *   10. [rateSun]            — same for Sundays.
 *   11. [weekOfYearSin]      — sin(2π · weekOfYear / 52); encodes yearly seasonality.
 *   12. [weekOfYearCos]      — cos(2π · weekOfYear / 52); paired with [weekOfYearSin].
 *
 * [WeeklyForecastUseCase] builds this vector from Room data before handing it to
 * [HabitPredictor.predictWeeklyRate].
 */
data class WeeklyForecastFeatures(
    val lastWeekRate: Float,
    val avgCurrentStreak: Float,
    val habitCount: Int,
    val rateMon: Float,
    val rateTue: Float,
    val rateWed: Float,
    val rateThu: Float,
    val rateFri: Float,
    val rateSat: Float,
    val rateSun: Float,
    val weekOfYearSin: Float,
    val weekOfYearCos: Float
) {
    /**
     * Returns the twelve features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictWeeklyRate].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        lastWeekRate,
        avgCurrentStreak,
        habitCount.toFloat(),
        rateMon,
        rateTue,
        rateWed,
        rateThu,
        rateFri,
        rateSat,
        rateSun,
        weekOfYearSin,
        weekOfYearCos
    )
}
