package com.example.evolvix.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Persisted record of a single daily summary post (Phase 7.2 v2).
 *
 * Each row represents one "inbox card" the user can review in [com.example.evolvix.ui.screens.SummaryInboxScreen].
 * Storing the summary in Room — rather than only posting a transient notification — gives
 * the user a scrollable history and lets us track read/unread state for the dismissal
 * auto-disable rule (7 consecutive dismissals → disable summary, per design).
 *
 * **Why a unique index on [date]:** the worker may try to insert twice for the same
 * day (e.g. test trigger + scheduled fire). The unique index causes the second insert
 * to be ignored via `OnConflictStrategy.IGNORE`.
 *
 * @property date The calendar day this summary describes (one row per day).
 * @property generatedAt Exact moment the worker composed and saved the summary.
 * @property title Short headline shown at the top of the inbox card and notification.
 * @property shortBody One-line text used inside the system notification (<=120 chars).
 * @property body Multi-line markdown-ish text rendered inside the inbox card.
 * @property todayProgressUpdates Number of `markProgress` events that day (any habit).
 * @property todayTargetReaches Number of distinct (habit × day) target-hits.
 * @property totalActiveHabits Snapshot of how many active habits the user had that day.
 * @property achievementsUnlockedToday Achievements whose `unlockedAt` falls on [date].
 * @property weekCompletionPct 0..100 — the `weekCompletionRate * 100` rounded.
 * @property isRead True once the user opens the corresponding inbox card or taps the
 *           notification — used to reset the dismiss-streak counter.
 */
@Entity(
    tableName = "daily_summaries",
    indices = [Index(value = ["date"], unique = true)]
)
data class DailySummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val date: LocalDate,
    val generatedAt: LocalDateTime,
    val title: String,
    val shortBody: String,
    val body: String,
    val todayProgressUpdates: Int,
    val todayTargetReaches: Int,
    val totalActiveHabits: Int,
    val achievementsUnlockedToday: Int,
    val weekCompletionPct: Int,
    val isRead: Boolean = false
)
