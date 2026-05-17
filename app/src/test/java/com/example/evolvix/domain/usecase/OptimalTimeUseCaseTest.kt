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
 * Unit tests for [OptimalTimeUseCase].
 *
 * [MathHabitPredictor.optimalHours] does not call [LocalDate.now()] internally — it only
 * inspects the hour field of completion timestamps. A fixed reference date is therefore
 * safe for all tests, making results fully deterministic.
 *
 * Coverage:
 * - [OptimalTimePrediction.hourlyBins] always has exactly 24 entries.
 * - [OptimalTimePrediction.hasEnoughData] toggles at the 5-completion threshold.
 * - Histogram bins correctly tally per-hour counts.
 * - [OptimalTimePrediction.rankedHours] respects the [topN] parameter.
 * - Fallback morning hours returned when data is insufficient.
 * - Most frequent hour ranks first.
 */
class OptimalTimeUseCaseTest {

    private lateinit var useCase: OptimalTimeUseCase

    private val today = LocalDate.of(2025, 1, 6)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = OptimalTimeUseCase(predictor = MathHabitPredictor())
    }

    private fun completion(daysAgo: Long, hour: Int, isTargetReached: Boolean = true) =
        HabitCompletionEntity(
            habitId = 1,
            progressUpdate = today.minusDays(daysAgo).atTime(hour, 0),
            isTargetReached = isTargetReached
        )

    // ── Histogram structure ───────────────────────────────────────────────────

    @Test
    fun `hourlyBins always has exactly 24 elements`() {
        assertEquals(24, useCase(habit, emptyList()).hourlyBins.size)
    }

    @Test
    fun `hourlyBins are all zero when there are no completions`() {
        val bins = useCase(habit, emptyList()).hourlyBins
        assertTrue("Expected all zero bins", bins.all { it == 0 })
    }

    @Test
    fun `hourlyBins correctly tallies target-reached completions per hour`() {
        // 3 completions at hour 7, 2 at hour 20, none at hour 12.
        val completions =
            (1L..3L).map { completion(daysAgo = it, hour = 7) } +
            (4L..5L).map { completion(daysAgo = it, hour = 20) }
        val bins = useCase(habit, completions).hourlyBins
        assertEquals(3, bins[7])
        assertEquals(2, bins[20])
        assertEquals(0, bins[12])
    }

    @Test
    fun `non-target-reached completions are excluded from hourlyBins`() {
        val completions = listOf(completion(daysAgo = 1, hour = 9, isTargetReached = false))
        val bins = useCase(habit, completions).hourlyBins
        assertEquals(0, bins[9])
    }

    // ── Data sufficiency ──────────────────────────────────────────────────────

    @Test
    fun `hasEnoughData is false when fewer than 5 target-reached completions exist`() {
        val sparse = (1L..4L).map { completion(daysAgo = it, hour = 9) }
        assertFalse(useCase(habit, sparse).hasEnoughData)
    }

    @Test
    fun `hasEnoughData is true when 5 or more target-reached completions exist`() {
        val sufficient = (1L..5L).map { completion(daysAgo = it, hour = 9) }
        assertTrue(useCase(habit, sufficient).hasEnoughData)
    }

    // ── Ranked hours ──────────────────────────────────────────────────────────

    @Test
    fun `rankedHours size equals the topN parameter`() {
        val sufficient = (1L..10L).map { completion(daysAgo = it, hour = (it % 5).toInt() + 6) }
        assertEquals(2, useCase(habit, sufficient, topN = 2).rankedHours.size)
    }

    @Test
    fun `rankedHours returns morning fallback hours when data is insufficient`() {
        assertEquals(listOf(8, 9, 10), useCase(habit, emptyList(), topN = 3).rankedHours)
    }

    @Test
    fun `most frequent hour appears first in rankedHours`() {
        // Hour 14 has 5 completions, hour 7 has 2 → hour 14 should be ranked first.
        val completions =
            (1L..5L).map { completion(daysAgo = it, hour = 14) } +
            (6L..7L).map { completion(daysAgo = it, hour = 7) }
        assertEquals(14, useCase(habit, completions, topN = 1).rankedHours.first())
    }
}
