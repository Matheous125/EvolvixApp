package com.example.evolvix.data.local

import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.SkipReason
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit tests for [Converters] — the Room TypeConverter class.
 *
 * All conversions are symmetric (round-trip), so each pair of @TypeConverter
 * methods is tested together as: original → stored → back to original.
 */
class ConvertersTest {

    private lateinit var converters: Converters

    @Before
    fun setUp() {
        converters = Converters()
    }

    // ── LocalDateTime ──────────────────────────────────────────────────────────

    @Test
    fun `fromTimestamp with valid string returns correct LocalDateTime`() {
        val input = "2025-06-15T14:30:00"
        val result = converters.fromTimestamp(input)
        assertEquals(LocalDateTime.of(2025, 6, 15, 14, 30, 0), result)
    }

    @Test
    fun `fromTimestamp with null returns null`() {
        assertNull(converters.fromTimestamp(null))
    }

    @Test
    fun `dateToTimestamp with valid date returns ISO string`() {
        val date = LocalDateTime.of(2025, 6, 15, 14, 30, 0)
        val result = converters.dateToTimestamp(date)
        assertEquals("2025-06-15T14:30:00", result)
    }

    @Test
    fun `dateToTimestamp with null returns null`() {
        assertNull(converters.dateToTimestamp(null))
    }

    @Test
    fun `LocalDateTime round-trip conversion is lossless`() {
        val original = LocalDateTime.of(2024, 12, 31, 23, 59, 59)
        val stored = converters.dateToTimestamp(original)
        val restored = converters.fromTimestamp(stored)
        assertEquals(original, restored)
    }

    // ── HabitFrequency ─────────────────────────────────────────────────────────

    @Test
    fun `fromHabitFrequency returns enum name as string`() {
        assertEquals("Daily", converters.fromHabitFrequency(HabitFrequency.Daily))
        assertEquals("Weekly", converters.fromHabitFrequency(HabitFrequency.Weekly))
        assertEquals("Monthly", converters.fromHabitFrequency(HabitFrequency.Monthly))
        assertEquals("Yearly", converters.fromHabitFrequency(HabitFrequency.Yearly))
    }

    @Test
    fun `toHabitFrequency parses name string back to enum`() {
        assertEquals(HabitFrequency.Daily, converters.toHabitFrequency("Daily"))
        assertEquals(HabitFrequency.Weekly, converters.toHabitFrequency("Weekly"))
        assertEquals(HabitFrequency.Monthly, converters.toHabitFrequency("Monthly"))
        assertEquals(HabitFrequency.Yearly, converters.toHabitFrequency("Yearly"))
    }

    @Test
    fun `HabitFrequency round-trip conversion is lossless`() {
        for (freq in HabitFrequency.entries) {
            val stored = converters.fromHabitFrequency(freq)
            val restored = converters.toHabitFrequency(stored)
            assertEquals(freq, restored)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toHabitFrequency throws on unknown string`() {
        converters.toHabitFrequency("INVALID_FREQUENCY")
    }

    // ── List<String> ───────────────────────────────────────────────────────────

    @Test
    fun `fromStringList joins with pipe delimiter`() {
        val result = converters.fromStringList(listOf("Health", "Fitness", "Mind"))
        assertEquals("Health|Fitness|Mind", result)
    }

    @Test
    fun `fromStringList with single item produces no delimiter`() {
        assertEquals("Solo", converters.fromStringList(listOf("Solo")))
    }

    @Test
    fun `fromStringList with empty list returns empty string`() {
        assertEquals("", converters.fromStringList(emptyList()))
    }

    @Test
    fun `toStringList splits on pipe delimiter`() {
        val result = converters.toStringList("Health|Fitness|Mind")
        assertEquals(listOf("Health", "Fitness", "Mind"), result)
    }

    @Test
    fun `toStringList with blank string returns empty list`() {
        assertTrue(converters.toStringList("").isEmpty())
        assertTrue(converters.toStringList("   ").isEmpty())
    }

    @Test
    fun `List-String round-trip conversion is lossless`() {
        val original = listOf("Alpha", "Beta", "Gamma")
        val stored = converters.fromStringList(original)
        val restored = converters.toStringList(stored)
        assertEquals(original, restored)
    }

    // ── LocalDate ──────────────────────────────────────────────────────────────

    @Test
    fun `fromLocalDate with valid date returns ISO date string`() {
        val date = LocalDate.of(2025, 1, 15)
        assertEquals("2025-01-15", converters.fromLocalDate(date))
    }

    @Test
    fun `fromLocalDate with null returns null`() {
        assertNull(converters.fromLocalDate(null))
    }

    @Test
    fun `toLocalDate with valid string returns correct LocalDate`() {
        val result = converters.toLocalDate("2025-01-15")
        assertEquals(LocalDate.of(2025, 1, 15), result)
    }

    @Test
    fun `toLocalDate with null returns null`() {
        assertNull(converters.toLocalDate(null))
    }

    @Test
    fun `LocalDate round-trip conversion is lossless`() {
        val original = LocalDate.of(2026, 5, 31)
        val stored = converters.fromLocalDate(original)
        val restored = converters.toLocalDate(stored)
        assertEquals(original, restored)
    }

    // ── SkipReason ─────────────────────────────────────────────────────────────

    @Test
    fun `fromSkipReason returns enum name as string`() {
        assertEquals("TOO_TIRED", converters.fromSkipReason(SkipReason.TOO_TIRED))
        assertEquals("TOO_BUSY", converters.fromSkipReason(SkipReason.TOO_BUSY))
        assertEquals("FORGOT", converters.fromSkipReason(SkipReason.FORGOT))
    }

    @Test
    fun `toSkipReason parses name string back to enum`() {
        assertEquals(SkipReason.TOO_TIRED, converters.toSkipReason("TOO_TIRED"))
        assertEquals(SkipReason.TOO_BUSY, converters.toSkipReason("TOO_BUSY"))
        assertEquals(SkipReason.FORGOT, converters.toSkipReason("FORGOT"))
    }

    @Test
    fun `SkipReason round-trip conversion is lossless`() {
        for (reason in SkipReason.entries) {
            val stored = converters.fromSkipReason(reason)
            val restored = converters.toSkipReason(stored)
            assertEquals(reason, restored)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `toSkipReason throws on unknown string`() {
        converters.toSkipReason("NOT_A_REASON")
    }
}
