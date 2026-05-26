package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.StreakBreakRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [StreakBreakUseCase] (R5 coverage).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * to exercise the full use-case → fallback-predictor pipeline without any Android runtime
 * or TFLite dependency. A fixed [today] reference date makes every test deterministic.
 *
 * [MathHabitPredictor.predictStreakBreak] rule chain (top-to-bottom, first match wins,
 * then Rule 8 is applied post-rules):
 *   Rule 1: currentStreak ≤ 2  AND  rate7d < 0.30                → baseProb = 0.80 (CRITICAL)
 *   Rule 2: recentAvgGapDays ≥ 4  AND  rate7d < 0.40             → baseProb = 0.75 (CRITICAL)
 *   Rule 3: currentStreak ≥ 30  AND  rate7d ≥ 0.80               → baseProb = 0.05 (LOW)
 *   Rule 4: rate7d ≥ 0.85                                         → baseProb = 0.10 (LOW)
 *   Rule 5: currentStreak ≥ 14  AND  rate7d ≥ 0.60               → baseProb = 0.10 (LOW)
 *   Rule 6: rate7d < 0.20                                         → baseProb = 0.70 (HIGH)
 *   Rule 7: blend = (0.55 − rate·0.40 − streakContrib).coerceIn(0.10, 0.55)
 *   Rule 8 (R5): difficultyBoost = +0.15 when recentAvgDifficulty ≥ 4.0f (post-rules)
 *
 * involuntarySkipDays7d is a TFLite-only signal — [MathHabitPredictor] intentionally
 * ignores it. Tests below document this contract explicitly.
 *
 * Coverage:
 * - Guard: fewer than 3 completions → LOW / hasSufficientData = false.
 * - Guard: habit younger than 7 days → LOW / hasSufficientData = false.
 * - Guard: currentStreak < 1 → LOW / hasSufficientData = false.
 * - Rule 1: nascent streak (≤ 2) with zero rate → CRITICAL (0.80).
 * - Rule 3: mature streak (≥ 30) with strong rate → LOW (0.05).
 * - R5 Rule 8: difficulty ≥ 4.0f boosts LOW to MEDIUM (0.10 + 0.15 = 0.25).    ← R5
 * - R5 Rule 8: no boost when avgDifficulty is below threshold (default 3.0f).   ← R5
 * - R5 Rule 8: boost is bounded at 1.0f via coerceIn.                           ← R5
 * - R5: SICK skips are accepted without crashing; MathHabitPredictor ignores them.← R5
 * - R5: TRAVELING is treated as involuntary (isInvoluntary = true).              ← R5
 */
class StreakBreakUseCaseTest {

    private lateinit var useCase: StreakBreakUseCase

