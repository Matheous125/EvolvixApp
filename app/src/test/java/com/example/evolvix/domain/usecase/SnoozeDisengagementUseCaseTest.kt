package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SnoozeDisengagementRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [SnoozeDisengagementUseCase] (Phase 9.2).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * to exercise the full use-case → fallback-predictor pipeline without any Android runtime
 * or TFLite dependency. A fixed [today] reference date makes every test deterministic.
 *
 * [MathHabitPredictor.predictSnoozeDisengagement] rule chain (top-to-bottom, first match wins):
 *   Rule 1: avgSnooze >= 2  AND  rate7d < 0.30                       → 0.85 (CRITICAL)
 *   Rule 2: snoozeFreq >= 0.80                                        → 0.65 (HIGH)
 *   Rule 3: streak >= 14                                              → 0.10 (LOW)
 *   Rule 4: avgSnooze >= 1  AND  rate7d < 0.50                       → 0.50 (HIGH)
 *   Rule 5: rate7d >= 0.70                                            → 0.15 (LOW)
 *   Default: (0.20 + avgSnooze * 0.075).coerceIn(0.20, 0.35)
 *
 * Data-sufficiency guards (checked before features are assembled):
 *   G1: completions.isEmpty()                                         → hasSufficientData=false
 *   G2: habitAge < 7 days                                             → hasSufficientData=false
 *   G3: reminderCompletions30d.size < 5 (MIN_REMINDER_COMPLETIONS)   → hasSufficientData=false
 *
 * Coverage:
 *   - G1: empty completions list.
 *   - G2: habit younger than 7 days / exactly 7 days old (boundary).
 *   - G3: fewer than 5 qualifying reminder completions in 30 days.
 *   - G3: snoozeCount=null reminder completions are excluded from the 30-day count.
 *   - G3: fromReminder=false completions are excluded from the 30-day count.
 *   - G3: reminder completions older than 30 days are excluded.
 *   - 14-day window: completions between 30d and 14d ago contribute to sufficiency
 *     but NOT to snooze metrics (avgSnooze/snoozeFreq fall back to 0.0).
 *   - Integration Rule 1: avgSnooze=2.0, rate7d=0     → CRITICAL (0.85f).
 *   - Integration Rule 2: snoozeFreq=0.80, avgSnooze<2 → HIGH    (0.65f).
 *   - Integration Rule 3: streak=20, avgSnooze<2       → LOW     (0.10f).
 *   - Integration Rule 4: avgSnooze=1.2, rate7d≈0.286  → HIGH    (0.50f).
 *   - Integration Rule 5: avgSnooze=0,  rate7d≈0.714   → LOW     (0.15f).
 *   - habitId is forwarded from habit to result.
 */
class SnoozeDisengagementUseCaseTest {

    private lateinit var useCase: SnoozeDisengagementUseCase

    /** Fixed reference date; all test data is expressed as "days before today". */
    private val today = LocalDate.of(2025, 6, 15)

