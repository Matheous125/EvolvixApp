package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing a single progress update for a habit.
 * Stores individual completion records with timestamps and target status.
 *
 * Over-completion is explicitly supported: there is no DB-level constraint clamping
 * the number of records per habit per cycle. A habit whose [isTargetReached] was
 * already true can still receive additional completion rows (progress > target).
 * The ViewModel layer is responsible for exposing `isOverCompleted` state.
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
    ],
    indices = [Index(value = ["habitId"])] // Prevents full table scan on parent (HabitEntity) delete/update
)
data class HabitCompletionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,                     // Unique identifier for each completion
    val habitId: Int,                    // References parent habit's ID
    val progressUpdate: LocalDateTime,    // Timestamp of the progress update
    val isTargetReached: Boolean,        // Indicates if this update completed the target
    val fromReminder: Boolean = false    // Phase 9.1: true when triggered via reminder notification
)