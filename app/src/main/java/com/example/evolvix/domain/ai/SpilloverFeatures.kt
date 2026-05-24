package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **SpilloverRegressor** TFLite model (Phase 8.5).
 *
 * Represents one ordered habit-pair (A, B): habit A was completed at a specific hour
 * today; the model predicts the *observational lift* [liftDelta][com.example.evolvix.domain.model.SpilloverPair.liftDelta]
 * — the change in habit B's same-day completion probability attributable to A's completion.
 *
 * ⚠ **Thesis note — causal caveat:** The output is a *predicted lift estimate* based on
 * historical co-occurrence patterns, NOT a causal treatment effect. Confounders (e.g.
 * high-energy days, days off work) can inflate co-occurrence independently of any A→B
 * mechanism. Frame accordingly in the thesis.
 *
 * The field order, types, and units MUST exactly mirror the Python training script
 * (`ml-training/generate_spillover_data.py` → `FEATURE_COLUMNS`) and the
 * `spillover_scaler.json` mean/scale arrays, which are indexed positionally:
 *
 * Field order (matches `spillover_scaler.json` → `feature_columns`):
 *   1. [rateA]             — habit A's 30-day completion rate ∈ [0, 1].
 *   2. [rateB]             — habit B's 30-day completion rate ∈ [0, 1].
 *   3. [hourACompleted]    — hour of day (0–23) at which A was completed today.
 *   4. [coOccurrenceRate]  — fraction of A-completed days on which B was also completed ∈ [0, 1].
 *   5. [typicalGapHours]   — median |t_B − t_A| in hours on shared days ∈ [0, 24].
 *                            Substitute with the training median (≈ 3.0 h, from
 *                            `spillover_scaler.json`) when shared history < 3 days.
 *
 * [com.example.evolvix.domain.usecase.SpilloverUseCase] builds this vector from Room
 * data before handing it to [HabitPredictor.predictSpillover].
 */
data class SpilloverFeatures(
    val rateA: Float,
    val rateB: Float,
    val hourACompleted: Int,       // 0..23; normalised by the StandardScaler at inference time
    val coOccurrenceRate: Float,
    val typicalGapHours: Float     // 0.0..24.0
) {
    /**
     * Returns the five features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictSpillover].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        rateA,
        rateB,
        hourACompleted.toFloat(),
        coOccurrenceRate,
        typicalGapHours
    )
}
