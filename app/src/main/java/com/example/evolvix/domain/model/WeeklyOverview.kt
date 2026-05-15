package com.example.evolvix.domain.model

import java.time.LocalDate

/**
 * Aggregated summary of habit completions for a 7-day rolling window.
 *
 * Produced by [com.example.evolvix.domain.usecase.WeeklyOverviewUseCase].
 * Consumed by StatisticsViewModel to drive the "Global Overview" card in StatisticsScreen.
 *
 * @property dailySummaries One [DaySummary] per day, ordered oldest → newest (7 entries).
 * @property totalActiveHabits Total number of active (non-paused) habits considered.
 * @property todayCompletedHabits Number of habits where target was reached today.
 * @property weekCompletionRate Fraction of (habit × day) pairs where target was reached
 *   over the full 7-day window. Range: [0.0, 1.0].
 */
data class WeeklyOverview(
    val dailySummaries: List<DaySummary>,
    val totalActiveHabits: Int,
    val todayCompletedHabits: Int,
    val weekCompletionRate: Float
)

/**
 * Completion snapshot for a single calendar day within the weekly window.
 *
 * @property date The calendar day this snapshot represents.
 * @property completedHabits Number of habits that reached their target on [date].
 * @property totalHabits Total number of active habits considered for this day.
 */
data class DaySummary(
    val date: LocalDate,
    val completedHabits: Int,
    val totalHabits: Int
)
