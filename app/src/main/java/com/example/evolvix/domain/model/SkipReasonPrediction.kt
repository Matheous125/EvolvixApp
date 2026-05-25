package com.example.evolvix.domain.model

import com.example.evolvix.data.model.SkipReason

/**
 * Output of [com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase] (Phase 9.5).
 *
 * Wraps the raw 6-class softmax distribution from
 * [com.example.evolvix.domain.ai.HabitPredictor.predictSkipReason] into a
 * structured result that the View layer can render without touching raw floats.
 *
 * **Design note — why expose the full [distribution] and not just [topReason]:**
 * [com.example.evolvix.data.model.SkipReason.SICK] and
 * [com.example.evolvix.data.model.SkipReason.TRAVELING] are inherently unpredictable
 * from behavioral features. When [topConfidence] is low (e.g. < 0.35), the View should
 * treat all six chips as equally valid and not pre-select any. Exposing the full
 * distribution gives the View enough information to implement this heuristic without
 * requiring an additional flag.
 *
 * ⚠ **Thesis note (observational caveat):** [topReason] is a predicted association,
 * not a causal diagnosis. Present as "most likely skip reason given current context."
 *
 * @property habitId          Room primary key of the assessed habit.
 * @property distribution     Softmax probability for each [SkipReason] class ∈ [0.0, 1.0].
 *                            All six values sum to ≈ 1.0.
 * @property topReason        The [SkipReason] with the highest probability in [distribution].
 * @property topConfidence    Probability of [topReason] ∈ [0.0, 1.0].
 * @property hasSufficientData False when fewer than [com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase.MIN_SKIPS]
 *                             skip records exist for this habit, indicating the prediction
 *                             is noise rather than a learned pattern. The View shows a
 *                             "not enough data" placeholder in this case.
 */
data class SkipReasonPrediction(
    val habitId: Int,
    val distribution: Map<SkipReason, Float>,
    val topReason: SkipReason,
    val topConfidence: Float,
    val hasSufficientData: Boolean
) {
    companion object {
        /**
         * Confidence threshold below which the View should display all reason chips
         * without pre-selecting any. At this level, the model has no strong opinion
         * and forcing a pre-selection would mislead the user.
         */
        const val LOW_CONFIDENCE_THRESHOLD = 0.35f

        /**
         * Builds a [SkipReasonPrediction] from a raw softmax float array (length 6)
         * output by the TFLite interpreter.
         *
         * [values] must be indexed in [SkipReason] declaration order:
         * 0=TOO_TIRED, 1=TOO_BUSY, 2=FORGOT, 3=SICK, 4=TRAVELING, 5=NO_REASON.
         */
        fun fromSoftmax(
            habitId: Int,
            values: FloatArray,
            hasSufficientData: Boolean
        ): SkipReasonPrediction {
            val reasons = SkipReason.entries
            val distribution = reasons.zip(values.toList())
                .associate { (reason, prob) -> reason to prob }
            val top = distribution.maxByOrNull { it.value }!!
            return SkipReasonPrediction(
                habitId = habitId,
                distribution = distribution,
                topReason = top.key,
                topConfidence = top.value,
                hasSufficientData = hasSufficientData
            )
        }
    }
}
