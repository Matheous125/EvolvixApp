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

    /**
     * Inserts or replaces a row by [AchievementEntity.key].
     *
     * Used exclusively by [com.example.evolvix.domain.sync.SyncController.syncAchievements]
     * to restore the user's achievement state from Firestore before the reactive evaluator
     * runs. By pre-populating [unlockedAt], the evaluator sees `existing.unlockedAt != null`
     * for already-earned achievements and skips the banner emit — preventing the spurious
     * banner storm that would otherwise occur on every login after a logout+clearAllTables.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertForSync(achievement: AchievementEntity)

    /** Emits the full achievement list whenever any row changes. */
    @Query("SELECT * FROM achievements ORDER BY unlockedAt DESC")
    fun getAllAchievements(): Flow<List<AchievementEntity>>

    /** Returns a single achievement by its stable [key], or null if absent. */
    @Query("SELECT * FROM achievements WHERE `key` = :key LIMIT 1")
    suspend fun findByKey(key: String): AchievementEntity?

    /** Returns all rows that have been unlocked (non-null [unlockedAt]). */
    @Query("SELECT * FROM achievements WHERE unlockedAt IS NOT NULL ORDER BY unlockedAt DESC")
    fun getUnlocked(): Flow<List<AchievementEntity>>

    /**
     * One-shot snapshot of every achievement row, used inside [persistDeltas] to
     * batch-load existing state before the evaluation loop.
     *
     * Loading all rows at once (O(1) query) and building a lookup map avoids issuing
     * one [findByKey] call per definition (O(50) queries) on every Flow emission.
     */
    @Query("SELECT * FROM achievements")
    suspend fun getAllAchievementsOnce(): List<AchievementEntity>
}
