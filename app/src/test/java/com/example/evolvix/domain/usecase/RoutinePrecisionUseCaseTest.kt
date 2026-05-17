package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.RoutinePrecision
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [RoutinePrecisionUseCase].
 *
 * The use case delegates stddev computation to [MathHabitPredictor.computeRoutinePrecision],
 * which requires ≥ 5 completions. Test data is built with fixed timestamps so that
 * the standard deviation of minutes-from-midnight is predictable and verifiable by hand.
 *
 * Stddev derivations used below (population stddev over minutes-from-midnight):
 * - All at 09:00 (540 min): σ = 0 → VERY_CONSISTENT
 * - At 08:00, 08:30, 09:00, 09:30, 10:00 (480,510,540,570,600): σ ≈ 42.4 → CONSISTENT
 * - At 07:00, 08:30, 09:00, 10:00, 10:30 (420,510,540,600,630): σ ≈ 73.5 → VARIABLE
 * - At 06:00, 09:00, 12:00, 15:00, 22:00 (360,540,720,900,1320): σ ≈ 329 → ERRATIC
 *
 * Coverage:
 * - Returns null when fewer than 5 completions exist.
 * - VERY_CONSISTENT for σ = 0 (identical timestamps).
 * - CONSISTENT for σ in [30, 60).
 * - VARIABLE for σ in [60, 120).
 * - ERRATIC for σ ≥ 120.
 * - sampleCount always equals completions.size.
 */
class RoutinePrecisionUseCaseTest {

    private lateinit var useCase: RoutinePrecisionUseCase

    private val baseDate = LocalDate.of(2025, 1, 1)

    @Before
    fun setUp() {
        useCase = RoutinePrecisionUseCase(predictor = MathHabitPredictor())
    }

    private fun completionAt(hour: Int, minute: Int = 0, daysOffset: Long = 0L) =
        HabitCompletionEntity(
            habitId = 1,
            progressUpdate = baseDate.plusDays(daysOffset).atTime(hour, minute),
            isTargetReached = true
        )

    // ── Null / insufficient data ──────────────────────────────────────────────

    @Test
    fun `returns null when completions list is empty`() {
        assertNull(useCase(emptyList()))
    }

    @Test
    fun `returns null when fewer than 5 completions exist`() {
        // MIN_PRECISION_SAMPLES = 5, so 4 is below the threshold.
        val completions = (0L..3L).map { completionAt(9, 0, it) }
        assertNull(useCase(completions))
    }

    // ── Rating tiers ──────────────────────────────────────────────────────────

    @Test
    fun `VERY_CONSISTENT when all completions at the same time (sigma = 0)`() {
        // σ = 0 < 30 → VERY_CONSISTENT
        val completions = (0L..4L).map { completionAt(9, 0, it) }
        val result = useCase(completions)
        assertNotNull(result)
        assertEquals(RoutinePrecision.Rating.VERY_CONSISTENT, result!!.rating)
        assertEquals(0.0, result.stddevMinutes, 0.001)
    }

    @Test
    fun `CONSISTENT when completions span about one hour (sigma approx 42 min)`() {
        // Hours: 08:00, 08:30, 09:00, 09:30, 10:00 — σ = sqrt(1800) ≈ 42.4, in [30, 60)
        val completions = listOf(
            completionAt(8, 0, 0),
            completionAt(8, 30, 1),
            completionAt(9, 0, 2),
            completionAt(9, 30, 3),
            completionAt(10, 0, 4)
        )
        val result = useCase(completions)
        assertNotNull(result)
        assertEquals(RoutinePrecision.Rating.CONSISTENT, result!!.rating)
        assertTrue(
            "Expected stddev in [30, 60) but got ${result.stddevMinutes}",
            result.stddevMinutes >= 30.0 && result.stddevMinutes < 60.0
        )
    }

    @Test
    fun `VARIABLE when completions spread over about two hours (sigma approx 73 min)`() {
        // Hours: 07:00(420), 08:30(510), 09:00(540), 10:00(600), 10:30(630) — σ ≈ 73.5, in [60, 120)
        val completions = listOf(
            completionAt(7, 0, 0),
            completionAt(8, 30, 1),
            completionAt(9, 0, 2),
            completionAt(10, 0, 3),
            completionAt(10, 30, 4)
        )
        val result = useCase(completions)
        assertNotNull(result)
        assertEquals(RoutinePrecision.Rating.VARIABLE, result!!.rating)
        assertTrue(
            "Expected stddev in [60, 120) but got ${result.stddevMinutes}",
            result.stddevMinutes >= 60.0 && result.stddevMinutes < 120.0
        )
    }

    @Test
    fun `ERRATIC when completions are scattered throughout the day (sigma over 120 min)`() {
        // Hours: 06:00, 09:00, 12:00, 15:00, 22:00 — σ ≈ 329, well above 120
        val completions = listOf(
            completionAt(6, 0, 0),
            completionAt(9, 0, 1),
            completionAt(12, 0, 2),
            completionAt(15, 0, 3),
            completionAt(22, 0, 4)
        )
        val result = useCase(completions)
        assertNotNull(result)
        assertEquals(RoutinePrecision.Rating.ERRATIC, result!!.rating)
        assertTrue(
            "Expected stddev >= 120 but got ${result.stddevMinutes}",
            result.stddevMinutes >= 120.0
        )
    }

    // ── sampleCount ───────────────────────────────────────────────────────────

    @Test
    fun `sampleCount equals the number of completions passed in`() {
        val completions = (0L..9L).map { completionAt(9, 0, it) }
        val result = useCase(completions)
        assertNotNull(result)
        assertEquals(10, result!!.sampleCount)
    }
}
