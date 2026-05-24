package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **SnoozeDisengagementClassifier** TFLite model (Phase 9.2).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_snooze_disengagement_data.py` / `train_snooze_disengagement_model.py`):
 * any discrepancy breaks inference because the `snooze_disengagement_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * Field order (matches `snooze_disengagement_scaler.json` → `feature_columns`):
 *   1. [habitAge]                     — days since first completion (1 … 730).
 *   2. [completionRateLast7Days]      — 0.0 … 1.0.
 *   3. [completionRateLast30Days]     — 0.0 … 1.0.
 *   4. [currentStreak]                — consecutive periods the habit was reached (0 … 200).
 *   5. [avgSnoozeCountLast14Days]     — mean [com.example.evolvix.data.model.HabitCompletionEntity.snoozeCount]
 *                                       across reminder-driven completions in the past 14 days (0.0 … 10.0).
 *                                       Computed only over rows where `snoozeCount != null`.
 *   6. [snoozeFrequencyLast14Days]    — fraction of reminder-driven completions (fromReminder = true)
 *                                       in the past 14 days that had snoozeCount ≥ 1 (0.0 … 1.0).
 *   7. [frequencyOrdinal]             — 0 = DAILY, 1 = WEEKLY, 2 = MONTHLY.
 *
 * ⚠ **Thesis note:** [avgSnoozeCountLast14Days] and [snoozeFrequencyLast14Days] are
 * observational features derived from the new [com.example.evolvix.data.model.HabitCompletionEntity.snoozeCount]
 * column (Phase 9.2 data collection). Until sufficient real data accumulates, the
 * [com.example.evolvix.domain.usecase.SnoozeDisengagementUseCase] sufficiency guard
 * will return `hasSufficientData = false` and the card will show a placeholder.
 */
data class SnoozeDisengagementFeatures(
    val habitAge: Int,
    val completionRateLast7Days: Float,
    val completionRateLast30Days: Float,
    val currentStreak: Int,
    val avgSnoozeCountLast14Days: Float,
    val snoozeFrequencyLast14Days: Float,
    val frequencyOrdinal: Int
) {
    /**
     * Returns the seven features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictSnoozeDisengagement].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        habitAge.toFloat(),
        completionRateLast7Days,
        completionRateLast30Days,
        currentStreak.toFloat(),
        avgSnoozeCountLast14Days,
        snoozeFrequencyLast14Days,
        frequencyOrdinal.toFloat()
    )
}
