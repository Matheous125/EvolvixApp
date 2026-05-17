package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SuccessPrediction
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Use Case / Interactor that estimates the probability of a habit being completed
 * successfully on the current day and hour.
 *
 * Responsibility: extract the five explicit feature values (day, hour, streak,
 * recentWeekRate, habitAge) from raw domain objects and delegate the actual
 * probability computation to [HabitPredictor] (Strategy + Dependency Inversion pattern).
 * This separation means the ViewModel never reaches into prediction math directly.
 *
 * Input features computed here:
 * - **day**        — ISO day-of-week of [now] (1 = Monday, 7 = Sunday).
 * - **hour**       — Hour of [now] in 24-hour format (0–23).
 * - **streak**     — Current unbroken streak computed via [CalculateStreakUseCase].
 * - **recentWeek** — Fraction of the last 7 calendar days where the target was reached.
 * - **age**        — Days since the habit's first ever recorded completion.
 *
 * @param predictor          Strategy implementation of [HabitPredictor]; injectable so
 *                           [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 * @param calculateStreak    Shared streak use case — reused to avoid duplicate logic.
 */
class SuccessProbabilityUseCase(
    private val predictor: HabitPredictor,
    private val calculateStreak: CalculateStreakUseCase = CalculateStreakUseCase()
) {

    /**
     * Computes a [SuccessPrediction] for [habit] given its [completions] history.
     *
     * @param habit       Domain model of the habit to evaluate.
     * @param completions All historical completion records for this habit.
     * @param now         Reference timestamp (defaults to system clock; injectable for testing).
     * @return [SuccessPrediction] containing the probability and all feature values used.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        now: LocalDateTime = LocalDateTime.now()
    ): SuccessPrediction {
        val today = now.toLocalDate()

        // Feature 1: day of week (1 = Mon, 7 = Sun) — ISO standard used throughout the project.
        val dayOfWeek = now.dayOfWeek.value

        // Feature 2: hour of day (0–23) — determines morning/evening bias in predictor.
        val hourOfDay = now.hour

        // Feature 3: current streak — delegate to the canonical streak use case so there
        // is a single source of truth for streak arithmetic across the whole codebase.
        val currentStreak = calculateStreak(completions, habit.frequency, today).current

        // Feature 4: recent-week completion rate — fraction of the last 7 calendar days
        // on which the habit reached its target at least once.
        val sevenDaysAgo = today.minusDays(7)
        val recentCompletedDays = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= sevenDaysAgo }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()
            .size
        val recentWeekRate = recentCompletedDays.toFloat() / 7f

        // Feature 5: habit age — days since first ever completion; 0 when there is no history.
        // Older habits tend to be more stable, which the predictor factors in.
        val habitAgeInDays = completions
            .minOfOrNull { it.progressUpdate.toLocalDate() }
            ?.let { ChronoUnit.DAYS.between(it, today) }
            ?: 0L

        // Delegate probability computation to the injected predictor (Strategy pattern).
        val probability = predictor.successProbability(habit, completions, dayOfWeek, hourOfDay)

        return SuccessPrediction(
            probability = probability,
            dayOfWeek = dayOfWeek,
            hourOfDay = hourOfDay,
            currentStreak = currentStreak,
            recentWeekRate = recentWeekRate,
            habitAgeInDays = habitAgeInDays
        )
    }
}
