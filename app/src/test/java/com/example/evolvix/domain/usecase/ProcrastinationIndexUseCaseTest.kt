package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.ProcrastinationIndex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [ProcrastinationIndexUseCase].
 *
 * The use case delegates to [MathHabitPredictor.computeProcrastination], which applies
 * the standard third-moment skewness formula over completion hour-of-day values.
 * Requires ≥ 10 completions (MIN_PROCRASTINATION_SAMPLES).
 *
 * Key statistical insight (verified by hand):
 * Standard moment-based skewness produces **negative** values when completions cluster
 * at HIGH hours (late = procrastination), because the few early outliers form a long
 * left tail. Conversely, mostly-early completions produce **positive** skewness.
 *
 * Skewness derivations for test cases:
 * - 8 × 22h + 2 × 06h: mass at 22, tail left → skewness ≈ −1.5 → PROCRASTINATOR
 * - 8 × 06h + 2 × 22h: mass at 06, tail right → skewness ≈ +1.5 → EARLY_COMPLETER
 * - 15,17,18,19,20,21,22,22,23,23: slight late bias → skewness ≈ −0.53 → MILD_PROCRASTINATOR
 * - Arithmetic sequence 0,2,4…18 (symmetric): skewness = 0.0 → BALANCED
 *
 * Coverage:
 * - Returns null when fewer than 10 completions exist.
 * - PROCRASTINATOR for late-clustered completions (negative skewness ≤ −1.0).
 * - MILD_PROCRASTINATOR for slight late bias (skewness in (−1.0, −0.5]).
 * - BALANCED for symmetric distribution (skewness ≈ 0).
 * - EARLY_COMPLETER for early-clustered completions (positive skewness ≥ 0.5).
 * - sampleCount equals completions.size.
 */
class ProcrastinationIndexUseCaseTest {

    private lateinit var useCase: ProcrastinationIndexUseCase

    private val baseDate = LocalDate.of(2025, 1, 1)

    private val habit = HabitData(
        id = 1, name = "Meditate", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = ProcrastinationIndexUseCase(predictor = MathHabitPredictor())
    }

    /**
     * Creates a completion at [hour]:00 on [baseDate] + [daysOffset].
     * Each completion is on a different day so the predator's date logic stays clean.
     */
    private fun completionAt(hour: Int, daysOffset: Long) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = baseDate.plusDays(daysOffset).atTime(hour, 0),
        isTargetReached = true
    )

    // ── Null / insufficient data ──────────────────────────────────────────────

    @Test
    fun `returns null when completions list is empty`() {
        assertNull(useCase(habit, emptyList()))
    }

    @Test
    fun `returns null when fewer than 10 completions exist`() {
        // MIN_PROCRASTINATION_SAMPLES = 10; provide only 9.
        val completions = (0L..8L).map { completionAt(9, it) }
        assertNull(useCase(habit, completions))
    }

    // ── Rating tiers ──────────────────────────────────────────────────────────

    @Test
    fun `PROCRASTINATOR for late-clustered completions (negative skewness at or below minus 1_0)`() {
        // 8 completions at 22h, 2 at 06h → mass at late hours → negative skewness ≈ −1.5
        val hours = listOf(22, 22, 22, 22, 22, 22, 22, 22, 6, 6)
        val completions = hours.mapIndexed { i, h -> completionAt(h, i.toLong()) }

        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ProcrastinationIndex.Rating.PROCRASTINATOR, result!!.rating)
        assertTrue(
            "Expected skewness ≤ −1.0 but got ${result.skewness}",
            result.skewness <= -1.0
        )
    }

    @Test
    fun `MILD_PROCRASTINATOR for slight late bias (skewness between minus 1_0 and minus 0_5)`() {
        // Hours [15,17,18,19,20,21,22,22,23,23] → skewness ≈ −0.53, in (−1.0, −0.5]
        val hours = listOf(15, 17, 18, 19, 20, 21, 22, 22, 23, 23)
        val completions = hours.mapIndexed { i, h -> completionAt(h, i.toLong()) }

        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ProcrastinationIndex.Rating.MILD_PROCRASTINATOR, result!!.rating)
        assertTrue(
            "Expected skewness in (−1.0, −0.5] but got ${result.skewness}",
            result.skewness > -1.0 && result.skewness <= -0.5
        )
    }

    @Test
    fun `BALANCED for symmetric hour distribution (skewness near zero)`() {
        // Arithmetic sequence 0,2,4,6,8,10,12,14,16,18 → symmetric around 9 → skewness = 0
        val hours = listOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18)
        val completions = hours.mapIndexed { i, h -> completionAt(h, i.toLong()) }

        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ProcrastinationIndex.Rating.BALANCED, result!!.rating)
        assertTrue(
            "Expected |skewness| < 0.5 but got ${result.skewness}",
            result.skewness > -0.5 && result.skewness < 0.5
        )
    }

    @Test
    fun `EARLY_COMPLETER for early-clustered completions (positive skewness at or above 0_5)`() {
        // 8 completions at 06h, 2 at 22h → mass at early hours → positive skewness ≈ +1.5
        val hours = listOf(6, 6, 6, 6, 6, 6, 6, 6, 22, 22)
        val completions = hours.mapIndexed { i, h -> completionAt(h, i.toLong()) }

        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(ProcrastinationIndex.Rating.EARLY_COMPLETER, result!!.rating)
        assertTrue(
            "Expected skewness ≥ 0.5 but got ${result.skewness}",
            result.skewness >= 0.5
        )
    }

    // ── sampleCount ───────────────────────────────────────────────────────────

    @Test
    fun `sampleCount equals the number of completions passed in`() {
        val completions = (0L..14L).map { completionAt(9, it) }
        val result = useCase(habit, completions)
        assertNotNull(result)
        assertEquals(15, result!!.sampleCount)
    }
}
