package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **StreakBreakClassifier** TFLite model (Phase 8.2).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_streak_break_data.py` and `ml-training/train_streak_break_model.py`):
 * any discrepancy breaks inference because the `streak_break_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * Field order (matches `streak_break_scaler.json` → `feature_columns`):
 *   1. [currentStreak]           — consecutive periods the habit was reached (1 … 200).
 *   2. [habitAge]                — days since first completion (1 … 730).
 *   3. [completionRateLast7Days] — 0.0 … 1.0.
 *   4. [dayOfWeek]               — 1 = Monday … 7 = Sunday (moment of evaluation).
 *   5. [hourOfDay]               — 0 … 23 (moment of evaluation).
 *   6. [recentAvgGapDays]        — mean calendar gap between target-reached dates in
 *                                  the last 30 days (0.0 … 30.0).
 *   7. [frequencyOrdinal]        — 0 = DAILY, 1 = WEEKLY, 2 = MONTHLY.
 *
 * The dataset is sampled from an **active-streak population** (all rows have
 * `currentStreak >= 1`), so this feature vector must only be constructed when the
 * habit currently has a non-zero streak. [StreakBreakUseCase] enforces this guard.
 */
data class StreakBreakFeatures(
    val currentStreak: Int,
    val habitAge: Int,
    val completionRateLast7Days: Float,
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val recentAvgGapDays: Float,
    val frequencyOrdinal: Int
) {
    /**
     * Returns the seven features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictStreakBreak].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        currentStreak.toFloat(),
        habitAge.toFloat(),
        completionRateLast7Days,
        dayOfWeek.toFloat(),
        hourOfDay.toFloat(),
        recentAvgGapDays,
        frequencyOrdinal.toFloat()
    )
}
