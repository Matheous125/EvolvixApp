package com.example.evolvix.domain.model

import com.example.evolvix.data.model.HabitEntity

/**
 * Aggregated statistics for a single habit, used by [StatisticsViewModel.perHabitStats].
 *
 * Bundles the outputs of [com.example.evolvix.domain.usecase.CalculateStreakUseCase]
 * and [com.example.evolvix.domain.usecase.SparklineUseCase] for one habit so the
 * Statistics screen can render per-habit cards without holding raw completion lists.
 *
 * @property habit The source [HabitEntity] (carries name, color, frequency, categories).
 * @property streak Current and best streak counts computed over all-time completions.
 * @property sparkline30d Daily target-reached flags for the last 30 calendar days,
 *   ordered oldest → newest. Drives the default sparkline/bar chart in the Statistics UI.
 * @property completionRate30d Fraction of days in the 30-day window where the target
 *   was reached, clamped to [0.0, 1.0].
 */
data class PerHabitStats(
    val habit: HabitEntity,
    val streak: StreakResult,
    val sparkline30d: List<SparklinePoint>,
    val completionRate30d: Float
)
