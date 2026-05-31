package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [LifeBalanceUseCase].
 *
 * The use case computes per-category completion rates over a rolling date window.
 * All tests inject a fixed [today] date so they are clock-independent.
 *
 * Algorithm facts:
 * - Window: [today − (windowDays − 1), today] inclusive.
 * - Only completions with [isTargetReached = true] are counted.
 * - Distinct (habitId, date) pairs are counted — same-day duplicates count once.
 * - Habits with no categories fall under the synthetic "Other" bucket.
 * - A multi-category habit contributes to each of its categories independently.
 * - Results are sorted alphabetically by category name.
 * - completionRate = completedPairs / (habitCount × windowDays), clamped to [0, 1].
 *
 * Coverage:
 * - Empty habits list → empty result.
 * - Habits with no completions → rate 0f.
 * - Habit with no categories → grouped under "Other".
 * - Single category, full completion → rate 1.0f.
 * - Partial completion → correct fractional rate.
 * - Two habits in same category → rates pooled correctly.
 * - Completions outside window → excluded.
 * - Completions with isTargetReached = false → excluded.
 * - Window lower boundary (first day) included.
 * - Day before window start excluded.
 * - Today (window end) included.
 * - Same-day duplicate completions counted once per (habitId, date).
 * - Multi-category habit contributes independently to each category.
 * - Results sorted alphabetically by category.
 * - Mixed categorized + uncategorized habits produce correct buckets.
 * - habitCount reflects number of habits assigned to each category.
 */
class LifeBalanceUseCaseTest {

    private lateinit var useCase: LifeBalanceUseCase

    /** Fixed reference date — tests are independent of the system clock. */
    private val today = LocalDate.of(2025, 1, 7)

    @Before
    fun setUp() {
        useCase = LifeBalanceUseCase()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal [HabitEntity] with only the fields relevant to [LifeBalanceUseCase]. */
    private fun habit(id: Int, categories: List<String> = emptyList()) = HabitEntity(
        id = id,
        name = "Habit $id",
        currentCount = 0,
        target = 1,
        categories = categories
    )

    /** A completion where the daily target was reached. */
    private fun reached(habitId: Int, date: LocalDate) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = date.atTime(9, 0),
        isTargetReached = true
    )

