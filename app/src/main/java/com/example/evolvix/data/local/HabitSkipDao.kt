package com.example.evolvix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * DAO for the [HabitSkipEntity] table (Phase 9.5).
 *
 * Responsibility: raw read/write access to the skip-event log.
 * All business logic (skip-rate computation, voluntary vs involuntary
 * classification for Resilience v2, feature extraction for
 * [com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase]) lives
 * at the use-case layer; the DAO stays intentionally thin.
 *
 * This is a DAO (Data Access Object) in the standard Room/Repository pattern:
 * Room's annotation processor generates the concrete implementation at build time.
 */
@Dao
interface HabitSkipDao {

    /**
     * Inserts a new skip record.
     * Called by [com.example.evolvix.notifications.SkipReasonPickerActivity]
     * after the user selects a reason chip, and by [HabitActionReceiver] when
     * the Skip notification action is tapped (defaults to [SkipReason.NO_REASON]
     * if the picker is not shown).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skip: HabitSkipEntity)

    /**
     * Emits all skip records for [habitId] ordered chronologically.
     * Observed by [com.example.evolvix.ui.viewmodel.StatisticsViewModel] to
     * keep the skip-reason prediction card up to date reactively.
     */
    @Query("SELECT * FROM habit_skips WHERE habitId = :habitId ORDER BY skippedAt ASC")
    fun getForHabit(habitId: Int): Flow<List<HabitSkipEntity>>

    /**
     * One-shot query returning skip records for [habitId] after [since].
     * Used by [com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase]
     * to compute [recentSkipRate14d] and by [ResilienceScoreUseCase] to gather
     * involuntary skips for gap-math exclusion.
     */
    @Query("SELECT * FROM habit_skips WHERE habitId = :habitId AND skippedAt >= :since ORDER BY skippedAt ASC")
    suspend fun getRecentForHabit(habitId: Int, since: LocalDateTime): List<HabitSkipEntity>

    /**
     * One-shot query returning all skip records across all habits after [since].
     * Used by [StatisticsViewModel] to bulk-load skips for all habits in a
     * single DB round-trip rather than N per-habit queries.
     */
    @Query("SELECT * FROM habit_skips WHERE skippedAt >= :since ORDER BY skippedAt ASC")
    suspend fun getAllRecent(since: LocalDateTime): List<HabitSkipEntity>
}
