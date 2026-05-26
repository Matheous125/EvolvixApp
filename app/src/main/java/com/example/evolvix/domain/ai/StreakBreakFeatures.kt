package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **StreakBreakClassifier** TFLite model (Phase 8.2).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_streak_break_data.py` and `ml-training/train_streak_break_model.py`):
 * any discrepancy breaks inference because the `streak_break_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * **R5 (2026-05-26):** Added [involuntarySkipDays7d] (field 8) and [recentAvgDifficulty]
 * (field 9) to exclude illness/travel gaps from the break signal and to incorporate
 * the user's self-reported effort level as a leading indicator of streak fragility.
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
 *   8. [involuntarySkipDays7d]   — distinct calendar days in the last 7d with a
 *                                  SICK or TRAVELING [SkipReason] (0 … 7). R5.
 *   9. [recentAvgDifficulty]     — rolling mean of [HabitCompletionEntity.perceivedDifficulty]
 *                                  over the last 14 rated completions (1.0 … 5.0;
 *                                  default 3.0 when no ratings exist). R5.
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
    val frequencyOrdinal: Int,
    val involuntarySkipDays7d: Int,    // R5: distinct SICK/TRAVELING days in last 7d (0..7)
    val recentAvgDifficulty: Float     // R5: rolling avg perceivedDifficulty (1.0..5.0; default 3.0)
) {
    /**
     * Returns the nine features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictStreakBreak].
     * Field order must stay in sync with `streak_break_scaler.json → feature_columns`.
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        currentStreak.toFloat(),
        habitAge.toFloat(),
        completionRateLast7Days,
        dayOfWeek.toFloat(),
        hourOfDay.toFloat(),
        recentAvgGapDays,
        frequencyOrdinal.toFloat(),
        involuntarySkipDays7d.toFloat(),  // R5 — field 8
        recentAvgDifficulty               // R5 — field 9
    )
}
