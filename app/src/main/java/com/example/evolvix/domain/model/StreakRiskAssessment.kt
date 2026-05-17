package com.example.evolvix.domain.model

import java.time.DayOfWeek

/**
 * Output of [com.example.evolvix.domain.usecase.StreakRecoveryUseCase].
 *
 * Goes beyond a simple boolean flag by surfacing which specific days of the week
 * are consistently missed, giving the UI enough context to display targeted advice
 * (e.g., "You tend to miss Sundays — set a reminder for that day").
 *
 * @property isAtRisk        True when the habit's streak shows a high-risk pattern.
 * @property riskDays        Days-of-week that were missed in 3 out of the last 4
 *                           occurrences (daily habits only; empty for weekly/monthly).
 * @property hasSufficientData False when fewer than [StreakRecoveryUseCase.MIN_DATA_THRESHOLD]
 *                             target-reached completions exist — the analysis is unreliable
 *                             and the UI should show a "not enough history" placeholder.
 */
data class StreakRiskAssessment(
    val isAtRisk: Boolean,
    val riskDays: List<DayOfWeek>,
    val hasSufficientData: Boolean
)
