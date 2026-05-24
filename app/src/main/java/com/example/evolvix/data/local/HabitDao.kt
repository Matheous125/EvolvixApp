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

    @Query("SELECT * FROM habits ORDER BY id ASC")
    protected abstract fun getHabitsByDefault(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY name ASC")
    protected abstract fun getHabitsByName(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY name DESC")
    protected abstract fun getHabitsByNameDesc(): Flow<List<HabitEntity>>

    @Query("""
        SELECT * FROM habits ORDER BY
        CASE frequency WHEN 'Daily' THEN 1 WHEN 'Weekly' THEN 2 WHEN 'Monthly' THEN 3 WHEN 'Yearly' THEN 4 ELSE 5 END ASC,
        frequencyN ASC,
        name ASC
    """)
    protected abstract fun getHabitsByFreqAsc(): Flow<List<HabitEntity>>

    @Query("""
        SELECT * FROM habits ORDER BY
        CASE frequency WHEN 'Daily' THEN 1 WHEN 'Weekly' THEN 2 WHEN 'Monthly' THEN 3 WHEN 'Yearly' THEN 4 ELSE 5 END DESC,
        frequencyN DESC,
        name ASC
    """)
    protected abstract fun getHabitsByFreqDesc(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY COALESCE(categoryGroup, '') ASC, name ASC")
    protected abstract fun getHabitsByCategory(): Flow<List<HabitEntity>>

    /**
     * Returns a reactive [Flow] of all habits ordered according to [sortMode].
     * Dispatches to the appropriate [Query] function based on the selected mode.
     * Called by the ViewModel whenever [SortMode] changes.
     * (Pattern: Strategy — the SortMode enum selects the concrete query at runtime)
     */
    fun getHabitsSorted(sortMode: SortMode): Flow<List<HabitEntity>> = when (sortMode) {
        SortMode.DEFAULT   -> getHabitsByDefault()
        SortMode.NAME      -> getHabitsByName()
        SortMode.NAME_DESC -> getHabitsByNameDesc()
        SortMode.FREQ_ASC  -> getHabitsByFreqAsc()
        SortMode.FREQ_DESC -> getHabitsByFreqDesc()
        SortMode.CATEGORY  -> getHabitsByCategory()
        SortMode.CUSTOM    -> getHabitsByManualOrder()
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

    /**
     * Deletes all habits that belong to [groupName] in one atomic DELETE.
     * Used by [HabitViewModel.deleteManualGroupWithHabits] for bulk group deletion.
     */
    @Query("DELETE FROM habits WHERE manualGroup = :groupName")
    abstract suspend fun deleteHabitsByGroup(groupName: String)

    /**
     * Removes a single habit from its manual group by clearing the [manualGroup] field.
     * The habit remains in the database as an ungrouped habit.
     * Used by [HabitViewModel.updateManualGroupMembers] when the user unchecks a habit.
     */
    @Query("UPDATE habits SET manualGroup = NULL WHERE id = :habitId")
    abstract suspend fun unassignHabitFromGroup(habitId: Int)

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

    // --- Phase 3.1: History Screen queries ---

    /**
     * Returns all completion records for a given habit, ordered newest-first.
     * This is the primary data source for [HistoryViewModel] — it emits a fresh list
     * every time a completion is inserted, updated, or deleted (Observer pattern via Flow).
     *
     * @param habitId The ID of the parent habit.
     * @return Flow of all [HabitCompletionEntity] rows for that habit.
     */
    @Query("SELECT * FROM habit_completions WHERE habitId = :habitId ORDER BY progressUpdate DESC")
    abstract fun getCompletionsForHabit(habitId: Int): Flow<List<HabitCompletionEntity>>

    /**
     * Returns only the completion records for [habitId] where the user provided an explicit
     * difficulty rating ([HabitCompletionEntity.perceivedDifficulty] IS NOT NULL).
     *
     * Used by [com.example.evolvix.domain.usecase.DifficultyEstimateUseCase] to compute
     * [recentAvgRated] — the average of the user's actual ratings over the last 14 days.
     * The WHERE clause keeps the result set small: rows without a rating are irrelevant
     * to the average and skipping them avoids filtering in Kotlin.
     *
     * @param habitId The ID of the parent habit.
     * @return Flow of rated [HabitCompletionEntity] rows, newest first.
     */
    @Query("SELECT * FROM habit_completions WHERE habitId = :habitId AND perceivedDifficulty IS NOT NULL ORDER BY progressUpdate DESC")
    abstract fun getCompletionsWithDifficulty(habitId: Int): Flow<List<HabitCompletionEntity>>

    /**
     * Returns ALL completion records across every habit as a single reactive [Flow].
     *
     * This is the data source for the streak engine in [HabitViewModel]: combining this
     * flow with the habit list flow via [combine] means any insert, update, or delete
     * made by [HistoryViewModel] automatically triggers a streak recomputation for all
     * habits — no manual refresh, no polling.
     *
     * (Pattern: Observer via Flow — Room invalidates and re-emits after every write to
     * the habit_completions table, regardless of which ViewModel caused the write)
     */
    @Query("SELECT * FROM habit_completions")
    abstract fun getAllCompletions(): Flow<List<HabitCompletionEntity>>

    /**
     * Overwrites an existing completion record in place.
     * Used by the History screen's inline edit flow to change [progressUpdate] or
     * [isTargetReached] without deleting and re-inserting (keeps the same primary key).
     * (Pattern: DAO / Repository — targeted UPDATE via @Update)
     *
     * @param completion The updated entity. Its [id] must match an existing row.
     */
    @Update
    abstract suspend fun updateCompletion(completion: HabitCompletionEntity)

    /**
     * Permanently removes a single completion record identified by [id].
     * Called from [HistoryViewModel] when the user swipes-to-delete or taps the delete icon.
     *
     * @param id Primary key of the [HabitCompletionEntity] to remove.
     */
    @Query("DELETE FROM habit_completions WHERE id = :id")
    abstract suspend fun deleteCompletion(id: Int)

    /**
     * Inserts a completion with a user-supplied past timestamp (retroactive entry).
     * Functionally identical to [insertCompletion] at the SQL level, but named separately
     * to make the intent explicit at the call site: the [progressUpdate] date comes from
     * the user via a [DatePicker] + [TimePicker] dialog, not from the current system time.
     * (Pattern: Repository — named intent over structural difference)
     *
     * @param completion A new [HabitCompletionEntity] with [id] = 0 (auto-generated).
     */
    @Insert
    abstract suspend fun insertRetroactive(completion: HabitCompletionEntity)

    /**
     * Counts how many completions for [habitId] have a [progressUpdate] timestamp
     * that falls on or after [cycleStart].
     *
     * Used by [HistoryViewModel] after any mutation (add / edit / delete) to recompute
     * [HabitEntity.currentCount] so the progress bar reflects the real number of
     * completion records in the current reset cycle.
     *
     * @param habitId    The parent habit's primary key.
     * @param cycleStart The start of the current cycle — equal to [HabitEntity.lastResetDate].
     * @return Count of matching completion rows.
     */
    @Query("SELECT COUNT(*) FROM habit_completions WHERE habitId = :habitId AND progressUpdate >= :cycleStart")
    abstract suspend fun getCompletionCountSince(habitId: Int, cycleStart: LocalDateTime): Int

    // ── Phase 7.2v2 — analytics queries for personalized timing ─────────────
    //
    // SQLite stores LocalDateTime via [Converters.dateToTimestamp] as ISO-8601
    // strings, so `strftime('%H'/'%M', column)` extracts hour-of-day / minute-of-hour
    // directly. We convert to a single minute-of-day scalar (hour*60 + min) which
    // both the daily-summary scheduler and the per-habit grace-period scheduler use.

    /**
     * Average "minute-of-day" of all target-reaching completions of [habitId] since
     * [since]. Used by `ScheduleReminderUseCase` to pick a personalized reminder time
     * (typically `avg + 30min`) for daily habits.
     *
     * @return Float in [0, 1440), or `null` when there is no qualifying data.
     */
    @Query("""
        SELECT AVG(
            CAST(strftime('%H', progressUpdate) AS REAL) * 60 +
            CAST(strftime('%M', progressUpdate) AS REAL)
        )
        FROM habit_completions
        WHERE habitId = :habitId
          AND isTargetReached = 1
          AND progressUpdate >= :since
    """)
    abstract suspend fun avgTargetReachMinuteForHabit(
        habitId: Int,
        since: LocalDateTime
    ): Float?

    /**
     * Across ALL habits, the average "last completion minute-of-day" for each
     * calendar day since [since]. Used by `DailySummaryWorker` to time the daily
     * summary notification ~30 minutes after the user's habitual last completion.
     *
     * The inner aggregation picks the latest completion per day, then the outer
     * `AVG` produces the user's typical "wind-down" minute.
     *
     * @return Float in [0, 1440), or `null` if the user has no completions yet.
     */
    @Query("""
        SELECT AVG(last_minute) FROM (
            SELECT
                CAST(strftime('%H', MAX(progressUpdate)) AS REAL) * 60 +
                CAST(strftime('%M', MAX(progressUpdate)) AS REAL) AS last_minute
            FROM habit_completions
            WHERE progressUpdate >= :since
            GROUP BY date(progressUpdate)
        )
    """)
    abstract suspend fun avgLastCompletionMinuteOverall(since: LocalDateTime): Float?

    /**
     * Count of (habit × day) target hits today — read by `ComposeDailySummaryUseCase`.
     */
    @Query("""
        SELECT COUNT(DISTINCT habitId)
        FROM habit_completions
        WHERE isTargetReached = 1
          AND date(progressUpdate) = date(:today)
    """)
    abstract suspend fun countTargetReachesOnDay(today: LocalDateTime): Int

    /** Count of progressUpdate events for today (any habit, even partial). */
    @Query("""
        SELECT COUNT(*)
        FROM habit_completions
        WHERE date(progressUpdate) = date(:today)
    """)
    abstract suspend fun countProgressUpdatesOnDay(today: LocalDateTime): Int
}