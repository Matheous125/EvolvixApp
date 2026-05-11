package com.example.evolvix.domain.model

/**
 * Domain model holding the computed streak metrics for a single habit.
 *
 * @property current Number of consecutive completed periods ending at or just before today.
 * @property best All-time longest consecutive run of completed periods.
 */
data class StreakResult(
    val current: Int,
    val best: Int
)
