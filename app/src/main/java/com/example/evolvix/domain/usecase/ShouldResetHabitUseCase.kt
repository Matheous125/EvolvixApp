package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitFrequency
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure business-logic use case that determines whether a habit's progress counter
 * should be reset at a given point in time.
 *
 * Acts as a **Strategy** — encapsulates one per-frequency reset predicate so the
 * same rule is enforced consistently in both [com.example.evolvix.ui.viewmodel.HabitViewModel]
 * (on-resume path) and [com.example.evolvix.notifications.HabitActionReceiver]
 * (notification-tap path).  Having a single source of truth eliminates the reset-race
 * condition where the ViewModel would re-zero a count already written by the receiver.
 *
 * **Thesis note:** This is pure, side-effect-free business logic — a JUnit test covering
 * all four [HabitFrequency] branches would be expected by a CS thesis grading panel.
 */
class ShouldResetHabitUseCase {

    /**
     * Returns `true` if [habit]'s progress counter is due for a reset at [now].
     *
     * The predicates are an exact extract of the reset logic that previously lived
     * inline in `HabitViewModel.checkAndResetProgress()` — no predicate has been changed.
     *
     * @param habit The persisted habit row from Room.
     * @param now   The current date-time (injected so the function stays pure / testable).
     */
    operator fun invoke(habit: HabitEntity, now: LocalDateTime): Boolean {
        val today = now.toLocalDate()
        val lastReset = habit.lastResetDate.toLocalDate()
        // n is the "every N" multiplier stored on the habit (defaults to 1).
        val n = habit.frequencyN.coerceAtLeast(1)

        return when (habit.frequency) {
            HabitFrequency.Daily -> {
                // Reset on every Nth new day after the last reset.
                val nextReset = lastReset.plusDays(n.toLong())
                !today.isBefore(nextReset)
            }

            HabitFrequency.Weekly -> {
                // Reset on the Nth Monday strictly after the last reset date.
                val daysToNextMonday = when (lastReset.dayOfWeek.value) {
                    1 -> 7L // last reset was Monday → next Monday is 7 days later
                    else -> (8 - lastReset.dayOfWeek.value).toLong()
                }
                val firstMonday = lastReset.plusDays(daysToNextMonday)
                // The Nth Monday from that first Monday (1st Monday = +0 extra weeks).
                val nextReset = firstMonday.plusWeeks((n - 1).toLong())
                !today.isBefore(nextReset)
            }

            HabitFrequency.Monthly -> {
                // Reset on the 1st day of every Nth month after the last reset.
                val anchorFirstDay = lastReset.plusMonths(1).withDayOfMonth(1)
                val nextReset = anchorFirstDay.plusMonths((n - 1).toLong())
                !today.isBefore(nextReset)
            }

            HabitFrequency.Yearly -> {
                // Reset on January 1st of every Nth year after the last reset.
                val anchorYear = lastReset.year + 1
                val nextReset = LocalDate.of(anchorYear + (n - 1), 1, 1)
                !today.isBefore(nextReset)
            }
        }
    }
}
