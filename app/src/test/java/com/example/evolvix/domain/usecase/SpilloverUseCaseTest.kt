package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.ai.MathHabitPredictor
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.SpilloverPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [SpilloverUseCase] (Phase 8.5 coverage).
 *
 * Uses [MathHabitPredictor] as the injected [com.example.evolvix.domain.ai.HabitPredictor]
 * to exercise the full use-case → math-fallback pipeline without any Android runtime
 * or TFLite dependency. A fixed [today] reference date makes every test deterministic.
 *
 * [MathHabitPredictor.predictSpillover] formula:
 *   baseLift   = coOccurrenceRate − rateB
 *   activity   = sqrt(rateA × rateB)
 *   gapFactor  = 1 − (typicalGapHours / 24).coerceIn(0, 1)
 *   raw        = baseLift × activity × gapFactor
 *   result     = (raw × 1.6).coerceIn(−0.5, 0.5)
 *
 * [SpilloverPair.directionFor] thresholds (NEUTRAL_THRESHOLD = 0.05):
 *   result >  0.05 → BOOST
 *   result < −0.05 → DRAG
 *   otherwise      → NEUTRAL (filtered out by the use case)
 *
 * Coverage:
 * - Guard: fewer than 2 habits → empty list.
 * - Guard: no target-reached completions today → empty list.
 * - Guard: completions today but isTargetReached = false → empty list.
 * - BOOST: high co-occurrence where coOccurrenceRate > rateB.
 * - DRAG: low co-occurrence where coOccurrenceRate < rateB.
 * - NEUTRAL pairs are filtered out and never appear in the result.
 * - MAX_PAIRS cap: at most 3 pairs returned even when more exist.
 * - Ranking: pairs ordered by |liftDelta| descending.
 * - Window: completions older than 30 days are excluded from rate computation.
 * - Fallback: typicalGapHours uses 3.0 h median when sharedDays < MIN_SHARED_DAYS(3).
 * - Names: habitAName and habitBName are populated from HabitData.name.
 * - Directional guard: only habits completed today act as trigger (habit A).
 */
class SpilloverUseCaseTest {

    private lateinit var useCase: SpilloverUseCase

    /** Fixed reference date — all test data is expressed relative to this. */
    private val today = LocalDate.of(2025, 3, 15)

    @Before
    fun setUp() {
        useCase = SpilloverUseCase(MathHabitPredictor())
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun habit(id: Int, name: String) = HabitData(
        id = id, name = name, currentCount = 0,
        frequency = HabitFrequency.Daily, target = 1
    )

    /**
     * Creates a [HabitCompletionEntity] at [date] at [hour]:00.
     * Defaults to [isTargetReached] = true (target reached).
     */
    private fun c(
        habitId: Int,
        date: LocalDate,
        hour: Int = 8,
        reached: Boolean = true
    ) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = date.atTime(hour, 0),
        isTargetReached = reached
    )

    // ── Guard conditions ───────────────────────────────────────────────────────

    @Test
    fun `guard - single habit returns empty list`() {
        val habits = listOf(habit(1, "Run"))
        val completions = listOf(c(1, today))

        val result = useCase(habits, completions, today)

        assertTrue("Single habit should produce no pairs", result.isEmpty())
    }

