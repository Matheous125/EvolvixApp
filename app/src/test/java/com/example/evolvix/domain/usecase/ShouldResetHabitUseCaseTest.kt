package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitFrequency
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Unit tests for [ShouldResetHabitUseCase].
 *
 * All tests use fixed dates so results are deterministic regardless of when the suite runs.
 * Covers all four [HabitFrequency] branches, the frequencyN multiplier, and boundary
 * conditions (exactly-on-reset-day vs. one-day-before).
 *
 * Reference date used as "today": 2024-05-13 (Monday). Chosen because:
 * - It's a Monday, which exercises the Weekly reset boundary cleanly.
 * - It's mid-month, which clearly separates monthly boundary cases.
 */
class ShouldResetHabitUseCaseTest {

    private lateinit var useCase: ShouldResetHabitUseCase

    @Before
    fun setUp() {
        useCase = ShouldResetHabitUseCase()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a minimal [HabitEntity] with only the fields [ShouldResetHabitUseCase] reads.
     * All other fields are left at their defaults.
     */
    private fun habit(
        frequency: HabitFrequency,
        frequencyN: Int = 1,
        lastResetDate: LocalDateTime
    ): HabitEntity = HabitEntity(
        name = "Test Habit",
        currentCount = 0,
        target = 1,
        frequency = frequency,
        frequencyN = frequencyN,
        lastResetDate = lastResetDate
    )

    // ── Daily (N = 1) ─────────────────────────────────────────────────────────

    @Test
    fun `daily N1 - reset not needed when lastReset is today`() {
        val now = LocalDateTime.of(2024, 5, 13, 10, 0)
        val h = habit(HabitFrequency.Daily, lastResetDate = now.minusHours(2))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `daily N1 - reset needed when lastReset was yesterday`() {
        val now = LocalDateTime.of(2024, 5, 13, 8, 0)
        val h = habit(HabitFrequency.Daily, lastResetDate = LocalDateTime.of(2024, 5, 12, 20, 0))
        assertTrue(useCase(h, now))
    }

    @Test
    fun `daily N1 - reset needed when lastReset was multiple days ago`() {
        val now = LocalDateTime.of(2024, 5, 13, 8, 0)
        val h = habit(HabitFrequency.Daily, lastResetDate = LocalDateTime.of(2024, 5, 9, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── Daily (N = 2) ─────────────────────────────────────────────────────────

    @Test
    fun `daily N2 - reset not needed when only 1 day has passed`() {
        val now = LocalDateTime.of(2024, 5, 13, 8, 0)
        // lastReset = May 12 → nextReset = May 14 → today May 13 = before nextReset
        val h = habit(HabitFrequency.Daily, frequencyN = 2, lastResetDate = LocalDateTime.of(2024, 5, 12, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `daily N2 - reset needed exactly on the 2nd day`() {
        val now = LocalDateTime.of(2024, 5, 14, 8, 0)
        // lastReset = May 12 → nextReset = May 14 → today = May 14 → reset
        val h = habit(HabitFrequency.Daily, frequencyN = 2, lastResetDate = LocalDateTime.of(2024, 5, 12, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── Weekly (N = 1) ────────────────────────────────────────────────────────

    @Test
    fun `weekly N1 - reset not needed on Sunday before the next Monday`() {
        // now = Sunday May 12 2024; lastReset = Mon May 6; nextReset = Mon May 13
        val now = LocalDateTime.of(2024, 5, 12, 10, 0)
        val h = habit(HabitFrequency.Weekly, lastResetDate = LocalDateTime.of(2024, 5, 6, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `weekly N1 - reset needed on Monday (exactly on nextReset)`() {
        // now = Monday May 13 2024; lastReset = Mon May 6; nextReset = Mon May 13
        val now = LocalDateTime.of(2024, 5, 13, 8, 0)
        val h = habit(HabitFrequency.Weekly, lastResetDate = LocalDateTime.of(2024, 5, 6, 9, 0))
        assertTrue(useCase(h, now))
    }

    @Test
    fun `weekly N1 - lastReset was a Monday - nextReset is 7 days later not same day`() {
        // lastReset = Mon May 13; nextReset = Mon May 20; now = Mon May 13 (same day) → no reset
        val now = LocalDateTime.of(2024, 5, 13, 12, 0)
        val h = habit(HabitFrequency.Weekly, lastResetDate = LocalDateTime.of(2024, 5, 13, 0, 1))
        assertFalse(useCase(h, now))
    }

    // ── Weekly (N = 2) ────────────────────────────────────────────────────────

    @Test
    fun `weekly N2 - reset not needed on 1st Monday after lastReset`() {
        // lastReset = Mon May 6; 1st Monday = May 13; 2nd Monday = May 20; now = May 13 → no reset
        val now = LocalDateTime.of(2024, 5, 13, 8, 0)
        val h = habit(HabitFrequency.Weekly, frequencyN = 2, lastResetDate = LocalDateTime.of(2024, 5, 6, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `weekly N2 - reset needed on the 2nd Monday after lastReset`() {
        // lastReset = Mon May 6; nextReset = Mon May 20; now = May 20 → reset
        val now = LocalDateTime.of(2024, 5, 20, 8, 0)
        val h = habit(HabitFrequency.Weekly, frequencyN = 2, lastResetDate = LocalDateTime.of(2024, 5, 6, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── Monthly (N = 1) ───────────────────────────────────────────────────────

    @Test
    fun `monthly N1 - reset not needed when still in same month as lastReset`() {
        // lastReset = Apr 15; nextReset = May 1; now = Apr 30 → no reset
        val now = LocalDateTime.of(2024, 4, 30, 10, 0)
        val h = habit(HabitFrequency.Monthly, lastResetDate = LocalDateTime.of(2024, 4, 15, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `monthly N1 - reset needed on the 1st of the next month`() {
        // lastReset = Apr 15; nextReset = May 1; now = May 1 → reset
        val now = LocalDateTime.of(2024, 5, 1, 0, 1)
        val h = habit(HabitFrequency.Monthly, lastResetDate = LocalDateTime.of(2024, 4, 15, 9, 0))
        assertTrue(useCase(h, now))
    }

    @Test
    fun `monthly N1 - reset needed mid-month after boundary has passed`() {
        // lastReset = Apr 15; nextReset = May 1; now = May 13 → still reset
        val now = LocalDateTime.of(2024, 5, 13, 10, 0)
        val h = habit(HabitFrequency.Monthly, lastResetDate = LocalDateTime.of(2024, 4, 15, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── Monthly (N = 2) ───────────────────────────────────────────────────────

    @Test
    fun `monthly N2 - reset not needed when only 1 month has passed`() {
        // lastReset = Mar 15; anchor = Apr 1; nextReset = May 1; now = Apr 13 → no reset
        val now = LocalDateTime.of(2024, 4, 13, 10, 0)
        val h = habit(HabitFrequency.Monthly, frequencyN = 2, lastResetDate = LocalDateTime.of(2024, 3, 15, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `monthly N2 - reset needed after 2 months`() {
        // lastReset = Mar 15; anchor = Apr 1; nextReset = May 1; now = May 13 → reset
        val now = LocalDateTime.of(2024, 5, 13, 10, 0)
        val h = habit(HabitFrequency.Monthly, frequencyN = 2, lastResetDate = LocalDateTime.of(2024, 3, 15, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── Yearly (N = 1) ────────────────────────────────────────────────────────

    @Test
    fun `yearly N1 - reset not needed before Jan 1 of next year`() {
        // lastReset = Mar 2023; nextReset = Jan 1 2024; now = Dec 31 2023 → no reset
        val now = LocalDateTime.of(2023, 12, 31, 23, 59)
        val h = habit(HabitFrequency.Yearly, lastResetDate = LocalDateTime.of(2023, 3, 10, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `yearly N1 - reset needed on Jan 1 of the following year`() {
        // lastReset = Mar 2023; nextReset = Jan 1 2024; now = Jan 1 2024 → reset
        val now = LocalDateTime.of(2024, 1, 1, 0, 1)
        val h = habit(HabitFrequency.Yearly, lastResetDate = LocalDateTime.of(2023, 3, 10, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── Yearly (N = 2) ────────────────────────────────────────────────────────

    @Test
    fun `yearly N2 - reset not needed on Jan 1 of year+1`() {
        // lastReset = Mar 2022; anchorYear = 2023; nextReset = Jan 1 2024; now = Jan 1 2023 → no reset
        val now = LocalDateTime.of(2023, 1, 1, 0, 1)
        val h = habit(HabitFrequency.Yearly, frequencyN = 2, lastResetDate = LocalDateTime.of(2022, 3, 10, 9, 0))
        assertFalse(useCase(h, now))
    }

    @Test
    fun `yearly N2 - reset needed on Jan 1 of year+2`() {
        // lastReset = Mar 2022; anchorYear = 2023; nextReset = Jan 1 2024; now = Jan 1 2024 → reset
        val now = LocalDateTime.of(2024, 1, 1, 0, 1)
        val h = habit(HabitFrequency.Yearly, frequencyN = 2, lastResetDate = LocalDateTime.of(2022, 3, 10, 9, 0))
        assertTrue(useCase(h, now))
    }

    // ── frequencyN coercion ───────────────────────────────────────────────────

    @Test
    fun `frequencyN of 0 is coerced to 1 - behaves same as N1`() {
        // frequencyN=0 → coerceAtLeast(1) → treated as Daily N=1
        val now = LocalDateTime.of(2024, 5, 13, 8, 0)
        val h = habit(HabitFrequency.Daily, frequencyN = 0, lastResetDate = LocalDateTime.of(2024, 5, 12, 9, 0))
        assertTrue(useCase(h, now))
    }
}