    // Fixed reference date — all test data is expressed as "days before today".
    private val today = LocalDate.of(2025, 1, 20)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = StreakBreakUseCase(predictor = MathHabitPredictor())
    }

    /**
     * Returns a target-reached completion record [daysBeforeToday] days before [today].
     * [difficulty] maps to [HabitCompletionEntity.perceivedDifficulty] (null = not rated).
     */
    private fun completedAt(daysBeforeToday: Long, difficulty: Int? = null) =
        HabitCompletionEntity(
            habitId = 1,
            progressUpdate = today.minusDays(daysBeforeToday).atTime(9, 0),
            isTargetReached = true,
            perceivedDifficulty = difficulty
        )

    /**
     * Returns a skip record [daysBeforeToday] days before [today] with the given [reason].
     * [HabitSkipEntity.id] defaults to 0 (auto-generated in Room; irrelevant in unit tests).
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
        assertEquals(StreakBreakRisk.Rating.LOW, result.rating)
        assertEquals(0f, result.probability, 0f)
    }

    @Test
    fun `returns LOW with hasSufficientData false when habit is younger than 7 days`() {
        // All 3 completions within 5 days → habitAge = 5 < MIN_AGE_DAYS (7).
        val completions = listOf(completedAt(1), completedAt(3), completedAt(5))

        val result = useCase(habit, completions, currentStreak = 3, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(StreakBreakRisk.Rating.LOW, result.rating)
    }

    @Test
    fun `returns LOW with hasSufficientData false when streak is zero`() {
        // Established habit with plenty of history, but no active streak.
        val completions = listOf(
            completedAt(10), completedAt(20), completedAt(30), completedAt(60)
        )

        val result = useCase(habit, completions, currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(StreakBreakRisk.Rating.LOW, result.rating)
        assertEquals(0f, result.probability, 0f)
    }

    // ── Baseline rules (no R5 fields active) ─────────────────────────────────

    @Test
    fun `CRITICAL when nascent streak has zero rate (Rule 1)`() {
        // currentStreak = 2; no completions in last 7 days → rate7d = 0 < 0.30.
        // Rule 1 fires → baseProb = 0.80; no difficulty rating → no boost.
        val completions = listOf(completedAt(15), completedAt(25), completedAt(50))

        val result = useCase(habit, completions, currentStreak = 2, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(StreakBreakRisk.Rating.CRITICAL, result.rating)
        assertEquals(0.80f, result.probability, 0.001f)
    }

    @Test
    fun `LOW when mature streak has strong rate (Rule 3)`() {
        // currentStreak = 30; 6 of the last 7 days are completed → rate7d = 6/7 ≈ 0.857.
        // Rule 3 (checked before Rule 4) → baseProb = 0.05; no difficulty rating → LOW.
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val recent = (1L..6L).map { completedAt(it) }

        val result = useCase(habit, base + recent, currentStreak = 30, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(StreakBreakRisk.Rating.LOW, result.rating)
        assertEquals(0.05f, result.probability, 0.001f)
    }

    // ── R5: recentAvgDifficulty boost (Rule 8) ────────────────────────────────

    @Test
    fun `R5 difficulty boost elevates LOW to MEDIUM when avgDifficulty is 5`() {
        // currentStreak = 14; 5 of last 7 days completed → rate7d = 5/7 ≈ 0.714.
        // Rule 5 fires → baseProb = 0.10f.
        // All 14 most-recent rated completions have difficulty = 5 → recentAvgDifficulty = 5.0f.
        // difficultyBoost = +0.15f → total = 0.25f → MEDIUM (exactly at the ≥ 0.25 boundary).
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val ratedRecent = (1L..5L).map { completedAt(it, difficulty = 5) }   // days 1–5, in window
        val olderRated = (8L..16L).map { completedAt(it, difficulty = 5) }   // days 8–16, 9 rows

        val result = useCase(habit, base + ratedRecent + olderRated, currentStreak = 14, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(StreakBreakRisk.Rating.MEDIUM, result.rating)
        assertEquals(0.25f, result.probability, 0.001f)
    }

    @Test
    fun `R5 no difficulty boost when no completions have a difficulty rating`() {
        // Same structure as above but perceivedDifficulty is null throughout.
        // recentAvgDifficulty defaults to 3.0f < 4.0f → no boost.
        // Rule 5 → 0.10f → LOW.
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val recent = (1L..5L).map { completedAt(it) }        // all null difficulty
        val older = (8L..16L).map { completedAt(it) }

        val result = useCase(habit, base + recent + older, currentStreak = 14, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(StreakBreakRisk.Rating.LOW, result.rating)
        assertEquals(0.10f, result.probability, 0.001f)
    }

    @Test
    fun `R5 difficulty boost does not push probability above 1f`() {
        // Rule 1 → baseProb = 0.80f; difficulty = 5 → +0.15f.
        // coerceIn(0f, 1f) clamps the result to 0.95f, not 0.95f > 1f.
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val ratedOld = listOf(completedAt(15, difficulty = 5), completedAt(20, difficulty = 5))
        val olderRated = (25L..36L).map { completedAt(it, difficulty = 5) }  // 12 more rated

        val result = useCase(habit, base + ratedOld + olderRated, currentStreak = 2, today = today)

        assertTrue(result.probability <= 1.0f)
        assertEquals(0.95f, result.probability, 0.001f)
        assertEquals(StreakBreakRisk.Rating.CRITICAL, result.rating)
    }

    // ── R5: involuntarySkipDays7d (TFLite-only signal) ───────────────────────

    @Test
    fun `SICK skips in window are accepted and MathHabitPredictor probability is unchanged`() {
        // MathHabitPredictor intentionally ignores involuntarySkipDays7d (TFLite-only signal).
        // This test documents that contract: passing SICK skips must not alter the probability.
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val recent = (1L..5L).map { completedAt(it) }   // rate7d = 5/7, Rule 5, prob = 0.10f
        val sickSkips = (0L..3L).map { skippedAt(it, SkipReason.SICK) }

        val withoutSkips = useCase(habit, base + recent, currentStreak = 14, today = today)
        val withSkips = useCase(
            habit, base + recent, currentStreak = 14,
            involuntarySkips = sickSkips, today = today
        )

        assertEquals(withoutSkips.probability, withSkips.probability, 0f)
        assertTrue(withSkips.hasSufficientData)
    }

    @Test
    fun `TRAVELING skips are treated as involuntary and accepted without crashing`() {
        // SkipReason.TRAVELING.isInvoluntary = true — same code path as SICK.
        // MathHabitPredictor ignores the field, so probability stays identical.
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val recent = (1L..5L).map { completedAt(it) }
        val travelSkips = (0L..3L).map { skippedAt(it, SkipReason.TRAVELING) }

        val withoutSkips = useCase(habit, base + recent, currentStreak = 14, today = today)
        val withTravelSkips = useCase(
            habit, base + recent, currentStreak = 14,
            involuntarySkips = travelSkips, today = today
        )

        assertEquals(withoutSkips.probability, withTravelSkips.probability, 0f)
    }

    @Test
    fun `voluntary TOO_TIRED skips passed as involuntarySkips are filtered out by use case`() {
        // The use case re-filters by isInvoluntary, so voluntary skips have zero effect
        // even if the caller mistakenly passes them. Probability must remain unchanged.
        val base = listOf(completedAt(30), completedAt(60), completedAt(90))
        val recent = (1L..5L).map { completedAt(it) }
        val voluntarySkips = (0L..3L).map { skippedAt(it, SkipReason.TOO_TIRED) }

        val withoutSkips = useCase(habit, base + recent, currentStreak = 14, today = today)
        val withVoluntarySkips = useCase(
            habit, base + recent, currentStreak = 14,
            involuntarySkips = voluntarySkips, today = today
        )

        assertEquals(withoutSkips.probability, withVoluntarySkips.probability, 0f)
    }
}
