package com.example.evolvix.domain.model

import com.example.evolvix.data.model.HabitEntity

/**
 * Aggregated statistics for a single habit, used by [StatisticsViewModel.perHabitStats].
 *
 * Bundles the outputs of [com.example.evolvix.domain.usecase.CalculateStreakUseCase]
 * and [com.example.evolvix.domain.usecase.SparklineUseCase] for one habit so the
 * Statistics screen can render per-habit cards without holding raw completion lists.
 *
 * @property habit The source [HabitEntity] (carries name, color, frequency, categories).
 * @property streak Current and best streak counts computed over all-time completions.
 * @property sparkline30d Daily target-reached flags for the last 30 calendar days,
 *   ordered oldest → newest. Drives the default sparkline/bar chart in the Statistics UI.
 * @property completionRate30d Fraction of days in the 30-day window where the target
 *   was reached, clamped to [0.0, 1.0].
 * @property resolvedIconEmoji Emoji to display in the Statistics card header.
 *   Priority: user-assigned [HabitEntity.iconKey] → [IconResolverUseCase] auto-resolution.
 *   Always non-null so the View renders it unconditionally (no Star fallback needed).
 *
 * AI fields (Phase 6 — MathHabitPredictor):
 * @property successProbabilityToday Predicted completion probability for today, in [0.05, 0.95].
 * @property optimalHours Top 3 hours (0–23) ranked by historical completion density.
 * @property relatedHabitNames Habit names that co-occur with this habit above the threshold.
 * @property isStreakAtRisk True when the predictor detects a recurring miss pattern.
 * @property targetDelta Suggested target adjustment: +1 (too easy), −1 (too hard), 0 (ok).
 * @property motivationMessageKey String resource key resolved at the View layer via strings.xml.
 * @property routinePrecision Std-dev of completion time (minutes from midnight); null if < 5 records.
 * @property resilience Average number of periods to recover after a gap; null if no gaps observed.
 * @property resolvedCategoryEmojis Per-category emoji resolutions for this habit's [HabitEntity.categories].
 *   Each [Pair] holds the raw category key (first) and the resolved emoji (second).
 *   Priority for emoji resolution:
 *     1. [StatisticsViewModel.BUILTIN_CATEGORY_EMOJI] — deterministic for the 7 built-in keys.
 *     2. [StatisticsViewModel.CATEGORY_EMOJI] keyed by [HabitPredictor.classifyIcon] output — ML-driven for custom categories.
 *     3. `"🏷️"` fallback when no model prediction is available.
 *   The raw key is kept so the View can still call [categoryDisplayName] for localization.
 */
data class PerHabitStats(
    val habit: HabitEntity,
    val streak: StreakResult,
    val sparkline30d: List<SparklinePoint>,
    val completionRate30d: Float,
    val resolvedIconEmoji: String,
    // ── Phase 6 AI fields ────────────────────────────────────────────────────
    val successProbabilityToday: Float,
    val optimalHours: List<Int>,
    val relatedHabitNames: List<String>,
    val isStreakAtRisk: Boolean,
    val targetDelta: Int,
    val motivationMessageKey: String,
    val routinePrecision: Double?,
    val resilience: Double?,
    // ── B1: ML-driven category emoji ─────────────────────────────────────────
    val resolvedCategoryEmojis: List<Pair<String, String>> = emptyList()
)
