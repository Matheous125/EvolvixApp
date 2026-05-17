package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.OptimalTimeUseCase].
 *
 * Provides both the ranked top hours (for the "best time" label in the UI) and the
 * full 24-bucket histogram (for rendering a compact bar chart on the Statistics card).
 *
 * @property rankedHours        Top [topN] hours of day (0–23) sorted by historical
 *                              completion density, highest first.
 * @property hourlyBins         24-element list where index = hour and value = number of
 *                              target-reached completions recorded in that hour bucket.
 *                              Always has exactly 24 entries (zero-filled when no data).
 * @property hasEnoughData      False when fewer than 5 target-reached completions exist;
 *                              the UI should show a "not enough history yet" placeholder
 *                              rather than the (default-fallback) ranked hours.
 */
data class OptimalTimePrediction(
    val rankedHours: List<Int>,
    val hourlyBins: List<Int>,
    val hasEnoughData: Boolean
)
