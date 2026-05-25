package com.example.evolvix.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.evolvix.data.model.AppSessionEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * DAO for the [AppSessionEntity] table (Phase 9.6).
 *
 * Responsibility: raw read/write access to the app-session log.
 * All feature derivation (average start hour, stddev, session length)
 * lives at the use-case layer in
 * [com.example.evolvix.domain.usecase.EngagementWindowUseCase]; this DAO stays thin.
 *
 * This is a DAO (Data Access Object) in the standard Room/Repository pattern:
 * Room's annotation processor generates the concrete implementation at build time.
 */
@Dao
interface AppSessionDao {

    /**
     * Inserts a new session record when the app moves to the foreground.
     * Called by [com.example.evolvix.notifications.SessionTracker.onStart].
     * Returns the auto-generated row ID so [SessionTracker] can update the same
     * row with [endedAt] and [screensVisited] when the session ends.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: AppSessionEntity): Long

    /**
     * Updates an existing session row with [endedAt] and [screensVisited].
     * Called by [com.example.evolvix.notifications.SessionTracker.onStop].
     */
    @Update
    suspend fun update(session: AppSessionEntity)

    /**
     * Emits all sessions that started at or after [since], ordered chronologically.
     * Observed reactively by [com.example.evolvix.ui.viewmodel.StatisticsViewModel]
     * to keep the engagement-window card up to date.
     */
    @Query("SELECT * FROM app_sessions WHERE startedAt >= :since ORDER BY startedAt ASC")
    fun getSince(since: LocalDateTime): Flow<List<AppSessionEntity>>

    /**
     * One-shot query returning the [limit] most-recent sessions.
     * Used by [com.example.evolvix.domain.usecase.EngagementWindowUseCase] to extract
     * the feature vector without reading the full session history.
     */
    @Query("SELECT * FROM app_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AppSessionEntity>

    /**
     * Returns the total number of recorded sessions.
     * Used by [EngagementWindowUseCase] for the [com.example.evolvix.domain.model.EngagementWindow.MIN_SESSIONS]
     * data-sufficiency guard.
     */
    @Query("SELECT COUNT(*) FROM app_sessions")
    suspend fun count(): Int
}
