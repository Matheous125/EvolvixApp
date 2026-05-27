package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.AchievementEntity
import com.example.evolvix.data.model.DailySummaryEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.model.WeeklyForecast
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure-function use case that turns today's raw data into a [DailySummaryEntity]
 * ready to insert + post as a notification (Phase 7.2 v2).
 *
 * **Why a separate use case** (instead of doing this inside the worker): keeps the
 * worker thin and the formatting logic unit-testable without WorkManager / Room.
 * (Pattern: **Interactor** — application-specific business rule.)
 *
 * Personalization rules:
 *  - Highlights at most one "win" line: target hits, then achievements, then progress.
 *  - Uses an encouraging tone when count > 0, a gentle nudge when count == 0.
 *  - Never references explicit habit names (privacy if notification is shown on lock screen).
 */
class ComposeDailySummaryUseCase {

    /**
     * Composes the summary.
     *
     * @param today         Reference date (injected for testing).
     * @param activeHabits  Snapshot of non-paused habits at compose time.
     * @param completionsAll All completions (Compose use case filters to today).
     * @param achievementsUnlockedToday Achievements with `unlockedAt.toLocalDate() == today`.
     * @param weekRate            0..1 weekly completion rate from [WeeklyOverviewUseCase].
     * @param nextWeekForecast    Optional Phase 8.3 forecast. When non-null and
     *                            [WeeklyForecast.hasSufficientData] is true and today is
     *                            Sunday, a one-line next-week outlook is appended to the body.
     *                            Defaults to null so existing callers need no change.
     */
    operator fun invoke(
        today: LocalDate,
        activeHabits: List<HabitEntity>,
        completionsAll: List<HabitCompletionEntity>,
        achievementsUnlockedToday: List<AchievementEntity>,
        weekRate: Float,
        nextWeekForecast: WeeklyForecast? = null
    ): DailySummaryEntity {
        val todays = completionsAll.filter { it.progressUpdate.toLocalDate() == today }
        val progressUpdates = todays.size
        val targetReaches = todays.filter { it.isTargetReached }
            .map { it.habitId }
            .toSet()
            .size
        val total = activeHabits.size
        val achievementsCount = achievementsUnlockedToday.size
        val weekPct = (weekRate * 100).toInt().coerceIn(0, 100)

        val title = when {
            targetReaches > 0 && targetReaches == total -> "Perfect day"
            targetReaches > 0                            -> "Today's wins"
            progressUpdates > 0                          -> "Some progress today"
            else                                         -> "Tomorrow's a fresh start"
        }

        val parts = mutableListOf<String>()
        if (targetReaches > 0) parts += "$targetReaches of $total habits hit target"
        else if (total > 0) parts += "0 of $total habits hit target"
        if (progressUpdates > 0 && targetReaches < progressUpdates)
            parts += "$progressUpdates progress check-ins"
        if (achievementsCount > 0)
            parts += "$achievementsCount new achievement${if (achievementsCount == 1) "" else "s"}"
        parts += "week at $weekPct%"

        val shortBody = parts.joinToString(" · ").take(120)

        val encouragement = when {
            targetReaches == total && total > 0 -> "Every habit met today — that's how streaks are built."
            targetReaches > 0                    -> "Good work locking in $targetReaches. Tomorrow we go again."
            progressUpdates > 0                  -> "You moved the needle today. Try to close at least one habit tomorrow."
            else                                 -> "No completions logged. A short, easy win tomorrow is the fastest reset."
        }

        // Sunday forecast line — append only when today is Sunday, the forecast is
        // available, and the model has seen enough history to trust the output.
        val forecastLine: String? =
            if (today.dayOfWeek == DayOfWeek.SUNDAY &&
                nextWeekForecast != null &&
                nextWeekForecast.hasSufficientData
            ) {
                val arrow = when (nextWeekForecast.direction) {
                    WeeklyForecast.Direction.UP   -> "↑"
                    WeeklyForecast.Direction.FLAT -> "→"
                    WeeklyForecast.Direction.DOWN -> "↓"
                }
                val pct = (nextWeekForecast.predictedRate * 100).toInt().coerceIn(0, 100)
                "Next week looks $arrow — predicted $pct% completion rate."
            } else null

        val body = buildString {
            parts.forEach { appendLine("• $it") }
            appendLine()
            append(encouragement)
            if (forecastLine != null) {
                appendLine()
                appendLine()
                append(forecastLine)
            }
        }.trimEnd()

        return DailySummaryEntity(
            date = today,
            generatedAt = LocalDateTime.now(),
            title = title,
            shortBody = shortBody,
            body = body,
            todayProgressUpdates = progressUpdates,
            todayTargetReaches = targetReaches,
            totalActiveHabits = total,
            achievementsUnlockedToday = achievementsCount,
            weekCompletionPct = weekPct
        )
    }
}