    @Test
    fun `guard - empty habits list returns empty list`() {
        val result = useCase(emptyList(), emptyList(), today)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `guard - nothing completed today returns empty list`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))
        // Both habits have completions, but only from yesterday — not today.
        val completions = listOf(
            c(1, today.minusDays(1)),
            c(2, today.minusDays(1))
        )

        val result = useCase(habits, completions, today)

        assertTrue("No completions today should produce no pairs", result.isEmpty())
    }

    @Test
    fun `guard - completion today with isTargetReached false is ignored`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))
        // Habit 1 has a completion record for today, but the target was NOT reached.
        val completions = listOf(c(1, today, reached = false))

        val result = useCase(habits, completions, today)

        assertTrue("Non-reached completion today should not trigger spillover", result.isEmpty())
    }

    // ── Direction: BOOST ───────────────────────────────────────────────────────

    /**
     * Setup:
     *   aDates (in 30-day window) = days 1..20 before today + today = 21 days → rateA ≈ 0.70
     *   bDates (in 30-day window) = days 1..15 before today           = 15 days → rateB = 0.50
     *   sharedDates = days 1..15 = 15 days ≥ MIN_SHARED_DAYS → actual gap computed
     *   Both habits complete at hour 8 → |gap| = 0 h → gapFactor = 1.0
     *   coOccurrenceRate = 15/21 ≈ 0.714
     *   baseLift = 0.714 − 0.500 = 0.214 → BOOST
     *   raw = 0.214 × sqrt(0.70 × 0.50) × 1.0 = 0.214 × 0.592 ≈ 0.127
     *   liftDelta ≈ 0.203 > NEUTRAL_THRESHOLD(0.05) → BOOST
     */
    @Test
    fun `boost - high co-occurrence produces BOOST direction`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))

        val completionsA = (1..20).map { c(1, today.minusDays(it.toLong()), hour = 8) }
        val completionsB = (1..15).map { c(2, today.minusDays(it.toLong()), hour = 8) }
        val todayA = c(1, today, hour = 8)

        val result = useCase(habits, completionsA + completionsB + listOf(todayA), today)

        assertTrue("High co-occurrence should yield at least one BOOST pair", result.isNotEmpty())
        assertEquals(SpilloverPair.Direction.BOOST, result.first().direction)
    }

    // ── Direction: DRAG ────────────────────────────────────────────────────────

    /**
     * Setup:
     *   aDates = days 1..20 + today = 21 days → rateA ≈ 0.70
     *   bDates = days 16..30          = 15 days → rateB = 0.50
     *   sharedDates = days 16..20 = 5 days → actual gap (both at hour 8 → gap = 0)
     *   coOccurrenceRate = 5/21 ≈ 0.238
     *   baseLift = 0.238 − 0.500 = −0.262 → DRAG
     *   raw = −0.262 × 0.592 × 1.0 ≈ −0.155
     *   liftDelta ≈ −0.248 < −NEUTRAL_THRESHOLD → DRAG
     */
    @Test
    fun `drag - low co-occurrence produces DRAG direction`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))

        val completionsA = (1..20).map { c(1, today.minusDays(it.toLong()), hour = 8) }
        // B completed on days 16..30 — only 5 overlap with A's days 1..20
        val completionsB = (16..30).map { c(2, today.minusDays(it.toLong()), hour = 8) }
        val todayA = c(1, today, hour = 8)

        val result = useCase(habits, completionsA + completionsB + listOf(todayA), today)

        assertTrue("Low co-occurrence should yield at least one DRAG pair", result.isNotEmpty())
        assertEquals(SpilloverPair.Direction.DRAG, result.first().direction)
    }

    // ── NEUTRAL filtering ──────────────────────────────────────────────────────

    /**
     * Setup: only 1 shared day, sparse rates.
     *   aDates = {today-5, today} = 2 days → rateA ≈ 0.0667
     *   bDates = {today-5}        = 1 day  → rateB ≈ 0.0333
     *   sharedDates = {today-5} = 1 day < MIN_SHARED_DAYS → fallback 3.0 h gap
     *   coOccurrenceRate = 1/2 = 0.50
     *   baseLift = 0.50 − 0.0333 = 0.467
     *   activity = sqrt(0.0667 × 0.0333) = 0.0471
     *   gapFactor = 1 − 3/24 = 0.875
     *   raw = 0.467 × 0.0471 × 0.875 ≈ 0.0192
     *   liftDelta ≈ 0.0307 < NEUTRAL_THRESHOLD(0.05) → NEUTRAL → filtered out
     */
    @Test
    fun `neutral pair is filtered out and result is empty`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))
        val sharedDay = today.minusDays(5)

        val completions = listOf(
            c(1, sharedDay),   // A completed 5 days ago
            c(2, sharedDay),   // B completed same day
            c(1, today)        // A completed today (triggers evaluation)
        )

        val result = useCase(habits, completions, today)

        assertTrue("NEUTRAL pair should be filtered out — result must be empty", result.isEmpty())
    }

    // ── MAX_PAIRS cap ──────────────────────────────────────────────────────────

    /**
     * 5 habits: A + B, C, D, E. Each B–E pair with A produces a BOOST.
     * The use case generates 4 pairs (A→B, A→C, A→D, A→E) but must return at most 3.
     */
    @Test
    fun `max 3 pairs returned when more non-neutral pairs exist`() {
        val habits = listOf(
            habit(1, "Run"),
            habit(2, "Meditate"),
            habit(3, "Read"),
            habit(4, "Journal"),
            habit(5, "Stretch")
        )

        val completionsA = (1..20).map { c(1, today.minusDays(it.toLong()), hour = 8) } +
                listOf(c(1, today, hour = 8))
        // All 4 partner habits completed on days 1..15 → all produce BOOST with A
        val completionsB = (1..15).map { c(2, today.minusDays(it.toLong()), hour = 8) }
        val completionsC = (1..15).map { c(3, today.minusDays(it.toLong()), hour = 8) }
        val completionsD = (1..15).map { c(4, today.minusDays(it.toLong()), hour = 8) }
        val completionsE = (1..15).map { c(5, today.minusDays(it.toLong()), hour = 8) }

        val completions = completionsA + completionsB + completionsC + completionsD + completionsE

        val result = useCase(habits, completions, today)

        assertTrue(
            "Result size ${result.size} must not exceed MAX_PAIRS=3",
            result.size <= 3
        )
    }

    // ── Ranking ────────────────────────────────────────────────────────────────

    /**
     * Two pairs: A→B (strong BOOST) and A→C (weak DRAG).
     * The pair with higher |liftDelta| must appear first.
     */
    @Test
    fun `pairs sorted by absolute liftDelta descending`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"), habit(3, "Read"))

        val completionsA = (1..20).map { c(1, today.minusDays(it.toLong()), hour = 8) } +
                listOf(c(1, today, hour = 8))
        // B: days 1..15 overlap with A → strong BOOST
        val completionsB = (1..15).map { c(2, today.minusDays(it.toLong()), hour = 8) }
        // C: days 21..30 — only today.minusDays(21..30) which are outside A's window (A only goes 1..20)
        // so coOccurrenceRate = 0 for C, rateC = 10/30 = 0.333
        // baseLift = 0 - 0.333 = -0.333 (DRAG, weaker |liftDelta| because rateA is small for these pairs)
        val completionsC = (21..30).map { c(3, today.minusDays(it.toLong()), hour = 8) }

        val result = useCase(habits, completionsA + completionsB + completionsC, today)

        for (i in 0 until result.size - 1) {
            assertTrue(
                "Pair at index $i (|liftDelta|=${Math.abs(result[i].liftDelta)}) " +
                        "should be ≥ pair at index ${i + 1} (|liftDelta|=${Math.abs(result[i + 1].liftDelta)})",
                Math.abs(result[i].liftDelta) >= Math.abs(result[i + 1].liftDelta)
            )
        }
    }

    // ── 30-day window enforcement ──────────────────────────────────────────────

    /**
     * Habit A has 20 in-window completions and 10 completions older than 30 days.
     * The old completions must NOT inflate rateA. The test verifies the use case
     * returns a non-empty result (BOOST) consistent with window-only rateA = 21/30.
     *
     *   aDates = days 1..20 + today = 21 days → rateA ≈ 0.70
     *   bDates = days 1..10 = 10 days          → rateB ≈ 0.333
     *   coOccurrenceRate = 10/21 ≈ 0.476
     *   baseLift = 0.476 − 0.333 = 0.143 → BOOST
     */
    @Test
    fun `completions older than 30 days are excluded from rate calculation`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))

        val inWindow = (1..20).map { c(1, today.minusDays(it.toLong())) }
        val outOfWindow = (31..40).map { c(1, today.minusDays(it.toLong())) } // older than 30 days
        val todayA = c(1, today)
        val completionsB = (1..10).map { c(2, today.minusDays(it.toLong())) }

        val completions = inWindow + outOfWindow + listOf(todayA) + completionsB

        val result = useCase(habits, completions, today)

        assertTrue(
            "Window-only data should produce a BOOST pair; got ${result.map { it.direction }}",
            result.any { it.direction == SpilloverPair.Direction.BOOST }
        )
    }

    // ── typicalGapHours fallback ───────────────────────────────────────────────

    /**
     * Habit A and B share only 2 days (< MIN_SHARED_DAYS=3), so typicalGapHours
     * must fall back to the training-data median (3.0 h). Even though the actual
     * timestamps differ by 16 h (hour 6 vs hour 22), the fallback gap (3 h) is
     * used, yielding a gapFactor = 0.875 instead of 0.333.
     *
     *   aDates = {today-1, today-2, today} → rateA = 3/30 = 0.10
     *   bDates = {today-1, today-2}        → rateB = 2/30 ≈ 0.0667
     *   sharedDates = {today-1, today-2} = 2 days < MIN_SHARED_DAYS → fallback 3.0 h
     *   coOccurrenceRate = 2/3 ≈ 0.667
     *   baseLift = 0.667 − 0.0667 = 0.600
     *   activity = sqrt(0.10 × 0.0667) = 0.0816
     *   gapFactor(fallback) = 1 − 3/24 = 0.875
     *   raw = 0.600 × 0.0816 × 0.875 ≈ 0.0428
     *   liftDelta ≈ 0.0685 > 0.05 → BOOST
     *
     * If the actual 16 h gap were used instead:
     *   gapFactor = 1 − 16/24 = 0.333 → liftDelta ≈ 0.026 < 0.05 → NEUTRAL (no result)
     */
    @Test
    fun `typicalGapHours uses 3h fallback when fewer than 3 shared days`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))

        val sharedDays = listOf(today.minusDays(1), today.minusDays(2))
        // A completes early; B completes late (16 h apart on shared days)
        val completionsA = sharedDays.map { c(1, it, hour = 6) } + listOf(c(1, today, hour = 6))
        val completionsB = sharedDays.map { c(2, it, hour = 22) }

        val result = useCase(habits, completionsA + completionsB, today)

        // With fallback gap (3 h) → BOOST. Without fallback (actual 16 h) → NEUTRAL/empty.
        assertTrue("Fallback gap should produce a BOOST pair", result.isNotEmpty())
        assertEquals(SpilloverPair.Direction.BOOST, result.first().direction)
    }

    // ── Name propagation ───────────────────────────────────────────────────────

    @Test
    fun `habitAName and habitBName are taken from HabitData name`() {
        val habits = listOf(habit(1, "Running"), habit(2, "Meditation"))

        val completionsA = (1..20).map { c(1, today.minusDays(it.toLong()), hour = 8) }
        val completionsB = (1..15).map { c(2, today.minusDays(it.toLong()), hour = 8) }
        val todayA = c(1, today, hour = 8)

        val result = useCase(habits, completionsA + completionsB + listOf(todayA), today)

        val pair = result.firstOrNull { it.direction == SpilloverPair.Direction.BOOST }
        if (pair != null) {
            assertEquals("Running", pair.habitAName)
            assertEquals("Meditation", pair.habitBName)
        }
    }

    // ── Directional guard: only today's trigger generates pairs ───────────────

    /**
     * Only habit 1 completed today. Habit 2 has historical data but is NOT
     * completed today, so it must NOT act as a trigger. All result pairs must
     * have habitAName = "Run".
     */
    @Test
    fun `only habit completed today acts as trigger in pairs`() {
        val habits = listOf(habit(1, "Run"), habit(2, "Meditate"))

        val completionsA = (1..20).map { c(1, today.minusDays(it.toLong())) } +
                listOf(c(1, today))
        val completionsB = (1..15).map { c(2, today.minusDays(it.toLong())) }
        // Habit 2 is NOT completed today — it should never appear as habitA.

        val result = useCase(habits, completionsA + completionsB, today)

        result.forEach { pair ->
            assertEquals(
                "Only habit completed today should be habitA, found: ${pair.habitAName}",
                "Run", pair.habitAName
            )
        }
    }
}
