package com.example.evolvix.domain.model

/**
 * Completion rate for a single life-balance category over an analysis window.
 *
 * Produced by [com.example.evolvix.domain.usecase.LifeBalanceUseCase].
 * Consumed by StatisticsViewModel to drive the "Life Balance" card in StatisticsScreen.
 *
 * @property category The category label (e.g. "Health", "Fitness"). Habits with no category
 *   are grouped under the synthetic "Other" bucket.
 * @property completionRate Fraction of expected completions achieved in the window,
 *   clamped to [0.0, 1.0]. Expected = habitCount × windowDays.
 * @property habitCount Number of habits assigned to this category.
 */
data class LifeBalanceEntry(
    val category: String,
    val completionRate: Float,
    val habitCount: Int
)
