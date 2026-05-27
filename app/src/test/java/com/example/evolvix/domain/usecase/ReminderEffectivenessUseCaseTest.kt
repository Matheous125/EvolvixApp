package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [ReminderEffectivenessUseCase] (R8 coverage).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * so the full use-case → fallback-predictor pipeline runs without any Android runtime
 * or TFLite dependency. A fixed [today] reference date makes every test deterministic.
 *
 * [MathHabitPredictor.predictReminderCompletion] rule chain relevant to R8:
 *   Base: 0.4 × rate7d + 0.4 × rate30d + 0.05 (if streak ≥ 3)
 *   reminderSent = 0 → return base (no boost)
 *   R8 suppress: snoozeCountToday ≥ 3 AND recentAvgDifficulty ≥ 4.0 → return base (lift = 0)
 *   Boost (otherwise): (0.10 + 0.20 × (1 − rate7d)) × (1 − snoozeCount/3).coerceIn(0, 0.5)
 *
 * Coverage:
 * - Guard: fewer than [ReminderEffectivenessUseCase.MIN_COMPLETIONS] completions
 *   → hasSufficientData = false, recommendSend = true.
 * - R8: snoozeCountToday ≥ 3 AND recentAvgDifficulty ≥ 4.0 → lift = 0 → suppress.
 * - Low engagement (rate7d = 0) + no snooze → large positive lift → recommendSend = true.
 * - High engagement → measurably lower lift than low engagement.
 * - Default snoozeCountToday = 0 → full boost applied, lift > 0.
 */
class ReminderEffectivenessUseCaseTest {

    private lateinit var useCase: ReminderEffectivenessUseCase

    // Fixed reference date — all test data expressed as "N days before today".
    private val today = LocalDate.of(2025, 6, 15)

    private val habit = HabitData(
        id = 1, name = "Meditate", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = ReminderEffectivenessUseCase(predictor = MathHabitPredictor())
    }

    /**
     * Creates a target-reached completion record [daysAgo] days before [today].
     * [difficulty] maps to [HabitCompletionEntity.perceivedDifficulty] (null = not rated).
     */
    private fun completedAt(daysAgo: Long, difficulty: Int? = null) =
        HabitCompletionEntity(
            habitId = 1,
            progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
            isTargetReached = true,
            perceivedDifficulty = difficulty
        )

    // ── Guard ────────────────────────────────────────────────────────────────

    /**
     * With fewer than [ReminderEffectivenessUseCase.MIN_COMPLETIONS] completions, the use
     * case returns a safe-default result: always send the reminder (new habit, no suppression).
     */
    @Test
    fun insufficientData_returnsSafeDefault() {
        val completions = (1L..3L).map { completedAt(it) }   // only 3, threshold is 5

        val result = useCase(habit, completions, currentStreak = 1, today = today)

        assertFalse("hasSufficientData should be false for new habits", result.hasSufficientData)
        assertTrue("New habits should always receive reminders", result.recommendSend)
        assertEquals("Lift should be 0 when data is insufficient", 0f, result.lift, 0.001f)
    }

    // ── R8 suppression ───────────────────────────────────────────────────────

    /**
     * When the user snoozed ≥ 3 times today AND perceived difficulty ≥ 4.0,
     * the R8 rule zeroes the boost → lift = 0 → reminder should be suppressed.
     */
    @Test
    fun r8_highSnoozeAndHighDifficulty_liftIsZeroAndSuppressed() {
        // 10 completions: 7 in the last 7 days (rate7d=1.0), all rated difficulty=5
        val completions = (0L..9L).map { completedAt(it, difficulty = 5) }

        val result = useCase(
            habit, completions,
            currentStreak = 7,
            today = today,
            snoozeCountToday = 3       // R8 threshold
        )

        // recentAvgDifficulty = 5.0 (≥ 4.0) → suppression fires
        assertTrue("Should have sufficient data", result.hasSufficientData)
        assertEquals("Lift must be 0 when R8 suppression fires", 0f, result.lift, 0.001f)
        assertFalse("Reminder should be suppressed when lift = 0", result.recommendSend)
    }

    /**
     * R8 boundary: snoozeCount = 2 (below threshold of 3) with high difficulty should NOT
     * suppress — the boost is reduced but still positive, so the reminder is sent.
     */
    @Test
    fun r8_snoozeCountBelowThreshold_reminderStillSent() {
        val completions = (0L..9L).map { completedAt(it, difficulty = 5) }

        val result = useCase(
            habit, completions,
            currentStreak = 7,
            today = today,
            snoozeCountToday = 2       // one below threshold
        )

        assertTrue("Lift should be positive when snooze < 3", result.lift > 0f)
        assertTrue("Reminder should be sent when snooze < 3", result.recommendSend)
    }

    // ── Lift magnitude ───────────────────────────────────────────────────────

    /**
     * A habit with zero completions in the last 7 days receives the maximum reminder boost
     * (rate7d = 0 → boost = 0.30), which comfortably exceeds [ReminderEffectivenessUseCase.SUPPRESS_THRESHOLD].
     */
    @Test
    fun lowEngagement_defaultSnooze_liftExceedsThreshold() {
        // 6 completions, all 8–13 days ago → outside 7-day window, inside 30-day window
        val completions = (8L..13L).map { completedAt(it) }

        val result = useCase(
            habit, completions,
            currentStreak = 0,
            today = today,
            snoozeCountToday = 0
        )

        assertTrue(
            "Low-engagement lift (${result.lift}) should exceed SUPPRESS_THRESHOLD",
            result.lift > ReminderEffectivenessUseCase.SUPPRESS_THRESHOLD
        )
        assertTrue(result.recommendSend)
    }

    /**
     * High-engagement habits (strong rate7d) receive a smaller reminder boost
     * than low-engagement habits because they have less to gain from the nudge.
     */
    @Test
    fun highEngagement_liftSmallerThanLowEngagement() {
        // Low engagement: all completions outside 7-day window
        val lowEngagementCompletions = (8L..13L).map { completedAt(it) }
        // High engagement: 6 completions in the last 7 days (rate7d = 6/7 ≈ 0.86)
        val highEngagementCompletions = (1L..6L).map { completedAt(it) }

        val lowLift = useCase(habit, lowEngagementCompletions,
            currentStreak = 0, today = today, snoozeCountToday = 0).lift
        val highLift = useCase(habit, highEngagementCompletions,
            currentStreak = 6, today = today, snoozeCountToday = 0).lift

        assertTrue(
            "High-engagement lift ($highLift) should be smaller than low-engagement lift ($lowLift)",
            highLift < lowLift
        )
    }

    // ── Default parameter ────────────────────────────────────────────────────

    /**
     * Calling [ReminderEffectivenessUseCase.invoke] without [snoozeCountToday] defaults to 0,
     * which means the full reminder boost is applied and lift is positive.
     * This documents the contract for [StatisticsViewModel] which has no Context.
     */
    @Test
    fun defaultSnoozeZero_fullBoostApplied() {
        val completions = (1L..6L).map { completedAt(it, difficulty = 5) }

        // No snoozeCountToday argument — uses default = 0
        val result = useCase(habit, completions, currentStreak = 3, today = today)

        assertTrue("Lift should be positive when snoozeCountToday defaults to 0", result.lift > 0f)
        assertTrue(result.recommendSend)
    }
}
