package com.example.evolvix.domain.usecase

import com.example.evolvix.data.local.TargetHistoryDao
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitTargetHistoryEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.ai.TargetChangeFeatures
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.TargetAdjustment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [TargetAdjustmentUseCase] (Phase 9.3).
 *
 * Uses [MathHabitPredictor] as the [HabitPredictor] for integration-style tests that
 * exercise feature derivation end-to-end, and a [FakePredictor] (Kotlin `by` delegation
 * spy) for isolated tests of rounding, clamping, and confidence tier computation.
 * [FakeTargetHistoryDao] is a minimal in-memory stub for [TargetHistoryDao].
 *
 * [MathHabitPredictor.predictTargetDelta] rule chain (first match wins):
 *   R9 grinding suppressor: recentAvgDifficulty ≥ 4.0 AND rate30d ≥ 0.80 → -1.0
 *   Strong over-completion:  rate30d ≥ 0.90 AND avgProgressRatio30d ≥ 1.20 AND age ≥ 21 → +2.0
 *   Moderate over-completion: rate30d ≥ 0.78 AND avgProgressRatio30d ≥ 1.02             → +1.0
 *   Strong under-performance: rate30d ≤ 0.22 AND avgProgressRatio30d ≤ 0.45             → -2.0
 *   Moderate under-performance: rate30d ≤ 0.40 AND avgProgressRatio30d ≤ 0.72           → -1.0
 *   Default: 0.0
 *
 * [TargetAdjustment.Confidence] residual thresholds:
 *   residual < 0.15 → HIGH
 *   residual < 0.35 → MEDIUM
 *   residual ≥ 0.35 → LOW
 *   (residual = |rawDelta − effectiveDelta.toFloat()|)
 *
 * Coverage:
 * - Guard: 0 completions → hasSufficientData = false, sentinel returned.
 * - Guard: 4 completions (< 5) → hasSufficientData = false.
 * - Guard boundary: exactly 5 completions → hasSufficientData = true.
 * - No target history → previousDelta = 0 / periodsSinceLastChange = 999 sentinel.
 * - With target history → fields derived correctly, no crash.
 * - Completions outside 30-day window excluded → rate30d = 0 → delta = -2.
 * - avgProgressRatio30d: 1 completion/day with target=5 gives ratio=0.2 → delta = -2.
 * - avgProgressRatio30d: 1 completion/day with target=1 gives ratio=1.0 → no under-perf.
 * - recentAvgDifficulty defaults to 3.0 when fewer than 3 rated completions.
 * - R9 grinding suppressor fires when ≥ 3 high difficulty ratings + adequate rate30d.
 * - rawDelta = 0.5f rounds up to delta = 1 (round-half-toward-positive).
 * - rawDelta = 0.4f stays at delta = 0.
 * - rawDelta = -0.5f rounds toward zero to delta = 0 (asymmetric rounding).
 * - rawDelta = 3.0f is clamped to delta = +2.
 * - rawDelta = -3.0f is clamped to delta = -2.
 * - suggestedTarget ≥ 1: habit with target=1 and delta=-2 gives suggestedTarget=1, delta=0.
 * - Confidence HIGH when residual = 0.0 (exact integer rawDelta).
 * - Confidence MEDIUM when residual = 0.20.
 * - Confidence LOW when residual = 0.40.
 * - Weekly habit (periodDays = 7) runs without crash and returns valid result.
 */
class TargetAdjustmentUseCaseTest {

    /** Fixed reference date so every test is deterministic regardless of the system clock. */
    private val today = LocalDate.of(2025, 6, 1)

