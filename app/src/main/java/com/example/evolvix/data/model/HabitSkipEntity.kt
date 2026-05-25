package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Room entity recording a single habit-skip event (Phase 9.5).
 *
 * A new row is inserted whenever the user explicitly skips a habit — either via the
 * [com.example.evolvix.notifications.SkipReasonPickerActivity] launched from a
 * reminder notification, or via the "Skip" entry in the habit context menu on
 * [com.example.evolvix.ui.screens.MainScreen].
 *
 * Responsibility: raw skip log with the user-selected [reason]. All feature derivation
 * (skip rate, voluntary-vs-involuntary classification) is done at the use-case layer;
 * the DAO layer is intentionally thin.
 *
 * **Resilience v2 note:** [com.example.evolvix.domain.usecase.ResilienceScoreUseCase]
 * reads [SICK] and [TRAVELING] rows and treats those dates as "virtual completions"
 * when computing gap math, so involuntary absences do not inflate the missed-period
 * count against the user.
 *
 * @property id         Auto-generated primary key.
 * @property habitId    References [HabitEntity.id]; CASCADE-deleted with the parent.
 * @property skippedAt  Device-local timestamp of the skip event.
 * @property reason     User-selected or system-defaulted skip reason (stored as string
 *                      via [com.example.evolvix.data.local.Converters.fromSkipReason]).
 */
@Entity(
    tableName = "habit_skips",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["habitId"]),
        Index(value = ["skippedAt"])
    ]
)
data class HabitSkipEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val habitId: Int,
    val skippedAt: LocalDateTime,
    val reason: SkipReason
)
