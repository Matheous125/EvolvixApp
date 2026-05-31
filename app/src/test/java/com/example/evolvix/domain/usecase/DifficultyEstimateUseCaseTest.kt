package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.PerceivedDifficultyEstimate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [DifficultyEstimateUseCase] (Phase 9.4).
 *
 * Uses [MathHabitPredictor] as the strategy (no Android/TFLite dependency).
 * [MathHabitPredictor.predictPerceivedDifficulty] formula:
 *   base    = 5 − 4 × rate30d
 *   penalty = +0.5 when streak == 0 AND rate7d < 0.30
 *   result  = (base + penalty).coerceIn(1f, 5f)
 *
 * All dates are built relative to the injectable [today] / [now] parameters
 * so tests remain deterministic regardless of the real system clock.
 *
 * Coverage:
 * - [PerceivedDifficultyEstimate.hasSufficientData] false when fewer than 10 completions.
 * - Sentinel values (predicted=3, rounded=3, MODERATE, recentAvgRated=null) on cold-start.
 * - [PerceivedDifficultyEstimate.hasSufficientData] true with exactly 10 completions.
 * - Predicted difficulty is 5.0 (maximum) when rate30d=0, streak=0, rate7d < 0.30.
 * - Predicted difficulty is 1.0 (minimum) when rate30d=1.0.
 * - [PerceivedDifficultyEstimate.rounded] equals truncated int of predicted.
 * - Rating tiers: EASY, MODERATE, HARD, VERY_HARD.
 * - [PerceivedDifficultyEstimate.recentAvgRated] is null when fewer than 5 ratings exist.
 * - [PerceivedDifficultyEstimate.recentAvgRated] is the mean of the most recent ratings.
 * - Ratings older than 14 days are excluded from recentAvgRated.
 */
class DifficultyEstimateUseCaseTest {

    private lateinit var useCase: DifficultyEstimateUseCase

    private val today = LocalDate.now()

    // Fixed reference time to keep hourOfDay deterministic across test runs.
    private val now = LocalTime.of(9, 0)

    private val habit = HabitData(
        id = 1, name = "Run", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = DifficultyEstimateUseCase(predictor = MathHabitPredictor())
    }

    /**
     * Creates a [HabitCompletionEntity] for [daysAgo] days before [today].
     *
     * @param daysAgo            How many days before today the completion occurred.
     * @param isTargetReached    Whether the habit target was reached (default true).
     * @param perceivedDifficulty User-provided star rating (null if not rated).
     */
    private fun completion(
        daysAgo: Long,
        isTargetReached: Boolean = true,
        perceivedDifficulty: Int? = null
    ) = HabitCompletionEntity(
        habitId = 1,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = isTargetReached,
        perceivedDifficulty = perceivedDifficulty
    )

    // ── Cold-start guard (MIN_COMPLETIONS = 10) ───────────────────────────────

    @Test
    fun `hasSufficientData is false when fewer than 10 completions`() {
        val completions = (1L..9L).map { completion(it) }
        assertFalse(useCase(habit, completions, 0, today, now).hasSufficientData)
    }

    @Test
    fun `cold start returns sentinel values when there are no completions`() {
        val result = useCase(habit, emptyList(), 0, today, now)
        assertFalse(result.hasSufficientData)
        assertEquals(3f, result.predicted, 0f)
        assertEquals(3, result.rounded)
        assertEquals(PerceivedDifficultyEstimate.Rating.MODERATE, result.rating)
        assertNull(result.recentAvgRated)
    }

    @Test
    fun `hasSufficientData is true with exactly 10 completions`() {
        // Place all 10 completions outside the 30-day window so rate calculations
        // remain 0.0 without interfering with this specific assertion.
        val completions = (31L..40L).map { completion(it) }
        assertTrue(useCase(habit, completions, 0, today, now).hasSufficientData)
    }

    // ── Predicted difficulty ──────────────────────────────────────────────────

    @Test
    fun `predicted is 5_0 when rate30d is 0 and streak is 0 and rate7d is below 0_30`() {
        // 10 completions all older than 30 days → rate7d=0, rate30d=0.
        // MathHabitPredictor: base=5−4×0=5, penalty=+0.5 (streak=0 & rate7d=0<0.30) → 5.5 clamped to 5.0.
        val completions = (31L..40L).map { completion(it) }
        assertEquals(5.0f, useCase(habit, completions, 0, today, now).predicted, 0.001f)
    }

