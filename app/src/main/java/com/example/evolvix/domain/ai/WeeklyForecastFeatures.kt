package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **WeeklyForecastRegressor** TFLite model (Phase 8.3).
 *
 * **R10 retrain (2026-05-27):** Extended from 12 to 17 features. Five new columns added
 * at indices 12–16: four K-Means cluster-proportion features and one aggregate abandonment
 * risk. These allow the model to distinguish a high-rate week that is fragile (many
 * habits in the Dormant/Struggling cluster, high avg abandonment risk) from one that is
 * structurally sound (mostly Effortless-Routine habits).
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
 *   1.  [lastWeekRate]                    — overall completion rate for the trailing 7 days (0..1).
 *   2.  [avgCurrentStreak]                — mean of current streaks across all active habits (0..200).
 *   3.  [habitCount]                      — total number of active (non-paused) habits (1..30).
 *   4.  [rateMon]                         — fraction of Mondays hit in the last 4 weeks (0..1).
 *   5.  [rateTue]                         — same for Tuesdays.
 *   6.  [rateWed]                         — same for Wednesdays.
 *   7.  [rateThu]                         — same for Thursdays.
 *   8.  [rateFri]                         — same for Fridays.
 *   9.  [rateSat]                         — same for Saturdays.
 *   10. [rateSun]                         — same for Sundays.
 *   11. [weekOfYearSin]                   — sin(2π · weekOfYear / 52); encodes yearly seasonality.
 *   12. [weekOfYearCos]                   — cos(2π · weekOfYear / 52); paired with [weekOfYearSin].
 *   13. [clusterProportionEffortless]     — fraction of active habits classified as effortless_routine (R10).
 *   14. [clusterProportionConsistent]     — fraction classified as consistent_effort (R10).
 *   15. [clusterProportionStruggling]     — fraction classified as struggling (R10).
 *   16. [clusterProportionDormant]        — fraction classified as dormant (R10).
 *   17. [avgAbandonmentRisk]              — mean abandonment probability across all active habits (R10).
 *
 * Cluster proportion fields always sum to 1.0 when at least one habit has sufficient
 * cluster data; all four are 0.0 during cold-start (no habit has ≥ 10 completions yet).
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
    val weekOfYearCos: Float,
    // R10: K-Means cluster distribution across all active habits (sum to 1.0 or all 0.0)
    val clusterProportionEffortless: Float = 0f,
    val clusterProportionConsistent: Float = 0f,
    val clusterProportionStruggling: Float = 0f,
    val clusterProportionDormant: Float = 0f,
    // R10: mean abandonment probability across all active habits, in [0, 1]
    val avgAbandonmentRisk: Float = 0f
) {
    /**
     * Returns all seventeen features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter and `weekly_forecast_scaler.json`.
     * Called by [TfliteHabitPredictor.predictWeeklyRate].
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
        weekOfYearCos,
        // R10 features (indices 12–16)
        clusterProportionEffortless,
        clusterProportionConsistent,
        clusterProportionStruggling,
        clusterProportionDormant,
        avgAbandonmentRisk
    )
}
