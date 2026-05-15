package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Database entity representing a single achievement unlock record.
 *
 * Each row corresponds to one achievement that has been either fully unlocked
 * or is currently in progress. The [key] field uniquely maps to a definition
 * in [com.example.evolvix.domain.model.AchievementDefinition].
 *
 * Pattern: **Room Entity** — pure data container, no behaviour.
 *
 * @property id Auto-generated primary key (Room handles it).
 * @property key Stable identifier matching an [AchievementDefinition] (e.g. "STREAK_7").
 * @property unlockedAt Epoch-millisecond timestamp of when the achievement was unlocked;
 *   null means the achievement is tracked but not yet earned.
 * @property progress Current numeric progress toward this achievement's threshold
 *   (e.g. 4 out of a required 7-day streak). Used only for locked achievements.
 */
@Entity(
    tableName = "achievements",
    indices = [Index(value = ["key"], unique = true)]
)
data class AchievementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val key: String,
    val unlockedAt: Long? = null,
    val progress: Int = 0
)
