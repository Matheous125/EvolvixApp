package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **HabitAbandonmentClassifier** TFLite model (Phase 8.1).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_abandonment_data.py` and `ml-training/train_abandonment_model.py`):
 * any discrepancy breaks inference because the `abandonment_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * **R2 retrain (2026-05-26):** Fields 8 and 9 added so the model can discount raw gap
 * signals when the user was legitimately absent (SICK / TRAVELING). The math fallback
 * in [MathHabitPredictor] uses [involuntarySkipDays7d] to compute an adjusted gap.
 *
 * Field order (matches `abandonment_scaler.json` → `feature_columns`):
 *   1. [habitAge]                 — days since habit creation (1 … 730).
 *   2. [daysSinceLastCompletion]  — 0 … 30 (capped at 30 for training stability).
 *   3. [completionRateLast7Days]  — 0.0 … 1.0.
 *   4. [completionRateLast30Days] — 0.0 … 1.0.
 *   5. [currentStreak]            — consecutive periods the habit was reached (0 … 200).
 *   6. [totalCompletions]         — all-time completion count (0 … 730).
 *   7. [frequencyOrdinal]         — 0 = DAILY, 1 = WEEKLY, 2 = MONTHLY.
 *   8. [involuntarySkipDays7d]    — distinct SICK/TRAVELING skip dates in last 7 days (0 … 7).
 *   9. [involuntarySkipDays30d]   — distinct SICK/TRAVELING skip dates in last 30 days (0 … 30).
 */
data class AbandonmentFeatures(
    val habitAge: Int,
    val daysSinceLastCompletion: Int,
    val completionRateLast7Days: Float,
    val completionRateLast30Days: Float,
    val currentStreak: Int,
    val totalCompletions: Int,
    val frequencyOrdinal: Int,
    // R2 — involuntary-skip counts; allow the model to discount gap signals for
    // users who were genuinely sick or traveling rather than disengaged.
    val involuntarySkipDays7d: Int,
    val involuntarySkipDays30d: Int
) {
    /**
     * Returns the nine features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter and `abandonment_scaler.json`. Called by
     * [TfliteHabitPredictor.predictAbandonment].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        habitAge.toFloat(),
        daysSinceLastCompletion.toFloat(),
        completionRateLast7Days,
        completionRateLast30Days,
        currentStreak.toFloat(),
        totalCompletions.toFloat(),
        frequencyOrdinal.toFloat(),
        involuntarySkipDays7d.toFloat(),
        involuntarySkipDays30d.toFloat()
    )
}
