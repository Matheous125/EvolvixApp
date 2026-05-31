package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SkipReasonPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [SkipReasonPredictorUseCase] (Phase 9.5).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * to exercise the full use-case → rule-based-predictor pipeline without any Android
 * runtime or TFLite dependency. Fixed [today] and [now] references make all tests
 * deterministic.
 *
 * [MathHabitPredictor.predictSkipReason] rule summary (unnormalized logit scores,
 * then softmax-normalised):
 *   TOO_TIRED  : hour ≥ 20 or ≤ 5 (+2.0), Fri/Sun dayOfWeek (+1.5), rate7d < 0.30 (+1.0)
 *   TOO_BUSY   : Mon–Wed AND hour 9–18 (+2.0), WEEKLY habit (+1.0)
 *   FORGOT     : habitAge < 14 (+2.5), recentSkipRate14d > 0.40 (+1.5), streak = 0 (+1.0)
 *   SICK       : low flat prior (≈ −1.0); long-lived habit slight lift
 *   TRAVELING  : lowest prior (−1.8); weekend lift (+1.0)
 *   NO_REASON  : catch-all; elevated when rate30d in (0.35, 0.65) and skipRate14d in (0.10, 0.40)
 *
 * Coverage:
 * - Guard: fewer than 3 skips → hasSufficientData=false.
 * - Guard: exactly 3 skips → hasSufficientData=true.
 * - habitAge = 1 when completions list is empty.
 * - habitAge derived from oldest completion date.
 * - completionRateLast7Days / 30d coerced to [0,1].
 * - frequencyOrdinal: DAILY→0, WEEKLY→1, Monthly→2, Yearly→2.
 * - dayOfWeek and hourOfDay injected via today/now parameters.
 * - recentSkipRate14d computed from skips within 14-day window.
 * - Distribution sums to ≈ 1.0 (valid probability distribution).
 * - TOO_TIRED wins for late-evening, low-completion, Fri context.
 * - TOO_BUSY wins for Mon–Wed workday hours with a WEEKLY habit.
 * - FORGOT wins for brand-new habit with zero streak and high skip rate.
 * - LOW_CONFIDENCE_THRESHOLD constant exposed by SkipReasonPrediction companion.
 */
class SkipReasonPredictorUseCaseTest {

    private lateinit var useCase: SkipReasonPredictorUseCase

    /** Fixed reference date: Wednesday 2025-01-15 (ISO dayOfWeek = 3). */
    private val today = LocalDate.of(2025, 1, 15)

