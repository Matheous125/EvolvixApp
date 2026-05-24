package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Room entity recording every change to a habit's target value (Phase 9.3).
 *
 * A new row is inserted by [HabitViewModel.updateHabit] whenever [HabitEntity.target]
 * changes. Together with [HabitCompletionEntity.targetVersion], this table lets
 * [TargetAdjustmentUseCase] compute per-version completion rates and derive the
 * `previousDelta` and `periodsSinceLastChange` features for the TFLite regressor.
 *
 * Responsibility: raw audit log of target changes — no derived fields. All aggregation
 * is done at the use-case layer (DAO layer is intentionally thin).
 *
 * @property id          Auto-generated primary key.
 * @property habitId     References [HabitEntity.id]; CASCADE-deleted with the parent.
 * @property oldTarget   The target value before the change.
 * @property newTarget   The target value after the change.
 * @property changedAt   Timestamp of the change (device local time).
 * @property version     The new [HabitEntity.targetVersion] value after this change
 *                       (i.e. the version that applies to completions recorded from
 *                       [changedAt] onward).
 */
@Entity(
    tableName = "habit_target_history",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["habitId"])]
)
data class HabitTargetHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val habitId: Int,
    val oldTarget: Int,
    val newTarget: Int,
    val changedAt: LocalDateTime,
    val version: Int
)
