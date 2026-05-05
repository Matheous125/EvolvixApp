package com.example.evolvix.domain.model

import com.example.evolvix.data.model.HabitFrequency
import java.time.LocalDateTime

/**
 * Domain model representing a habit.
 * Used in business logic layer, separating database entity from UI representation.
 *
 * @property id Unique identifier of the habit
 * @property name User-defined name of the habit
 * @property currentCount Current progress towards target
 * @property frequency How often the habit should reset (Daily/Weekly/Monthly/Yearly)
 * @property target Number of times to complete before considering habit done
 * @property totalProgressUpdates Total number of times progress was updated
 * @property totalTargetReaches Number of times the target was reached
 * @property lastResetDate When the progress was last reset to zero
 */
data class HabitData(
    val id: Int = 0,
    val name: String,
    val currentCount: Int,
    val frequency: HabitFrequency = HabitFrequency.Daily,
    val target: Int,
    val totalProgressUpdates: Int = 0,
    val totalTargetReaches: Int = 0,
    val lastResetDate: LocalDateTime = LocalDateTime.now()
)