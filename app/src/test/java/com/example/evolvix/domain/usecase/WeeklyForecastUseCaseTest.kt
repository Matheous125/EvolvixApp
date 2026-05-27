package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [WeeklyForecastUseCase] (R10 coverage).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * so all inference runs on the pure-Kotlin math fallback — no Android runtime or TFLite
 * dependency involved. A fixed [today] reference date (2025-06-01, Sunday) makes every
 * test deterministic regardless of when the suite is executed.
 *
 * **R10 math fallback rule verified here:**
 *   base = 0.70 × lastWeekRate + 0.30 × mean(rateMon..rateSun)
 *   result = clip(base − 0.10 × avgAbandonmentRisk, 0.0, 1.0)
 *
 * [MathHabitPredictor.predictAbandonment] rule chain excerpt (Rule 5 exercised in Test 2):
 *   Rule 5: completionRateLast30Days < 0.1 → probability = 0.70
 *
 * Coverage:
 *   Guard: empty habits list           → [WeeklyForecast.hasSufficientData] = false.
 *   R10 penalty: two habits with high avgAbandonmentRisk (0.70 via Rule 5) →
 *     predictedRate = (base − 0.10 × 0.70).coerceIn(0, 1) < base (no-penalty baseline).
 *   Healthy mix: ≥ 2 habits + ≥ 7 days of history →
 *     [WeeklyForecast.hasSufficientData] = true, predictedRate ∈ [0.0, 1.0].
 */
class WeeklyForecastUseCaseTest {

    private val predictor = MathHabitPredictor()

    /**
     * Fixed reference date: 2025-06-01 is a Sunday (DayOfWeek = 7, idx = 6).
     * Day 1 ago = May 31 = Saturday (idx = 5) — the only weekday with completions in
     * Test 2, which determines the expected weekdayMean = 0.25 / 7.
     */
    private val today = LocalDate.of(2025, 6, 1)

    private fun habitData(id: Int) = HabitData(
        id = id, name = "Habit $id", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    /** Returns a reached [HabitCompletionEntity] for [habitId], [daysAgo] days before [today]. */
    private fun completedAt(habitId: Int, daysAgo: Long) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = true
    )

    // ── Test 1: Insufficient-data guard ──────────────────────────────────────

    /**
     * [WeeklyForecastUseCase] requires MIN_HABITS = 2 active habits.
     * An empty list fires the guard immediately, returning a safe zero-forecast.
     */
    @Test
    fun `returns hasSufficientData false when habits list is empty`() {
        val useCase = WeeklyForecastUseCase(predictor)

        val result = useCase(
            habits = emptyList(),
            completions = emptyList(),
            currentStreaks = emptyMap(),
            today = today
        )

        assertFalse(result.hasSufficientData)
    }

    // ── Test 2: R10 abandonment-risk penalty ──────────────────────────────────

    /**
     * Two habits with completions at days 1, 30, and 60 ago:
     *
     * **AbandonmentRiskUseCase logic (per habit):**
     * - [rateInWindow] uses strict `>` so the day-30 completion is excluded from the
     *   30-day window (boundary date equals `today.minusDays(30)`).
     * - `completionRateLast30Days = 1 / 30 ≈ 0.033 < 0.1` → Rule 5 fires → probability = 0.70.
     * - `avgAbandonmentRisk = (0.70 + 0.70) / 2 = 0.70`.
     *
     * **WeeklyForecastUseCase feature computation:**
     * - `lastWeekRate = 2 / (7 days × 2 habits) = 1/7`  [day-1 completion for both habits]
     * - Day 1 ago = Saturday (idx 5): 2 reached / (4 Saturdays × 2 habits) = 0.25.
     * - All other weekdays: 0 completions in the 28-day window.
     * - `weekdayMean = 0.25 / 7`.
     * - `base = 0.70 × (1/7) + 0.30 × (0.25/7)`.
     *
     * **R10 penalty assertion:**
     * - `resultWithAbandon = (base − 0.10 × 0.70).coerceIn(0, 1) < base`.
     */
    @Test
    fun `R10 abandonment risk penalty reduces predicted rate`() {
        val completions = listOf(
            completedAt(1, 1L),
            completedAt(1, 30L),
            completedAt(1, 60L),
            completedAt(2, 1L),
            completedAt(2, 30L),
            completedAt(2, 60L)
        )
        val habits = listOf(habitData(1), habitData(2))
        // currentStreak = 1 for each habit: too low to trigger streak-based abandonment rules.
        val currentStreaks = mapOf(1 to 1, 2 to 1)

        val useCaseNoAbandon = WeeklyForecastUseCase(predictor)
        val useCaseWithAbandon = WeeklyForecastUseCase(
            predictor = predictor,
            abandonmentUseCase = AbandonmentRiskUseCase(predictor)
        )

        val resultNoAbandon   = useCaseNoAbandon(habits, completions, currentStreaks, today)
        val resultWithAbandon = useCaseWithAbandon(habits, completions, currentStreaks, today)

        // Both invocations have 60 days of history and 2 habits → sufficient data.
        assertTrue(resultNoAbandon.hasSufficientData)
        assertTrue(resultWithAbandon.hasSufficientData)

        // R10 penalty: the abandonment-adjusted rate must be strictly below the base.
        assertTrue(resultWithAbandon.predictedRate < resultNoAbandon.predictedRate)

        // Verify exact formula: result = (base − 0.10 × avgAbandonmentRisk).coerceIn(0, 1).
        // avgAbandonmentRisk = 0.70 (Rule 5 for both habits); base = resultNoAbandon.predictedRate.
        val expectedWithPenalty = (resultNoAbandon.predictedRate - 0.10f * 0.70f).coerceIn(0f, 1f)
        assertEquals(expectedWithPenalty, resultWithAbandon.predictedRate, 0.002f)
    }

    // ── Test 3: Healthy mix ───────────────────────────────────────────────────

    /**
     * Two habits with 10 consecutive daily completions each.
     * - `habits.size = 2 ≥ MIN_HABITS = 2` ✓
     * - `daysOfHistory = 10 ≥ MIN_HISTORY_DAYS = 7` ✓
     * Expected: [WeeklyForecast.hasSufficientData] = true and predictedRate ∈ [0.0, 1.0].
     */
    @Test
    fun `returns valid forecast for two habits with sufficient history`() {
        val completions = (1L..10L).flatMap { day ->
            listOf(completedAt(1, day), completedAt(2, day))
        }
        val habits = listOf(habitData(1), habitData(2))
        val currentStreaks = mapOf(1 to 10, 2 to 10)

        val useCase = WeeklyForecastUseCase(predictor)

        val result = useCase(habits, completions, currentStreaks, today)

        assertTrue(result.hasSufficientData)
        assertTrue(result.predictedRate >= 0f)
        assertTrue(result.predictedRate <= 1f)
    }
}
