package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.AbandonmentRisk
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [AbandonmentRiskUseCase] (R2 coverage).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * to exercise the full use-case → fallback-predictor pipeline without any Android runtime
 * or TFLite dependency. A fixed [today] reference date makes every test deterministic.
 *
 * [MathHabitPredictor.predictAbandonment] rule chain (top-to-bottom, first match wins):
 *   Rule 1: adjustedGap ≥ 14                          → 0.95 (CRITICAL)
 *   Rule 2: adjustedGap ≥ 7  AND rate7d < 0.2         → 0.85 (CRITICAL)
 *   Rule 3: currentStreak ≥ 14                         → 0.05 (LOW)
 *   Rule 4: rate7d ≥ 0.8  OR  currentStreak ≥ 7       → 0.10 (LOW)
 *   Rule 5: rate30d < 0.1                              → 0.70 (HIGH)
 *   Rule 6: blend = 0.5·rate7d + 0.5·rate30d          → coerceIn(0.10, 0.60)
 *
 * where adjustedGap = (daysSinceLast − involuntarySkipDays7d).coerceAtLeast(0).  ← R2
 *
 * Coverage:
 * - Guard: fewer than 3 completions → LOW / hasSufficientData=false.
 * - Guard: habit younger than 7 days → LOW / hasSufficientData=false.
 * - No involuntary skips, gap = 15 days → CRITICAL (Rule 1).
 * - 7 SICK skips in last 7 days collapse a 10-day gap to 3 → MEDIUM (Rule 6).  ← R2
 * - Same scenario WITHOUT involuntary skips → CRITICAL (Rule 2, baseline).
 * - TRAVELING skips produce the same discount as SICK.                          ← R2
 * - Voluntary TOO_TIRED skips are NOT discounted; gap stays at 10 → CRITICAL.  ← R2
 * - Long active streak (≥ 14) overrides a small gap signal → LOW (Rule 3).
 */
class AbandonmentRiskUseCaseTest {

    private lateinit var useCase: AbandonmentRiskUseCase