    /** A progress record where the target was NOT reached. */
    private fun notReached(habitId: Int, date: LocalDate) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = date.atTime(9, 0),
        isTargetReached = false
    )

    // ── Empty / trivial inputs ────────────────────────────────────────────────

    @Test
    fun `returns empty list when no habits provided`() {
        val result = useCase(habits = emptyList(), completions = emptyList(), today = today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns zero rate when habits have no completions`() {
        val h = habit(1, listOf("Health"))
        val result = useCase(listOf(h), emptyList(), windowDays = 7, today = today)

        assertEquals(1, result.size)
        assertEquals("Health", result[0].category)
        assertEquals(0f, result[0].completionRate, 0.001f)
        assertEquals(1, result[0].habitCount)
    }

    // ── "Other" fallback bucket ───────────────────────────────────────────────

    @Test
    fun `habit with no categories is grouped under Other`() {
        val h = habit(1, categories = emptyList())
        val result = useCase(listOf(h), emptyList(), windowDays = 1, today = today)

        assertEquals(1, result.size)
        assertEquals(LifeBalanceUseCase.UNCATEGORIZED_LABEL, result[0].category)
    }

    @Test
    fun `Other bucket gets rate 1f when uncategorized habit completes in a 1-day window`() {
        val h = habit(1, categories = emptyList())
        // windowDays=1: expectedPairs = 1*1 = 1, completedPairs = 1 → rate = 1.0f
        val result = useCase(listOf(h), listOf(reached(1, today)), windowDays = 1, today = today)

        assertEquals(1.0f, result[0].completionRate, 0.001f)
    }

    // ── Completion rate arithmetic ────────────────────────────────────────────

    @Test
    fun `full completion over window yields rate 1f`() {
        val h = habit(1, listOf("Health"))
        // Complete all 7 days in the window.
        val completions = (0L..6L).map { d -> reached(1, today.minusDays(d)) }
        val result = useCase(listOf(h), completions, windowDays = 7, today = today)

        assertEquals(1.0f, result[0].completionRate, 0.001f)
    }

    @Test
    fun `partial completion yields correct fractional rate`() {
        val h = habit(1, listOf("Health"))
        // 1 completion in a 4-day window → completedPairs=1, expected=4, rate=0.25
        val completions = listOf(reached(1, today))
        val result = useCase(listOf(h), completions, windowDays = 4, today = today)

        assertEquals(0.25f, result[0].completionRate, 0.001f)
    }

    @Test
    fun `two habits in same category pool their completions correctly`() {
        val h1 = habit(1, listOf("Health"))
        val h2 = habit(2, listOf("Health"))
        // windowDays=2: expectedPairs = 2 habits × 2 days = 4
        // Only h1 completes today → completedPairs = 1, rate = 0.25f
        val completions = listOf(reached(1, today))
        val result = useCase(listOf(h1, h2), completions, windowDays = 2, today = today)

        assertEquals(1, result.size)
        assertEquals(0.25f, result[0].completionRate, 0.001f)
        assertEquals(2, result[0].habitCount)
    }

    // ── Filtering: isTargetReached = false ───────────────────────────────────

    @Test
    fun `completions with isTargetReached false are excluded from count`() {
        val h = habit(1, listOf("Fitness"))
        val completions = listOf(notReached(1, today))
        val result = useCase(listOf(h), completions, windowDays = 1, today = today)

        assertEquals(0f, result[0].completionRate, 0.001f)
    }

    // ── Window boundary tests ─────────────────────────────────────────────────

    @Test
    fun `completion on first day of window (windowStart) is included`() {
        val h = habit(1, listOf("Health"))
        // windowDays=7, today=Jan 7 → windowStart = Jan 1 (today − 6)
        val firstDay = today.minusDays(6) // Jan 1
        val completions = listOf(reached(1, firstDay))
        val result = useCase(listOf(h), completions, windowDays = 7, today = today)

        // 1/7 ≈ 0.143 — should be > 0
        assertTrue("Completion on windowStart must be counted", result[0].completionRate > 0f)
    }

    @Test
    fun `completion one day before window start is excluded`() {
        val h = habit(1, listOf("Health"))
        // windowDays=7, today=Jan 7 → windowStart = Jan 1. Day before = Dec 31 = today − 7.
        val dayBeforeWindow = today.minusDays(7)
        val completions = listOf(reached(1, dayBeforeWindow))
        val result = useCase(listOf(h), completions, windowDays = 7, today = today)

        assertEquals(0f, result[0].completionRate, 0.001f)
    }

    @Test
    fun `completion on today (window end) is included`() {
        val h = habit(1, listOf("Health"))
        val completions = listOf(reached(1, today))
        val result = useCase(listOf(h), completions, windowDays = 7, today = today)

        assertTrue("Completion on today must be counted", result[0].completionRate > 0f)
    }

    // ── Duplicate (habitId, date) deduplication ───────────────────────────────

    @Test
    fun `multiple completions on same day count as one pair per habit`() {
        val h = habit(1, listOf("Health"))
        // Three completions on the same day; the Set deduplicates to 1 pair.
        val completions = listOf(reached(1, today), reached(1, today), reached(1, today))
        val result = useCase(listOf(h), completions, windowDays = 1, today = today)

        // completedPairs = 1, expected = 1*1 = 1 → rate = 1.0f
        assertEquals(1.0f, result[0].completionRate, 0.001f)
    }

    // ── Multi-category habits ─────────────────────────────────────────────────

    @Test
    fun `multi-category habit contributes independently to each category`() {
        val h = habit(1, listOf("Health", "Fitness"))
        val completions = listOf(reached(1, today))
        val result = useCase(listOf(h), completions, windowDays = 1, today = today)

        // Both Health and Fitness get one entry; each has rate 1.0f.
        assertEquals(2, result.size)
        val categorySet = result.map { it.category }.toSet()
        assertTrue("Health" in categorySet)
        assertTrue("Fitness" in categorySet)
        result.forEach { entry ->
            assertEquals("Rate for ${entry.category}", 1.0f, entry.completionRate, 0.001f)
        }
    }

    // ── Alphabetical ordering ─────────────────────────────────────────────────

    @Test
    fun `results are sorted alphabetically by category name`() {
        val h1 = habit(1, listOf("Zen"))
        val h2 = habit(2, listOf("Alpha"))
        val h3 = habit(3, listOf("Mind"))
        val result = useCase(listOf(h1, h2, h3), emptyList(), windowDays = 1, today = today)

        assertEquals(listOf("Alpha", "Mind", "Zen"), result.map { it.category })
    }

    // ── Mixed categorized + uncategorized habits ──────────────────────────────

    @Test
    fun `mixed categorized and uncategorized habits produce separate buckets`() {
        val h1 = habit(1, listOf("Health"))
        val h2 = habit(2, emptyList()) // → "Other"
        val result = useCase(listOf(h1, h2), emptyList(), windowDays = 1, today = today)

        val categories = result.map { it.category }.toSet()
        assertTrue("Health" in categories)
        assertTrue(LifeBalanceUseCase.UNCATEGORIZED_LABEL in categories)
        assertEquals(2, result.size)
    }

    // ── habitCount field accuracy ─────────────────────────────────────────────

    @Test
    fun `habitCount reflects the number of habits assigned to each category`() {
        val h1 = habit(1, listOf("Health"))
        val h2 = habit(2, listOf("Health"))
        val h3 = habit(3, listOf("Fitness"))
        val result = useCase(listOf(h1, h2, h3), emptyList(), windowDays = 1, today = today)

        val health = result.first { it.category == "Health" }
        val fitness = result.first { it.category == "Fitness" }
        assertEquals(2, health.habitCount)
        assertEquals(1, fitness.habitCount)
    }
}
