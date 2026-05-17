package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.MotivationMessage
import java.time.LocalDateTime

/**
 * Use Case / Interactor that selects a context-aware motivation message key for a habit.
 *
 * Responsibility: extract the contextual inputs (current streak, day of week) from raw
 * domain objects, then delegate the template selection to [HabitPredictor] (Strategy +
 * Dependency Inversion pattern). The result drives habit cards in MainScreen and the
 * `✨ Smart Insight` card in StatisticsScreen.
 *
 * The 9 message keys returned by [HabitPredictor.motivationMessageKey] map to
 * `<plurals>` entries in `res/values/strings.xml` (and `values-pl/`). The View layer
 * resolves the key using `pluralStringResource(...)` with [MotivationMessage.streak]
 * as the quantity argument — this is why the use case returns the streak count
 * alongside the key rather than pre-formatting a string (doing so here would bypass
 * Android's plurals mechanism and break Polish inflection).
 *
 * The 9 template priorities (highest first, implemented in [MathHabitPredictor]):
 * 1. No completions ever         → "motivation_cold_start"
 * 2. Long active streak          → "motivation_streak_milestone"
 * 3. At-risk day / very low rate → "motivation_gentle_nudge"
 * 4. Rate ≥ 90 % last week       → "motivation_celebrate_consistency"
 * 5. Rate ≤ 30 % last week       → "motivation_recovery_encouragement"
 * 6. Morning (6–10 AM)           → "motivation_morning_optimistic"
 * 7. Evening (19–22)             → "motivation_evening_reflection"
 * 8. Weekend                     → "motivation_weekend_warrior"
 * 9. Fallback                    → "motivation_quiet_encouragement"
 *
 * @param predictor       Strategy implementation of [HabitPredictor]; injectable so
 *                        [MathHabitPredictor] and [TfliteHabitPredictor] are interchangeable.
 * @param calculateStreak Shared streak use case — reused to keep streak arithmetic in
 *                        one place (Single Source of Truth for streak math).
 */
class MotivationMessageUseCase(
    private val predictor: HabitPredictor,
    private val calculateStreak: CalculateStreakUseCase = CalculateStreakUseCase()
) {

    /**
     * Selects a [MotivationMessage] for [habit] given its [completions] history.
     *
     * @param habit       Domain model of the habit to generate a message for.
     * @param completions All historical completion records for this habit.
     * @param now         Reference timestamp (defaults to system clock; injectable for testing).
     * @return [MotivationMessage] with the resource key, streak count, and day-of-week.
     */
    operator fun invoke(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        now: LocalDateTime = LocalDateTime.now()
    ): MotivationMessage {
        // Feature: day of week (1 = Mon, 7 = Sun) — drives weekend and Sunday risk rules.
        val dayOfWeek = now.dayOfWeek.value

        // Feature: current streak — delegate to the canonical streak use case so there
        // is a single source of truth for streak arithmetic across the codebase.
        val currentStreak = calculateStreak(completions, habit.frequency, now.toLocalDate()).current

        // Delegate template selection to the injected predictor (Strategy pattern).
        // The predictor applies its own priority rule chain using the two features above
        // plus the recent completion rate it computes from the completions list.
        val messageKey = predictor.motivationMessageKey(habit, completions, currentStreak, dayOfWeek)

        return MotivationMessage(
            messageKey = messageKey,
            streak = currentStreak,
            dayOfWeek = dayOfWeek
        )
    }
}
