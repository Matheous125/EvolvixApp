package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing a single progress update for a habit.
 * Stores individual completion records with timestamps and target status.
 */
@Entity(
    tableName = "habit_completions",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE // Automatically deletes completions when habit is deleted
        )
    ]
)
data class HabitCompletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,                     // Unique identifier for each completion
    val habitId: Int,                    // References parent habit's ID
    val progressUpdate: LocalDateTime,    // Timestamp of the progress update
    val isTargetReached: Boolean         // Indicates if this update completed the target
)