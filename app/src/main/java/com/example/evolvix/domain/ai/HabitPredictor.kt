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

    // ── Phase 8.3 — Weekly Performance Forecaster ─────────────────────────────

    /**
     * Predicts the user's overall habit-completion rate for the **next 7 days**,
     * given a pre-computed [WeeklyForecastFeatures] vector aggregated across all
     * active habits.
     *
     * This is a **regression** output: the returned value is in [0.0, 1.0] (not a
     * class probability), representing the predicted fraction of (habit × day) pairs
     * that will be completed next week.
     *
     * Backed by `weekly_forecast_regressor.tflite` in [TfliteHabitPredictor];
     * [MathHabitPredictor] provides a naive-blend fallback:
     * `0.7 × lastWeekRate + 0.3 × mean(weekday rates)`.
     *
     * Callers should wrap the raw output in a [com.example.evolvix.domain.model.WeeklyForecast]
     * via [com.example.evolvix.domain.usecase.WeeklyForecastUseCase], which adds the
     * direction indicator, confidence score, and data-sufficiency flag.
     */
    fun predictWeeklyRate(features: WeeklyForecastFeatures): Float

    // ── Phase 8.4 — Habit Behavioral Clustering ───────────────────────────────

    /**
     * Classifies a habit's behavioral pattern into one of four K-Means tiers and
     * returns the matching label key from `habit_clusters.json`:
     *  - `"effortless_routine"` — high rate30d, consistent timing, mature habit.
     *  - `"consistent_effort"`  — good rate30d, moderate timing variability.
     *  - `"struggling"`         — low-to-moderate rate30d, chaotic timing.
     *  - `"dormant"`            — very low rate30d, near-zero engagement.
     *
     * Unlike the TFLite-backed methods, this method has **no neural network**.
     * [TfliteHabitPredictor] performs a nearest-centroid lookup in standardized
     * feature space using centroids loaded from `habit_clusters.json`. There is no
     * `.tflite` interpreter involved (Strategy + Dependency Inversion still applies:
     * [MathHabitPredictor] provides a rate30d threshold fallback).
     *
     * Null analytics ([ClusterFeatures.routinePrecisionStddev],
     * [ClusterFeatures.resilienceAvgGap]) must be substituted with training medians
     * by the caller ([BehavioralClusterUseCase]) **before** invoking this method,
     * so both implementations always receive a fully-populated [ClusterFeatures].
     *
     * The raw key is resolved to a typed [com.example.evolvix.domain.model.BehavioralCluster]
     * by [com.example.evolvix.domain.usecase.BehavioralClusterUseCase] via
     * [com.example.evolvix.domain.model.BehavioralCluster.fromKey].
     */
    fun classifyBehavioralCluster(features: ClusterFeatures): String

    /**
     * Per-feature training-data medians from `habit_clusters.json`.
     * Read by [com.example.evolvix.domain.usecase.BehavioralClusterUseCase] to
     * substitute missing analytics values before calling [classifyBehavioralCluster].
     * Index order mirrors [ClusterFeatures.toFloatArray]: rate30d, routinePrecisionStddev,
     * procrastinationSkew, habitAge, resilienceAvgGap.
     */
    val clusterTrainingMedians: FloatArray

    // ── Phase 8.5 — Cross-Habit Spillover Model ───────────────────────────────

    /**
     * Predicts the *observational lift* on habit B's same-day completion probability
     * given that habit A was completed at a specific hour today, as encoded in
     * [features].
     *
     * The returned value is in **[-0.5, +0.5]**:
     * - Positive → A's completion is associated with a higher chance of B being done.
     * - Negative → A's completion is associated with a lower chance of B (time-crowding).
     * - Near 0   → no meaningful association.
     *
     * ⚠ **Causal caveat:** This is a *predicted lift estimate* based on historical
     * co-occurrence patterns, NOT a causal treatment effect. Confounders (high-energy
     * days, free days) can inflate observed co-occurrence independently of any A→B
     * mechanism. The output should be presented with hedged language in the UI.
     *
     * Backed by `spillover_regressor.tflite` (tanh × 0.5 output layer, MAE-trained)
     * in [TfliteHabitPredictor]; [MathHabitPredictor] provides a co-occurrence-based
     * heuristic fallback extending the existing [relatedHabits] logic.
     *
     * Callers should wrap the raw float in a [com.example.evolvix.domain.model.SpilloverPair]
     * via [com.example.evolvix.domain.usecase.SpilloverUseCase], which adds direction
     * classification and filters out NEUTRAL pairs.
     */
    fun predictSpillover(features: SpilloverFeatures): Float

    // ── Phase 9.1 — Reminder Effectiveness (Lift) Model ──────────────────────

    /**
     * Returns the predicted completion probability (0.0 … 1.0) for a given habit
     * context, with [ReminderLiftFeatures.reminderSent] acting as the treatment variable.
     *
     * At inference time [com.example.evolvix.domain.usecase.ReminderEffectivenessUseCase]
     * calls this method **twice** — once with [reminderSent=0] and once with
     * [reminderSent=1] — and computes lift = P(sent=1) − P(sent=0).
     * A reminder is suppressed when `lift < SUPPRESS_THRESHOLD` and the habit has
     * enough data history ([ReminderEffectivenessUseCase.MIN_COMPLETIONS]).
     *
     * ⚠ **Thesis note:** The returned probability is a *predicted lift estimator*,
     * NOT a causal treatment effect. It should be presented as "predicted lift" rather
     * than "causal effect recovery" in all thesis documentation.
     *
     * Backed by `reminder_lift_classifier.tflite` (8-feature MLP, sigmoid output)
     * in [TfliteHabitPredictor]; [MathHabitPredictor] provides a rate-based
     * heuristic fallback (Strategy + Dependency Inversion).
     */
    fun predictReminderCompletion(features: ReminderLiftFeatures): Float

    // ── Phase 9.2 — Snooze Disengagement Predictor ───────────────────────────

    /**
     * Returns the probability (0.0 … 1.0) that the habit will receive **zero completions
     * in the next 7 days**, given a pre-computed [SnoozeDisengagementFeatures] vector.
     *
     * This is a shorter-horizon early-warning signal than [predictAbandonment] (14 days).
     * The distinguishing features are [SnoozeDisengagementFeatures.avgSnoozeCountLast14Days]
     * and [SnoozeDisengagementFeatures.snoozeFrequencyLast14Days], which capture the
     * snooze-drift pattern before it escalates into full abandonment.
     *
     * ⚠ **Thesis note:** The returned value is a *predicted disengagement risk* based on
     * observational snooze data, NOT a causal effect of snoozing. Present accordingly.
     *
     * Backed by `snooze_disengagement_classifier.tflite` (7-feature MLP, sigmoid output)
     * in [TfliteHabitPredictor]; [MathHabitPredictor] provides a rule-based fallback
     * (Strategy + Dependency Inversion).
     *
     * Callers should map the raw probability to [com.example.evolvix.domain.model.SnoozeDisengagementRisk.Rating]
     * via [com.example.evolvix.domain.model.SnoozeDisengagementRisk.ratingFor] rather
     * than thresholding the float directly.
     */
    fun predictSnoozeDisengagement(features: SnoozeDisengagementFeatures): Float

    // ── Phase 9.3 — Target Change Effectiveness Regressor ────────────────────

    /**
     * Predicts the optimal target delta (continuous ∈ [-2.0, +2.0]) for a single habit,
     * given a pre-computed [TargetChangeFeatures] vector.
     *
     * The raw float represents how many repetitions the target should shift:
     * - Positive → user is consistently over-completing; raise the bar.
     * - Negative → user is struggling; ease the target.
     * - Near 0   → target is well-calibrated.
     *
     * The caller ([com.example.evolvix.domain.usecase.TargetAdjustmentUseCase]) rounds
     * the output to the nearest integer in {-2, -1, 0, +1, +2} and wraps it in a
     * [com.example.evolvix.domain.model.TargetAdjustment] together with confidence and
     * the suggested concrete target value.
     *
     * ⚠ **Causal caveat:** This is an *observational recommender*, not a counterfactual
     * treatment-effect estimator. The model predicts which target correlates with
     * sustained high performance given the current habit state.
     *
     * Backed by `target_change_regressor.tflite` (8-feature MLP, tanh×2 output layer)
     * in [TfliteHabitPredictor]; [MathHabitPredictor] provides a rule-chain fallback
     * that mirrors the training priors from `generate_target_change_data.py`
     * (Strategy + Dependency Inversion).
     */
    fun predictTargetDelta(features: TargetChangeFeatures): Float

    // ── Phase 9.4 — Perceived Difficulty Regressor ────────────────────────────

    /**
     * Predicts the user's **subjective difficulty rating** (continuous ∈ [1.0, 5.0])
     * for completing the habit given the current context encoded in [features].
     *
     * The returned value mirrors the model's sigmoid×4+1 output layer:
     * - 1.0 → very easy (thriving: long streak, high rate30d).
     * - 5.0 → very hard (struggling: zero streak, low rate7d).
     *
     * ⚠ **Observational caveat (thesis):** The model is trained on *synthetic* labels
     * derived from behavioral priors (completion rates, streak length) and predicts
     * *expected self-reported difficulty*, not objective task complexity. Present as
     * "predicted perceived difficulty" in all thesis documentation.
     *
     * The caller ([com.example.evolvix.domain.usecase.DifficultyEstimateUseCase]) wraps
     * the raw float in a [com.example.evolvix.domain.model.PerceivedDifficultyEstimate]
     * which adds [rounded], [rating], and the user-sourced [recentAvgRated] average.
     *
     * Backed by `perceived_difficulty_regressor.tflite` (8-feature MLP, sigmoid→[1,5])
     * in [TfliteHabitPredictor]; [MathHabitPredictor] provides a rule-based fallback
     * (`5 − 4 × rate30d`, clipped to [1,5]) (Strategy + Dependency Inversion).
     */
    fun predictPerceivedDifficulty(features: DifficultyFeatures): Float

    // ── Phase 9.5 — Skip Reason Classifier ────────────────────────────────────

    /**
     * Returns a probability distribution over the six [com.example.evolvix.data.model.SkipReason]
     * classes, given a pre-computed [SkipReasonFeatures] vector.
     *
     * The returned map contains exactly one entry per [SkipReason] enum constant.
     * Values are softmax probabilities in [0.0, 1.0] summing to ≈ 1.0.
     * The caller ([com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase]) wraps
     * the map in a [com.example.evolvix.domain.model.SkipReasonPrediction] via
     * [com.example.evolvix.domain.model.SkipReasonPrediction.fromSoftmax].
     *
     * Returning the **full distribution** (not just argmax) is intentional:
     * [com.example.evolvix.data.model.SkipReason.SICK] and
     * [com.example.evolvix.data.model.SkipReason.TRAVELING] are inherently unpredictable
     * from behavioral features, so when confidence is low the View layer can present all
     * reason chips without a pre-selected highlight (see
     * [com.example.evolvix.domain.model.SkipReasonPrediction.LOW_CONFIDENCE_THRESHOLD]).
     *
     * ⚠ **Thesis note (observational caveat):** Predictions reflect learned
     * associations between context features (hour, day, rate) and past skip reasons —
     * not causal explanations of why skipping occurs. Present as "predicted skip reason
     * given current context."
     *
     * ⚠ **Noise-class caveat:** SICK and TRAVELING have low per-class F1 (~0.05–0.15)
     * because illness and travel cannot be anticipated from behavioral features. This is
     * expected and correct; a high-uncertainty output for those two classes is the model
     * behaving as designed, not a defect.
     *
     * Backed by `skip_reason_classifier.tflite` (8-feature MLP, 6-way softmax output)
     * in [TfliteHabitPredictor]; [MathHabitPredictor] provides a rule-based prior
     * returning a probability map without a neural network (Strategy + Dependency Inversion).
     */
    fun predictSkipReason(features: SkipReasonFeatures): Map<com.example.evolvix.data.model.SkipReason, Float>
}
