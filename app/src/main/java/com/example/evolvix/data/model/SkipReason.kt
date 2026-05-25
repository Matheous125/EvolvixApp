package com.example.evolvix.data.model

/**
 * Enum representing the reason a user skipped a habit (Phase 9.5).
 *
 * Stored as a string in [HabitSkipEntity] via [com.example.evolvix.data.local.Converters].
 * The ordinal index mirrors the `class_labels` array in `skip_reason_scaler.json` and
 * the Python training script — they MUST remain in the same order for TFLite inference
 * results to map correctly to enum values in [TfliteHabitPredictor.predictSkipReason].
 *
 * Classification into **voluntary** vs **involuntary** matters for Resilience v2
 * ([com.example.evolvix.domain.usecase.ResilienceScoreUseCase]):
 * - Involuntary: [SICK], [TRAVELING] — excluded from resilience gap math.
 * - Voluntary:   all others — counted against the user's recovery score.
 */
enum class SkipReason {
    /** User felt physically or mentally too exhausted to complete the habit. */
    TOO_TIRED,

    /** No time slot was available; schedule conflict. */
    TOO_BUSY,

    /**
     * Passive omission — no reminder reached the user, or the user simply
     * did not think about the habit.
     */
    FORGOT,

    /**
     * Involuntary skip due to illness or medical issue.
     * Excluded from resilience gap math in [ResilienceScoreUseCase].
     */
    SICK,

    /**
     * Involuntary skip because the user was away from their usual context
     * (e.g. business trip, holiday).
     * Excluded from resilience gap math in [ResilienceScoreUseCase].
     */
    TRAVELING,

    /**
     * Catch-all: the user dismissed the reason picker without selecting a reason,
     * or the skip was recorded programmatically (e.g. from a notification action)
     * without showing the picker.
     */
    NO_REASON;

    /** Returns true for skips that should NOT penalize the resilience score. */
    val isInvoluntary: Boolean
        get() = this == SICK || this == TRAVELING

    /** Short user-facing label shown on FilterChips and in StatisticsScreen cards. */
    val displayLabel: String
        get() = when (this) {
            TOO_TIRED  -> "Too tired"
            TOO_BUSY   -> "Too busy"
            FORGOT     -> "Forgot"
            SICK       -> "Not feeling well"
            TRAVELING  -> "Traveling"
            NO_REASON  -> "No particular reason"
        }
}
