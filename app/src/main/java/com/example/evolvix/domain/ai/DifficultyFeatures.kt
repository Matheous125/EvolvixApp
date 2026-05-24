package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **PerceivedDifficultyRegressor** TFLite model (Phase 9.4).
 *
 * The field order, types, and units must exactly mirror the Python training script
 * (`ml-training/generate_difficulty_data.py` and `ml-training/train_difficulty_model.py`):
 * any discrepancy breaks inference because the `perceived_difficulty_scaler.json`
 * mean/scale arrays are indexed positionally.
 *
 * ⚠ THESIS NOTE — OBSERVATIONAL CAVEAT:
 * These features are *correlates* of perceived difficulty, not its causes.
 * The model predicts "expected subjective rating given current habit state,"
 * not a causal measure of objective task difficulty. Present accordingly.
 *
 * Field order (matches `perceived_difficulty_scaler.json` → `feature_columns`):
 *   1. [dayOfWeek]               — 1 (Mon) … 7 (Sun), per `LocalDate.dayOfWeek.value`.
 *   2. [hourOfDay]               — 0 … 23.
 *   3. [currentStreak]           — consecutive periods the habit was reached (0 … 200).
 *   4. [completionRateLast7Days] — 0.0 … 1.0.
 *   5. [completionRateLast30Days]— 0.0 … 1.0.
 *   6. [habitAgeDays]            — days since habit creation (1 … 1800).
 *   7. [targetCount]             — the habit's daily target value (1 … 20).
 *   8. [avgProgressRatio30d]     — mean(completions / target) per period over last 30 days
 *                                  (0.0 … 3.0); values > 1.0 indicate over-completion.
 */
data class DifficultyFeatures(
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val currentStreak: Int,
    val completionRateLast7Days: Float,
    val completionRateLast30Days: Float,
    val habitAgeDays: Int,
    val targetCount: Int,
    val avgProgressRatio30d: Float
) {
    /**
     * Returns the eight features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictPerceivedDifficulty].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        dayOfWeek.toFloat(),
        hourOfDay.toFloat(),
        currentStreak.toFloat(),
        completionRateLast7Days,
        completionRateLast30Days,
        habitAgeDays.toFloat(),
        targetCount.toFloat(),
        avgProgressRatio30d
    )
}
