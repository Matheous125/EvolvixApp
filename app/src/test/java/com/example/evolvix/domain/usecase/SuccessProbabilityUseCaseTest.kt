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
 * Unit tests for [SuccessProbabilityUseCase].
 *
 * Two categories of tests:
 * - **Feature extraction** (dayOfWeek, hourOfDay, streak, recentWeekRate, habitAgeInDays):
 *   use a fixed [fixedNow] so assertions are deterministic. Completion dates are built
 *   relative to [fixedToday] so [SuccessProbabilityUseCase]'s window aligns correctly.
 * - **Probability range**: safe with fixed [fixedNow] + empty completions because
 *   [MathHabitPredictor.successProbability] with no history yields only hour bias, which
 *   is determined by the injected [hourOfDay] parameter (not [LocalDate.now]).
 *
 * All tests are pure JVM — no Android SDK, no emulator.
 */
class SuccessProbabilityUseCaseTest {

    private lateinit var useCase: SuccessProbabilityUseCase

    // Fixed Monday 8 AM: dayOfWeek = 1, hourOfDay = 8. Not a weekend, not late night —
    // ensures the predictor's hour and day branches behave predictably in assertions.
    private val fixedNow = LocalDateTime.of(2025, 1, 6, 8, 0)
    private val fixedToday: LocalDate = fixedNow.toLocalDate()

    private val habit = HabitData(
        id = 1, name = "Morning run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = SuccessProbabilityUseCase(predictor = MathHabitPredictor())
    }

    /** Creates a target-reached completion at [fixedToday] − [daysAgo] days at [hour]. */
    private fun completion(daysAgo: Long, hour: Int = 8) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = fixedToday.minusDays(daysAgo).atTime(hour, 0),
        isTargetReached = true
    )

    // ── Probability range ─────────────────────────────────────────────────────

    @Test
    fun `probability is always within 0_05 to 0_95`() {
        val result = useCase(habit, emptyList(), fixedNow)
        assertTrue("Below lower bound 0.05", result.probability >= 0.05f)
        assertTrue("Above upper bound 0.95", result.probability <= 0.95f)
    }

    // ── Feature: day and hour extraction ─────────────────────────────────────

    @Test
    fun `dayOfWeek in result matches the ISO day of the injected now`() {
        val result = useCase(habit, emptyList(), fixedNow)
        assertEquals(1, result.dayOfWeek) // Monday = 1 in ISO-8601
    }

    @Test
    fun `hourOfDay in result matches the hour of the injected now`() {
        val result = useCase(habit, emptyList(), fixedNow)
        assertEquals(8, result.hourOfDay)
    }

    // ── Feature: streak ───────────────────────────────────────────────────────

    @Test
    fun `currentStreak is zero when completions list is empty`() {
        val result = useCase(habit, emptyList(), fixedNow)
        assertEquals(0, result.currentStreak)
    }

    @Test
    fun `currentStreak equals the number of consecutive completed days`() {
        // 5 consecutive days ending yesterday (daysAgo 1..5) → streak = 5.
        val completions = (1L..5L).map { completion(daysAgo = it) }
        val result = useCase(habit, completions, fixedNow)
        assertEquals(5, result.currentStreak)
    }

    // ── Feature: recent week rate ─────────────────────────────────────────────

    @Test
    fun `recentWeekRate is 3 over 7 for exactly 3 completions in the last 7 days`() {
        val completions = (1L..3L).map { completion(daysAgo = it) }
        val result = useCase(habit, completions, fixedNow)
        assertEquals(3f / 7f, result.recentWeekRate, 0.001f)
    }

    @Test
    fun `recentWeekRate is zero when all completions are older than 7 days`() {
        val completions = (8L..14L).map { completion(daysAgo = it) }
        val result = useCase(habit, completions, fixedNow)
        assertEquals(0f, result.recentWeekRate, 0.001f)
    }

    // ── Feature: habit age ────────────────────────────────────────────────────

    @Test
    fun `habitAgeInDays is zero when completions list is empty`() {
        val result = useCase(habit, emptyList(), fixedNow)
        assertEquals(0L, result.habitAgeInDays)
    }

    @Test
    fun `habitAgeInDays equals days from oldest completion to today`() {
        // Oldest completion 14 days ago; more recent records should be ignored.
        val completions = listOf(
            completion(daysAgo = 14),
            completion(daysAgo = 7),
            completion(daysAgo = 1)
        )
        val result = useCase(habit, completions, fixedNow)
        assertEquals(14L, result.habitAgeInDays)
    }
}
