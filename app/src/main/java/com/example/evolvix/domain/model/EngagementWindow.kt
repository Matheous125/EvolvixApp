package com.example.evolvix.domain.model

import kotlin.math.abs

/**
 * Output of [com.example.evolvix.domain.usecase.EngagementWindowUseCase] (Phase 9.6).
 *
 * Wraps the raw regression output from
 * [com.example.evolvix.domain.ai.HabitPredictor.predictEngagementHour] into a
 * rounded integer hour and a confidence score, so the View layer and
 * [com.example.evolvix.domain.usecase.ScheduleReminderUseCase] never consume raw
 * float model output directly.
 *
 * ⚠ **Thesis note — observational caveat:** [predictedHour] reflects *when the user
 * typically opens the app*, not *when they would respond optimally to a notification*.
 * [ScheduleReminderUseCase] uses this value only when [hasSufficientData] is true
 * AND [confidence] ≥ 0.6, to prevent low-confidence overrides of user-set reminder times.
 *
 * @property predictedHour       Rounded model output ∈ [0, 23]; the most likely hour
 *                               the user will open the app next.
 * @property rawPredictedHour    Unrounded model output ∈ [0.0, 24.0); retained so
 *                               callers can apply custom rounding or band logic.
 * @property confidence          Reliability indicator ∈ [0.0, 1.0], derived from
 *                               `1 - (stddevStartHour14d / 12.0)`. A stddev of 0 h
 *                               (perfectly consistent user) yields 1.0; 12 h (random
 *                               open time) yields 0.0. Clamped to [0.0, 1.0].
 * @property hasSufficientData   False when fewer than [MIN_SESSIONS] sessions have
 *                               been recorded. The View shows a placeholder and
 *                               [ScheduleReminderUseCase] skips the override when false.
 */
data class EngagementWindow(
    val predictedHour: Int,
    val rawPredictedHour: Float,
    val confidence: Float,
    val hasSufficientData: Boolean
) {
    companion object {
        /**
         * Minimum number of recorded [com.example.evolvix.data.model.AppSessionEntity]
         * rows required before a prediction is considered meaningful.
         *
         * 14 sessions ≈ two weeks of daily use, which is enough to establish a
         * stable chronotype pattern (morning / evening / bimodal).
         */
        const val MIN_SESSIONS = 14

        /**
         * Confidence threshold used by [com.example.evolvix.domain.usecase.ScheduleReminderUseCase]
         * to decide whether to override the default reminder timing with [predictedHour].
         * Below this value the model is too uncertain to be useful for scheduling.
         */
        const val CONFIDENCE_THRESHOLD = 0.6f

        /**
         * Computes the confidence score from the session-start-hour standard deviation.
         * A perfectly consistent user (stddev = 0) yields 1.0; a fully random opener
         * (stddev = 12 h, half the 24-hour range) yields 0.0.
         *
         * Called by [com.example.evolvix.domain.usecase.EngagementWindowUseCase].
         */
        fun confidenceFrom(stddevHours: Float): Float =
            (1f - (stddevHours / 12f)).coerceIn(0f, 1f)

        /** Convenience: returns the not-enough-data placeholder instance. */
        val insufficient = EngagementWindow(
            predictedHour = 12,
            rawPredictedHour = 12f,
            confidence = 0f,
            hasSufficientData = false
        )
    }
}
