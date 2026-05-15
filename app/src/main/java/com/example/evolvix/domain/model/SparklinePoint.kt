package com.example.evolvix.domain.model

import java.time.LocalDate

/**
 * A single data point for a per-habit sparkline or bar chart.
 *
 * Produced by [com.example.evolvix.domain.usecase.SparklineUseCase].
 * Consumed by StatisticsViewModel to drive per-habit chart views in StatisticsScreen
 * (7D / 30D / 3M / ALL range tabs).
 *
 * @property date Calendar day represented by this point.
 * @property reached Whether the habit's target was reached on [date].
 */
data class SparklinePoint(
    val date: LocalDate,
    val reached: Boolean
)