    // Fixed reference date: all test data is expressed as "days before today".
    private val today = LocalDate.of(2025, 1, 20)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = AbandonmentRiskUseCase(predictor = MathHabitPredictor())
    }

    /** Creates a completed habit record [daysBeforeToday] days before [today]. */
    private fun completedAt(daysBeforeToday: Long) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = today.minusDays(daysBeforeToday).atTime(9, 0),
        isTargetReached = true
    )

    /**
     * Creates a skip record [daysBeforeToday] days before [today] with the given [reason].
     * [HabitSkipEntity.id] defaults to 0 (auto-generated in Room, irrelevant in unit tests).
     */
    private fun skippedAt(daysBeforeToday: Long, reason: SkipReason) = HabitSkipEntity(
        habitId = 1,
        skippedAt = today.minusDays(daysBeforeToday).atTime(9, 0),
        reason = reason
    )

    // ── Sufficiency guards ────────────────────────────────────────────────────

    @Test
    fun `returns LOW with hasSufficientData false when fewer than 3 completions`() {
        // Only 2 completions — the MIN_COMPLETIONS = 3 guard fires immediately.
        val completions = listOf(completedAt(30), completedAt(60))

        val result = useCase(habit, completions, currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.LOW, result.rating)
        assertEquals(0f, result.probability, 0f)
    }

    @Test
    fun `returns LOW with hasSufficientData false when habit is younger than 7 days`() {
        // 5 completions, all within the last 5 days → habitAge = 5 < MIN_AGE_DAYS (7).
        val completions = (1L..5L).map { completedAt(it) }

        val result = useCase(habit, completions, currentStreak = 5, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.LOW, result.rating)
    }

    // ── Rule 1: adjustedGap ≥ 14 → CRITICAL ──────────────────────────────────

    @Test
    fun `CRITICAL when gap is 15 days and no involuntary skips (Rule 1)`() {
        // Habit established 90 days ago; last completion was 15 days ago.
        // adjustedGap = 15 − 0 = 15 ≥ 14  → Rule 1 → 0.95f → CRITICAL.
        val completions = listOf(
            completedAt(15), completedAt(50), completedAt(70), completedAt(90)
        )

        val result = useCase(habit, completions, currentStreak = 0, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.CRITICAL, result.rating)
        assertEquals(0.95f, result.probability, 0.001f)
    }

    // ── R2: involuntary skips discount the gap ────────────────────────────────

    @Test
    fun `MEDIUM when 7 SICK days collapse a 10-day gap to 3 (R2 Rule 6)`() {
        // Last completion was 10 days ago; 7 SICK skips on days 0–6 (Jan 20–14) qualify.
        //   involuntarySkipDays7d = 7  →  adjustedGap = 10 − 7 = 3.
        //   Rule 1: 3 < 14  → skip.
        //   Rule 2: 3 < 7   → skip.
        //   Rules 3/4: streak=0 < 14, rate7d=0 < 0.8  → skip.
        //   Rule 5: rate30d = 15/30 = 0.5 ≥ 0.1  → skip.
        //   Rule 6: blend = 0.5·0 + 0.5·0.5 = 0.25  →  0.60 − 0.125 = 0.475  → MEDIUM.
        val completions = (10L..24L).map { completedAt(it) }  // 15 completions, days 10–24
        val sickSkips = (0L..6L).map { skippedAt(it, SkipReason.SICK) }  // 7 days in last 7d

        val result = useCase(
            habit, completions, currentStreak = 0,
            involuntarySkips = sickSkips, today = today
        )

        assertTrue(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.MEDIUM, result.rating)
        assertEquals(0.475f, result.probability, 0.001f)
    }

    @Test
    fun `CRITICAL without involuntary skips when gap is 10 days (Rule 2 baseline)`() {
        // Same completions as the SICK test above, but no involuntary skips passed.
        // adjustedGap = 10 ≥ 7  AND  rate7d = 0 < 0.2  →  Rule 2  →  0.85f  →  CRITICAL.
        val completions = (10L..24L).map { completedAt(it) }

        val result = useCase(habit, completions, currentStreak = 0, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.CRITICAL, result.rating)
        assertEquals(0.85f, result.probability, 0.001f)
    }

    @Test
    fun `TRAVELING skips are treated as involuntary and discount the gap`() {
        // Identical numeric setup to the SICK test; only the SkipReason differs.
        // TRAVELING.isInvoluntary = true, so the discount applies identically.
        val completions = (10L..24L).map { completedAt(it) }
        val travelSkips = (0L..6L).map { skippedAt(it, SkipReason.TRAVELING) }

        val result = useCase(
            habit, completions, currentStreak = 0,
            involuntarySkips = travelSkips, today = today
        )

        assertTrue(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.MEDIUM, result.rating)
    }

    @Test
    fun `voluntary TOO_TIRED skips are NOT discounted and gap remains CRITICAL`() {
        // TOO_TIRED.isInvoluntary = false — the use case must filter these out.
        // Without the discount, adjustedGap = 10 → Rule 2 fires → CRITICAL.
        val completions = (10L..24L).map { completedAt(it) }
        val voluntarySkips = (0L..6L).map { skippedAt(it, SkipReason.TOO_TIRED) }

        val result = useCase(
            habit, completions, currentStreak = 0,
            involuntarySkips = voluntarySkips, today = today
        )

        assertEquals(AbandonmentRisk.Rating.CRITICAL, result.rating)
    }

    // ── Rule 3: long streak → LOW ─────────────────────────────────────────────

    @Test
    fun `LOW when currentStreak is 14 even with a small gap (Rule 3)`() {
        // daysSinceLast = 1 → adjustedGap = 1 < 7 → Rules 1 & 2 skip.
        // currentStreak = 14 passed explicitly → Rule 3 → 0.05f → LOW.
        val completions = listOf(
            completedAt(1), completedAt(40), completedAt(70), completedAt(90)
        )

        val result = useCase(habit, completions, currentStreak = 14, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(AbandonmentRisk.Rating.LOW, result.rating)
        assertEquals(0.05f, result.probability, 0.001f)
    }
}
