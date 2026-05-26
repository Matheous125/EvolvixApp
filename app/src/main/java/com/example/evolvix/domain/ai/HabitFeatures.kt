package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **HabitSuccessClassifier** TFLite model (Phase 6.5.2).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * ([`ml-training/generate_success_data.py`] and [`ml-training/train_success_model.py`]):
 * any discrepancy here breaks inference because the `success_scaler.json` mean/scale
 * arrays are indexed positionally.
 *
 * Field order (matches `success_scaler.json` → `feature_columns`):
 *   1. [dayOfWeek] — 1 (Mon) … 7 (Sun), per `LocalDate.dayOfWeek.value`.
 *   2. [hourOfDay] — 0 … 23.
 *   3. [currentStreak] — number of consecutive periods the habit was reached.
 *   4. [completionRateLast7Days] — 0.0 … 1.0.
 *   5. [habitAge] — days since habit creation (1 … 730).
 *   6. [hoursSinceLastCompletion] — 0 … 336 (capped at 14 days for training stability).
 *   7. [targetCount] — the habit's daily target value (1 … 20).
 *   8. [recentAvgDifficulty] — rolling avg of perceivedDifficulty over the last 14
 *      completions (1.0 … 5.0). Defaults to 3.0 (neutral midpoint) when no rated
 *      completions exist. Added in R6 retrain (2026-05-26).
 *   9. [spilloverLiftAggregate] — sum of positive spillover lift deltas from partner
 *      habits completed today (clamped to [-0.5, +0.5]). Added in R7 retrain (2026-05-26).
 */
data class HabitFeatures(
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val currentStreak: Int,
    val completionRateLast7Days: Float,
    val habitAge: Int,
    val hoursSinceLastCompletion: Int,
    val targetCount: Int,
    /** R6: rolling avg of perceivedDifficulty (last 14 completions), 1.0–5.0. Default 3.0 = neutral. */
    val recentAvgDifficulty: Float = 3.0f,
    /** R7: sum of positive spillover lift deltas from partner habits completed today, clamped to [-0.5, +0.5]. */
    val spilloverLiftAggregate: Float = 0f
) {
    /**
     * Returns the nine features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictSuccess].
     * Order must match Python `FEATURE_COLUMNS` in `generate_success_data.py`.
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        dayOfWeek.toFloat(),
        hourOfDay.toFloat(),
        currentStreak.toFloat(),
        completionRateLast7Days,
        habitAge.toFloat(),
        hoursSinceLastCompletion.toFloat(),
        targetCount.toFloat(),
        recentAvgDifficulty,
        spilloverLiftAggregate
    )
}
