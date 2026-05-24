package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.SpilloverUseCase] (Phase 8.5).
 *
 * Represents a directional relationship between two habits where completing
 * [habitAName] on a given day is associated with a measurable change in the
 * same-day completion probability for [habitBName].
 *
 * ⚠ **Thesis note — causal caveat:** [liftDelta] is a *predicted lift estimate*
 * derived from historical co-occurrence, NOT a causal treatment effect. The View
 * should present it with hedged language (e.g. "tends to boost" rather than "causes").
 *
 * @property habitAName  Name of the trigger habit (the one completed first today).
 * @property habitBName  Name of the target habit whose probability is being estimated.
 * @property liftDelta   Predicted change in B's same-day completion probability
 *                       ∈ [-0.5, +0.5]. Positive = BOOST; negative = DRAG.
 *                       Produced by [com.example.evolvix.domain.ai.HabitPredictor.predictSpillover];
 *                       the model output is architecturally bounded to this range via
 *                       a `tanh × 0.5` output layer in `spillover_regressor.tflite`.
 * @property direction   Qualitative summary: BOOST / NEUTRAL / DRAG, derived from
 *                       [liftDelta] via [directionFor] (±[NEUTRAL_THRESHOLD] dead zone).
 */
data class SpilloverPair(
    val habitAName: String,
    val habitBName: String,
    val liftDelta: Float,
    val direction: Direction
) {
    /**
     * Qualitative direction of the spillover effect shown on StatisticsScreen.
     *
     * - [BOOST] — completing A raises the likelihood of completing B.
     * - [NEUTRAL] — no meaningful association detected.
     * - [DRAG] — completing A is associated with a lower likelihood of completing B
     *            (time-crowding or competing habit effect).
     */
    enum class Direction { BOOST, NEUTRAL, DRAG }

    companion object {
        /**
         * Dead zone: lift deltas within ±[NEUTRAL_THRESHOLD] are reported as
         * [Direction.NEUTRAL] to avoid surfacing noise as actionable insight.
         * Mirrors the threshold used by [com.example.evolvix.domain.usecase.SpilloverUseCase].
         */
        const val NEUTRAL_THRESHOLD = 0.05f

        /**
         * Derives the [Direction] from a raw [liftDelta] value.
         * Applied by [com.example.evolvix.domain.usecase.SpilloverUseCase] so the
         * View never thresholds raw floats directly.
         */
        fun directionFor(delta: Float): Direction = when {
            delta >  NEUTRAL_THRESHOLD -> Direction.BOOST
            delta < -NEUTRAL_THRESHOLD -> Direction.DRAG
            else                        -> Direction.NEUTRAL
        }
    }
}
