package com.example.evolvix.data.local

import androidx.room.TypeConverter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.ui.theme.HabitColorScheme

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
     * Converts string to HabitColorScheme enum
     * @param value String representation of color scheme
     * @return HabitColorScheme enum value
     */
    @TypeConverter
    fun toHabitColorScheme(value: String): HabitColorScheme {
        return HabitColorScheme.valueOf(value)
    }

     /**
     * Converts HabitColorScheme enum to string
     * @param colorScheme HabitColorScheme enum value
     * @return String representation of color scheme
     */
    @TypeConverter
    fun fromHabitColorScheme(colorScheme: HabitColorScheme): String {
        return colorScheme.name
    }
}