package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Room entity recording a single user app-session (Phase 9.6).
 *
 * A session begins when the app moves to the foreground (ON_START) and ends when it
 * moves to the background (ON_STOP), as observed by
 * [com.example.evolvix.notifications.SessionTracker] via `ProcessLifecycleOwner`.
 *
 * Responsibility: raw session log with timestamps and visited-screen names.
 * All feature derivation (average start hour, standard deviation, session count) is
 * performed at the use-case layer in
 * [com.example.evolvix.domain.usecase.EngagementWindowUseCase]; the DAO stays thin.
 *
 * **`screensVisited` storage:** stored as a pipe-delimited (`|`) string via
 * [com.example.evolvix.data.local.Converters.fromStringList]. The list is
 * deduplicated-consecutive and capped at 32 entries by [SessionTracker] before
 * the row is persisted, preventing unbounded column growth.
 *
 * @property id             Auto-generated primary key.
 * @property startedAt      Device-local timestamp when the app entered the foreground.
 * @property endedAt        Device-local timestamp when the app entered the background;
 *                          null while the session is still in progress.
 * @property screensVisited Ordered, deduplicated list of Compose navigation route names
 *                          visited during this session (max 32 entries).
 */
@Entity(
    tableName = "app_sessions",
    indices = [Index(value = ["startedAt"])]
)
data class AppSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val screensVisited: List<String>
)
