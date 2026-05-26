package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **Habit Behavioral Clustering** K-Means model (Phase 8.4).
 *
 * Unlike the TFLite models in this package, the clustering model has no `.tflite` file.
 * Classification is performed in [TfliteHabitPredictor.classifyBehavioralCluster] as a
 * pure Kotlin nearest-centroid lookup using the centroids stored in `habit_clusters.json`.
 *
 * The field order, types, and units MUST exactly mirror the Python training scripts
 * (`ml-training/generate_clustering_data.py` and `ml-training/train_clustering_model.py`):
 * any discrepancy breaks inference because `habit_clusters.json` → `feature_means` and
 * `feature_scales` are indexed positionally (matching `feature_columns`).
 *
 * Field order (matches `habit_clusters.json` → `feature_columns`, R4: 7 features):
 *   1. [rate30d]                    — 30-day completion rate (0.0 … 1.0).
 *                                     Fraction of days in the last 30 where the target was reached.
 *   2. [routinePrecisionStddev]     — std-dev of daily completion hour in MINUTES (0 … 300).
 *                                     Lower = more consistent routine timing. Substitute
 *                                     [training_medians[1]] when [HabitPredictor.computeRoutinePrecision]
 *                                     returns null (< 5 completions).
 *   3. [procrastinationSkew]        — skewness of completion hour-of-day distribution (−3 … 3).
 *                                     Positive = completions cluster at end of day (procrastinating).
 *                                     Substitute 0.0 (neutral; training_medians[2]) when
 *                                     [HabitPredictor.computeProcrastination] returns null (< 10 completions).
 *   4. [habitAge]                   — days since first recorded completion (1 … 365).
 *                                     Conservative proxy for habit creation date (capped at 365).
 *   5. [resilienceAvgGap]           — mean days to resume after a missed period (0 … 15).
 *                                     0 = habit has never been missed. Substitute
 *                                     [training_medians[4]] when [HabitPredictor.computeResilience]
 *                                     returns null (no recovery events observable).
 *   6. [voluntarySkipRate30d]       — (R4) fraction of last-30d days with a voluntary skip (0.0 … 1.0).
 *                                     Voluntary = [SkipReason.isInvoluntary] == false
 *                                     (TOO_TIRED, TOO_BUSY, FORGOT, NO_REASON).
 *                                     Substitute 0.0 when no skip history exists (safe: no skips = no
 *                                     voluntary disengagement signal).
 *   7. [involuntarySkipRate30d]     — (R4) fraction of last-30d days with an involuntary skip (0.0 … 1.0).
 *                                     Involuntary = SICK or TRAVELING.
 *                                     Substitute 0.0 when no skip history exists.
 *
 * [BehavioralClusterUseCase] builds this vector from Room data, applying training-median
 * substitutions for null analytics, then delegates to [HabitPredictor.classifyBehavioralCluster].
 */
data class ClusterFeatures(
    val rate30d: Float,
    val routinePrecisionStddev: Float,
    val procrastinationSkew: Float,
    val habitAge: Int,
    val resilienceAvgGap: Float,
    /** R4: fraction of last-30d days with a voluntary skip (SkipReason.isInvoluntary == false). */
    val voluntarySkipRate30d: Float,
    /** R4: fraction of last-30d days with an involuntary skip (SICK / TRAVELING). */
    val involuntarySkipRate30d: Float
) {
    /**
     * Returns the seven features as a [FloatArray] in the exact order expected by the
     * nearest-centroid lookup in [TfliteHabitPredictor.classifyBehavioralCluster].
     * The array is standardized inside that method using `feature_means` and `feature_scales`
     * from `habit_clusters.json` before computing Euclidean distances to centroids.
     *
     * Field order (R4): rate30d, routinePrecisionStddev, procrastinationSkew, habitAge,
     * resilienceAvgGap, voluntarySkipRate30d, involuntarySkipRate30d.
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        rate30d,
        routinePrecisionStddev,
        procrastinationSkew,
        habitAge.toFloat(),
        resilienceAvgGap,
        voluntarySkipRate30d,
        involuntarySkipRate30d
    )
}
