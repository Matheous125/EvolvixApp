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
    }
}
