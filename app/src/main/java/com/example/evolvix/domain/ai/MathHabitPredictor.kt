package com.example.evolvix.domain.ai

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.model.HabitData
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Rule-based / statistical implementation of [HabitPredictor].
 *
 * All logic is pure Kotlin — no Android SDK, no ML model, no I/O.
 * This is the Stage 1 (Phase 6) predictor. Phase 6.5 introduces [TfliteHabitPredictor],
 * which overrides the ML-backed methods and delegates the statistical ones here via
 * composition (Strategy + Dependency Inversion pattern).
 *
 * Each method is intentionally self-contained and side-effect free so it can be
 * unit-tested without mocking Android classes.
 */
class MathHabitPredictor : HabitPredictor {

    /** Hardcoded training-data medians matching the `habit_clusters.json` produced by Phase 8.4. */
    override val clusterTrainingMedians: FloatArray =
        floatArrayOf(0.55f, 79.59f, 0.60f, 118.5f, 4.36f)

    // ── Phase 6.5 — TFLite interface (rule-based fallback) ───────────────────

    /**
     * Rule-based fallback for [predictSuccess]. Mirrors the deterministic biases that
     * `generate_success_data.py` bakes into the synthetic training labels so callers
     * see qualitatively similar probabilities even when no TFLite model is loaded.
     * Result is clamped to [0.05, 0.95] (same envelope as [successProbability]).
     *
     * **R6 (2026-05-26):** Added difficulty multiplier — high [HabitFeatures.recentAvgDifficulty]
     * scales `p` down proportionally, mirroring the logit penalty in the training data.
     */
    override fun predictSuccess(features: HabitFeatures): Float {
        var p = 0.5f
        // Morning bias (mirrors training data rule).
        if (features.hourOfDay in 6..10) p += 0.25f
        // Long-streak bonus.
        if (features.currentStreak > 7) p += 0.20f
        // Low recent rate penalty.
        if (features.completionRateLast7Days < 0.3f) p -= 0.30f
        // Mature-habit bonus.
        if (features.habitAge > 30) p += 0.10f
        // Weekend evening penalty.
        if ((features.dayOfWeek == 6 || features.dayOfWeek == 7) && features.hourOfDay >= 18) {
            p -= 0.15f
        }
        // R6: difficulty multiplier — each point above neutral (3.0) reduces p by 5%;
        // each point below neutral adds 5%. Mirrors the logit penalty in the training data.
        val difficultyMultiplier = (1.0f - 0.05f * (features.recentAvgDifficulty - 3.0f)).coerceIn(0f, 1f)
        return (p * difficultyMultiplier).coerceIn(0.05f, 0.95f)
    }

