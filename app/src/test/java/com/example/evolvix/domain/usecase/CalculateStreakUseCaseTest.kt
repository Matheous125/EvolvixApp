package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [CalculateStreakUseCase].
 *
 * Every test is self-contained: it builds a synthetic list of [HabitCompletionEntity]
 * rows and passes a fixed [today] date so results are deterministic regardless of when
 * the test suite runs.
 *
 * Coverage targets:
 * - Empty input → both streaks are 0
 * - Only non-target-reached records → both streaks are 0
 * - Single completed period
 * - Unbroken run including today
 * - Unbroken run ending yesterday (today not yet logged) — streak still alive
 * - Gap breaks both current and best computation
 * - Best streak is longer than the current streak
 * - Over-completion: multiple records per period all with isTargetReached = false
 *   except the canonical one → period counts once
 * - Weekly and Monthly frequency granularity
 */
class CalculateStreakUseCaseTest {

    private lateinit var useCase: CalculateStreakUseCase

    // Fixed reference date used as "today" in every test so results never drift.
    // 2024-03-10 is a Sunday — convenient for weekly boundary checks.
    private val today = LocalDate.of(2024, 3, 10)

    @Before
    fun setUp() {
        useCase = CalculateStreakUseCase()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Creates a [HabitCompletionEntity] with only the fields the use case reads:
     * [progressUpdate] and [isTargetReached]. [habitId] and [id] are irrelevant
     * to pure streak logic and are defaulted to 1 / 0 respectively.
     */
    private fun completion(date: LocalDate, targetReached: Boolean): HabitCompletionEntity =
        HabitCompletionEntity(
            id = 0,
            habitId = 1,
            progressUpdate = date.atTime(9, 0),
            isTargetReached = targetReached
        )

    /** Shorthand: a completion that counts toward the streak. */
    private fun hit(date: LocalDate) = completion(date, targetReached = true)

    /** Shorthand: a completion that does NOT count toward the streak. */
    private fun miss(date: LocalDate) = completion(date, targetReached = false)

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `empty list returns zero streaks`() {
        val result = useCase(emptyList(), HabitFrequency.Daily, today)
        assertEquals(0, result.current)
        assertEquals(0, result.best)
    }

    @Test
    fun `only non-target-reached records returns zero streaks`() {
        val completions = listOf(
            miss(today),
            miss(today.minusDays(1)),
            miss(today.minusDays(2))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(0, result.current)
        assertEquals(0, result.best)
    }

    @Test
    fun `single completed period today gives streak of 1`() {
        val result = useCase(listOf(hit(today)), HabitFrequency.Daily, today)
        assertEquals(1, result.current)
        assertEquals(1, result.best)
    }

    @Test
    fun `unbroken run including today counts correctly`() {
        // Days: today, yesterday, day before → 3-day streak
        val completions = listOf(
            hit(today),
            hit(today.minusDays(1)),
            hit(today.minusDays(2))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `streak is still alive when today is not yet completed but yesterday was`() {
        // User hasn't logged today yet — streak ends at yesterday, but is still alive.
        val completions = listOf(
            hit(today.minusDays(1)),
            hit(today.minusDays(2)),
            hit(today.minusDays(3))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `gap breaks current streak but best streak is preserved`() {
        // 3-day run a week ago, then a gap, then 2-day run ending today.
        // current = 2, best = 3
        val completions = listOf(
            hit(today),
            hit(today.minusDays(1)),
            // gap at minusDays(2)
            hit(today.minusDays(3)),
            hit(today.minusDays(4)),
            hit(today.minusDays(5))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(2, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `current streak is zero when last completion has a gap before yesterday`() {
        // Last hit was 3 days ago — streak is broken.
        val completions = listOf(
            hit(today.minusDays(3)),
            hit(today.minusDays(4))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(0, result.current)
        assertEquals(2, result.best)
    }

    @Test
    fun `over-completion multiple records in same period count as one`() {
        // 3 taps on today: only the 3rd has isTargetReached = true (target = 3).
        // The period still counts as completed exactly once.
        val completions = listOf(
            miss(today),          // tap 1
            miss(today),          // tap 2
            hit(today),           // tap 3 — target reached
            miss(today.minusDays(1)),
            miss(today.minusDays(1)),
            hit(today.minusDays(1))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(2, result.current)
        assertEquals(2, result.best)
    }

    @Test
    fun `weekly frequency treats same ISO week as one period`() {
        // today = 2024-03-10 (Sunday, week 10).
        // Three consecutive weeks with a target hit each.
        val week0Hit = today                        // week 10, 2024
        val week1Hit = today.minusWeeks(1)          // week 9
        val week2Hit = today.minusWeeks(2)          // week 8
        val completions = listOf(hit(week0Hit), hit(week1Hit), hit(week2Hit))
        val result = useCase(completions, HabitFrequency.Weekly, today)
        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `weekly frequency two hits in same week count as one period`() {
        // Monday and Thursday of the same week → one completed period, not two.
        val monday = LocalDate.of(2024, 3, 4)   // week 9
        val thursday = LocalDate.of(2024, 3, 7) // same week 9
        val previousWeek = LocalDate.of(2024, 2, 26) // week 8
        val completions = listOf(hit(monday), hit(thursday), hit(previousWeek))
        val result = useCase(completions, HabitFrequency.Weekly, today)
        // today (week 10) not completed → streak walks back to week 9 and week 8 → 2
        assertEquals(2, result.current)
        assertEquals(2, result.best)
    }

    @Test
    fun `monthly frequency treats same calendar month as one period`() {
        // March (today), February, January — 3-month streak.
        val march     = today                         // 2024-03-10
        val february  = LocalDate.of(2024, 2, 15)
        val january   = LocalDate.of(2024, 1, 20)
        val completions = listOf(hit(march), hit(february), hit(january))
        val result = useCase(completions, HabitFrequency.Monthly, today)
        assertEquals(3, result.current)
        assertEquals(3, result.best)
    }

    @Test
    fun `monthly frequency gap month breaks current streak`() {
        // March and January completed, February missing.
        val march   = today
        val january = LocalDate.of(2024, 1, 5)
        val completions = listOf(hit(march), hit(january))
        val result = useCase(completions, HabitFrequency.Monthly, today)
        assertEquals(1, result.current) // only March
        assertEquals(1, result.best)
    }

    @Test
    fun `monthly frequency year boundary is handled correctly`() {
        // December 2023 → January 2024 — adjacent months across year boundary.
        val january  = LocalDate.of(2024, 1, 10)
        val december = LocalDate.of(2023, 12, 20)
        // today is March 2024, so current streak does not include these two old months.
        val completions = listOf(hit(january), hit(december))
        val result = useCase(completions, HabitFrequency.Monthly, today)
        assertEquals(0, result.current)
        assertEquals(2, result.best)
    }

    @Test
    fun `best streak considers all historical runs not just current`() {
        // Old 5-day run, long gap, then 1-day run ending yesterday.
        val completions = listOf(
            hit(today.minusDays(1)),
            // gap
            hit(today.minusDays(10)),
            hit(today.minusDays(11)),
            hit(today.minusDays(12)),
            hit(today.minusDays(13)),
            hit(today.minusDays(14))
        )
        val result = useCase(completions, HabitFrequency.Daily, today)
        assertEquals(1, result.current)
        assertEquals(5, result.best)
    }
}
