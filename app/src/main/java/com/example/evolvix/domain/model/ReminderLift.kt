package com.example.evolvix.domain.model

/**
 * Output of [ReminderEffectivenessUseCase] (Phase 9.1).
 *
 * Wraps the raw predicted-lift computation from [HabitPredictor.predictReminderCompletion]
 * into a recommendation flag and data-sufficiency guard, so the View layer and
 * [ScheduleReminderUseCase] never have to threshold raw floats directly.
 *
 * **Thesis note:** [lift] is a *predicted lift estimator* — the difference in
 * predicted completion probability between "reminder sent" and "no reminder sent"
 * conditions, as approximated by the ReminderLiftClassifier. It is NOT a causal
 * treatment effect; it should be framed as "predicted lift" in the thesis, not
 * "causal effect recovery."
 *
 * @property habitId             Room primary key of the assessed habit.
 * @property baselineProb        Model output for [reminderSent=0] — predicted completion
 *                               probability without a reminder, in [0.0, 1.0].
 * @property withReminderProb    Model output for [reminderSent=1] — predicted completion
 *                               probability with a reminder, in [0.0, 1.0].
 * @property lift                Predicted lift = [withReminderProb] − [baselineProb].
 *                               Positive values mean the reminder is expected to help.
 * @property recommendSend       True when [lift] ≥ [ReminderEffectivenessUseCase.SUPPRESS_THRESHOLD]
 *                               and [hasSufficientData] is true.
 * @property hasSufficientData   False when the habit has fewer than
 *                               [ReminderEffectivenessUseCase.MIN_COMPLETIONS] completions.
 *                               When false, reminders are sent unconditionally (safe default).
 */
data class ReminderLift(
    val habitId: Int,
    val baselineProb: Float,
    val withReminderProb: Float,
    val lift: Float,
    val recommendSend: Boolean,
    val hasSufficientData: Boolean
)
