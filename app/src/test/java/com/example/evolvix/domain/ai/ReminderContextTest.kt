package com.example.evolvix.domain.ai

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [ReminderContext.toFloatArray].
 *
 * Verifies that the float encoding matches the Python `feature_columns` order exactly,
 * specifically that R3's [ReminderContext.abandonmentProbability] occupies slot 5 as a
 * raw float — not the old Boolean-encoded 1f/0f that [isAtRisk] used in R1.
 *
 * No Android SDK, no mocking — pure JVM JUnit.
 */
class ReminderContextTest {

    private fun context(
        abandonmentProbability: Float,
        targetReachedToday: Boolean = false,
        currentStreak: Int = 5,
        completionRateLast7Days: Float = 0.7f,
        daysSinceLastCompletion: Int = 1,
        dayOfWeek: Int = 3,
        hourOfDay: Int = 9,
        snoozeCountToday: Int = 0
    ) = ReminderContext(
        currentStreak = currentStreak,
        completionRateLast7Days = completionRateLast7Days,
        daysSinceLastCompletion = daysSinceLastCompletion,
        dayOfWeek = dayOfWeek,
        hourOfDay = hourOfDay,
        abandonmentProbability = abandonmentProbability,
        targetReachedToday = targetReachedToday,
        snoozeCountToday = snoozeCountToday
    )

    @Test
    fun `toFloatArray has exactly 8 elements`() {
        assertEquals(8, context(abandonmentProbability = 0.5f).toFloatArray().size)
    }

    @Test
    fun `toFloatArray slot 5 equals abandonmentProbability exactly (R3)`() {
        // R3: slot 5 must carry the raw float, not a boolean 0f/1f encoding.
        val prob = 0.73f
        val arr = context(abandonmentProbability = prob).toFloatArray()
        assertEquals("Slot 5 must be the raw abandonmentProbability", prob, arr[5], 0.0001f)
    }

    @Test
    fun `toFloatArray slot 5 carries fractional probability not rounded to 0 or 1`() {
        // Confirms the old boolean encoding (1f / 0f) is gone: mid-range value must survive.
        val arr = context(abandonmentProbability = 0.42f).toFloatArray()
        assertNotEquals("Slot 5 must not be rounded to 1f", 1f, arr[5], 0.0001f)
        assertNotEquals("Slot 5 must not be rounded to 0f", 0f, arr[5], 0.0001f)
    }

    @Test
    fun `toFloatArray slot 6 is 1f when targetReachedToday is true`() {
        val arr = context(abandonmentProbability = 0.2f, targetReachedToday = true).toFloatArray()
        assertEquals(1f, arr[6], 0.0001f)
    }

    @Test
    fun `toFloatArray slot 6 is 0f when targetReachedToday is false`() {
        val arr = context(abandonmentProbability = 0.2f, targetReachedToday = false).toFloatArray()
        assertEquals(0f, arr[6], 0.0001f)
    }

    @Test
    fun `toFloatArray first five slots match constructor order`() {
        val arr = context(
            abandonmentProbability = 0.5f,
            currentStreak = 7,
            completionRateLast7Days = 0.85f,
            daysSinceLastCompletion = 2,
            dayOfWeek = 4,
            hourOfDay = 18
        ).toFloatArray()
        assertEquals(7f, arr[0], 0.0001f)   // currentStreak
        assertEquals(0.85f, arr[1], 0.0001f) // completionRateLast7Days
        assertEquals(2f, arr[2], 0.0001f)   // daysSinceLastCompletion
        assertEquals(4f, arr[3], 0.0001f)   // dayOfWeek
        assertEquals(18f, arr[4], 0.0001f)  // hourOfDay
    }
}
