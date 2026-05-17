package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [MotivationMessageUseCase].
 *
 * [MathHabitPredictor.motivationMessageKey] contains hour-based rules (morning 6–10,
 * evening 19–22) that depend on [java.time.LocalTime.now()] — not injectable.
 * Tests are therefore scoped to rules that are controlled by streak and rate,
 * which are determined by the completion list and the injectable [now] parameter:
 *
 * - `cold_start`: always first when completions are empty (predictor checks `isEmpty()`).
 * - `streak_milestone`: triggered by streak ≥ 30, which overrides any hour branch.
 * - Streak field correctness, dayOfWeek field extraction, non-blank key invariant.
 *
 * [LocalDate.now()] is used for completions because [MathHabitPredictor.recentRate]
 * also calls [LocalDate.now()] internally; [now] is derived from [LocalDate.now()]
 * so [CalculateStreakUseCase] and the predictor stay aligned.
 */
class MotivationMessageUseCaseTest {

    private lateinit var useCase: MotivationMessageUseCase

    // Derive `now` from LocalDate.now() so that the injectable today parameter in
    // CalculateStreakUseCase matches the completion dates built below.
    private val today: LocalDate = LocalDate.now()

    // Noon avoids morning (6-10) and evening (19-22) hour branches in the predictor.
    private val now: LocalDateTime = today.atTime(12, 0)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = MotivationMessageUseCase(
            predictor = MathHabitPredictor(),
            calculateStreak = CalculateStreakUseCase()
        )
    }

    private fun completion(daysAgo: Long) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = true
    )

    // ── Cold start ────────────────────────────────────────────────────────────

    @Test
    fun `messageKey is cold_start when completions list is empty`() {
        val result = useCase(habit, emptyList(), now)
        assertEquals("motivation_cold_start", result.messageKey)
    }

    // ── Streak milestone ──────────────────────────────────────────────────────

    @Test
    fun `messageKey is streak_milestone for a 30-day active streak`() {
        // 30 consecutive days including today → currentStreak = 30 → milestone.
        val completions = (0L..29L).map { completion(it) }
        val result = useCase(habit, completions, now)
        assertEquals("motivation_streak_milestone", result.messageKey)
    }

    // ── Field extraction ──────────────────────────────────────────────────────

    @Test
    fun `streak in result equals the current streak computed from completions`() {
        // 7 consecutive days including today → streak = 7.
        val completions = (0L..6L).map { completion(it) }
        val result = useCase(habit, completions, now)
        assertEquals(7, result.streak)
    }

    @Test
    fun `streak in result is zero when completions list is empty`() {
        val result = useCase(habit, emptyList(), now)
        assertEquals(0, result.streak)
    }

    @Test
    fun `dayOfWeek in result is within the valid ISO range 1 to 7`() {
        val result = useCase(habit, emptyList(), now)
        assertTrue("dayOfWeek must be in 1..7", result.dayOfWeek in 1..7)
    }

    // ── Invariant ─────────────────────────────────────────────────────────────

    @Test
    fun `messageKey is never blank regardless of completions state`() {
        val cases = listOf(
            emptyList(),
            (0L..6L).map { completion(it) },    // 7-day streak
            (1L..3L).map { completion(it) }     // 3 recent completions
        )
        cases.forEachIndexed { index, completions ->
            val key = useCase(habit, completions, now).messageKey
            assertTrue("Expected non-blank messageKey for case $index", key.isNotBlank())
        }
    }
}
