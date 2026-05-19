package com.example.evolvix.data.local

import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.example.evolvix.data.model.HabitFrequency

/**
 * Room database type converters for custom data types.
 * Handles conversion between complex objects and their database representations.
 */
class Converters {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Converts stored timestamp string to LocalDateTime object
     * @param value ISO formatted date-time string
     * @return LocalDateTime object or null if input is null
     */
    @TypeConverter
    fun fromTimestamp(value: String?): LocalDateTime? {
        return value?.let { LocalDateTime.parse(it, formatter) }
    }

    /**
     * Converts LocalDateTime to string for database storage
     * @param date LocalDateTime to convert
     * @return ISO formatted date-time string or null if input is null
     */
    @TypeConverter
    fun dateToTimestamp(date: LocalDateTime?): String? {
        return date?.format(formatter)
    }

    /**
     * Converts HabitFrequency enum to string
     * @param frequency HabitFrequency enum value
     * @return String representation of frequency
     */
    @TypeConverter
    fun fromHabitFrequency(frequency: HabitFrequency): String {
        return frequency.name
    }

     /**
     * Converts string to HabitFrequency enum
     * @param value String representation of frequency
     * @return HabitFrequency enum value
     */
    @TypeConverter
    fun toHabitFrequency(value: String): HabitFrequency {
        return HabitFrequency.valueOf(value)
    }

    /**
     * Converts a List<String> to a single pipe-delimited string for Room storage.
     * Pipe (|) is used as a delimiter because category names will never contain it.
     */
    @TypeConverter
    fun fromStringList(list: List<String>): String {
        return list.joinToString(separator = "|")
    }

    /**
     * Converts a pipe-delimited string back to List<String>.
     * Returns an empty list when the stored value is blank.
     */
    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isBlank()) emptyList() else value.split("|")
    }

    // ── Phase 7.2v2 — LocalDate converters (used by DailySummaryEntity.date) ──
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun toLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }
}