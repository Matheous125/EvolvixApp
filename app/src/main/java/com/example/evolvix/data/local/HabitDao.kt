package com.example.evolvix.data.local

import androidx.room.*
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Data Access Object (DAO) for habit-related database operations.
 * Provides methods for CRUD operations on habits and their completions.
 */
@Dao
interface HabitDao {
    /**
     * Retrieves all habits as a Flow for reactive updates
     * @return Flow of List<HabitEntity>
     */
    @Query("SELECT * FROM habits")
    fun getAllHabits(): Flow<List<HabitEntity>>

    /**
     * Retrieves only habits that are currently active (not paused).
     * A habit is active if [pausedUntil] is NULL or its pause period has already expired.
     * [now] should be [System.currentTimeMillis].
     * (Pattern: DAO / Repository — filtered query for pause system)
     */
    @Query("SELECT * FROM habits WHERE pausedUntil IS NULL OR pausedUntil <= :now")
    fun getActiveHabits(now: Long): Flow<List<HabitEntity>>

    /*
    Retrieves all habits from database
    Returns Flow for reactive updates
    No suspension needed as Flow handles asynchronous operations
    */
    @Query("SELECT * FROM habits WHERE id = :habitId")
    suspend fun getHabitById(habitId: Int): HabitEntity?
    /*
    Retrieves single habit by ID
    Returns nullable HabitEntity
    */

    /**
     * Finds a habit whose name matches [name] case-insensitively.
     * Used by the ViewModel for pre-insert validation before attempting to write,
     * avoiding a SQLite unique-constraint exception on the name index.
     * Returns null if no matching habit exists (name is available).
     * (Pattern: Repository / DAO — validation query)
     */
    @Query("SELECT * FROM habits WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByNameIgnoreCase(name: String): HabitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabit(habit: HabitEntity)
    /*
    Inserts new habit
    Replaces existing if ID conflicts
    */

    @Update
    suspend fun updateHabit(habit: HabitEntity)
    /*
    Updates existing habit
    */

    @Query("DELETE FROM habits WHERE id = :habitId")
    suspend fun deleteHabit(habitId: Int)
    /*
    Deletes habit by ID
    */

    @Insert
    suspend fun insertCompletion(completion: HabitCompletionEntity)

    @Query("""
        SELECT * FROM habit_completions 
        WHERE habitId = :habitId AND 
        progressUpdate BETWEEN :startDate AND :endDate
    """)
    fun getCompletionsForPeriod(
        habitId: Int,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Flow<List<HabitCompletionEntity>>

      /**
     * Gets all completions for a habit within a date range
     * @param habitId The habit's ID
     * @param startDate Start of the period
     * @param endDate End of the period
     * @return Flow of completion records
     */
    @Query("""
        SELECT COUNT(*) FROM habit_completions 
        WHERE habitId = :habitId AND 
        progressUpdate BETWEEN :startDate AND :endDate
    """)
    suspend fun getCompletionCountForPeriod(
        habitId: Int,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Int

    @Query("""
        SELECT * FROM habit_completions 
        WHERE habitId = :habitId AND isTargetReached = 1 
        ORDER BY progressUpdate DESC
    """)
    fun getTargetCompletions(habitId: Int): Flow<List<HabitCompletionEntity>>

    @Query("""
        SELECT * FROM habit_completions 
        WHERE habitId = :habitId AND 
        progressUpdate BETWEEN :startDate AND :endDate 
        ORDER BY progressUpdate ASC
    """)
    fun getProgressUpdates(
        habitId: Int,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Flow<List<HabitCompletionEntity>>
}