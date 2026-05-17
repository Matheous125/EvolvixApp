package com.example.evolvix.domain.model

/**
 * Output of [com.example.evolvix.domain.usecase.SuccessProbabilityUseCase].
 *
 * Bundles the predicted probability with the five explicit feature values that were
 * used to compute it. The StatisticsScreen can surface these features alongside the
 * probability to make the AI card explainable to the user.
 *
 * @property probability Estimated completion probability in [0.05, 0.95].
 * @property dayOfWeek   Current day of week used as input (1 = Mon, 7 = Sun).
 * @property hourOfDay   Current hour of day used as input (0–23).
 * @property currentStreak Unbroken streak length (in periods) at query time.
 * @property recentWeekRate Fraction of the last 7 calendar days where the target was reached (0.0–1.0).
 * @property habitAgeInDays Days elapsed since the habit's first ever completion; 0 if no history.
 */
data class SuccessPrediction(
    val probability: Float,
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val currentStreak: Int,
    val recentWeekRate: Float,
    val habitAgeInDays: Long
)
