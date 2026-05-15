package com.example.evolvix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.evolvix.data.model.AchievementEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO (Data Access Object) for achievement persistence.
 *
 * Follows the **Repository / DAO** pattern — all queries are
 * [suspend] or [Flow]-returning so callers never block the main thread.
 */
@Dao
interface AchievementDao {

    /**
     * Inserts a new achievement row. If a row with the same [key] already
     * exists the insert is silently ignored ([OnConflictStrategy.IGNORE]).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(achievement: AchievementEntity)

    /**
     * Replaces a row entirely. Used when the evaluator updates [unlockedAt]
     * or [progress] on an existing entry.
     */
    @Update
    suspend fun update(achievement: AchievementEntity)

    /** Emits the full achievement list whenever any row changes. */
    @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC")
    fun getAllAchievements(): Flow<List<AchievementEntity>>

    /** Returns a single achievement by its stable [key], or null if absent. */
    @Query("SELECT * FROM achievements WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): AchievementEntity?

    /** Returns all rows that have been unlocked (non-null [unlockedAt]). */
    @Query("SELECT * FROM achievements WHERE unlockedAt IS NOT NULL ORDER BY unlockedAt DESC")
    fun getUnlocked(): Flow<List<AchievementEntity>>
}
