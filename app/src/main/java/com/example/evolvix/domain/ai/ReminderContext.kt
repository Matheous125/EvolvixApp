package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **ReminderTemplateClassifier** TFLite model (Phase 6.5.4).
 *
 * Retrained in **R1** (2026-05-26) to add [snoozeCountToday] as the 8th feature,
 * enabling gentler template selection for users who have already snoozed the reminder
 * multiple times in the current day (test accuracy after R1 retrain: 0.7327).
 *
 * Field order, types, and units mirror the Python training script
 * ([`ml-training/generate_reminder_data.py`] / [`ml-training/train_reminder_model.py`]).
 * Order must equal `reminder_scaler.json` → `feature_columns`:
 *   1. [currentStreak] — Int, periods.
 *   2. [completionRateLast7Days] — 0.0 … 1.0.
 *   3. [daysSinceLastCompletion] — Int, days.
 *   4. [dayOfWeek] — 1 (Mon) … 7 (Sun).
 *   5. [hourOfDay] — 0 … 23.
 *   6. [isAtRisk] — Boolean (encoded as 1.0 / 0.0).
 *   7. [targetReachedToday] — Boolean (encoded as 1.0 / 0.0).
 *   8. [snoozeCountToday] — Int, number of snooze taps in the current reminder cycle (R1).
 */
data class ReminderContext(
    val currentStreak: Int,
    val completionRateLast7Days: Float,
    val daysSinceLastCompletion: Int,
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val isAtRisk: Boolean,
    val targetReachedToday: Boolean,
    val snoozeCountToday: Int  // R1: read from SnoozePreferences before constructing
) {
    /**
     * Returns the eight features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Booleans are encoded as 1.0f / 0.0f.
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        currentStreak.toFloat(),
        completionRateLast7Days,
        daysSinceLastCompletion.toFloat(),
        dayOfWeek.toFloat(),
        hourOfDay.toFloat(),
        if (isAtRisk) 1f else 0f,
        if (targetReachedToday) 1f else 0f,
        snoozeCountToday.toFloat()  // R1: 8th feature; matches Python FEATURE_COLUMNS[7]
    )
}
