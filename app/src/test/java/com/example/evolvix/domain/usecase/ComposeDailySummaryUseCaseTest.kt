package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.AchievementEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for [ComposeDailySummaryUseCase].
 *
 * [ComposeDailySummaryUseCase] is a pure function — no Room, no WorkManager, no Android
 * context. All inputs are injected; [today] is a fixed date to make assertions fully
 * deterministic regardless of when the test suite runs.
 *
 * Coverage targets:
 *  - All four title branches (perfect / some wins / progress only / no completions).
 *  - Today-only date filter (yesterday's records must not pollute today's counts).
 *  - [DailySummaryEntity.weekCompletionPct] calculation.
 *  - Achievement count and plural/singular text.
 *  - [DailySummaryEntity.shortBody] 120-char hard cap.
 *  - Stored counter semantics (distinct target reaches vs raw progress count).
 *  - Encouragement text for each emotional state.
 */
class ComposeDailySummaryUseCaseTest {

    private lateinit var useCase: ComposeDailySummaryUseCase

    /** Fixed reference date — eliminates timezone / clock flakiness in CI. */
    private val today = LocalDate.of(2026, 5, 20)

    @Before
    fun setUp() {
        useCase = ComposeDailySummaryUseCase()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal [HabitEntity] fixture — only [id] is meaningful for these tests. */
    private fun habit(id: Int) = HabitEntity(
        id = id, name = "Habit $id", currentCount = 0, target = 1
    )

    /**
     * Creates a [HabitCompletionEntity] for [habitId] on [today] at noon.
     * Pass [onToday] = false to produce a yesterday record (date-filter tests).
     */
    private fun completion(
        habitId: Int,
        isTargetReached: Boolean = true,
        onToday: Boolean = true
    ) = HabitCompletionEntity(
        habitId = habitId,
        progressUpdate = (if (onToday) today else today.minusDays(1)).atTime(12, 0),
        isTargetReached = isTargetReached
    )

    /**
     * Minimal [AchievementEntity] — the use case only counts how many are passed in,
     * so [key] is the only required field beyond the primary key.
     */
    private fun achievement(id: Int) = AchievementEntity(id = id, key = "ACH_$id")

    // ── Title branch ──────────────────────────────────────────────────────────

    @Test
    fun `perfect day when every active habit hits target`() {
        val habits = listOf(habit(1), habit(2))
        val completions = listOf(completion(1), completion(2))

        val result = useCase(today, habits, completions, emptyList(), 1.0f)

        assertEquals("Perfect day", result.title)
    }

    @Test
    fun `today wins when some but not all habits hit target`() {
        val habits = listOf(habit(1), habit(2), habit(3))
        val completions = listOf(completion(1), completion(2)) // habit 3 missed

        val result = useCase(today, habits, completions, emptyList(), 0.5f)

        assertEquals("Today's wins", result.title)
    }

    @Test
    fun `some progress today when progress logged but no target reached`() {
        val habits = listOf(habit(1), habit(2))
        val completions = listOf(completion(1, isTargetReached = false))

        val result = useCase(today, habits, completions, emptyList(), 0.2f)

        assertEquals("Some progress today", result.title)
    }

    @Test
    fun `tomorrow is fresh start when no completions at all`() {
        val habits = listOf(habit(1), habit(2))

        val result = useCase(today, habits, emptyList(), emptyList(), 0.0f)

        assertEquals("Tomorrow's a fresh start", result.title)
    }

    // ── Date filter ───────────────────────────────────────────────────────────

    @Test
    fun `completions from yesterday are excluded from today counts`() {
        val habits = listOf(habit(1))
        // Target reached yesterday — should NOT count as today's progress.
        val completions = listOf(completion(1, isTargetReached = true, onToday = false))

        val result = useCase(today, habits, completions, emptyList(), 0.0f)

        assertEquals("Tomorrow's a fresh start", result.title)
        assertEquals(0, result.todayProgressUpdates)
        assertEquals(0, result.todayTargetReaches)
    }

    // ── weekCompletionPct ─────────────────────────────────────────────────────

    @Test
    fun `weekCompletionPct converts weekRate to integer percentage`() {
        val full    = useCase(today, emptyList(), emptyList(), emptyList(), 1.0f)
        val zero    = useCase(today, emptyList(), emptyList(), emptyList(), 0.0f)
        val quarter = useCase(today, emptyList(), emptyList(), emptyList(), 0.75f)

        assertEquals(100, full.weekCompletionPct)
        assertEquals(0,   zero.weekCompletionPct)
        assertEquals(75,  quarter.weekCompletionPct)
    }

    // ── Achievements ──────────────────────────────────────────────────────────

    @Test
    fun `single achievement uses singular text`() {
        val result = useCase(today, emptyList(), emptyList(), listOf(achievement(1)), 0.0f)

        assertEquals(1, result.achievementsUnlockedToday)
        assertTrue("Expected singular in body", result.body.contains("1 new achievement"))
        assertTrue("Expected singular in shortBody", result.shortBody.contains("1 new achievement"))
        // Must NOT say "achievements" (plural)
        assertTrue(result.body.contains("1 new achievement") && !result.body.contains("1 new achievements"))
    }

    @Test
    fun `multiple achievements use plural text`() {
        val result = useCase(
            today, emptyList(), emptyList(),
            listOf(achievement(1), achievement(2)), 0.0f
        )

        assertTrue("Expected plural in body", result.body.contains("2 new achievements"))
    }

    // ── shortBody length cap ──────────────────────────────────────────────────

    @Test
    fun `shortBody never exceeds 120 characters even with many parts`() {
        val habits = (1..10).map { habit(it) }
        val completions = habits.map { completion(it.id) }
        val achievements = (1..5).map { achievement(it) }

        val result = useCase(today, habits, completions, achievements, 0.99f)

        assertTrue(
            "shortBody length ${result.shortBody.length} exceeds 120 chars",
            result.shortBody.length <= 120
        )
    }

    // ── Stored counter semantics ──────────────────────────────────────────────

    @Test
    fun `todayTargetReaches counts distinct habit IDs not raw records`() {
        val habits = listOf(habit(1))
        // Habit 1 logged twice today — but only one distinct habit ID reached target.
        val completions = listOf(completion(1), completion(1))

        val result = useCase(today, habits, completions, emptyList(), 1.0f)

        assertEquals("Distinct target reaches should be 1", 1, result.todayTargetReaches)
        assertEquals("All progress records should be counted", 2, result.todayProgressUpdates)
    }

    @Test
    fun `totalActiveHabits mirrors the passed habit list size`() {
        val habits = listOf(habit(1), habit(2), habit(3))

        val result = useCase(today, habits, emptyList(), emptyList(), 0.0f)

        assertEquals(3, result.totalActiveHabits)
    }

    // ── Encouragement text ────────────────────────────────────────────────────

    @Test
    fun `perfect day encouragement is correct`() {
        val habits = listOf(habit(1))
        val completions = listOf(completion(1))

        val result = useCase(today, habits, completions, emptyList(), 1.0f)

        assertTrue(result.body.contains("Every habit met today"))
    }

    @Test
    fun `partial wins encouragement mentions target reach count`() {
        // 1 of 3 habits reached target → encouragement says "locking in 1"
        val habits = listOf(habit(1), habit(2), habit(3))
        val completions = listOf(completion(1))

        val result = useCase(today, habits, completions, emptyList(), 0.3f)

        assertTrue(result.body.contains("locking in 1"))
    }

    @Test
    fun `progress only encouragement nudges closing a habit`() {
        val habits = listOf(habit(1))
        val completions = listOf(completion(1, isTargetReached = false))

        val result = useCase(today, habits, completions, emptyList(), 0.1f)

        assertTrue(result.body.contains("You moved the needle today"))
    }

    @Test
    fun `no completions encouragement suggests easy win tomorrow`() {
        val result = useCase(today, listOf(habit(1)), emptyList(), emptyList(), 0.0f)

        assertTrue(result.body.contains("No completions logged"))
    }
}
