package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.HabitRecommendationUseCase].
 *
 * Carries the list of co-occurring habit names alongside a data-sufficiency flag
 * so the UI can distinguish "no related habits found" from "not enough history yet".
 *
 * @property relatedHabitNames Names of habits that frequently co-occur with the focal
 *                             habit on the same calendar day, ranked by co-occurrence
 *                             count (highest first). Empty when no related habits pass
 *                             the minimum threshold.
 * @property hasSufficientData False when the focal habit has fewer than 5 target-reached
 *                             completion dates — below this the co-occurrence signal is
 *                             unreliable and the UI should show a placeholder.
 */
data class HabitRecommendation(
    val relatedHabitNames: List<String>,
    val hasSufficientData: Boolean
)
