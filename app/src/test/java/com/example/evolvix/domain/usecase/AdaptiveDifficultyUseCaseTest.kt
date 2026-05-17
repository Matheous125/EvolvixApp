package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [AdaptiveDifficultyUseCase].
 *
 * [MathHabitPredictor.suggestTargetDelta] uses [LocalDate.now()] internally to define
 * its 14-day rolling window. Completion dates are built relative to [LocalDate.now()]
 * and the injectable [today] parameter is set to [LocalDate.now()] so the use case's
 * window calculation stays aligned with the predictor.
 *
 * Coverage:
 * - delta = +1 at ≥ 90 % completion rate (14 of 14 days).
 * - delta = -1 at ≤ 40 % completion rate (5 of 14 days).
 * - delta =  0 when rate is between 40 % and 90 % (8 of 14 days).
 * - [DifficultyAdjustment.hasSufficientData] false for a monthly habit inside a 14-day window.
 * - [DifficultyAdjustment.suggestedTarget] is clamped to at least 1.
 * - [DifficultyAdjustment.rollingRate] equals reachedDays / totalPeriods.
 * - [DifficultyAdjustment.suggestedTarget] equals currentTarget + delta.
 */
class AdaptiveDifficultyUseCaseTest {

    private lateinit var useCase: AdaptiveDifficultyUseCase

    // Aligned with predictor's internal LocalDate.now() window.
    private val today = LocalDate.now()

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 2
    )

    @Before
    fun setUp() {
        useCase = AdaptiveDifficultyUseCase(predictor = MathHabitPredictor())
    }

    private fun completion(daysAgo: Long) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = true
    )

    // ── Delta computation ─────────────────────────────────────────────────────

    @Test
    fun `delta is plus 1 when all 14 days in the window are completed`() {
        // Rate = 14/14 = 100 % ≥ 90 % → delta = +1.
        val completions = (1L..14L).map { completion(it) }
        assertEquals(1, useCase(habit, completions, today).delta)
    }

    @Test
    fun `delta is minus 1 when only 5 of 14 days are completed`() {
        // Rate = 5/14 ≈ 35.7 % ≤ 40 % → delta = -1.
        val completions = (1L..5L).map { completion(it) }
        assertEquals(-1, useCase(habit, completions, today).delta)
    }

    @Test
    fun `delta is 0 when completion rate is between 40 and 90 percent`() {
        // Rate = 8/14 ≈ 57 % → delta = 0.
        val completions = (1L..8L).map { completion(it) }
        assertEquals(0, useCase(habit, completions, today).delta)
    }

    // ── Data sufficiency ──────────────────────────────────────────────────────

    @Test
    fun `hasSufficientData is false for a monthly habit within the 14-day window`() {
        // Monthly: totalPeriods = (14 / 30) = 0 → coerceAtLeast(1) = 1 < MIN_PERIODS (5).
        val monthlyHabit = habit.copy(frequency = HabitFrequency.Monthly)
        assertFalse(useCase(monthlyHabit, emptyList(), today).hasSufficientData)
    }

    // ── Suggested target constraints ──────────────────────────────────────────

    @Test
    fun `suggestedTarget is clamped to at least 1 even when delta would reduce it below 1`() {
        // habit.target = 1, rate ≤ 40 % → delta = -1 → suggestedTarget would be 0 → clamped to 1.
        val lowTargetHabit = habit.copy(target = 1)
        val completions = (1L..5L).map { completion(it) } // 35.7 % → delta = -1
        val result = useCase(lowTargetHabit, completions, today)
        assertTrue("suggestedTarget must be ≥ 1", result.suggestedTarget >= 1)
    }

    @Test
    fun `suggestedTarget equals currentTarget plus delta`() {
        val completions = (1L..14L).map { completion(it) } // 100 % → delta = +1
        val result = useCase(habit, completions, today)
        assertEquals(result.currentTarget + result.delta, result.suggestedTarget)
    }

    // ── Rolling rate ──────────────────────────────────────────────────────────

    @Test
    fun `rollingRate is 0_5 when exactly half the window days are completed`() {
        // 7 of 14 days → 7 / 14 = 0.5.
        val completions = (1L..7L).map { completion(it) }
        assertEquals(0.5f, useCase(habit, completions, today).rollingRate, 0.001f)
    }
}
