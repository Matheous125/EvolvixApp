package com.example.evolvix.data.local

import androidx.room.*
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.model.SortMode
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Data Access Object (DAO) for habit-related database operations.
 * Provides methods for CRUD operations on habits and their completions.
 */
@Dao
abstract class HabitDao {
    /**
     * Retrieves all habits as a Flow for reactive updates
     * @return Flow of List<HabitEntity>
     */
    @Query("SELECT * FROM habits")
    abstract fun getAllHabits(): Flow<List<HabitEntity>>

    // --- Sorted queries (backing functions for getHabitsSorted) ---

    @Query("SELECT * FROM habits ORDER BY sortOrder ASC")
    protected abstract fun getHabitsByManualOrder(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY name ASC")
    protected abstract fun getHabitsByName(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY categoryGroup ASC, sortOrder ASC")
    protected abstract fun getHabitsByCategory(): Flow<List<HabitEntity>>

    /**
     * Returns a reactive [Flow] of all habits ordered according to [sortMode].
     * Dispatches to the appropriate [Query] function based on the selected mode.
     * Called by the ViewModel whenever [SortMode] changes.
     * (Pattern: Strategy — the SortMode enum selects the concrete query at runtime)
     */
    fun getHabitsSorted(sortMode: SortMode): Flow<List<HabitEntity>> = when (sortMode) {
        SortMode.MANUAL   -> getHabitsByManualOrder()
        SortMode.NAME     -> getHabitsByName()
        SortMode.CATEGORY -> getHabitsByCategory()
    }

    /**
     * Retrieves only habits that are currently active (not paused).
     * A habit is active if [pausedUntil] is NULL or its pause period has already expired.
     * [now] should be [System.currentTimeMillis].
     * (Pattern: DAO / Repository — filtered query for pause system)
     */
    @Query("SELECT * FROM habits WHERE pausedUntil IS NULL OR pausedUntil <= :now")
    abstract fun getActiveHabits(now: Long): Flow<List<HabitEntity>>

    /*
    Retrieves all habits from database
    Returns Flow for reactive updates
    No suspension needed as Flow handles asynchronous operations
    */
    @Query("SELECT * FROM habits WHERE id = :habitId")
    abstract suspend fun getHabitById(habitId: Int): HabitEntity?
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
    abstract suspend fun findByNameIgnoreCase(name: String): HabitEntity?

    /**
     * Returns all habits that belong to the given [manualGroup] as a one-shot list.
     * Used by [HabitViewModel.renameManualGroup] to batch-update group members.
     */
    @Query("SELECT * FROM habits WHERE manualGroup = :manualGroup")
    abstract suspend fun getHabitsByManualGroup(manualGroup: String): List<HabitEntity>

    /**
     * Returns all habits as a one-shot snapshot, ordered by [sortOrder].
     * Used during group creation to determine where to pack new group members.
     */
    @Query("SELECT * FROM habits ORDER BY sortOrder ASC")
    abstract suspend fun getAllHabitsOnce(): List<HabitEntity>

    /**
     * Renames a manual group in one atomic SQL UPDATE.
     * All habits whose [manualGroup] equals [oldName] are updated to [newName].
     */
    @Query("UPDATE habits SET manualGroup = :newName WHERE manualGroup = :oldName")
    abstract suspend fun renameManualGroup(oldName: String, newName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertHabit(habit: HabitEntity)
    /*
    Inserts new habit
    Replaces existing if ID conflicts
    */

    @Update
    abstract suspend fun updateHabit(habit: HabitEntity)
    /*
    Updates existing habit
    */

    @Query("DELETE FROM habits WHERE id = :habitId")
    abstract suspend fun deleteHabit(habitId: Int)
    /*
    Deletes habit by ID
    */

    @Insert
    abstract suspend fun insertCompletion(completion: HabitCompletionEntity)

    @Query("""
        SELECT * FROM habit_completions 
        WHERE habitId = :habitId AND 
        progressUpdate BETWEEN :startDate AND :endDate
    """)
    abstract fun getCompletionsForPeriod(
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
    abstract suspend fun getCompletionCountForPeriod(
        habitId: Int,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Int

    @Query("""
        SELECT * FROM habit_completions 
        WHERE habitId = :habitId AND isTargetReached = 1 
        ORDER BY progressUpdate DESC
    """)
    abstract fun getTargetCompletions(habitId: Int): Flow<List<HabitCompletionEntity>>

    @Query("""
        SELECT * FROM habit_completions 
        WHERE habitId = :habitId AND 
        progressUpdate BETWEEN :startDate AND :endDate 
        ORDER BY progressUpdate ASC
    """)
    abstract fun getProgressUpdates(
        habitId: Int,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): Flow<List<HabitCompletionEntity>>
}