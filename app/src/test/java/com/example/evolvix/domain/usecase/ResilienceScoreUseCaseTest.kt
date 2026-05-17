package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.ResilienceScore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [ResilienceScoreUseCase].
 *
 * The use case delegates gap-averaging to [MathHabitPredictor.computeResilience].
 * That method maps each completion date to a period key (epoch-days for daily habits)
 * and measures gaps between consecutive "reached" period keys.
 *
 * Gap derivations used below (daily habit, period key = epoch-days):
 * - Day 0 → Day 2 : gap = key(2) − key(0) − 1 = 2 − 0 − 1 = 1  → avg = 1.0  → EXCELLENT
 * - Day 0 → Day 3 : gap = 3 − 0 − 1 = 2                          → avg = 2.0  → GOOD
 * - Day 0 → Day 6 : gap = 6 − 0 − 1 = 5                          → avg = 5.0  → MODERATE
 * - Day 0 → Day 9 : gap = 9 − 0 − 1 = 8                          → avg = 8.0  → LOW
 * - Day 0 → Day 1 : gap = 0 (consecutive) → no recovery events    → null
 *
 * Coverage:
 * - Returns null for empty list.
 * - Returns null for a single completion (< 2 distinct period keys).
 * - Returns null when all completions are on consecutive days (no gaps).
 * - EXCELLENT for avg gap < 1.5.
 * - GOOD for avg gap in [1.5, 3.0).
 * - MODERATE for avg gap in [3.0, 7.0).
 * - LOW for avg gap ≥ 7.0.
 * - Multiple gaps are averaged correctly.
 */
class ResilienceScoreUseCaseTest {

    private lateinit var useCase: ResilienceScoreUseCase

    // Fixed base date so tests are independent of the system clock.
    private val base = LocalDate.of(2025, 1, 1)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = ResilienceScoreUseCase(predictor = MathHabitPredictor())
    }

    private fun reachedAt(daysFromBase: Long) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = base.plusDays(daysFromBase).atTime(9, 0),
        isTargetReached = true
    )

    // ── Null / insufficient data ──────────────────────────────────────────────

    @Test
    fun `returns null for empty completions`() {
        assertNull(useCase(habit, emptyList()))
    }

    @Test
    fun `returns null when only one distinct completion date exists`() {
        // Two records on the same day → only 1 distinct period key → < 2 → null.
        val completions = listOf(reachedAt(0), reachedAt(0))
        assertNull(useCase(habit, completions))
    }

    @Test
    fun `returns null when all completions are on consecutive days (no gaps)`() {
        // Days 0, 1, 2 → keys 0,1,2 — every gap = 0 → no recovery events.
        val completions = listOf(reachedAt(0), reachedAt(1), reachedAt(2))
        assertNull(useCase(habit, completions))
    }

    // ── Rating tiers ──────────────────────────────────────────────────────────

    @Test
    fun `EXCELLENT when avg missed periods is less than 1_5`() {
        // Day 0 and Day 2 → gap = 1 → avg = 1.0 < 1.5
        val completions = listOf(reachedAt(0), reachedAt(2))
        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ResilienceScore.Rating.EXCELLENT, result!!.rating)
        assertEquals(1.0, result.avgMissedPeriods, 0.001)
    }

    @Test
    fun `GOOD when avg missed periods is between 1_5 and 3_0`() {
        // Day 0 and Day 3 → gap = 2 → avg = 2.0, in [1.5, 3.0)
        val completions = listOf(reachedAt(0), reachedAt(3))
        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ResilienceScore.Rating.GOOD, result!!.rating)
        assertEquals(2.0, result.avgMissedPeriods, 0.001)
    }

    @Test
    fun `MODERATE when avg missed periods is between 3_0 and 7_0`() {
        // Day 0 and Day 6 → gap = 5 → avg = 5.0, in [3.0, 7.0)
        val completions = listOf(reachedAt(0), reachedAt(6))
        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ResilienceScore.Rating.MODERATE, result!!.rating)
        assertEquals(5.0, result.avgMissedPeriods, 0.001)
    }

    @Test
    fun `LOW when avg missed periods is 7_0 or more`() {
        // Day 0 and Day 9 → gap = 8 → avg = 8.0 ≥ 7.0
        val completions = listOf(reachedAt(0), reachedAt(9))
        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ResilienceScore.Rating.LOW, result!!.rating)
        assertEquals(8.0, result.avgMissedPeriods, 0.001)
    }

    // ── Multiple gaps averaged ────────────────────────────────────────────────

    @Test
    fun `averages multiple recovery gaps correctly`() {
        // Days 0, 2, 5 → two consecutive pairs:
        //   gap(2−0−1) = 1, gap(5−2−1) = 2 → avg = (1+2)/2 = 1.5 → GOOD
        val completions = listOf(reachedAt(0), reachedAt(2), reachedAt(5))
        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(1.5, result!!.avgMissedPeriods, 0.001)
        assertEquals(ResilienceScore.Rating.GOOD, result.rating)
    }
}