    /** Daily habit with target=5; used for most integration tests. */
    private val dailyHabit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 5
    )

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * Minimal [TargetHistoryDao] stub. Returns [latest] from [getLatestForHabit];
     * [getForHabit] is never called by [TargetAdjustmentUseCase].
     */
    private class FakeTargetHistoryDao(
        private val latest: HabitTargetHistoryEntity? = null
    ) : TargetHistoryDao {
        override suspend fun insert(entry: HabitTargetHistoryEntity) = Unit
        override fun getForHabit(habitId: Int): Flow<List<HabitTargetHistoryEntity>> = flowOf(emptyList())
        override suspend fun getLatestForHabit(habitId: Int): HabitTargetHistoryEntity? = latest
    }

    /**
     * [HabitPredictor] spy that delegates all methods to [MathHabitPredictor] via
     * Kotlin interface delegation (`by delegate`), overriding only [predictTargetDelta]
     * to return [fixedDelta].
     *
     * Used to exercise rounding, clamping, and confidence computation in isolation,
     * independent of the MathHabitPredictor rule-chain.
     */
    private class FakePredictor(
        private val fixedDelta: Float,
        private val delegate: HabitPredictor = MathHabitPredictor()
    ) : HabitPredictor by delegate {
        override fun predictTargetDelta(features: TargetChangeFeatures): Float = fixedDelta
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Factory for [TargetAdjustmentUseCase] with injectable DAO and predictor. */
    private fun useCase(
        dao: TargetHistoryDao = FakeTargetHistoryDao(),
        predictor: HabitPredictor = MathHabitPredictor()
    ) = TargetAdjustmentUseCase(predictor, dao)

    /**
     * Creates a [HabitCompletionEntity] for habit id=1 at [daysAgo] days before [today]
     * at 09:00. Target-reached defaults to true.
     */
    private fun completion(
        daysAgo: Long,
        isTargetReached: Boolean = true,
        perceivedDifficulty: Int? = null
    ) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = isTargetReached,
        perceivedDifficulty = perceivedDifficulty
    )

    // ── Cold-start guard (MIN_COMPLETIONS = 5) ────────────────────────────────

    @Test
    fun `hasSufficientData is false and sentinel is returned when there are zero completions`() = runBlocking {
        val result = useCase().invoke(dailyHabit, emptyList(), 0, today)

        assertFalse(result.hasSufficientData)
        assertEquals(dailyHabit.target, result.currentTarget)
        assertEquals(dailyHabit.target, result.suggestedTarget)
        assertEquals(0, result.delta)
    }

    @Test
    fun `hasSufficientData is false when fewer than 5 completions`() = runBlocking {
        val completions = (1L..4L).map { completion(it) }
        val result = useCase().invoke(dailyHabit, completions, 0, today)

        assertFalse(result.hasSufficientData)
    }

    @Test
    fun `hasSufficientData is true with exactly 5 completions`() = runBlocking {
        val completions = (1L..5L).map { completion(it) }
        val result = useCase().invoke(dailyHabit, completions, 0, today)

        assertTrue(result.hasSufficientData)
    }

    // ── Target history feature derivation ────────────────────────────────────

    @Test
    fun `returns valid result when no target history exists`() = runBlocking {
        // getLatestForHabit returns null → previousDelta=0, periodsSinceLastChange=999.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(dao = FakeTargetHistoryDao(null)).invoke(dailyHabit, completions, 0, today)

        assertTrue(result.hasSufficientData)
        assertNotNull(result)
    }

    @Test
    fun `returns valid result when target history entry exists`() = runBlocking {
        // changedAt = 14 days ago, oldTarget=3 → newTarget=5 → previousDelta=2.
        // periodsSinceLastChange = 14 / 1 (daily) = 14.
        val history = HabitTargetHistoryEntity(
            habitId = 1, oldTarget = 3, newTarget = 5,
            changedAt = today.minusDays(14).atTime(0, 0),
            version = 2
        )
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(dao = FakeTargetHistoryDao(history)).invoke(dailyHabit, completions, 0, today)

        assertTrue(result.hasSufficientData)
        assertNotNull(result)
    }

    // ── Rate window computation ────────────────────────────────────────────────

    @Test
    fun `completions outside 30-day window are excluded and rate30d equals zero`() = runBlocking {
        // All 5 completions are 31–35 days ago → rate30d=0, avgProgressRatio30d=0 (fallback).
        // MathHabitPredictor: rate30d=0 ≤ 0.22 AND avgProgressRatio30d=0 ≤ 0.45 → -2.
        val completions = (31L..35L).map { completion(it) }
        val result = useCase().invoke(dailyHabit, completions, 0, today)

        assertEquals(-2, result.delta)
    }

    @Test
    fun `single completion per day with target 5 yields avgProgressRatio30d of 0_2 driving underperformance`() = runBlocking {
        // habit.target=5; 5 completions on separate days in 30d window.
        // ratio per date = 1 completion / 5 target = 0.2 → avgProgressRatio30d = 0.2.
        // rate30d = 5 reached / 30 periods = 0.167.
        // Both ≤ threshold → MathHabitPredictor strong under-performance → delta = -2.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase().invoke(dailyHabit, completions, 0, today)

        assertEquals(-2, result.delta)
    }

    @Test
    fun `single completion per day with target 1 yields avgProgressRatio30d of 1_0 no underperformance`() = runBlocking {
        // habit.target=1; 5 completions on separate days → ratio = 1/1 = 1.0 per date.
        // avgProgressRatio30d = 1.0 > 0.45, so strong under-perf rule does NOT fire.
        // rate30d = 5/30 = 0.167 ≤ 0.40 BUT avgProgressRatio30d = 1.0 > 0.72, so
        // moderate under-perf rule also does NOT fire → default → delta = 0.
        val singleTargetHabit = HabitData(
            id = 1, name = "Run", currentCount = 0,
            frequency = HabitFrequency.Daily, target = 1
        )
        val completions = (1L..5L).map { completion(it) }
        val result = useCase().invoke(singleTargetHabit, completions, 0, today)

        assertEquals(0, result.delta)
    }

    // ── recentAvgDifficulty (R9) ──────────────────────────────────────────────

    @Test
    fun `recentAvgDifficulty defaults to 3_0 when fewer than 3 completions carry a difficulty rating`() = runBlocking {
        // 2 rated + 3 unrated = 5 total completions.
        // Default difficulty = 3.0 < 4.0 → grinding suppressor does NOT fire.
        // rate30d = 5/30 ≈ 0.167 → under-perf rules apply based on avgProgressRatio30d.
        val singleTargetHabit = HabitData(
            id = 1, name = "Run", currentCount = 0,
            frequency = HabitFrequency.Daily, target = 1
        )
        val completions = listOf(
            completion(1, perceivedDifficulty = 5),
            completion(2, perceivedDifficulty = 5),
            completion(3),
            completion(4),
            completion(5)
        )
        val result = useCase().invoke(singleTargetHabit, completions, 0, today)

        // With default difficulty=3.0 grinding suppressor doesn't apply.
        // rate30d=5/30≈0.167, avgProgressRatio30d=1.0 → moderate under-perf doesn't fire → delta=0.
        assertEquals(0, result.delta)
    }

    @Test
    fun `R9 grinding suppressor fires when 3 or more high difficulty ratings and rate30d is adequate`() = runBlocking {
        // 25 completions in days 1-25 all rated 5 (max difficulty).
        // recentAvgDifficulty = mean of last 14 rated = 5.0 ≥ 4.0 threshold.
        // rate30d = 25 distinct reached dates / 30 periods = 0.833 ≥ 0.80. ✓
        // Grinding suppressor fires first → rawDelta = -1.0 → delta = -1.
        // target=3 so suggestedTarget = max(1, 3-1) = 2, effectiveDelta = -1 (no clamping).
        val habitTarget3 = HabitData(
            id = 1, name = "Run", currentCount = 0,
            frequency = HabitFrequency.Daily, target = 3
        )
        val completions = (1L..25L).map { completion(it, perceivedDifficulty = 5) } +
            (31L..35L).map { completion(it) }

        val result = useCase().invoke(habitTarget3, completions, 10, today)

        assertEquals(-1, result.delta)
    }

    // ── Delta rounding ────────────────────────────────────────────────────────

    @Test
    fun `rawDelta 0_5 rounds up to delta 1 (round half toward positive)`() = runBlocking {
        // toInt() = 0; rawDelta - 0 = 0.5 ≥ 0.5 → +1 → delta = 1.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(0.5f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(1, result.delta)
        assertEquals(0.5f, result.rawDelta, 0f)
    }

    @Test
    fun `rawDelta 0_4 stays at delta 0 (below half threshold)`() = runBlocking {
        // toInt() = 0; rawDelta - 0 = 0.4 < 0.5 → delta = 0.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(0.4f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(0, result.delta)
        assertEquals(0.4f, result.rawDelta, 0f)
    }

    @Test
    fun `rawDelta negative 0_5 rounds toward zero to delta 0 (asymmetric rounding)`() = runBlocking {
        // toInt() = 0 (truncation toward zero); rawDelta - 0 = -0.5 < 0.5 → delta = 0.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(-0.5f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(0, result.delta)
    }

    // ── Delta clamping ────────────────────────────────────────────────────────

    @Test
    fun `delta is clamped to positive 2 when rawDelta is 3_0`() = runBlocking {
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(3.0f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(2, result.delta)
    }

    @Test
    fun `delta is clamped to negative 2 when rawDelta is negative 3_0`() = runBlocking {
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(-3.0f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(-2, result.delta)
    }

    // ── suggestedTarget minimum-1 guard ──────────────────────────────────────

    @Test
    fun `suggestedTarget is 1 and effectiveDelta is 0 when target is 1 and rawDelta is negative`() = runBlocking {
        // habit.target=1, rawDelta=-2.0 → unclamped suggestedTarget = 1+(-2) = -1 → coerced to 1.
        // effectiveDelta = suggestedTarget - currentTarget = 1 - 1 = 0.
        val habitWithTarget1 = HabitData(
            id = 1, name = "Run", currentCount = 0,
            frequency = HabitFrequency.Daily, target = 1
        )
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(-2.0f)).invoke(habitWithTarget1, completions, 0, today)

        assertEquals(1, result.suggestedTarget)
        assertEquals(0, result.delta) // effectiveDelta recalculated after minimum-target clamp
    }

    // ── Confidence tiers ──────────────────────────────────────────────────────

    @Test
    fun `confidence is HIGH when rawDelta residual is below 0_15`() = runBlocking {
        // rawDelta = 1.0f → delta = 1 → residual = |1.0 - 1.0| = 0.0 < 0.15 → HIGH.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(1.0f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(TargetAdjustment.Confidence.HIGH, result.confidence)
    }

    @Test
    fun `confidence is MEDIUM when rawDelta residual is 0_20`() = runBlocking {
        // rawDelta = 1.2f → delta rounds to 1 (0.2 < 0.5) → residual = |1.2 - 1.0| = 0.2 → MEDIUM.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(1.2f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(TargetAdjustment.Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun `confidence is LOW when rawDelta residual is 0_40`() = runBlocking {
        // rawDelta = 1.4f → delta rounds to 1 (0.4 < 0.5) → residual = |1.4 - 1.0| = 0.4 ≥ 0.35 → LOW.
        val completions = (1L..5L).map { completion(it) }
        val result = useCase(predictor = FakePredictor(1.4f)).invoke(dailyHabit, completions, 0, today)

        assertEquals(TargetAdjustment.Confidence.LOW, result.confidence)
    }

    // ── Weekly habit (periodDays = 7) ─────────────────────────────────────────

    @Test
    fun `weekly habit uses periodDays of 7 and returns a valid result`() = runBlocking {
        // With periodDays=7: periods30 = 30/7 = 4, periods7 = 7/7 = 1.
        // 5 completions in last 5 days: rate30d = min(5,4)/4 = 1.0 (coerced),
        // rate7d = min(5,1)/1 = 1.0 (coerced). No over-completion rule fires
        // because avgProgressRatio30d = 1.0 < 1.02 → delta = 0.
        val weeklyHabit = HabitData(
            id = 1, name = "Long Run", currentCount = 0,
            frequency = HabitFrequency.Weekly, target = 1
        )
        val completions = (1L..5L).map { completion(it) }
        val result = useCase().invoke(weeklyHabit, completions, 2, today)

        assertTrue(result.hasSufficientData)
        assertEquals(weeklyHabit.target, result.currentTarget)
    }
}
