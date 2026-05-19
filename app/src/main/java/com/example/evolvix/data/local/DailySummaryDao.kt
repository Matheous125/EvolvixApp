package com.example.evolvix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.evolvix.data.model.DailySummaryEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Room access layer for daily summary cards (Phase 7.2 v2).
 *
 * Exposes Flow-based reads so [com.example.evolvix.ui.viewmodel.SummaryInboxViewModel]
 * stays reactive (Observer pattern), plus a few suspend helpers used by the worker and
 * by the deep-link / notification-tap path in `MainActivity`.
 */
@Dao
abstract class DailySummaryDao {

    /** Reverse-chronological feed for the inbox screen. */
    @Query("SELECT * FROM daily_summaries ORDER BY date DESC")
    abstract fun getAll(): Flow<List<DailySummaryEntity>>

    /** Drives the unread-badge count rendered next to the inbox icon on MainScreen. */
    @Query("SELECT COUNT(*) FROM daily_summaries WHERE isRead = 0")
    abstract fun getUnreadCount(): Flow<Int>

    /** Used by the worker to avoid generating two summaries for the same date. */
    @Query("SELECT * FROM daily_summaries WHERE date = :date LIMIT 1")
    abstract suspend fun findByDate(date: LocalDate): DailySummaryEntity?

    /** Used by the notification tap path to fetch the freshest summary. */
    @Query("SELECT * FROM daily_summaries ORDER BY generatedAt DESC LIMIT 1")
    abstract suspend fun findLatest(): DailySummaryEntity?

    /**
     * OnConflictStrategy.IGNORE prevents duplicate inserts for the same date. The
     * worker treats a 0/-1 row id as "another worker already inserted today's row".
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: DailySummaryEntity): Long

    @Query("UPDATE daily_summaries SET isRead = 1 WHERE id = :id")
    abstract suspend fun markRead(id: Int)

    @Query("UPDATE daily_summaries SET isRead = 1")
    abstract suspend fun markAllRead()

    /** Bulk delete used by future settings "clear inbox" affordance. */
    @Query("DELETE FROM daily_summaries")
    abstract suspend fun deleteAll()
}