    @Test
    fun `predicted is 1_0 when rate30d is 1_0`() {
        // 30 unique dates in last 30 days → rate30d=30/30=1.0.
        // MathHabitPredictor: base=5−4×1=1, no penalty (streak=30≠0) → 1.0.
        val completions = (1L..30L).map { completion(it) }
        assertEquals(1.0f, useCase(habit, completions, 30, today, now).predicted, 0.001f)
    }

    // ── Rounded field ─────────────────────────────────────────────────────────

    @Test
    fun `rounded equals the truncated integer of predicted`() {
        // 10 completions at days 1-10: rate30d=10/30≈0.333, rate7d=7/7=1.0.
        // predicted ≈ 5 − 4×0.333 = 3.667 → truncated int = 3.
        val completions = (1L..10L).map { completion(it) }
        val result = useCase(habit, completions, 10, today, now)
        assertEquals(result.predicted.toInt().coerceIn(1, 5), result.rounded)
        assertEquals(3, result.rounded)
    }

    // ── Rating tiers ──────────────────────────────────────────────────────────

    @Test
    fun `rating is EASY when predicted is below 2_0`() {
        // rate30d=1.0 → predicted=1.0 < 2.0 → EASY.
        val completions = (1L..30L).map { completion(it) }
        assertEquals(
            PerceivedDifficultyEstimate.Rating.EASY,
            useCase(habit, completions, 30, today, now).rating
        )
    }

    @Test
    fun `rating is MODERATE when predicted is between 2_0 and 3_5`() {
        // 15 completions in last 30 days: rate30d=15/30=0.5.
        // predicted = 5 − 4×0.5 = 3.0, streak=15≠0 → no penalty → 3.0 ∈ [2.0, 3.5) → MODERATE.
        val completions = (1L..15L).map { completion(it) }
        assertEquals(
            PerceivedDifficultyEstimate.Rating.MODERATE,
            useCase(habit, completions, 15, today, now).rating
        )
    }

    @Test
    fun `rating is HARD when predicted is between 3_5 and 4_5`() {
        // 10 completions at days 1-10: rate30d=10/30≈0.333.
        // predicted ≈ 3.667, streak=10≠0 → no penalty → HARD (≥3.5 and <4.5).
        val completions = (1L..10L).map { completion(it) }
        assertEquals(
            PerceivedDifficultyEstimate.Rating.HARD,
            useCase(habit, completions, 10, today, now).rating
        )
    }

    @Test
    fun `rating is VERY_HARD when predicted is 4_5 or above`() {
        // All 10 completions older than 30 days: rate30d=0, rate7d=0.
        // streak=5≠0 → no penalty → predicted=5.0 ≥ 4.5 → VERY_HARD.
        val completions = (31L..40L).map { completion(it) }
        assertEquals(
            PerceivedDifficultyEstimate.Rating.VERY_HARD,
            useCase(habit, completions, 5, today, now).rating
        )
    }

    // ── recentAvgRated ────────────────────────────────────────────────────────

    @Test
    fun `recentAvgRated is null when fewer than 5 rated completions exist in last 14 days`() {
        // 10 completions total; only 4 carry a perceivedDifficulty value.
        val completions = (1L..4L).map { completion(it, perceivedDifficulty = 3) } +
                (5L..10L).map { completion(it) }
        assertNull(useCase(habit, completions, 10, today, now).recentAvgRated)
    }

    @Test
    fun `recentAvgRated is the mean of the recent rated completions when 5 or more exist`() {
        // 5 rated completions with perceivedDifficulty=4 → mean = 4.0.
        val completions = (1L..5L).map { completion(it, perceivedDifficulty = 4) } +
                (6L..10L).map { completion(it) }
        assertEquals(4.0f, useCase(habit, completions, 10, today, now).recentAvgRated!!, 0.001f)
    }

    @Test
    fun `recentAvgRated excludes ratings older than 14 days`() {
        // Days 1–5 (recent, within 14-day window): perceivedDifficulty=2 → included.
        // Days 15–19 (older than 14 days): perceivedDifficulty=5 → excluded.
        // Mean of included ratings = 2.0.
        val recentRated = (1L..5L).map { completion(it, perceivedDifficulty = 2) }
        val oldRated    = (15L..19L).map { completion(it, perceivedDifficulty = 5) }
        val completions = recentRated + oldRated
        assertEquals(2.0f, useCase(habit, completions, 5, today, now).recentAvgRated!!, 0.001f)
    }
}
