package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Unit tests for [StreakRecoveryUseCase].
 *
 * [MathHabitPredictor.isStreakAtRisk] and [MathHabitPredictor.isDailyStreakAtRisk]
 * call [LocalDate.now()] internally. Completion dates are therefore built relative to
 * [LocalDate.now()] and the injectable [today] parameter is set to [LocalDate.now()] so
 * both the use case's feature extraction and the predictor's internal logic stay aligned.
 *
 * Coverage:
 * - [StreakRiskAssessment.hasSufficientData] threshold at 7 target-reached completions.
 * - Sunday flagged as risk day when missed 3 of the last 4 occurrences.
 * - No risk days when every weekday is consistently completed.
 * - [StreakRiskAssessment.riskDays] is always empty for non-daily habits.
 * - Early-exit state when data is insufficient.
 */
class StreakRecoveryUseCaseTest {

    private lateinit var useCase: StreakRecoveryUseCase

    // today is aligned with the predictor's internal LocalDate.now().
    private val today = LocalDate.now()

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = StreakRecoveryUseCase(predictor = MathHabitPredictor())
    }

    private fun completion(date: LocalDate) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = date.atTime(9, 0),
        isTargetReached = true
    )

    /**
     * Returns the Sunday of the ISO week that is [weeksBack] weeks before [today].
     * Mirrors [StreakRecoveryUseCase.detectRiskDays] and [MathHabitPredictor.isDailyStreakAtRisk].
     */
    private fun sundayAt(weeksBack: Int): LocalDate =
        today.minusWeeks(weeksBack.toLong()).with(DayOfWeek.SUNDAY)

    // ── Data sufficiency ──────────────────────────────────────────────────────

    @Test
    fun `hasSufficientData is false when fewer than 7 target-reached completions exist`() {
        val sparse = (1..6).map { completion(today.minusDays(it.toLong())) }
        assertFalse(useCase(habit, sparse, today).hasSufficientData)
    }

    @Test
    fun `hasSufficientData is true when 7 or more target-reached completions exist`() {
        val sufficient = (1..7).map { completion(today.minusDays(it.toLong())) }
        assertTrue(useCase(habit, sufficient, today).hasSufficientData)
    }

    // ── Risk day detection ────────────────────────────────────────────────────

    @Test
    fun `sunday is flagged as a risk day when missed 3 of the last 4 occurrences`() {
        // The 3 most recent Sundays are excluded; the oldest Sunday (week 4) is included.
        val missedSundays = (1..3).map { sundayAt(it) }.toSet()

        val completions = (1L..28L)
            .map { today.minusDays(it) }
            .filter { it !in missedSundays }
            .map { completion(it) }

        val result = useCase(habit, completions, today)
        assertTrue("Sunday should be a risk day", DayOfWeek.SUNDAY in result.riskDays)
    }

    @Test
    fun `no risk days when all weekdays are completed across all 4 lookback weeks`() {
        val completions = (1L..28L).map { completion(today.minusDays(it)) }
        val result = useCase(habit, completions, today)
        assertTrue("Expected no risk days with consistent history", result.riskDays.isEmpty())
    }

    // ── Non-daily habits ──────────────────────────────────────────────────────

    @Test
    fun `riskDays is always empty for non-daily habits`() {
        val weeklyHabit = habit.copy(frequency = HabitFrequency.Weekly)
        // 7 completions (≥ MIN_DATA_THRESHOLD) at weekly intervals.
        val completions = (1..7).map { completion(today.minusDays(it.toLong() * 7)) }
        assertTrue(
            "Weekly habit should never produce riskDays",
            useCase(weeklyHabit, completions, today).riskDays.isEmpty()
        )
    }

    // ── Early-exit state ──────────────────────────────────────────────────────

    @Test
    fun `isAtRisk is false and riskDays is empty when hasSufficientData is false`() {
        val result = useCase(habit, emptyList(), today)
        assertFalse(result.hasSufficientData)
        assertFalse(result.isAtRisk)
        assertTrue(result.riskDays.isEmpty())
    }
}
