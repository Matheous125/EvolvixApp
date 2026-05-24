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

    // ── Phase 6.5 — TFLite ML interface ──────────────────────────────────────

    /**
     * Returns the probability (0.0 … 1.0) that the user will successfully complete the
     * habit, given a pre-computed [HabitFeatures] vector.
     *
     * Backed by `habit_success_classifier.tflite` in [TfliteHabitPredictor];
     * [MathHabitPredictor] provides a rule-based fallback so the interface remains
     * implementable without TFLite (Strategy + Dependency Inversion).
     */
    fun predictSuccess(features: HabitFeatures): Float

    /**
     * Returns the top 3 hours of the day (0–23) at which the user is most likely to
     * complete the habit, computed by scoring [predictSuccess] across all 24 hours and
     * keeping the highest-scoring slots.
     */
    fun findOptimalHours(features: HabitFeatures): List<Int>

    /**
     * Classifies [habitName] into one of the 17 icon categories defined in
     * `ml-training/generate_icon_data.py` (`fitness`, `health`, `learning`, …, `other`).
     *
     * Backed by `habit_icon_classifier.tflite` in [TfliteHabitPredictor];
     * [MathHabitPredictor] returns a default category as fallback.
     */
    fun classifyIcon(habitName: String): String

    /**
     * Selects a notification / motivation template key (one of 15 categories defined in
     * `ml-training/generate_reminder_data.py`) given a [ReminderContext].
     *
     * The returned key is resolvable in `strings.xml` so the View layer can honor
     * Polish/English plurals at render time.
     */
    fun selectReminderTemplate(features: ReminderContext): String

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

    // ── Phase 8.1 — Habit Abandonment Predictor ───────────────────────────────

    /**
     * Returns the probability (0.0 … 1.0) that the habit will receive **zero completions
     * in the next 14 days**, given a pre-computed [AbandonmentFeatures] vector.
     *
     * Backed by `habit_abandonment_classifier.tflite` in [TfliteHabitPredictor];
     * [MathHabitPredictor] provides a rule-based fallback (Strategy pattern).
     *
     * Callers should map the raw probability to [AbandonmentRisk.Rating] via
     * [AbandonmentRisk.ratingFor] rather than thresholding the float directly.
     */
    fun predictAbandonment(features: AbandonmentFeatures): Float

    // ── Phase 8.2 — Streak Break Predictor ───────────────────────────────────

    /**
     * Returns the probability (0.0 … 1.0) that the habit's **active streak will end**
     * within the next N periods (N = 3 for daily, N = 2 for weekly), given a
     * pre-computed [StreakBreakFeatures] vector.
     *
     * Backed by `streak_break_classifier.tflite` in [TfliteHabitPredictor];
     * [MathHabitPredictor] provides a rule-based fallback that mirrors the logit
     * priors from `generate_streak_break_data.py` (Strategy pattern).
     *
     * This method must only be called when the habit has an active streak (> 0).
     * [com.example.evolvix.domain.usecase.StreakBreakUseCase] enforces this guard
     * and returns a safe LOW result with [com.example.evolvix.domain.model.StreakBreakRisk.hasSufficientData]
     * = false when the streak is zero or data is insufficient.
     *
     * Callers should map the raw probability to [com.example.evolvix.domain.model.StreakBreakRisk.Rating]
     * via [com.example.evolvix.domain.model.StreakBreakRisk.ratingFor] rather than
     * thresholding the float directly.
     */
    fun predictStreakBreak(features: StreakBreakFeatures): Float
}
