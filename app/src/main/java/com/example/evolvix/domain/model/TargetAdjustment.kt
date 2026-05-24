package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.TargetAdjustmentUseCase] (Phase 9.3).
 *
 * Represents the ML model's target-change recommendation for a single habit.
 * The raw regression output is a continuous value ∈ [-2.0, +2.0]; [delta] is that
 * value rounded to the nearest integer in {-2, -1, 0, +1, +2}.
 *
 * The recommendation should be presented as a suggestion, not a command — the user
 * always has the final say. Confidence is derived from how close [rawDelta] is to its
 * rounded integer value: a value near 0.5 (maximally ambiguous) yields [Confidence.LOW];
 * a value near 0.0 (very certain) yields [Confidence.HIGH].
 *
 * @property delta              Integer adjustment recommendation: -2/-1 = ease up,
 *                              0 = on track, +1/+2 = raise the bar.
 * @property rawDelta           Unrounded regression output ∈ [-2.0, +2.0], retained for
 *                              thesis-level traceability and confidence computation.
 * @property currentTarget      The habit's current daily/weekly repetition target.
 * @property suggestedTarget    ([currentTarget] + [delta]).coerceAtLeast(1) — the
 *                              concrete value shown in the recommendation card.
 * @property confidence         How strongly the model commits to [delta];
 *                              derived from |[rawDelta] - [delta].toFloat()|.
 * @property hasSufficientData  False when fewer than the minimum required completions
 *                              exist (< 5 periods in the window); the card shows a
 *                              "not enough data yet" placeholder instead of a suggestion.
 */
data class TargetAdjustment(
    val delta: Int,
    val rawDelta: Float,
    val currentTarget: Int,
    val suggestedTarget: Int,
    val confidence: Confidence,
    val hasSufficientData: Boolean
) {
    /**
     * Confidence tier for a [TargetAdjustment] recommendation.
     *
     * Derived from the absolute rounding residual r = |rawDelta - delta.toFloat()|.
     * - r < 0.15 → [HIGH]   — model output is very close to an integer, low ambiguity.
     * - r < 0.35 → [MEDIUM] — moderate ambiguity; borderline between two deltas.
     * - r ≥ 0.35 → [LOW]    — output near ±0.5, the model is uncertain.
     */
    enum class Confidence { LOW, MEDIUM, HIGH }

    companion object {
        /** Convenience factory for the insufficient-data sentinel. */
        fun insufficientData(currentTarget: Int) = TargetAdjustment(
            delta = 0,
            rawDelta = 0f,
            currentTarget = currentTarget,
            suggestedTarget = currentTarget,
            confidence = Confidence.LOW,
            hasSufficientData = false
        )

        /** Derives [Confidence] from the rounding residual of a raw regression output. */
        fun confidenceFrom(rawDelta: Float, delta: Int): Confidence {
            val residual = kotlin.math.abs(rawDelta - delta.toFloat())
            return when {
                residual < 0.15f -> Confidence.HIGH
                residual < 0.35f -> Confidence.MEDIUM
                else             -> Confidence.LOW
            }
        }
    }
}
