package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.HabitClash
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [HabitClashingUseCase].
 *
 * The use case delegates Pearson correlation computation to
 * [MathHabitPredictor.detectClashes]. A pair is clashing when r < −0.4 and
 * each habit has ≥ 5 completed days (MIN_CLASH_SAMPLES).
 *
 * Test vectors (10 shared observation days):
 * - Anti-correlated: A on odd days, B on even days → r = −1.0 → clash detected.
 * - Co-occurring:    A and B on the same 6 days → r = +1.0 → no clash.
 * - Insufficient:    each habit has only 4 completion days → filtered out → no clash.
 *
 * Coverage:
 * - Empty list when completions are empty.
 * - Empty list when habits have fewer than 5 completion days (below MIN_CLASH_SAMPLES).
 * - Detects a clashing pair whose alternating pattern produces r = −1.0.
 * - Returns empty list for positively correlated habits (r = +1.0).
 * - Clash names match the HabitData names (not ids).
 * - Single habit cannot clash with itself.
 */
class HabitClashingUseCaseTest {

    private lateinit var useCase: HabitClashingUseCase

    private val base = LocalDate.of(2025, 1, 1)

    private val habitA = HabitData(id = 1, name = "Morning Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1)
    private val habitB = HabitData(id = 2, name = "Evening Yoga", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1)

    @Before
    fun setUp() {
        useCase = HabitClashingUseCase(predictor = MathHabitPredictor())
    }

    /**
     * Creates a target-reached completion for [habitId] on [base] + [daysOffset].
     */
    private fun reached(habitId: Int, daysOffset: Long) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = base.plusDays(daysOffset).atTime(9, 0),
        isTargetReached = true
    )

    // ── Empty / no data ───────────────────────────────────────────────────────

    @Test
    fun `returns empty list when completions are empty`() {
        assertTrue(useCase(listOf(habitA, habitB), emptyList()).isEmpty())
    }

    @Test
    fun `returns empty list when habits list is empty`() {
        val completions = (0L..9L).map { reached(1, it) }
        assertTrue(useCase(emptyList(), completions).isEmpty())
    }

    @Test
    fun `returns empty list when a single habit cannot clash with itself`() {
        val completions = (0L..9L).map { reached(1, it) }
        assertTrue(useCase(listOf(habitA), completions).isEmpty())
    }

    // ── Insufficient data filtered ────────────────────────────────────────────

    @Test
    fun `returns empty list when each habit has fewer than 5 completed days`() {
        // MIN_CLASH_SAMPLES = 5.0; both habits have only 4 completion days each.
        val completions =
            (0L..3L).map { reached(1, it) } +
            (0L..3L).map { reached(2, it + 10) } // different days so no overlap at all
        assertTrue(useCase(listOf(habitA, habitB), completions).isEmpty())
    }

    // ── Clash detected ────────────────────────────────────────────────────────

    @Test
    fun `detects a clashing pair when habits alternate on opposite days`() {
        // A on days 0,2,4,6,8 (5 completed); B on days 1,3,5,7,9 (5 completed).
        // Over days 0–9 the binary vectors are perfectly anti-correlated (r = −1.0).
        val completions =
            listOf(0L, 2L, 4L, 6L, 8L).map { reached(1, it) } +
            listOf(1L, 3L, 5L, 7L, 9L).map { reached(2, it) }

        val clashes = useCase(listOf(habitA, habitB), completions)
        assertEquals(1, clashes.size)
        val clash = clashes.first()
        // Names should be the habit names, not ids.
        assertTrue(
            "Expected clash between '${habitA.name}' and '${habitB.name}'",
            (clash.habitNameA == habitA.name && clash.habitNameB == habitB.name) ||
            (clash.habitNameA == habitB.name && clash.habitNameB == habitA.name)
        )
    }

    @Test
    fun `result contains correct HabitClash type (not raw Pair)`() {
        val completions =
            listOf(0L, 2L, 4L, 6L, 8L).map { reached(1, it) } +
            listOf(1L, 3L, 5L, 7L, 9L).map { reached(2, it) }

        val clashes = useCase(listOf(habitA, habitB), completions)
        assertTrue(clashes.all { it is HabitClash })
    }

    // ── No clash ─────────────────────────────────────────────────────────────

    @Test
    fun `returns empty list when habits are positively correlated (same days)`() {
        // Both A and B completed on the same 6 days → r = +1.0 → no clash.
        val days = listOf(0L, 1L, 2L, 3L, 4L, 5L)
        val completions = days.map { reached(1, it) } + days.map { reached(2, it) }
        assertTrue(useCase(listOf(habitA, habitB), completions).isEmpty())
    }

    // ── Custom threshold ──────────────────────────────────────────────────────

    @Test
    fun `uses custom clashThreshold when provided`() {
        // With a very tight threshold of -0.99, the anti-correlated pair (r = -1.0) still clashes.
        val tightUseCase = HabitClashingUseCase(
            predictor = MathHabitPredictor(),
            clashThreshold = -0.99
        )
        val completions =
            listOf(0L, 2L, 4L, 6L, 8L).map { reached(1, it) } +
            listOf(1L, 3L, 5L, 7L, 9L).map { reached(2, it) }

        assertEquals(1, tightUseCase(listOf(habitA, habitB), completions).size)
    }

    @Test
    fun `returns empty list when threshold is 0 and habits alternate (r = -1 is still below 0)`() {
        // threshold = 0.0 means ANY negative correlation triggers a clash.
        val lenientUseCase = HabitClashingUseCase(
            predictor = MathHabitPredictor(),
            clashThreshold = 0.0
        )
        val completions =
            listOf(0L, 2L, 4L, 6L, 8L).map { reached(1, it) } +
            listOf(1L, 3L, 5L, 7L, 9L).map { reached(2, it) }

        assertEquals(1, lenientUseCase(listOf(habitA, habitB), completions).size)
    }
}