    /**
     * Rule-based fallback for [findOptimalHours]: scores [predictSuccess] across all
     * 24 hours and returns the 3 hours with the highest score (deterministic tie-break
     * on lower hour index because [Iterable.sortedByDescending] is stable).
     */
    override fun findOptimalHours(features: HabitFeatures): List<Int> =
        (0..23)
            .map { h -> h to predictSuccess(features.copy(hourOfDay = h)) }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }

    /**
     * Rule-based fallback for [classifyIcon]. Reuses the substring-matching keyword
     * heuristic that the existing [com.example.evolvix.domain.usecase.IconResolverUseCase]
     * applies; on no match returns the 17th category `"other"` (matching the Python
     * label set in `generate_icon_data.py`).
     */
    override fun classifyIcon(habitName: String): String {
        val lower = habitName.lowercase()
        for ((keywords, label) in ICON_LABEL_KEYWORDS) {
            if (keywords.any { lower.contains(it) }) return label
        }
        return "other"
    }

    /**
     * Rule-based fallback for [selectReminderTemplate]. Picks one of the 15 template
     * keys in priority order, mirroring the rules used to generate Model 3's training
     * data so the fallback is qualitatively consistent with the ML model.
     *
     * **R1 (2026-05-26):** Rule 0 added — heavy snoozers (≥ 2 snoozes today) are
     * routed to `gentle_nudge_at_risk` before any other rule fires.
     *
     * **R3 (2026-05-26):** Rule 5 updated — `isAtRisk: Boolean` replaced by
     * `abandonmentProbability: Float`; threshold 0.6 mirrors the Python Rule 2/6
     * boundary used in training data generation.
     */
    override fun selectReminderTemplate(features: ReminderContext): String {
        // Rule 0 (R1): gentleness wins over celebration/urgency for heavy snoozers.
        if (features.snoozeCountToday >= 2) return "gentle_nudge_at_risk"
        if (features.targetReachedToday) return "target_smashed"
        if (features.currentStreak == 0 && features.daysSinceLastCompletion >= 7) return "cold_start"
        if (features.daysSinceLastCompletion >= 3) return "comeback_after_break"
        // R3: continuous probability threshold replaces hard isAtRisk boolean.
        if (features.abandonmentProbability >= 0.6f) return "gentle_nudge_at_risk"
        if (features.currentStreak >= 30) return "cheer_streak_milestone"
        if (features.currentStreak in 1..6) return "first_week_support"
        if (features.completionRateLast7Days >= 0.85f) return "celebrate_consistency"
        if (features.completionRateLast7Days <= 0.30f) return "recovery_encouragement"
        if (features.dayOfWeek == 6 || features.dayOfWeek == 7) return "weekend_warrior"
        if (features.hourOfDay in 6..10) return "morning_optimistic"
        if (features.hourOfDay in 19..22) return "evening_reflection"
        if (features.currentStreak >= 7) return "streak_save"
        if (features.completionRateLast7Days in 0.30f..0.60f) return "pace_yourself"
        return "quiet_encouragement"
    }

    // ── Phase 6.2 — Predictive features ──────────────────────────────────────

    /**
     * Estimates completion probability using a weighted combination of:
     * 1. Overall target-reached rate (last 30 days) — base signal.
     * 2. Day-of-week rate — how often the user completes on *this* weekday.
     * 3. Hour-of-day bias — mornings boost probability, late night reduces it.
     * 4. Streak bonus — an active streak slightly increases motivation.
     *
     * Result is clamped to [0.05, 0.95] so the UI never shows absolute certainty.
     */
    override fun successProbability(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        dayOfWeek: Int,
        hourOfDay: Int
    ): Float {
        val periodDays = habit.frequency.days
        val since = LocalDate.now().minusDays(30L)

        // Distinct dates within the last 30 days where target was reached.
        val recentTargetDates: Set<LocalDate> = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        // Base rate = reached periods / total periods in last 30 days.
        val totalPeriods = (30 / periodDays).coerceAtLeast(1)
        val baseRate = recentTargetDates.size.toFloat() / totalPeriods

        // Day-of-week completion rate over the last 8 occurrences of this weekday.
        val dayRate = dayOfWeekRate(completions, dayOfWeek, windowWeeks = 8)

        // Hour-of-day bias: mornings are positive, late night negative.
        val hourBias = when (hourOfDay) {
            in 6..10 -> 0.12f
            in 11..13 -> 0.06f
            in 14..18 -> 0.02f
            in 19..21 -> -0.05f
            in 22..23, in 0..4 -> -0.12f
            else -> 0f
        }

        // Streak bonus: moderate reward for an active streak.
        val streakBonus = when {
            habit.totalTargetReaches >= 30 -> 0.10f
            habit.totalTargetReaches >= 14 -> 0.07f
            habit.totalTargetReaches >= 7 -> 0.04f
            else -> 0f
        }

        // Weighted blend: base rate has highest weight; day rate adds local context.
        val raw = 0.50f * baseRate + 0.30f * dayRate + hourBias + streakBonus
        return raw.coerceIn(0.05f, 0.95f)
    }

    /**
     * Bins all target-reached completions by hour of day and returns the [topN] hours
     * with the highest completion count. Falls back to [listOf(8, 9, 10)] (typical
     * morning defaults) when there is insufficient history (< 5 completions).
     */
    override fun optimalHours(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        topN: Int
    ): List<Int> {
        val targetCompletions = completions.filter { it.isTargetReached }
        if (targetCompletions.size < 5) return listOf(8, 9, 10).take(topN)

        // Count completions per hour bucket.
        val hourCounts = IntArray(24)
        for (c in targetCompletions) {
            hourCounts[c.progressUpdate.hour]++
        }

        return hourCounts.indices
            .sortedByDescending { hourCounts[it] }
            .take(topN)
    }

    /**
     * Counts co-occurrences on the same calendar date between [habit] and every other
     * habit in [allHabits]. A habit is considered "related" when it shares at least
     * [MIN_CO_OCCURRENCES] dates AND its co-occurrence rate (relative to [habit]'s
     * completion dates) exceeds [CO_OCCURRENCE_RATE_THRESHOLD].
     *
     * This is a simple co-occurrence / association-rule approach (support-based).
     */
    override fun relatedHabits(
        habit: HabitData,
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>
    ): List<String> {
        // Dates where the focal habit reached its target.
        val focalDates: Set<LocalDate> = allCompletions
            .filter { it.habitId == habit.id && it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        if (focalDates.size < MIN_CO_OCCURRENCES) return emptyList()

        // Group all completion dates by habit id for efficient lookup.
        val datesByHabit: Map<Int, Set<LocalDate>> = allCompletions
            .filter { it.isTargetReached }
            .groupBy { it.habitId }
            .mapValues { (_, list) -> list.map { it.progressUpdate.toLocalDate() }.toSet() }

        return allHabits
            .filter { it.id != habit.id }
            .mapNotNull { other ->
                val otherDates = datesByHabit[other.id] ?: return@mapNotNull null
                val shared = (focalDates intersect otherDates).size
                val rate = shared.toFloat() / focalDates.size
                if (shared >= MIN_CO_OCCURRENCES && rate >= CO_OCCURRENCE_RATE_THRESHOLD) {
                    Pair(other.name, shared)
                } else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Detects streak risk by scanning the last [RISK_WINDOW_WEEKS] occurrences of each
     * weekday. If the user missed the habit on 3 or more of the last 4 occurrences of
     * the *same* weekday, the streak is considered at risk.
     *
     * For non-daily habits the check uses a simpler rolling-miss threshold.
     */
    override fun isStreakAtRisk(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Boolean {
        if (habit.frequency == HabitFrequency.Daily) {
            return isDailyStreakAtRisk(completions)
        }

        // For weekly/monthly: if the last 2 consecutive periods were both missed → at risk.
        val reachedPeriods: Set<Long> = completions
            .filter { it.isTargetReached }
            .map { toPeriodKey(it.progressUpdate.toLocalDate(), habit.frequency) }
            .toSet()

        val today = LocalDate.now()
        val currentKey = toPeriodKey(today, habit.frequency)
        val missed = (1..2).count { offset -> (currentKey - offset) !in reachedPeriods }
        return missed >= 2
    }

    /**
     * Suggests a target change based on the rolling 14-day completion rate:
     * - Rate ≥ 90 % → return +1 (habit is too easy, increase challenge).
     * - Rate ≤ 40 % → return −1 (habit is too hard, reduce target).
     * - Otherwise → return 0 (target is well-calibrated).
     *
     * Requires at least [MIN_TARGET_SAMPLE] periods of history to avoid premature advice.
     */
    override fun suggestTargetDelta(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Int {
        val periodDays = habit.frequency.days
        val windowDays = 14L
        val since = LocalDate.now().minusDays(windowDays)

        val reachedDates: Set<LocalDate> = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val totalPeriods = (windowDays / periodDays).toInt().coerceAtLeast(1)
        if (totalPeriods < MIN_TARGET_SAMPLE) return 0

        val rate = reachedDates.size.toFloat() / totalPeriods
        return when {
            rate >= 0.90f -> +1
            rate <= 0.40f -> -1
            else -> 0
        }
    }

    /**
     * Selects one of 9 motivation message keys based on a priority rule chain.
     * The returned key must exist in `res/values/strings.xml` (and its `values-pl/`
     * counterpart) so the View layer can resolve localized text with `<plurals>` support.
     *
     * Priority (highest first):
     * 1. No completions ever → "motivation_cold_start"
     * 2. Streak milestone (≥ 30 / ≥ 7) → "motivation_streak_milestone"
     * 3. At-risk day (Sunday or missed last period) → "motivation_gentle_nudge"
     * 4. Rate ≥ 90 % last week → "motivation_celebrate_consistency"
     * 5. Rate ≤ 30 % last week → "motivation_recovery_encouragement"
     * 6. Morning (6–10 AM) → "motivation_morning_optimistic"
     * 7. Evening (19–22) → "motivation_evening_reflection"
     * 8. Weekend → "motivation_weekend_warrior"
     * 9. Fallback → "motivation_quiet_encouragement"
     */
    override fun motivationMessageKey(
        habit: HabitData,
        completions: List<HabitCompletionEntity>,
        currentStreak: Int,
        dayOfWeek: Int
    ): String {
        if (completions.isEmpty()) return "motivation_cold_start"

        if (currentStreak >= 30 || currentStreak >= 7 && habit.totalTargetReaches >= 30) {
            return "motivation_streak_milestone"
        }

        val weekRate = recentRate(completions, days = 7)

        if (dayOfWeek == 7 || weekRate < 0.20f) return "motivation_gentle_nudge"
        if (weekRate >= 0.90f) return "motivation_celebrate_consistency"
        if (weekRate <= 0.30f) return "motivation_recovery_encouragement"

        val hour = java.time.LocalTime.now().hour
        if (hour in 6..10) return "motivation_morning_optimistic"
        if (hour in 19..22) return "motivation_evening_reflection"
        if (dayOfWeek in 6..7) return "motivation_weekend_warrior"

        return "motivation_quiet_encouragement"
    }

    // ── Phase 6.3 — Passive analytics ────────────────────────────────────────

    /**
     * Computes standard deviation of completion timestamps in **minutes from midnight**
     * across all completions. A low value means the user has a very consistent daily routine.
     *
     * Returns `null` when there are fewer than [MIN_PRECISION_SAMPLES] completions.
     */
    override fun computeRoutinePrecision(completions: List<HabitCompletionEntity>): Double? {
        if (completions.size < MIN_PRECISION_SAMPLES) return null

        val minutesFromMidnight = completions.map { c ->
            (c.progressUpdate.hour * 60 + c.progressUpdate.minute).toDouble()
        }
        return stddev(minutesFromMidnight)
    }

    /**
     * Computes resilience as the **average number of periods** the user took to resume
     * the habit after each gap (a missed period followed by a completed one).
     *
     * Algorithm:
     * 1. Build the sorted set of period keys where the target was reached.
     * 2. Walk consecutive reached periods; any gap > 1 period is a "miss event".
     * 3. Record the gap length (number of missed periods before resuming).
     * 4. Average all recorded gap lengths.
     *
     * Returns `null` when no recovery events are observable.
     */
    override fun computeResilience(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double? {
        val reachedKeys: List<Long> = completions
            .filter { it.isTargetReached }
            .map { toPeriodKey(it.progressUpdate.toLocalDate(), habit.frequency) }
            .distinct()
            .sorted()

        if (reachedKeys.size < 2) return null

        val gaps = mutableListOf<Long>()
        for (i in 1 until reachedKeys.size) {
            val gap = reachedKeys[i] - reachedKeys[i - 1] - 1 // missed periods between two reached ones
            if (gap > 0) gaps.add(gap)
        }

        return if (gaps.isEmpty()) null else gaps.average()
    }

    /**
     * Detects negatively-correlated habit pairs using Pearson's r over daily binary
     * completion vectors (1 if target reached that day, 0 otherwise).
     *
     * Only pairs with Pearson r < [threshold] (default −0.4) are returned as "clashing".
     * Requires at least [MIN_CLASH_SAMPLES] shared observation days between the pair.
     */
    override fun detectClashes(
        allHabits: List<HabitData>,
        allCompletions: List<HabitCompletionEntity>,
        threshold: Double
    ): List<Pair<String, String>> {
        // Build date → set-of-habitIds that were reached map.
        val reachedByDate: Map<LocalDate, Set<Int>> = allCompletions
            .filter { it.isTargetReached }
            .groupBy { it.progressUpdate.toLocalDate() }
            .mapValues { (_, list) -> list.map { it.habitId }.toSet() }

        if (reachedByDate.isEmpty()) return emptyList()

        val allDates = reachedByDate.keys.sorted()
        val habits = allHabits
        val clashes = mutableListOf<Pair<String, String>>()

        for (i in habits.indices) {
            for (j in i + 1 until habits.size) {
                val a = habits[i]
                val b = habits[j]

                // Build aligned binary vectors over the union of dates.
                val vecA = allDates.map { if (a.id in (reachedByDate[it] ?: emptySet())) 1.0 else 0.0 }
                val vecB = allDates.map { if (b.id in (reachedByDate[it] ?: emptySet())) 1.0 else 0.0 }

                if (vecA.sum() < MIN_CLASH_SAMPLES || vecB.sum() < MIN_CLASH_SAMPLES) continue

                val r = pearsonCorrelation(vecA, vecB)
                if (r < threshold) clashes.add(Pair(a.name, b.name))
            }
        }
        return clashes
    }

    /**
     * Computes the **skewness** of completion hour-of-day values across all completions.
     * Positive skew → completions cluster in the late part of the day (procrastination).
     * Negative skew → completions are front-loaded (early completer).
     *
     * Uses the standard moment-based skewness formula:
     *   skewness = (1/n) * Σ((xi − μ) / σ)³
     *
     * Returns `null` when there are fewer than [MIN_PROCRASTINATION_SAMPLES] completions.
     */
    override fun computeProcrastination(
        habit: HabitData,
        completions: List<HabitCompletionEntity>
    ): Double? {
        if (completions.size < MIN_PROCRASTINATION_SAMPLES) return null

        val hours = completions.map { it.progressUpdate.hour.toDouble() }
        val mean = hours.average()
        val sd = stddev(hours)
        if (sd == 0.0) return 0.0

        val skewness = hours.sumOf { ((it - mean) / sd).pow(3) } / hours.size
        return skewness
    }

    // ── Phase 8.1 — Habit Abandonment Predictor ───────────────────────────────

    /**
     * Rule-based fallback for abandonment probability when the TFLite model is
     * unavailable. Mirrors the logit priors baked into `generate_abandonment_data.py`
     * so the fallback and the ML model agree directionally.
     *
     * **R2 (2026-05-26):** Gap-based rules now use [adjustedDaysSinceLast], which
     * subtracts [AbandonmentFeatures.involuntarySkipDays7d] from the raw gap so that
     * SICK/TRAVELING absences do not inflate the abandonment signal — matching the
     * `adjusted_gap` logic in the Python data generator.
     *
     * Rule chain (evaluated top-to-bottom; first match wins):
     *   1. 2+ weeks of effective silence (adjusted)    → 0.95 (almost certainly quit)
     *   2. 1-week effective gap AND rate7d < 0.2       → 0.85
     *   3. Long active streak (≥14)                    → 0.05 (very unlikely to quit)
     *   4. Healthy recent rate (≥0.8) OR streak (≥7)  → 0.10
     *   5. Very low 30-day rate (<0.1)                 → 0.70
     *   6. Continuous fallback: linear blend of rates  → [0.10, 0.60]
     */
    override fun predictAbandonment(features: AbandonmentFeatures): Float {
        // R2: subtract involuntary-skip days so a 10-day illness trip does not
        // look like 10 days of disengagement. Clamped ≥ 0 to avoid negative gaps.
        val adjustedDaysSinceLast = (features.daysSinceLastCompletion - features.involuntarySkipDays7d)
            .coerceAtLeast(0)

        // Rule 1: 2+ weeks of effective silence → almost certain abandonment
        if (adjustedDaysSinceLast >= 14) return 0.95f

        // Rule 2: 1-week effective gap AND very low recent rate → strong signal
        if (adjustedDaysSinceLast >= 7 && features.completionRateLast7Days < 0.2f) return 0.85f

        // Rule 3: long active streak → very unlikely to abandon
        if (features.currentStreak >= 14) return 0.05f

        // Rule 4: excellent recent engagement → low risk
        if (features.completionRateLast7Days >= 0.8f || features.currentStreak >= 7) return 0.10f

        // Rule 5: very low 30-day rate regardless of gap
        if (features.completionRateLast30Days < 0.1f) return 0.70f

        // Rule 6: continuous blend — higher rate → lower abandonment risk
        val blendedRate = 0.5f * features.completionRateLast7Days + 0.5f * features.completionRateLast30Days
        return (0.60f - blendedRate * 0.50f).coerceIn(0.10f, 0.60f)
    }

    // ── Phase 8.2 — Streak Break Predictor ───────────────────────────────────

    /**
     * Rule-based fallback for streak-break probability when the TFLite model is
     * unavailable. Mirrors the logit priors baked into `generate_streak_break_data.py`
     * so the fallback and the ML model agree directionally.
     *
     * Rule chain (evaluated top-to-bottom; first match wins):
     *   1. Nascent streak (≤ 2) + very low rate (< 0.30)     → 0.80 (high risk)
     *   2. Widening gaps (≥ 4 d) + low rate (< 0.40)          → 0.75
     *   3. Mature streak (≥ 30) + strong rate (≥ 0.80)        → 0.05 (very safe)
     *   4. Excellent recent rate (≥ 0.85)                     → 0.10
     *   5. Healthy streak (≥ 14) + decent rate (≥ 0.60)       → 0.10
     *   6. Very low engagement (rate < 0.20)                   → 0.70
     *   7. Continuous blend: rate + streak contribution        → [0.10, 0.55]
     *   8. R5 difficulty boost: +0.15 when recentAvgDifficulty ≥ 4.0 (applied post-rules).
     *      [involuntarySkipDays7d] has no math fallback rule — TFLite-only signal.
     */
    override fun predictStreakBreak(features: StreakBreakFeatures): Float {
        val rate = features.completionRateLast7Days

        // Rules 1–7 produce a base probability; Rule 8 adjusts it afterward.
        val baseProb: Float

        // Rule 1: barely-started streak already showing poor engagement
        if (features.currentStreak <= 2 && rate < 0.30f) {
            baseProb = 0.80f
        // Rule 2: gaps are widening and engagement is low
        } else if (features.recentAvgGapDays >= 4f && rate < 0.40f) {
            baseProb = 0.75f
        // Rule 3: mature long streak with consistent rate → very safe
        } else if (features.currentStreak >= 30 && rate >= 0.80f) {
            baseProb = 0.05f
        // Rule 4: excellent recent rate regardless of streak length
        } else if (rate >= 0.85f) {
            baseProb = 0.10f
        // Rule 5: established streak with decent rate
        } else if (features.currentStreak >= 14 && rate >= 0.60f) {
            baseProb = 0.10f
        // Rule 6: very low engagement alone is a strong break signal
        } else if (rate < 0.20f) {
            baseProb = 0.70f
        // Rule 7: continuous blend — higher rate and longer streak lower the risk
        } else {
            val streakContribution = minOf(features.currentStreak, 14) / 14f * 0.15f
            baseProb = (0.55f - rate * 0.40f - streakContribution).coerceIn(0.10f, 0.55f)
        }

        // Rule 8 (R5): perceived difficulty ≥ 4.0 adds +0.15 — high effort is a leading
        // fragility indicator. involuntarySkipDays7d has no math fallback rule (TFLite-only).
        val difficultyBoost = if (features.recentAvgDifficulty >= 4.0f) 0.15f else 0.0f
        return (baseProb + difficultyBoost).coerceIn(0f, 1f)
    }

    // ── Phase 8.3 — Weekly Performance Forecaster ─────────────────────────────

    /**
     * Naive-blend fallback for next-week completion rate when the TFLite model is
     * unavailable. Mirrors the generative prior from `generate_weekly_forecast_data.py`:
     *
     *   base = 0.70 × lastWeekRate + 0.30 × mean(rateMon..rateSun)
     *
     * The 70/30 split gives more weight to the immediately-preceding week (strong
     * auto-correlation in habit behaviour) while the weekday-mean grounds the
     * prediction in the user's structural pattern across days of the week.
     * Result is clamped to [0.0, 1.0].
     */
    override fun predictWeeklyRate(features: WeeklyForecastFeatures): Float {
        val weekdayMean = (features.rateMon + features.rateTue + features.rateWed +
                features.rateThu + features.rateFri + features.rateSat + features.rateSun) / 7f
        return (0.70f * features.lastWeekRate + 0.30f * weekdayMean).coerceIn(0f, 1f)
    }

    // ── Phase 8.4 — Behavioral Clustering math fallback ──────────────────────

    /**
     * Classifies a habit's behavioral tier using a simple threshold chain on [ClusterFeatures.rate30d].
     *
     * This is the math-only fallback used when `habit_clusters.json` fails to load in
     * [TfliteHabitPredictor]. It intentionally uses only [ClusterFeatures.rate30d] so it
     * remains usable even when analytics data is sparse.
     *
     * **R4 note:** [ClusterFeatures] was extended from 5 to 7 fields in retrain R4
     * ([ClusterFeatures.voluntarySkipRate30d] and [ClusterFeatures.involuntarySkipRate30d]
     * added as features 6 & 7). The fallback deliberately ignores those two new fields —
     * rate30d alone is sufficient for a conservative 4-tier classification, and K=4 was
     * retained (sil_K5 = 0.3683 failed the K=5 silhouette gate ≥ 0.4261).
     *
     * Thresholds mirror the archetype boundaries in `generate_clustering_data.py`:
     *  - rate30d ≥ 0.85 → "effortless_routine"
     *  - rate30d ≥ 0.55 → "consistent_effort"
     *  - rate30d ≥ 0.20 → "struggling"
     *  - rate30d  < 0.20 → "dormant"
     */
    override fun classifyBehavioralCluster(features: ClusterFeatures): String = when {
        features.rate30d >= 0.85f -> "effortless_routine"
        features.rate30d >= 0.55f -> "consistent_effort"
        features.rate30d >= 0.20f -> "struggling"
        else                      -> "dormant"
    }

    // ── Phase 8.5 — Cross-Habit Spillover math fallback ──────────────────────

    /**
     * Co-occurrence-based heuristic estimating the observational lift of completing
     * habit A on habit B's same-day completion probability.
     *
     * Formula (mirrors the generative model in `generate_spillover_data.py`):
     *   base_lift      = coOccurrenceRate − rateB
     *   activity       = sqrt(rateA × rateB)   — down-weights sparse pairs
     *   gap_factor     = 1 − typicalGapHours / 24   — temporal proximity boost
     *   raw            = base_lift × activity × gap_factor
     *   result         = clip(raw × 1.6, −0.5, +0.5)
     *
     * This is an extension of the existing [relatedHabits] co-occurrence logic —
     * it reuses the same underlying signal (shared-day frequency) but adds
     * directionality and temporal proximity weighting.
     *
     * ⚠ Causal caveat: output is a correlation-based estimate, not a causal effect.
     */
    override fun predictSpillover(features: SpilloverFeatures): Float {
        val baseLift = features.coOccurrenceRate - features.rateB
        // sqrt gives a gentler down-weighting than a plain product for sparse pairs.
        val activity = kotlin.math.sqrt(features.rateA * features.rateB.toDouble()).toFloat()
        val gapFactor = 1f - (features.typicalGapHours / 24f).coerceIn(0f, 1f)
        val raw = baseLift * activity * gapFactor
        return (raw * 1.6f).coerceIn(-0.5f, 0.5f)
    }

    // ── Phase 9.1 — Reminder Effectiveness (Lift) Model ──────────────────────

    /**
     * Heuristic fallback for [predictReminderCompletion] used when TFLite is unavailable.
     *
     * Base completion probability is a weighted blend of recent-rate signals:
     *   `base = 0.4 × rate7d + 0.4 × rate30d + 0.05 × (streak ≥ 3 bonus)`
     * When [ReminderLiftFeatures.reminderSent] == 1, an additive boost is applied:
     *   `boost = 0.10 + 0.20 × (1 − rate7d)`
     * so users with weaker recent engagement receive a larger expected reminder lift —
     * mirroring the generative prior in `generate_reminder_lift_data.py`.
     */
    override fun predictReminderCompletion(features: ReminderLiftFeatures): Float {
        val rate7d  = features.completionRateLast7Days.coerceIn(0f, 1f)
        val rate30d = features.completionRateLast30Days.coerceIn(0f, 1f)
        val streakBonus = if (features.currentStreak >= 3) 0.05f else 0f
        val base = (0.4f * rate7d + 0.4f * rate30d + streakBonus).coerceIn(0f, 1f)
        return if (features.reminderSent == 1) {
            val boost = 0.10f + 0.20f * (1f - rate7d)
            (base + boost).coerceIn(0f, 1f)
        } else {
            base
        }
    }

    // ── Phase 9.2 — Snooze Disengagement Predictor ───────────────────────────

    /**
     * Rule-based fallback for [predictSnoozeDisengagement] used when TFLite is unavailable.
     *
     * Rule chain (mirrors the logit priors in `generate_snooze_disengagement_data.py`):
     *
     *   1. Heavy snooze + low engagement → CRITICAL signal (0.85)
     *      `avgSnoozeCount ≥ 2 AND rate7d < 0.3`
     *   2. High snooze frequency alone → HIGH signal (0.65)
     *      `snoozeFrequency ≥ 0.8`
     *   3. Moderate snooze + low rate → MEDIUM signal (0.50)
     *      `avgSnoozeCount ≥ 1 AND rate7d < 0.5`
     *   4. Long active streak — strong protective override (0.10)
     *      `streak ≥ 14`
     *   5. Solid recent rate — healthy baseline (0.15)
     *      `rate7d ≥ 0.7`
     *   6. Default — mild concern proportional to snooze count (0.20 … 0.35)
     */
    override fun predictSnoozeDisengagement(features: SnoozeDisengagementFeatures): Float {
        val rate7d      = features.completionRateLast7Days.coerceIn(0f, 1f)
        val avgSnooze   = features.avgSnoozeCountLast14Days.coerceIn(0f, 10f)
        val snoozeFreq  = features.snoozeFrequencyLast14Days.coerceIn(0f, 1f)
        val streak      = features.currentStreak

        return when {
            // Strongest combined signal — mirrors +3.0 logit nudge in training data
            avgSnooze >= 2f && rate7d < 0.30f -> 0.85f
            // High snooze frequency alone — mirrors +2.0 nudge
            snoozeFreq >= 0.80f               -> 0.65f
            // Long streak is a very strong protective signal — mirrors -3.0 nudge
            streak >= 14                      -> 0.10f
            // Moderate snooze + below-average rate — mirrors +1.5 + +1.0 nudges
            avgSnooze >= 1f && rate7d < 0.50f -> 0.50f
            // Solid recent rate — mirrors -2.0 nudge
            rate7d >= 0.70f                   -> 0.15f
            // Default: mild risk, linearly scaled by snooze count (0 → 0.20, 1 → 0.28, 2 → 0.35)
            else -> (0.20f + avgSnooze * 0.075f).coerceIn(0.20f, 0.35f)
        }
    }

    // ── Phase 9.3 — Target Change Effectiveness Regressor ────────────────────

    /**
     * Rule-chain fallback for [predictTargetDelta] used when TFLite is unavailable.
     *
     * Mirrors the generative priors in `generate_target_change_data.py` exactly,
     * so both implementations produce consistent recommendations at the boundary:
     *
     *   1. Strong over-completion → +2.0  (raise the bar significantly)
     *      `rate30d ≥ 0.90 AND avgProgressRatio30d ≥ 1.20 AND habitAgeDays ≥ 21`
     *   2. Moderate over-completion → +1.0  (nudge up)
     *      `rate30d ≥ 0.78 AND avgProgressRatio30d ≥ 1.02`
     *   3. Strong under-performance → -2.0  (ease significantly)
     *      `rate30d ≤ 0.22 AND avgProgressRatio30d ≤ 0.45`
     *   4. Moderate under-performance → -1.0  (ease slightly)
     *      `rate30d ≤ 0.40 AND avgProgressRatio30d ≤ 0.72`
     *   5. Default → 0.0  (target is well-calibrated)
     */
    override fun predictTargetDelta(features: TargetChangeFeatures): Float {
        val r30  = features.rate30d.coerceIn(0f, 1f)
        val apr  = features.avgProgressRatio30d.coerceIn(0f, 3f)
        val age  = features.habitAgeDays

        return when {
            r30 >= 0.90f && apr >= 1.20f && age >= 21 ->  2.0f
            r30 >= 0.78f && apr >= 1.02f              ->  1.0f
            r30 <= 0.22f && apr <= 0.45f              -> -2.0f
            r30 <= 0.40f && apr <= 0.72f              -> -1.0f
            else                                      ->  0.0f
        }
    }

    // ── Phase 9.4 — Perceived Difficulty Regressor fallback ──────────────────

    /**
     * Rule-based fallback for [predictPerceivedDifficulty] used when TFLite is unavailable.
     *
     * Formula: `5 − 4 × rate30d`, which maps the behavioral priors in
     * `generate_difficulty_data.py` onto the [1.0, 5.0] scale without a neural network:
     * - rate30d = 1.0 → predicted difficulty = 1.0 (very easy)
     * - rate30d = 0.0 → predicted difficulty = 5.0 (very hard)
     *
     * A +0.5 penalty is applied when the current streak is zero AND rate7d < 0.30,
     * matching the "struggling" cluster prior (difficulty mean ≈ 5.0) from training.
     * Result is clipped to [1.0, 5.0] before returning.
     */
    override fun predictPerceivedDifficulty(features: DifficultyFeatures): Float {
        val base = 5f - 4f * features.completionRateLast30Days.coerceIn(0f, 1f)
        val penalty = if (features.currentStreak == 0 && features.completionRateLast7Days < 0.30f) 0.5f else 0f
        return (base + penalty).coerceIn(1f, 5f)
    }

    // ── Phase 9.5 — Skip Reason Classifier (rule-based prior) ────────────────

    /**
     * Rule-based prior for [predictSkipReason].
     *
     * Mirrors the logit priors from `generate_skip_reason_data.py` but expressed as
     * unnormalized scores which are then softmax-normalised before returning. This
     * ensures the output is a valid probability distribution over all six
     * [com.example.evolvix.data.model.SkipReason] values even without a TFLite model.
     *
     * Rules (directly matching the Python generator's logit priors):
     * - TOO_TIRED  : elevated late evening / night (hour ≥ 20 or ≤ 5) and on Fri/Sun.
     * - TOO_BUSY   : elevated weekday work hours (Mon–Wed, 9–18) and weekly habits.
     * - FORGOT     : elevated for very new habits (age < 14 d) and zero-streak.
     * - SICK       : low flat prior — not predictable from behavioral features.
     * - TRAVELING  : low flat prior with mild weekend lift.
     * - NO_REASON  : moderate base; strongest when no other signal is dominant.
     *
     * This fallback intentionally reflects the *prior* distribution, not a learned
     * discriminative boundary — the TFLite model provides the refinement.
     */
    override fun predictSkipReason(
        features: SkipReasonFeatures
    ): Map<com.example.evolvix.data.model.SkipReason, Float> {
        val scores = FloatArray(6)

        // TOO_TIRED (index 0)
        scores[0] = -0.3f
        if (features.hourOfDay >= 20 || features.hourOfDay <= 5) scores[0] += 2.0f
        if (features.dayOfWeek == 5 || features.dayOfWeek == 7)  scores[0] += 1.5f
        if (features.completionRateLast7Days < 0.30f)             scores[0] += 1.0f
        if (features.hourOfDay in 8..12)                          scores[0] -= 1.5f

        // TOO_BUSY (index 1)
        scores[1] = -0.2f
        if (features.dayOfWeek in 1..3 && features.hourOfDay in 9..18) scores[1] += 2.0f
        if (features.currentStreak >= 10 && features.recentSkipRate14d > 0.2f) scores[1] += 1.5f
        if (features.frequencyOrdinal == 1)                        scores[1] += 1.0f
        if (features.dayOfWeek == 6 || features.dayOfWeek == 7)   scores[1] -= 1.0f

        // FORGOT (index 2)
        scores[2] = 0.0f
        if (features.habitAge < 14)                               scores[2] += 2.5f
        if (features.recentSkipRate14d > 0.40f)                   scores[2] += 1.5f
        if (features.currentStreak == 0)                          scores[2] += 1.0f
        if (features.hourOfDay in 0..7)                           scores[2] += 0.5f
        if (features.currentStreak >= 14)                         scores[2] -= 2.0f
        if (features.completionRateLast7Days >= 0.70f)            scores[2] -= 1.0f

        // SICK (index 3) — low flat prior; illness is not behaviorally predictable
        scores[3] = -1.5f + 0.5f
        if (features.habitAge > 180) scores[3] += 0.3f

        // TRAVELING (index 4) — lowest flat prior with weekend lift
        scores[4] = -1.8f
        if (features.dayOfWeek in 5..7)     scores[4] += 1.0f
        if (features.frequencyOrdinal >= 1) scores[4] += 0.5f
        if (features.habitAge > 90)         scores[4] += 0.3f

        // NO_REASON (index 5) — catch-all; wins when all other signals are weak
        scores[5] = 0.2f
        if (features.completionRateLast30Days in 0.35f..0.65f)     scores[5] += 1.5f
        if (features.recentSkipRate14d in 0.10f..0.40f)            scores[5] += 1.0f
        if (features.hourOfDay in 20..23)                           scores[5] -= 1.5f
        if (features.habitAge < 14)                                 scores[5] -= 1.0f

        // Softmax normalisation so values sum to 1.0
        val maxScore = scores.max()
        val expScores = FloatArray(6) { kotlin.math.exp((scores[it] - maxScore).toDouble()).toFloat() }
        val sumExp = expScores.sum()
        val probs = FloatArray(6) { expScores[it] / sumExp }

        return com.example.evolvix.data.model.SkipReason.entries
            .zip(probs.toList())
            .associate { (reason, prob) -> reason to prob }
    }

    // ── Phase 9.6 — Engagement Window Predictor (zero-model fallback) ────────

    /**
     * Zero-model fallback for [predictEngagementHour].
     *
     * Returns [EngagementWindowFeatures.recentAvgStartHour14d] directly — i.e. the
     * simple 14-day mean session-start hour already computed from Room data.
     * This is the best single-number estimate without a trained model and is used:
     *   (a) when `engagement_window_regressor.tflite` fails to load, and
     *   (b) in [MathHabitPredictor] where no TFLite interpreter is present.
     *
     * The returned value is clamped to [0.0, 23.0] to stay within a valid hour range.
     */
    override fun predictEngagementHour(features: EngagementWindowFeatures): Float =
        features.recentAvgStartHour14d.coerceIn(0f, 23f)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the fraction of target-reached days in the last [days] calendar days.
     * Used internally for message key selection and other rate checks.
     */
    private fun recentRate(completions: List<HabitCompletionEntity>, days: Int): Float {
        val since = LocalDate.now().minusDays(days.toLong())
        val reachedDates = completions
            .filter { it.isTargetReached && it.progressUpdate.toLocalDate() >= since }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()
        return reachedDates.size.toFloat() / days
    }

    /**
     * Returns the fraction of the last [windowWeeks] occurrences of [dayOfWeek] (1=Mon, 7=Sun)
     * on which the habit target was reached. Used for day-specific probability adjustment.
     */
    private fun dayOfWeekRate(
        completions: List<HabitCompletionEntity>,
        dayOfWeek: Int,
        windowWeeks: Int
    ): Float {
        val today = LocalDate.now()
        // Build the list of past dates that fell on this weekday (going back windowWeeks weeks).
        val targetDates: List<LocalDate> = (1..windowWeeks).map { w ->
            today.minusWeeks(w.toLong()).with(java.time.DayOfWeek.of(dayOfWeek))
        }

        val reachedDates: Set<LocalDate> = completions
            .filter { it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val reached = targetDates.count { it in reachedDates }
        return reached.toFloat() / targetDates.size
    }

    /**
     * Returns true when a daily habit has missed the same weekday in 3 of the last 4
     * occurrences — the canonical "streak at risk" signal for daily habits.
     */
    private fun isDailyStreakAtRisk(completions: List<HabitCompletionEntity>): Boolean {
        val reachedDates: Set<LocalDate> = completions
            .filter { it.isTargetReached }
            .map { it.progressUpdate.toLocalDate() }
            .toSet()

        val today = LocalDate.now()
        // Check each day of week separately over the last 4 occurrences.
        for (dow in 1..7) {
            val occurrences = (1..4).map { w ->
                today.minusWeeks(w.toLong()).with(java.time.DayOfWeek.of(dow))
            }
            val missed = occurrences.count { it !in reachedDates }
            if (missed >= 3) return true
        }
        return false
    }

    /**
     * Maps a [LocalDate] to a monotonically increasing period key based on [frequency].
     * Consecutive periods always differ by exactly 1, so streak adjacency = key diff of 1.
     * Mirrors the same logic used in [CalculateStreakUseCase].
     */
    private fun toPeriodKey(date: LocalDate, frequency: HabitFrequency): Long {
        val epoch = LocalDate.ofEpochDay(0)
        return when (frequency) {
            HabitFrequency.Daily -> ChronoUnit.DAYS.between(epoch, date)
            HabitFrequency.Weekly -> ChronoUnit.WEEKS.between(epoch, date)
            HabitFrequency.Monthly -> ChronoUnit.MONTHS.between(epoch, date)
            HabitFrequency.Yearly -> ChronoUnit.YEARS.between(epoch, date)
        }
    }

    /** Population standard deviation of a list of doubles. Returns 0.0 for single-element lists. */
    private fun stddev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean).pow(2) } / values.size
        return sqrt(variance)
    }

    /** Pearson correlation coefficient for two equal-length double lists. Returns 0.0 if stddev is zero. */
    private fun pearsonCorrelation(a: List<Double>, b: List<Double>): Double {
        val n = a.size
        if (n == 0) return 0.0
        val meanA = a.average()
        val meanB = b.average()
        val cov = a.indices.sumOf { (a[it] - meanA) * (b[it] - meanB) } / n
        val sdA = stddev(a)
        val sdB = stddev(b)
        return if (sdA == 0.0 || sdB == 0.0) 0.0 else cov / (sdA * sdB)
    }

    companion object {
        /** Minimum history required before [computeRoutinePrecision] returns a value. */
        private const val MIN_PRECISION_SAMPLES = 5

        /** Minimum completions per habit for it to appear in [detectClashes] vectors. */
        private const val MIN_CLASH_SAMPLES = 5.0

        /** Minimum completions before [computeProcrastination] yields a result. */
        private const val MIN_PROCRASTINATION_SAMPLES = 10

        /** Minimum shared completion count for [relatedHabits] to flag a relationship. */
        private const val MIN_CO_OCCURRENCES = 5

        /** Minimum co-occurrence rate (0–1) for [relatedHabits] to include a habit. */
        private const val CO_OCCURRENCE_RATE_THRESHOLD = 0.30f

        /** Minimum number of periods in the 14-day window before [suggestTargetDelta] gives advice. */
        private const val MIN_TARGET_SAMPLE = 5

        /**
         * Keyword → ML icon-category label mapping. Kept private to this class because
         * it is intentionally narrower than [com.example.evolvix.domain.usecase.IconResolverUseCase]'s
         * emoji map: here we emit the 17 LABEL strings used by Model 2
         * (`generate_icon_data.py`) so the fallback output is interchangeable with the
         * TFLite classifier's output.
         */
        private val ICON_LABEL_KEYWORDS: List<Pair<List<String>, String>> = listOf(
            listOf("meditat", "mindful", "breath", "gratitude", "pray", "yoga") to "mindfulness",
            listOf("sleep", "nap", "bedtime") to "sleep",
            listOf("run", "jog", "gym", "workout", "exercise", "push", "squat", "cycling", "bike", "swim", "lift", "cardio", "fitness") to "fitness",
            listOf("drink water", "hydrat", "vitamin", "medicine", "floss", "brush teeth", "stretch", "posture", "doctor") to "health",
            listOf("read", "book", "chapter", "article", "novel") to "reading",
            listOf("writ", "journal", "diary", "blog", "essay") to "writing",
            listOf("study", "learn", "course", "lesson", "language", "duolingo") to "learning",
            listOf("draw", "paint", "sketch", "design", "photo", "art") to "creative",
            listOf("call mom", "call dad", "friend", "family", "social", "text") to "social",
            listOf("plan", "task", "todo", "email", "inbox", "review") to "productivity",
            listOf("budget", "expense", "save", "invest", "finance", "money") to "finance",
            listOf("cook", "meal", "breakfast", "lunch", "dinner", "fruit", "veg") to "food",
            listOf("clean", "tidy", "dishes", "laundry", "vacuum") to "cleaning",
            listOf("walk", "garden", "plant", "outdoor", "nature", "hike") to "nature",
            listOf("dog", "cat", "pet", "feed pet") to "pet",
            listOf("guitar", "piano", "sing", "music", "practice instrument") to "music"
        )
    }
}
