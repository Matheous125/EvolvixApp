package com.example.evolvix.domain.ai

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.model.HabitData

/**
 * Strategy abstraction for the AI/analytics layer.
 *
 * All predictive features and passive analytics are routed through this interface,
 * enabling the **Strategy + Dependency Inversion** pattern: callers (ViewModels, use cases)
 * depend only on this interface, never on a concrete implementation.
 *
 * Current implementations:
 * - [MathHabitPredictor] — pure Kotlin rule-based / statistical engine (Phase 6).
 * - [TfliteHabitPredictor] — TFLite ML override of selected methods (Phase 6.5).
 *
 * Phase 6.5 will expand this interface with `predictSuccess`, `findOptimalHours`,
 * `classifyIcon`, and `selectReminderTemplate` after the model data classes are defined.
 */
interface HabitPredictor {

    // ── Phase 6.2 — Predictive features ──────────────────────────────────────

    /**
     * Estimates the probability (0.0–1.0) that [habit] will be completed successfully
     * given the current [dayOfWeek] (1 = Mon, 7 = Sun), [hourOfDay] (0–23), and its
     * recent [completions] history.
     *
     * Used by: `🎯 Success Prediction` card in [StatisticsScreen].
     */
    fun successProbability(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        dayOfWeek: Int,
        hourOfDay: Int
    ): Float

    /**
     * Returns the top [topN] hours of the day (0–23) at which the user is most likely
     * to complete [habit], ranked by historical completion density.
     *
     * Used by: `🕒 Optimal Timing` card in [StatisticsScreen].
     */
    fun optimalHours(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        topN: Int = 3
    ): List<Int>

    /**
     * Returns habit names from [allHabits] that frequently co-occur with [habit] on the
     * same day, based on [allCompletions]. Empty list if there is insufficient data.
     *
     * Used by: `🧠 Behavioral Patterns` card in [StatisticsScreen].
     */
    fun relatedHabits(
        habit: HabitData,
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>
    ): List<String>

    /**
     * Returns `true` when [habit] is at risk of breaking its current streak based on
     * recent [completions] patterns (e.g. consistently missing a specific day of week).
     *
     * Used by: `✨ Smart Insight` card in [StatisticsScreen].
     */
    fun isStreakAtRisk(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Boolean

    /**
     * Suggests whether to increase or decrease the daily [HabitData.target] for [habit].
     * Returns a positive delta to increase, negative to decrease, or 0 to keep as-is.
     *
     * Used by: `✨ Smart Insight` card in [StatisticsScreen].
     */
    fun suggestTargetDelta(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Int

    /**
     * Returns a context-aware motivation message key (resolved via `strings.xml`) for [habit]
     * based on its current streak, recent completion rate, and [dayOfWeek].
     *
     * The returned value is a string resource key, not user-visible text directly,
     * so Polish/English plurals are honored at the View layer.
     *
     * Used by: habit cards in [MainScreen] and reminder notifications (Phase 7).
     */
    fun motivationMessageKey(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        dayOfWeek: Int
    ): String

    // ── Phase 6.3 — Passive analytics ────────────────────────────────────────

    /**
     * Computes the routine precision of [habit] as the standard deviation (in minutes)
     * of completion timestamps within each period. Lower = more consistent routine.
     *
     * Returns `null` when there are fewer than 5 completions (insufficient data).
     */
    fun computeRoutinePrecision(
        completions: List<HabitCompletionEntity>
    ): Double?

    /**
     * Computes the resilience score of [habit] as the average number of days it took
     * to resume after a missed period. Lower = bounces back faster.
     *
     * Returns `null` when there are no observable recovery events.
     */
    fun computeResilience(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double?

    /**
     * Detects pairs of habits from [allHabits] whose completion patterns are negatively
     * correlated (Pearson r < [threshold]), suggesting they "clash" for the user's time.
     *
     * Returns a list of clashing name pairs; empty if none found or data is insufficient.
     */
    fun detectClashes(
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>,
        threshold: Double = -0.4
    ): List<Pair<String, String>>

    /**
     * Computes the procrastination index of [habit] as the skewness of completion
     * timestamps within each period cycle (e.g. completions clustered at end of day).
     * Positive skew = procrastinating; negative skew = early completer.
     *
     * Returns `null` when there are fewer than 10 completions.
     */
    fun computeProcrastination(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double?
}
