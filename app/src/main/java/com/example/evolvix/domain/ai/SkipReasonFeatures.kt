package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **SkipReasonClassifier** TFLite model (Phase 9.5).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_skip_reason_data.py` / `train_skip_reason_model.py`):
 * any discrepancy breaks inference because the `skip_reason_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * Field order (matches `skip_reason_scaler.json` → `feature_columns`):
 *   1. [habitAge]                   — days since first completion (1 … 730).
 *   2. [completionRateLast7Days]    — 0.0 … 1.0.
 *   3. [completionRateLast30Days]   — 0.0 … 1.0.
 *   4. [currentStreak]              — consecutive periods the habit was reached (0 … 200).
 *   5. [dayOfWeek]                  — ISO 8601: 1 = Monday … 7 = Sunday.
 *   6. [hourOfDay]                  — 0 … 23 (device local time at skip moment).
 *   7. [frequencyOrdinal]           — 0 = DAILY, 1 = WEEKLY, 2 = MONTHLY.
 *   8. [recentSkipRate14d]          — skips / opportunities in the past 14 days (0.0 … 1.0).
 *                                     Derived by [com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase]
 *                                     from [com.example.evolvix.data.local.HabitSkipDao.getRecentForHabit].
 *
 * ⚠ **Thesis note (observational caveat):** [dayOfWeek] and [hourOfDay] capture
 * *when* the skip occurred, not *why*. The model learns correlations between context
 * and skip reason (e.g. Friday evening → TOO_TIRED), but these are observational
 * associations, not causal relationships. Present in the thesis as "predicted skip
 * reason given current context," not as evidence that time-of-day *causes* fatigue.
 *
 * ⚠ **Noise-class caveat:** [com.example.evolvix.data.model.SkipReason.SICK] and
 * [com.example.evolvix.data.model.SkipReason.TRAVELING] are inherently unpredictable
 * from these behavioral features. The classifier's low confidence on those two classes
 * is expected and correct; high softmax entropy for SICK/TRAVELING signals the View
 * layer to present all reason chips without a pre-selection highlight.
 */
data class SkipReasonFeatures(
    val habitAge: Int,
    val completionRateLast7Days: Float,
    val completionRateLast30Days: Float,
    val currentStreak: Int,
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val frequencyOrdinal: Int,
    val recentSkipRate14d: Float
) {
    /**
     * Returns the eight features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictSkipReason].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        habitAge.toFloat(),
        completionRateLast7Days,
        completionRateLast30Days,
        currentStreak.toFloat(),
        dayOfWeek.toFloat(),
        hourOfDay.toFloat(),
        frequencyOrdinal.toFloat(),
        recentSkipRate14d
    )
}
