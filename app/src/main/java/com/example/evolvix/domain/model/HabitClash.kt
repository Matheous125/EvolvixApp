package com.example.evolvix.domain.model

/**
 * Output model of [HabitClashingUseCase].
 *
 * Represents a pair of habits whose daily completion patterns are negatively correlated
 * (Pearson r below the detection threshold), suggesting they compete for the user's
 * time or motivation on the same day.
 *
 * Displayed on the Statistics screen under the `🧠 Behavioral Patterns` card.
 *
 * @param habitNameA Name of the first clashing habit.
 * @param habitNameB Name of the second clashing habit.
 */
data class HabitClash(
    val habitNameA: String,
    val habitNameB: String
)
