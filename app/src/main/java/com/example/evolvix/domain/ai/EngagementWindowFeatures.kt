package com.example.evolvix.domain.ai

/**
 * Input feature vector for the **EngagementWindowRegressor** TFLite model (Phase 9.6).
 *
 * Captures the user's recent app-session statistics so the model can predict the
 * hour-of-day at which they are most likely to open the app next. The result is used
 * by [com.example.evolvix.domain.usecase.EngagementWindowUseCase] to populate an
 * [com.example.evolvix.domain.model.EngagementWindow] domain object.
 *
 * ⚠ **Thesis note — observational caveat:** The predicted hour reflects *when the user
 * typically opens the app*, not *when they would respond best to a push notification*.
 * The two signals are correlated but not identical.  The
 * [com.example.evolvix.domain.usecase.ScheduleReminderUseCase] integration is therefore
 * gated behind a confidence threshold (≥ 0.6) and a data-sufficiency guard
 * (≥ [com.example.evolvix.domain.model.EngagementWindow.MIN_SESSIONS] sessions) to prevent
 * low-confidence predictions from overriding user-set reminder times.
 *
 * The field order, types, and units MUST exactly mirror the Python training script
 * (`ml-training/generate_engagement_window_data.py` → `FEATURE_COLUMNS`) and the
 * `engagement_window_scaler.json` mean/scale arrays, which are indexed positionally:
 *
 * Field order (matches `engagement_window_scaler.json` → `feature_columns`):
 *   1. [dayOfWeek]               — 0 = Monday … 6 = Sunday.
 *   2. [isWeekend]               — 0 (Mon–Fri) or 1 (Sat–Sun).
 *   3. [recentAvgStartHour14d]   — mean session-start hour over the last 14 days ∈ [0, 24).
 *   4. [stddevStartHour14d]      — stddev of session-start hours over 14 days ∈ [0, 12].
 *                                  0.0 when fewer than 2 sessions exist in that window.
 *   5. [sessionCountLast7d]      — number of sessions recorded in the last 7 days (0 … 30).
 *   6. [avgSessionLengthMin]     — mean session duration in minutes ∈ [0.5, 60.0].
 *                                  Use 3.0 (training median) when no session length is known.
 *   7. [daysSinceFirstSession]   — days elapsed since the first recorded session (1 … 365).
 *   8. [prevSessionStartHour]    — start hour of the most recent session ∈ [0, 24).
 *                                  Use 12.0 (training default) when no previous session exists.
 *
 * [com.example.evolvix.domain.usecase.EngagementWindowUseCase] builds this vector from
 * [com.example.evolvix.data.local.AppSessionDao] records before handing it to
 * [HabitPredictor.predictEngagementHour].
 */
data class EngagementWindowFeatures(
    val dayOfWeek: Int,                  // 0=Mon … 6=Sun
    val isWeekend: Int,                  // 0 or 1
    val recentAvgStartHour14d: Float,    // 0.0 … 23.99
    val stddevStartHour14d: Float,       // 0.0 … 12.0
    val sessionCountLast7d: Int,         // 0 … 30
    val avgSessionLengthMin: Float,      // 0.5 … 60.0
    val daysSinceFirstSession: Int,      // 1 … 365
    val prevSessionStartHour: Float      // 0.0 … 23.99 (12.0 when no prior session)
) {
    /**
     * Returns the eight features as a [FloatArray] in the exact order expected by
     * the TFLite interpreter. Called by [TfliteHabitPredictor.predictEngagementHour].
     */
    fun toFloatArray(): FloatArray = floatArrayOf(
        dayOfWeek.toFloat(),
        isWeekend.toFloat(),
        recentAvgStartHour14d,
        stddevStartHour14d,
        sessionCountLast7d.toFloat(),
        avgSessionLengthMin,
        daysSinceFirstSession.toFloat(),
        prevSessionStartHour
    )
}
