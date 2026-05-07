package com.example.evolvix.data.model

/**
 * Enum representing the frequency of habit tracking.
 * Provides functionality for reset scheduling and progress formatting.
 *
 * @property days Number of days in the frequency period
 * @property displayName User-friendly name for UI display
 */
enum class HabitFrequency(val days: Int, val displayName: String) {
    Daily(1, "day"),
    Weekly(7, "week"),
    Monthly(30, "month"),
    Yearly(365, "year");
}