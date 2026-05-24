package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **ReminderLiftClassifier** TFLite model (Phase 9.1).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_reminder_lift_data.py` / `train_reminder_lift_model.py`):
 * any discrepancy breaks inference because the `reminder_lift_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * Field order (matches `reminder_lift_scaler.json` → `feature_columns`):
 *   1. [habitAge]                  — days since habit creation (0 … 730).
 *   2. [completionRateLast7Days]   — 0.0 … 1.0.
 *   3. [completionRateLast30Days]  — 0.0 … 1.0.
 *   4. [currentStreak]             — consecutive periods the habit was reached (0 … 200).
 *   5. [hourOfDay]                 — local hour of reminder slot, 0 … 23.
 *   6. [dayOfWeekOrdinal]          — 0 = Monday … 6 = Sunday.
 *   7. [frequencyOrdinal]          — 0 = DAILY, 1 = WEEKLY, 2 = MONTHLY.
 *   8. [reminderSent]              — treatment variable: 0 = no reminder, 1 = reminder sent.
 *
 * At inference time [ReminderEffectivenessUseCase] calls [TfliteHabitPredictor.predictReminderCompletion]
 * twice — once with [reminderSent] = 0 and once with [reminderSent] = 1 — and computes
 * lift = P(sent=1) − P(sent=0) to decide whether a reminder should be suppressed.
 */
data class ReminderLiftFeatures(
    val habitAge: Int,
    val completionRateLast7Days: Float,
    val completionRateLast30Days: Float,
    val currentStreak: Int,
    val hourOfDay: Int,
    val dayOfWeekOrdinal: Int,
    val frequencyOrdinal: Int,
    val reminderSent: Int          // 0 or 1 — the treatment variable
) {
    /**
     * Returns the eight features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictReminderCompletion].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        habitAge.toFloat(),
        completionRateLast7Days,
        completionRateLast30Days,
        currentStreak.toFloat(),
        hourOfDay.toFloat(),
        dayOfWeekOrdinal.toFloat(),
        frequencyOrdinal.toFloat(),
        reminderSent.toFloat()
    )
}
