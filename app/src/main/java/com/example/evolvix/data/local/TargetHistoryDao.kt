package com.example.evolvix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.evolvix.data.model.HabitTargetHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the [HabitTargetHistoryEntity] table (Phase 9.3).
 *
 * Responsibility: raw read/write access to the target-change audit log.
 * All business logic (rate aggregation, delta computation) lives in
 * [com.example.evolvix.domain.usecase.TargetAdjustmentUseCase].
 *
 * This is a DAO (Data Access Object) in the standard Room/Repository pattern:
 * it is an interface compiled by Room's annotation processor into a concrete
 * implementation at build time.
 */
@Dao
interface TargetHistoryDao {

    /**
     * Inserts a new target-change record.
     * Called by [HabitViewModel.updateHabit] immediately after [HabitDao.updateHabit]
     * when the habit's target value changes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HabitTargetHistoryEntity)

    /**
     * Returns all target-change records for [habitId] ordered chronologically.
     * Emits a new list whenever the table is written — the use case collects
     * the latest emission once per recommendation cycle.
     */
    @Query("SELECT * FROM habit_target_history WHERE habitId = :habitId ORDER BY changedAt ASC")
    fun getForHabit(habitId: Int): Flow<List<HabitTargetHistoryEntity>>

    /**
     * One-shot query returning the most recent target-change record for [habitId],
     * or null if the target has never been changed. Used by [HabitViewModel.updateHabit]
     * to determine the next [HabitTargetHistoryEntity.version] value synchronously.
     */
    @Query("SELECT * FROM habit_target_history WHERE habitId = :habitId ORDER BY changedAt DESC LIMIT 1")
    suspend fun getLatestForHabit(habitId: Int): HabitTargetHistoryEntity?
}