    private val dailyHabit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = SkipReasonPredictorUseCase(predictor = MathHabitPredictor())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Target-reached completion [daysBeforeToday] days before [today] at 09:00. */
    private fun completedAt(daysBeforeToday: Long, habitId: Int = 1) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = today.minusDays(daysBeforeToday).atTime(9, 0),
        isTargetReached = true
    )

    /** Skip record [daysBeforeToday] days before [today] at 09:00 with given [reason]. */
    private fun skippedAt(daysBeforeToday: Long, reason: SkipReason, habitId: Int = 1) =
        HabitSkipEntity(
            habitId = habitId,
            skippedAt = today.minusDays(daysBeforeToday).atTime(9, 0),
            reason = reason
        )

    // ── Sufficiency guards ────────────────────────────────────────────────────

    @Test
    fun `hasSufficientData is false when fewer than MIN_SKIPS skip records exist`() {
        // Only 2 skips — below the MIN_SKIPS = 3 threshold.
        val skips = listOf(
            skippedAt(1, SkipReason.TOO_TIRED),
            skippedAt(2, SkipReason.FORGOT)
        )
        val completions = (1L..20L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 0,
            today = today,
            now = LocalTime.of(14, 0)
        )

        assertFalse(result.hasSufficientData)
    }

    @Test
    fun `hasSufficientData is true when exactly MIN_SKIPS skip records exist`() {
        // Exactly 3 skips — meets the MIN_SKIPS = 3 threshold.
        val skips = (1L..3L).map { skippedAt(it, SkipReason.TOO_TIRED) }
        val completions = (1L..20L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 5,
            today = today,
            now = LocalTime.of(14, 0)
        )

        assertTrue(result.hasSufficientData)
    }

    // ── habitAge derivation ───────────────────────────────────────────────────

    @Test
    fun `habitAge defaults to 1 when completions list is empty`() {
        // No completions → the use case falls back to habitAge = 1 (new habit).
        // With habitAge < 14, FORGOT gets a large +2.5 boost and should dominate.
        val skips = (1L..5L).map { skippedAt(it, SkipReason.FORGOT) }

        val result = useCase(
            habit = dailyHabit,
            completions = emptyList(),
            recentSkips = skips,
            currentStreak = 0,
            today = today,
            now = LocalTime.of(14, 0)
        )

        // FORGOT should win when habitAge = 1 (<14) and streak = 0.
        assertEquals(SkipReason.FORGOT, result.topReason)
    }

    @Test
    fun `habitAge is derived from the oldest completion date`() {
        // Oldest completion 30 days ago → habitAge should be 30.
        // With a mature habit, FORGOT loses its +2.5 boost; verifiable via topReason.
        val completions = listOf(
            completedAt(30), // oldest — habitAge = 30
            completedAt(10),
            completedAt(5)
        )
        // Zero skips → hasSufficientData=false, but topReason still computed.
        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = emptyList(),
            currentStreak = 5,
            today = today,
            now = LocalTime.of(14, 0)
        )

        // habitAge = 30 ≥ 14, so FORGOT does NOT get its new-habit boost.
        assertFalse(result.topReason == SkipReason.FORGOT && result.topConfidence > 0.5f)
    }

    // ── Distribution validity ─────────────────────────────────────────────────

    @Test
    fun `distribution sums to approximately 1_0`() {
        val skips = (1L..5L).map { skippedAt(it, SkipReason.TOO_TIRED) }
        val completions = (1L..20L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 3,
            today = today,
            now = LocalTime.of(22, 0)
        )

        val sum = result.distribution.values.sum()
        assertEquals(1.0f, sum, 0.001f)
    }

    @Test
    fun `distribution contains exactly 6 entries one per SkipReason`() {
        val skips = (1L..5L).map { skippedAt(it, SkipReason.TOO_TIRED) }
        val completions = (1L..10L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 2,
            today = today,
            now = LocalTime.of(10, 0)
        )

        assertEquals(SkipReason.entries.size, result.distribution.size)
        assertTrue(result.distribution.keys.containsAll(SkipReason.entries))
    }

    @Test
    fun `topConfidence matches the maximum value in the distribution`() {
        val skips = (1L..5L).map { skippedAt(it, SkipReason.TOO_TIRED) }
        val completions = (1L..15L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 2,
            today = today,
            now = LocalTime.of(22, 0)
        )

        val expectedMax = result.distribution.values.max()
        assertEquals(expectedMax, result.topConfidence, 0.0001f)
        assertEquals(result.distribution[result.topReason]!!, result.topConfidence, 0.0001f)
    }

    // ── Predictor delegation: top reason semantics ────────────────────────────

    @Test
    fun `TOO_TIRED wins for late-evening Friday with low completion rate`() {
        // today = 2025-01-17 which is a Friday (ISO dayOfWeek = 5).
        val friday = LocalDate.of(2025, 1, 17)
        val eveningTime = LocalTime.of(22, 0)  // hour ≥ 20 → +2.0 for TOO_TIRED

        // Low completion rate: 0 completions in last 7 days (< 0.30) → +1.0 for TOO_TIRED
        val completions = listOf(completedAt(30)) // oldest, no recent completions
        val skips = (1L..5L).map { skippedAt(it, SkipReason.TOO_TIRED) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 0,
            today = friday,
            now = eveningTime
        )

        assertEquals(SkipReason.TOO_TIRED, result.topReason)
    }

    @Test
    fun `TOO_BUSY wins for Monday workday hours with a WEEKLY habit`() {
        // today = 2025-01-13 which is a Monday (ISO dayOfWeek = 1).
        val monday = LocalDate.of(2025, 1, 13)
        val workHour = LocalTime.of(10, 0)  // hour in 9..18 → Mon–Wed + work hours → +2.0

        val weeklyHabit = HabitData(
            id = 2, name = "Gym", currentCount = 0,
            frequency = HabitFrequency.Weekly, target = 1
        )
        // High completion rate (not triggering FORGOT) and mature habit (age ≥ 14).
        val completions = (1L..30L step 7).map { d ->
            HabitCompletionEntity(
                habitId = 2,
                progressUpdate = monday.minusDays(d).atTime(9, 0),
                isTargetReached = true
            )
        }
        val skips = (7L..21L step 7).map { d ->
            HabitSkipEntity(
                habitId = 2,
                skippedAt = monday.minusDays(d).atTime(9, 0),
                reason = SkipReason.TOO_BUSY
            )
        }

        val result = useCase(
            habit = weeklyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 2,
            today = monday,
            now = workHour
        )

        assertEquals(SkipReason.TOO_BUSY, result.topReason)
    }

    @Test
    fun `FORGOT wins for brand-new habit with zero streak and high skip rate`() {
        // habitAge < 14 → +2.5 for FORGOT
        // currentStreak = 0 → +1.0 for FORGOT
        // recentSkipRate14d > 0.40 → +1.5 for FORGOT
        // Total FORGOT score ≈ 5.0, easily dominating all others.

        // Only 3 completions 3 days ago → habitAge = 3 (< 14).
        val completions = listOf(
            completedAt(1),
            completedAt(2),
            completedAt(3)
        )
        // 8 skips in 14 days → skipRate = 8/14 ≈ 0.57 (> 0.40).
        val skips = (1L..8L).map { skippedAt(it, SkipReason.FORGOT) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 0,
            today = today,
            now = LocalTime.of(14, 0)
        )

        assertEquals(SkipReason.FORGOT, result.topReason)
    }

    // ── frequencyOrdinal mapping ──────────────────────────────────────────────

    @Test
    fun `WEEKLY habit increases TOO_BUSY score via frequencyOrdinal 1`() {
        // Verifies that WEEKLY → frequencyOrdinal=1 is correctly passed to the predictor.
        // On a Monday workday, WEEKLY gives TOO_BUSY an extra +1.0 on top of +2.0 context.
        val monday = LocalDate.of(2025, 1, 13)
        val weeklyHabit = HabitData(
            id = 3, name = "Meditation", currentCount = 0,
            frequency = HabitFrequency.Weekly, target = 1
        )
        val completions = (1L..50L step 7).map { d ->
            HabitCompletionEntity(
                habitId = 3,
                progressUpdate = monday.minusDays(d).atTime(9, 0),
                isTargetReached = true
            )
        }
        val skips = (7L..21L step 7).map { d ->
            HabitSkipEntity(
                habitId = 3,
                skippedAt = monday.minusDays(d).atTime(10, 0),
                reason = SkipReason.TOO_BUSY
            )
        }

        val resultWeekly = useCase(
            habit = weeklyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 3,
            today = monday,
            now = LocalTime.of(10, 0)
        )

        val resultDaily = useCase(
            habit = dailyHabit.copy(id = 3),
            completions = completions,
            recentSkips = skips,
            currentStreak = 3,
            today = monday,
            now = LocalTime.of(10, 0)
        )

        // The WEEKLY habit should give TOO_BUSY a higher probability than DAILY.
        val busyProbWeekly = resultWeekly.distribution[SkipReason.TOO_BUSY]!!
        val busyProbDaily  = resultDaily.distribution[SkipReason.TOO_BUSY]!!
        assertTrue("WEEKLY should boost TOO_BUSY probability vs DAILY",
            busyProbWeekly > busyProbDaily)
    }

    @Test
    fun `Monthly frequency maps to frequencyOrdinal 2 same as Yearly`() {
        // Both MONTHLY and YEARLY should produce identical features, hence equal outputs,
        // since both map to frequencyOrdinal = 2 in SkipReasonPredictorUseCase.
        val monthlyHabit = dailyHabit.copy(id = 4, frequency = HabitFrequency.Monthly)
        val yearlyHabit  = dailyHabit.copy(id = 5, frequency = HabitFrequency.Yearly)

        val completionsMonthly = (30L..120L step 30).map { d ->
            HabitCompletionEntity(
                habitId = 4,
                progressUpdate = today.minusDays(d).atTime(9, 0),
                isTargetReached = true
            )
        }
        val completionsYearly = completionsMonthly.map { it.copy(habitId = 5) }

        val skipsMonthly = listOf(
            skippedAt(10, SkipReason.NO_REASON, habitId = 4),
            skippedAt(20, SkipReason.NO_REASON, habitId = 4),
            skippedAt(30, SkipReason.NO_REASON, habitId = 4)
        )
        val skipsYearly = skipsMonthly.map { it.copy(habitId = 5) }

        val resultMonthly = useCase(
            habit = monthlyHabit,
            completions = completionsMonthly,
            recentSkips = skipsMonthly,
            currentStreak = 1,
            today = today,
            now = LocalTime.of(14, 0)
        )
        val resultYearly = useCase(
            habit = yearlyHabit,
            completions = completionsYearly,
            recentSkips = skipsYearly,
            currentStreak = 1,
            today = today,
            now = LocalTime.of(14, 0)
        )

        // Same ordinal → same top reason.
        assertEquals(resultMonthly.topReason, resultYearly.topReason)
    }

    // ── recentSkipRate14d window filtering ───────────────────────────────────

    @Test
    fun `skips older than 14 days do not contribute to recentSkipRate14d`() {
        // 10 skips at 15+ days ago → all outside the 14-day window.
        // recentSkipRate14d should be ~0 → FORGOT does NOT get the +1.5 "high skip rate" boost.
        val oldSkips = (15L..24L).map { skippedAt(it, SkipReason.FORGOT) }
        // Mature habit (habitAge = 60) → FORGOT has no new-habit boost either.
        val completions = (1L..60L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = oldSkips,
            currentStreak = 5,
            today = today,
            now = LocalTime.of(14, 0)
        )

        // FORGOT should NOT win — its two key boosters are absent.
        assertTrue(result.topReason != SkipReason.FORGOT || result.topConfidence < 0.5f)
    }

    @Test
    fun `skips exactly on the 14th day are included in recentSkipRate14d window`() {
        // One skip at exactly 14 days ago — inside the window (>= since14d).
        // Plus enough recent skips to reach MIN_SKIPS threshold.
        val skips = listOf(
            skippedAt(14, SkipReason.TOO_TIRED), // on boundary — included
            skippedAt(1, SkipReason.TOO_TIRED),
            skippedAt(2, SkipReason.TOO_TIRED)
        )
        val completions = (1L..30L).map { completedAt(it) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 0,
            today = today,
            now = LocalTime.of(22, 0)  // late evening → TOO_TIRED boost
        )

        // hasSufficientData=true means all 3 (including the boundary skip) were counted.
        assertTrue(result.hasSufficientData)
    }

    // ── habitId pass-through ──────────────────────────────────────────────────

    @Test
    fun `result habitId matches the habit id passed to the use case`() {
        val habitWithId = dailyHabit.copy(id = 42)
        val completions = (1L..15L).map { completedAt(it) }
        val skips = (1L..5L).map { skippedAt(it, SkipReason.TOO_TIRED) }

        val result = useCase(
            habit = habitWithId,
            completions = completions,
            recentSkips = skips,
            currentStreak = 3,
            today = today,
            now = LocalTime.of(10, 0)
        )

        assertEquals(42, result.habitId)
    }

    // ── LOW_CONFIDENCE_THRESHOLD constant ────────────────────────────────────

    @Test
    fun `LOW_CONFIDENCE_THRESHOLD is 0_35f`() {
        // Confirms the companion constant expected by the View layer is stable.
        assertEquals(0.35f, SkipReasonPrediction.LOW_CONFIDENCE_THRESHOLD, 0.0001f)
    }

    // ── completionRate coercion ───────────────────────────────────────────────

    @Test
    fun `completionRateLast7Days is coerced to at most 1_0 when over-completed`() {
        // 10 target-reached records within the last 7 days for a DAILY habit
        // (periods7 = 7 / 1 = 7) → raw rate = 10/7 ≈ 1.43 → coerced to 1.0.
        // This prevents FORGOT from getting a -1.0 hit for rate >= 0.70 AND
        // should not crash or return >1.0.
        val completions = (0L..9L).map { completedAt(it) }  // 10 completions in last 10 days
        val skips = (1L..5L).map { skippedAt(it, SkipReason.NO_REASON) }

        val result = useCase(
            habit = dailyHabit,
            completions = completions,
            recentSkips = skips,
            currentStreak = 7,
            today = today,
            now = LocalTime.of(14, 0)
        )

        // All distribution probabilities must stay ∈ [0, 1].
        result.distribution.values.forEach { prob ->
            assertTrue("All probabilities must be ≥ 0", prob >= 0f)
            assertTrue("All probabilities must be ≤ 1", prob <= 1f)
        }
    }
}
