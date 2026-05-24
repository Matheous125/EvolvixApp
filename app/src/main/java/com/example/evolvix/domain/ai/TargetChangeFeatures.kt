package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **TargetAdjustmentRegressor** TFLite model (Phase 9.3).
 *
 * Predicts a continuous target delta ∈ [-2.0, +2.0]. The caller
 * ([com.example.evolvix.domain.usecase.TargetAdjustmentUseCase]) rounds the raw output
 * to the nearest integer in {-2, -1, 0, +1, +2} before surfacing the recommendation.
 *
 * ⚠ **Thesis note — causal caveat:** The model is trained on synthetic priors that
 * encode behavioral hypotheses about when a target change is beneficial. It is an
 * *observational recommender*, not a counterfactual treatment-effect estimator. Frame
 * accordingly: the model predicts "what target tends to correlate with sustained high
 * performance given the current habit state," not "what would happen if we forced the
 * target to change."
 *
 * ⚠ **Phase 9.4 note:** `perceivedDifficulty` is unavailable until Phase 9.4 adds the
 * column to `HabitCompletionEntity`. [avgProgressRatio30d] serves as a proxy here (a
 * value > 1.0 signals the user is over-completing, analogous to low perceived
 * difficulty). Phase 9.4 will retrain the model with `perceivedDifficulty` as a 9th
 * input feature.
 *
 * The field order, types, and units MUST exactly mirror the Python training script
 * (`ml-training/generate_target_change_data.py` → `FEATURE_COLUMNS`) and the
 * `target_change_scaler.json` mean/scale arrays, which are indexed positionally:
 *
 * Field order (matches `target_change_scaler.json` → `feature_columns`):
 *   1. [currentTarget]           — current daily/weekly repetition target (≥ 1).
 *   2. [rate30d]                 — 30-day reached-period rate ∈ [0, 1].
 *   3. [rate7d]                  — 7-day reached-period rate ∈ [0, 1].
 *   4. [avgProgressRatio30d]     — mean(completions_in_period / target) over the
 *                                  30-day window; values > 1.0 indicate over-completion.
 *   5. [currentStreak]           — current streak length in periods.
 *   6. [habitAgeDays]            — days since habit creation.
 *   7. [previousDelta]           — last target delta applied from history;
 *                                  0 if the target has never been changed.
 *   8. [periodsSinceLastChange]  — periods elapsed since the last target change;
 *                                  999 sentinel when the target has never changed.
 *
 * [com.example.evolvix.domain.usecase.TargetAdjustmentUseCase] builds this vector from
 * Room data before handing it to [HabitPredictor.predictTargetDelta].
 */
data class TargetChangeFeatures(
    val currentTarget: Int,
    val rate30d: Float,
    val rate7d: Float,
    val avgProgressRatio30d: Float,   // proxy for perceived difficulty until Phase 9.4
    val currentStreak: Int,
    val habitAgeDays: Int,
    val previousDelta: Int,           // 0 if target was never changed
    val periodsSinceLastChange: Int   // 999 sentinel = never changed
) {
    /**
     * Returns the eight features as a [FloatArray] in the exact order expected by the
     * TFLite interpreter. Called by [TfliteHabitPredictor.predictTargetDelta].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        currentTarget.toFloat(),
        rate30d,
        rate7d,
        avgProgressRatio30d,
        currentStreak.toFloat(),
        habitAgeDays.toFloat(),
        previousDelta.toFloat(),
        periodsSinceLastChange.toFloat()
    )
}