    private val habit = HabitData(
        id = 42, name = "Meditation", currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    @Before
    fun setUp() {
        useCase = SnoozeDisengagementUseCase(predictor = MathHabitPredictor())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Creates a regular (non-reminder) completion [daysAgo] days before [today]. */
    private fun completedAt(daysAgo: Long, isTargetReached: Boolean = true) =
        HabitCompletionEntity(
            habitId = habit.id,
            progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
            isTargetReached = isTargetReached,
            fromReminder = false,
            snoozeCount = null
        )

    /**
     * Creates a reminder-driven completion [daysAgo] days before [today].
     * [snoozeCount] is non-null so the row qualifies for the snooze-sufficiency count.
     */
    private fun reminderAt(
        daysAgo: Long,
        snoozeCount: Int,
        isTargetReached: Boolean = true
    ) = HabitCompletionEntity(
        habitId = habit.id,
        progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
        isTargetReached = isTargetReached,
        fromReminder = true,
        snoozeCount = snoozeCount
    )

    // ── Guard G1: empty completions ───────────────────────────────────────────

    @Test
    fun `G1 empty completions returns hasSufficientData false and LOW`() {
        val result = useCase(habit, emptyList(), currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(SnoozeDisengagementRisk.Rating.LOW, result.rating)
        assertEquals(0f, result.probability, 0f)
        assertEquals(habit.id, result.habitId)
    }

    // ── Guard G2: habit age ───────────────────────────────────────────────────

    @Test
    fun `G2 habit younger than 7 days returns hasSufficientData false`() {
        // Earliest completion 5 days ago → habitAge = 5 < MIN_AGE_DAYS (7).
        val completions = (1L..5L).map { completedAt(it) }

        val result = useCase(habit, completions, currentStreak = 5, today = today)

        assertFalse(result.hasSufficientData)
        assertEquals(SnoozeDisengagementRisk.Rating.LOW, result.rating)
    }

    @Test
    fun `G2 habit exactly 7 days old passes the age guard`() {
        // Earliest completion 7 days ago → habitAge = 7 == MIN_AGE_DAYS → guard passes.
        // Five qualifying reminder completions ensure the G3 guard also passes.
        val old = completedAt(7)
        val reminders = (1L..5L).map { reminderAt(it, snoozeCount = 0) }

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 5, today = today)

        assertTrue(result.hasSufficientData)
    }

    // ── Guard G3: insufficient qualifying reminder completions ────────────────

    @Test
    fun `G3 fewer than 5 qualifying reminder completions returns hasSufficientData false`() {
        // Habit old enough; only 4 qualifying reminder completions in past 30 days.
        val old = completedAt(60)
        val reminders = (1L..4L).map { reminderAt(it, snoozeCount = 1) }

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
    }

    @Test
    fun `G3 reminder completions with null snoozeCount are excluded from sufficiency count`() {
        // Five fromReminder=true completions, but all have snoozeCount=null → count = 0.
        val old = completedAt(60)
        val nullSnoozeReminders = (1L..5L).map { daysAgo ->
            HabitCompletionEntity(
                habitId = habit.id,
                progressUpdate = today.minusDays(daysAgo).atTime(9, 0),
                isTargetReached = true,
                fromReminder = true,
                snoozeCount = null
            )
        }

        val result = useCase(habit, listOf(old) + nullSnoozeReminders, currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
    }

    @Test
    fun `G3 fromReminder false completions are excluded from sufficiency count`() {
        // Five completions with fromReminder=false → not reminder-driven → count = 0.
        val old = completedAt(60)
        val nonReminders = (1L..5L).map { completedAt(it) }

        val result = useCase(habit, listOf(old) + nonReminders, currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
    }

    @Test
    fun `G3 reminder completions older than 30 days are excluded from sufficiency count`() {
        // Five reminder completions at days 31–35 ago → strictly outside the 30-day window.
        val old = completedAt(90)
        val outdatedReminders = (31L..35L).map { reminderAt(it, snoozeCount = 2) }

        val result = useCase(habit, listOf(old) + outdatedReminders, currentStreak = 0, today = today)

        assertFalse(result.hasSufficientData)
    }

    // ── Snooze metric windowing ───────────────────────────────────────────────

    @Test
    fun `completions between 14 and 30 days ago contribute to sufficiency but not to 14d snooze metrics`() {
        // Five qualifying reminder completions fall at days 15–19 (inside 30d window, outside 14d window).
        // They pass the G3 sufficiency count, but avgSnooze/snoozeFreq fall back to 0.0.
        // Five regular completions in days 1–5 produce rate7d = 5/7 ≈ 0.714.
        // With avgSnooze=0 and rate7d≥0.70 → MathHabitPredictor Rule 5 → 0.15f → LOW.
        val old = completedAt(90)
        val between30And14 = (15L..19L).map { reminderAt(it, snoozeCount = 3, isTargetReached = false) }
        val recentReached = (1L..5L).map { completedAt(it, isTargetReached = true) }

        val result = useCase(
            habit,
            listOf(old) + between30And14 + recentReached,
            currentStreak = 0,
            today = today
        )

        assertTrue(result.hasSufficientData)
        assertEquals(SnoozeDisengagementRisk.Rating.LOW, result.rating)
        assertEquals(0.15f, result.probability, 0.001f)
    }

    // ── Integration: MathHabitPredictor rule coverage ─────────────────────────

    @Test
    fun `Rule1 avgSnooze2 and zeroRate7d returns CRITICAL probability 0_85`() {
        // Five reminder completions in days 1–5, snoozeCount=2 each, isTargetReached=false.
        // avgSnooze = (2+2+2+2+2)/5 = 2.0 >= 2; snoozeFreq = 5/5 = 1.0.
        // rate7d = 0 (no isTargetReached=true completions in 7 days) < 0.30.
        // → Rule 1 → 0.85f → CRITICAL.
        val old = completedAt(60)
        val reminders = (1L..5L).map { reminderAt(it, snoozeCount = 2, isTargetReached = false) }

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 0, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(0.85f, result.probability, 0.001f)
        assertEquals(SnoozeDisengagementRisk.Rating.CRITICAL, result.rating)
    }

    @Test
    fun `Rule2 snoozeFreq08 returns HIGH probability 0_65`() {
        // Four reminders with snoozeCount=1, one with snoozeCount=0 (all isTargetReached=true).
        // avgSnooze = (1+1+1+1+0)/5 = 0.8 < 2 → Rule 1 skipped.
        // snoozeFreq = 4/5 = 0.80 >= 0.80 → Rule 2 → 0.65f → HIGH.
        // rate7d = 5/7 ≈ 0.71 (also ensures Rule 1 doesn't fire on the rate condition).
        val old = completedAt(60)
        val reminders = listOf(
            reminderAt(1, snoozeCount = 1),
            reminderAt(2, snoozeCount = 1),
            reminderAt(3, snoozeCount = 1),
            reminderAt(4, snoozeCount = 1),
            reminderAt(5, snoozeCount = 0)
        )

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 0, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(0.65f, result.probability, 0.001f)
        assertEquals(SnoozeDisengagementRisk.Rating.HIGH, result.rating)
    }

    @Test
    fun `Rule3 longStreak returns LOW probability 0_10`() {
        // avgSnooze = 0 and snoozeFreq = 0 → Rules 1 and 2 skipped.
        // streak = 20 >= 14 → Rule 3 → 0.10f → LOW.
        val old = completedAt(60)
        val reminders = (1L..5L).map { reminderAt(it, snoozeCount = 0) }

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 20, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(0.10f, result.probability, 0.001f)
        assertEquals(SnoozeDisengagementRisk.Rating.LOW, result.rating)
    }

    @Test
    fun `Rule4 moderateSnooze and belowHalfRate returns HIGH probability 0_50`() {
        // Reminders: snooze counts [2, 2, 2, 0, 0] → avgSnooze = 1.2 >= 1.
        // snoozeFreq = 3/5 = 0.60 < 0.80 → Rule 2 skipped.
        // isTargetReached=true only for days 1 and 2 → rate7d = 2/7 ≈ 0.286 < 0.50.
        // → Rule 4 → 0.50f → HIGH.
        val old = completedAt(60)
        val reminders = listOf(
            reminderAt(1, snoozeCount = 2, isTargetReached = true),
            reminderAt(2, snoozeCount = 2, isTargetReached = true),
            reminderAt(3, snoozeCount = 2, isTargetReached = false),
            reminderAt(4, snoozeCount = 0, isTargetReached = false),
            reminderAt(5, snoozeCount = 0, isTargetReached = false)
        )

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 5, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(0.50f, result.probability, 0.001f)
        assertEquals(SnoozeDisengagementRisk.Rating.HIGH, result.rating)
    }

    @Test
    fun `Rule5 noSnooze and highRate returns LOW probability 0_15`() {
        // avgSnooze = 0 → Rules 1 and 4 skipped.
        // snoozeFreq = 0 → Rule 2 skipped.
        // streak = 5 < 14 → Rule 3 skipped.
        // rate7d = 5/7 ≈ 0.714 >= 0.70 → Rule 5 → 0.15f → LOW.
        val old = completedAt(60)
        val reminders = (1L..5L).map { reminderAt(it, snoozeCount = 0, isTargetReached = true) }

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 5, today = today)

        assertTrue(result.hasSufficientData)
        assertEquals(0.15f, result.probability, 0.001f)
        assertEquals(SnoozeDisengagementRisk.Rating.LOW, result.rating)
    }

    // ── Result fields ─────────────────────────────────────────────────────────

    @Test
    fun `habitId is forwarded from the habit parameter to the risk result`() {
        val old = completedAt(60)
        val reminders = (1L..5L).map { reminderAt(it, snoozeCount = 0) }

        val result = useCase(habit, listOf(old) + reminders, currentStreak = 5, today = today)

        assertEquals(habit.id, result.habitId)
    }
}
