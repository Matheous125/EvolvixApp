package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.model.AchievementDefinition
import com.example.evolvix.domain.model.StreakResult
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Result type representing a single achievement that has been earned.
 *
 * Intentionally minimal — [definition] is the single source of truth for all
 * display data (title, points, icon). The [AchievementsViewModel] is responsible
 * for stamping [com.example.evolvix.data.model.AchievementEntity.unlockedAt]
 * the first time it sees this value.
 */
data class UnlockedAchievement(val definition: AchievementDefinition)

/**
 * Use Case that evaluates which of the 50 achievements the user has earned.
 *
 * Pattern: **Strategy + Pure Function** — each [AchievementDefinition] object
 * is a concrete strategy. `invoke` is a stateless pure function: the same inputs
 * always produce the same output with no side effects. All mutation (persisting
 * unlocks, setting timestamps) is delegated to the ViewModel layer.
 *
 * The caller is also responsible for **retraction**: re-invoking this use case
 * after history edits and revoking DB rows whose key no longer appears in the result.
 *
 * Notable limitations documented inline:
 * - [AchievementDefinition.SpringCleaning] and [AchievementDefinition.Journalist]
 *   always return false — their prerequisites (deletion history / notes) are not
 *   yet represented in the schema.
 */
class EvaluateAchievementsUseCase {

    /** Reused across calls — [CalculateStreakUseCase] is also stateless. */
    private val calculateStreak = CalculateStreakUseCase()

    /**
     * Evaluates all achievements and returns the subset whose requirements are met.
     *
     * Shared aggregates are computed once before the evaluation loop to avoid
     * redundant O(n) scans for every achievement.
     *
     * @param habits      All habit entities currently in the database.
     * @param completions Full completion history across all habits.
     * @return [Set] of [UnlockedAchievement] for every satisfied requirement.
     */
    operator fun invoke(
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>
    ): Set<UnlockedAchievement> {

        // Pre-compute shared aggregates — each is O(n), computed once.
        val targetReached = completions.filter { it.isTargetReached }
        val byDate: Map<LocalDate, List<HabitCompletionEntity>> =
            targetReached.groupBy { it.progressUpdate.toLocalDate() }
        val streaks: Map<Int, StreakResult> = habits.associate { h ->
            h.id to calculateStreak(
                completions.filter { it.habitId == h.id },
                h.frequency
            )
        }
        val totalCount = targetReached.size

        // A day is "perfect" when every habit has ≥1 target-reached completion that day.
        val perfectDays: Set<LocalDate> = if (habits.isEmpty()) emptySet()
        else byDate.entries
            .filter { (_, records) ->
                records.map { it.habitId }.distinct().size >= habits.size
            }
            .map { it.key }
            .toSet()

        // Evaluate all non-meta achievements first (PlatinumTrophy depends on the count).
        val nonMeta: Set<UnlockedAchievement> = AchievementDefinition.all
            .filter { it !is AchievementDefinition.PlatinumTrophy }
            .filter { def ->
                isUnlocked(def, habits, targetReached, byDate, streaks, totalCount, perfectDays)
            }
            .map { UnlockedAchievement(it) }
            .toSet()

        // PlatinumTrophy unlocks when all other 49 are earned.
        return if (nonMeta.size >= 49) {
            nonMeta + UnlockedAchievement(AchievementDefinition.PlatinumTrophy)
        } else {
            nonMeta
        }
    }

    /**
     * Returns the current numeric progress toward a single [definition].
     *
     * Used by the UI to populate progress bars on locked achievements.
     * Returns 0 for binary or schema-unsupported achievements where no
     * meaningful incremental value can be shown.
     *
     * @param definition The achievement to measure progress for.
     * @param habits     Current habit list.
     * @param completions Full completion history.
     * @return Progress value in the same unit as [AchievementDefinition.threshold].
     */
    fun computeProgress(
        definition: AchievementDefinition,
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>
    ): Int {
        val targetReached = completions.filter { it.isTargetReached }
        val streaks: Map<Int, StreakResult> = habits.associate { h ->
            h.id to calculateStreak(
                completions.filter { it.habitId == h.id },
                h.frequency
            )
        }
        return when (definition) {

            // Getting Started
            is AchievementDefinition.FirstStep -> habits.size
            is AchievementDefinition.ActionTaker -> if (targetReached.isNotEmpty()) 1 else 0
            is AchievementDefinition.DoubleTrouble,
            is AchievementDefinition.ThreesACharm -> {
                val byDate = targetReached.groupBy { it.progressUpdate.toLocalDate() }
                byDate.values.maxOfOrNull { it.map { c -> c.habitId }.distinct().size } ?: 0
            }
            is AchievementDefinition.PerfectDay -> {
                if (habits.isEmpty()) return 0
                val byDate = targetReached.groupBy { it.progressUpdate.toLocalDate() }
                // Express as percentage so the progress bar is intuitive for this binary goal.
                byDate.values.maxOfOrNull {
                    it.map { c -> c.habitId }.distinct().size * 100 / habits.size
                } ?: 0
            }
            is AchievementDefinition.TheComeback -> 0

            // Streaks — progress = highest current streak across all habits
            is AchievementDefinition.WarmingUp,
            is AchievementDefinition.Unstoppable,
            is AchievementDefinition.Fortnight,
            is AchievementDefinition.HabitForming,
            is AchievementDefinition.MonthlyMaster,
            is AchievementDefinition.SoaringHigh,
            is AchievementDefinition.CenturyClub,
            is AchievementDefinition.HalfAYear,
            is AchievementDefinition.FullCircle -> streaks.values.maxOfOrNull { it.current } ?: 0

            is AchievementDefinition.Juggler ->
                streaks.values.count { it.current >= 7 }
            is AchievementDefinition.Multitasker ->
                streaks.values.count { it.current >= 30 }

            // Milestones — progress = lifetime target-reached count
            is AchievementDefinition.Novice,
            is AchievementDefinition.Apprentice,
            is AchievementDefinition.Journeyman,
            is AchievementDefinition.Expert,
            is AchievementDefinition.Master,
            is AchievementDefinition.Grandmaster,
            is AchievementDefinition.Legend,
            is AchievementDefinition.Mythic,
            is AchievementDefinition.TenKClub,
            is AchievementDefinition.AYearInActions -> targetReached.size

            // Time of day — binary events, no meaningful incremental progress
            is AchievementDefinition.EarlyBird,
            is AchievementDefinition.HighNoon,
            is AchievementDefinition.NightOwl,
            is AchievementDefinition.MidnightOil,
            is AchievementDefinition.Bookends,
            is AchievementDefinition.Clockwork -> 0

            is AchievementDefinition.BreakfastChampion -> {
                val byDate = targetReached.groupBy { it.progressUpdate.toLocalDate() }
                byDate.values.maxOfOrNull { day -> day.count { it.progressUpdate.hour < 9 } } ?: 0
            }
            is AchievementDefinition.AfternoonHustle -> {
                val byDate = targetReached.groupBy { it.progressUpdate.toLocalDate() }
                byDate.values.maxOfOrNull { day ->
                    day.count { it.progressUpdate.hour in 13..16 }
                } ?: 0
            }

            // Weekly — binary events (no incremental value to surface)
            is AchievementDefinition.MondayMotivation,
            is AchievementDefinition.HumpDayHero,
            is AchievementDefinition.TGIF,
            is AchievementDefinition.WeekendWarrior,
            is AchievementDefinition.NoDaysOff,
            is AchievementDefinition.TheDailyGrind -> 0

            is AchievementDefinition.PerfectWeek -> {
                if (habits.isEmpty()) return 0
                val byDate = targetReached.groupBy { it.progressUpdate.toLocalDate() }
                val perfectDays = byDate.entries
                    .filter { (_, r) -> r.map { it.habitId }.distinct().size >= habits.size }
                    .map { it.key }.sorted()
                var run = if (perfectDays.isEmpty()) 0 else 1
                var best = run
                for (i in 1 until perfectDays.size) {
                    run = if (perfectDays[i - 1].plusDays(1) == perfectDays[i]) run + 1 else 1
                    if (run > best) best = run
                }
                best
            }

            // Organization
            is AchievementDefinition.TheArchitect,
            is AchievementDefinition.Visionary -> habits.size
            is AchievementDefinition.ColorfulLife -> habits.map { it.colorHex }.distinct().size
            is AchievementDefinition.SpringCleaning,
            is AchievementDefinition.Journalist -> 0

            // God Tier
            is AchievementDefinition.TheMachine -> {
                val byDate = targetReached.groupBy { it.progressUpdate.toLocalDate() }
                val dates = byDate.keys.sorted()
                if (dates.isEmpty()) 0
                else dates.maxOfOrNull { start ->
                    val end = start.plusDays(6)
                    targetReached.count { c ->
                        !c.progressUpdate.toLocalDate().isBefore(start) &&
                        !c.progressUpdate.toLocalDate().isAfter(end)
                    }
                } ?: 0
            }
            is AchievementDefinition.AbsoluteZero -> 0
            // PlatinumTrophy progress = number of other achievements unlocked; the ViewModel
            // derives this from invoke() directly rather than calling computeProgress().
            is AchievementDefinition.PlatinumTrophy -> 0
        }
    }

    // ── Private evaluation helpers ────────────────────────────────────────────

    /**
     * Core dispatch function — maps each [AchievementDefinition] strategy to its
     * unlock condition. The exhaustive `when` ensures a compile error if any of the
     * 50 achievements is removed or added to the sealed hierarchy without updating
     * this evaluator (Pattern: **Sealed Class exhaustiveness guarantee**).
     */
    private fun isUnlocked(
        def: AchievementDefinition,
        habits: List<HabitEntity>,
        targetReached: List<HabitCompletionEntity>,
        byDate: Map<LocalDate, List<HabitCompletionEntity>>,
        streaks: Map<Int, StreakResult>,
        totalCount: Int,
        perfectDays: Set<LocalDate>
    ): Boolean = when (def) {

        // ── Group 1: Getting Started ──────────────────────────────────────────

        is AchievementDefinition.FirstStep ->
            habits.isNotEmpty()

        is AchievementDefinition.ActionTaker ->
            targetReached.isNotEmpty()

        is AchievementDefinition.DoubleTrouble ->
            byDate.values.any { day -> day.map { it.habitId }.distinct().size >= 2 }

        is AchievementDefinition.ThreesACharm ->
            byDate.values.any { day -> day.map { it.habitId }.distinct().size >= 3 }

        is AchievementDefinition.PerfectDay ->
            perfectDays.isNotEmpty()

        is AchievementDefinition.TheComeback -> {
            // A comeback: completed on day D, where D-1 was missed (not in the completion set).
            // Requires ≥2 distinct dates so a single first-ever completion is not counted.
            habits.any { habit ->
                val dates = targetReached
                    .filter { it.habitId == habit.id }
                    .map { it.progressUpdate.toLocalDate() }
                    .distinct().sorted()
                val dateSet = dates.toSet()
                // Skip first date (no prior day to compare); check if any later date has a gap.
                dates.size >= 2 && dates.drop(1).any { date -> date.minusDays(1) !in dateSet }
            }
        }

        // ── Group 2: Streaks & Consistency ───────────────────────────────────

        is AchievementDefinition.WarmingUp ->
            streaks.values.any { it.current >= 3 }
        is AchievementDefinition.Unstoppable ->
            streaks.values.any { it.current >= 7 }
        is AchievementDefinition.Fortnight ->
            streaks.values.any { it.current >= 14 }
        is AchievementDefinition.HabitForming ->
            streaks.values.any { it.current >= 21 }
        is AchievementDefinition.MonthlyMaster ->
            streaks.values.any { it.current >= 30 }
        is AchievementDefinition.SoaringHigh ->
            streaks.values.any { it.current >= 60 }
        is AchievementDefinition.CenturyClub ->
            streaks.values.any { it.current >= 100 }
        is AchievementDefinition.HalfAYear ->
            streaks.values.any { it.current >= 180 }
        is AchievementDefinition.FullCircle ->
            streaks.values.any { it.current >= 365 }

        is AchievementDefinition.Juggler ->
            streaks.values.count { it.current >= 7 } >= 3
        is AchievementDefinition.Multitasker ->
            streaks.values.count { it.current >= 30 } >= 3

        // ── Group 3: Lifetime Milestones ──────────────────────────────────────

        is AchievementDefinition.Novice -> totalCount >= 10
        is AchievementDefinition.Apprentice -> totalCount >= 50
        is AchievementDefinition.Journeyman -> totalCount >= 100
        is AchievementDefinition.Expert -> totalCount >= 250
        is AchievementDefinition.Master -> totalCount >= 500
        is AchievementDefinition.Grandmaster -> totalCount >= 1000
        is AchievementDefinition.Legend -> totalCount >= 2500
        is AchievementDefinition.Mythic -> totalCount >= 5000
        is AchievementDefinition.TenKClub -> totalCount >= 10000
        is AchievementDefinition.AYearInActions -> totalCount >= 365

        // ── Group 4: Time of Day ──────────────────────────────────────────────

        is AchievementDefinition.EarlyBird ->
            targetReached.any { it.progressUpdate.hour < 7 }

        is AchievementDefinition.BreakfastChampion ->
            byDate.values.any { day -> day.count { it.progressUpdate.hour < 9 } >= 3 }

        is AchievementDefinition.HighNoon ->
            targetReached.any { it.progressUpdate.hour == 12 }

        is AchievementDefinition.AfternoonHustle ->
            byDate.values.any { day -> day.count { it.progressUpdate.hour in 13..16 } >= 3 }

        is AchievementDefinition.NightOwl ->
            targetReached.any { it.progressUpdate.hour >= 22 }

        is AchievementDefinition.MidnightOil ->
            targetReached.any { it.progressUpdate.hour == 0 }

        is AchievementDefinition.Bookends ->
            byDate.values.any { day ->
                day.any { it.progressUpdate.hour < 8 } &&
                day.any { it.progressUpdate.hour >= 20 }
            }

        is AchievementDefinition.Clockwork -> {
            // Same habit completed at the same clock-hour on 3 consecutive calendar days.
            habits.any { habit ->
                val byDateHabit = targetReached
                    .filter { it.habitId == habit.id }
                    .groupBy { it.progressUpdate.toLocalDate() }
                    .entries.sortedBy { it.key }

                byDateHabit.size >= 3 && byDateHabit.windowed(3).any { window ->
                    val (a, b, c) = window
                    a.key.plusDays(1) == b.key && b.key.plusDays(1) == c.key &&
                    a.value.map { it.progressUpdate.hour }.toSet()
                        .intersect(b.value.map { it.progressUpdate.hour }.toSet())
                        .intersect(c.value.map { it.progressUpdate.hour }.toSet())
                        .isNotEmpty()
                }
            }
        }

        // ── Group 5: Weekly Warriors ──────────────────────────────────────────

        is AchievementDefinition.MondayMotivation ->
            habits.isNotEmpty() && byDate.entries.any { (date, day) ->
                date.dayOfWeek == DayOfWeek.MONDAY &&
                day.map { it.habitId }.distinct().size >= habits.size
            }

        is AchievementDefinition.HumpDayHero ->
            habits.isNotEmpty() && byDate.entries.any { (date, day) ->
                date.dayOfWeek == DayOfWeek.WEDNESDAY &&
                day.map { it.habitId }.distinct().size >= habits.size
            }

        is AchievementDefinition.TGIF ->
            habits.isNotEmpty() && byDate.entries.any { (date, day) ->
                date.dayOfWeek == DayOfWeek.FRIDAY &&
                day.map { it.habitId }.distinct().size >= habits.size
            }

        is AchievementDefinition.WeekendWarrior -> {
            // Any Sat-Sun pair (consecutive calendar days) where both have ≥1 completion.
            val saturdays = byDate.keys.filter { it.dayOfWeek == DayOfWeek.SATURDAY }.toSet()
            val sundays = byDate.keys.filter { it.dayOfWeek == DayOfWeek.SUNDAY }.toSet()
            saturdays.any { sat -> sat.plusDays(1) in sundays }
        }

        is AchievementDefinition.NoDaysOff -> {
            // 4 consecutive perfect weekends (4 Saturdays, 1 week apart, each with a perfect Sunday).
            val perfectSats = perfectDays.filter { it.dayOfWeek == DayOfWeek.SATURDAY }.sorted()
            val perfectSuns = perfectDays.filter { it.dayOfWeek == DayOfWeek.SUNDAY }.toSet()
            perfectSats.size >= 4 && perfectSats.windowed(4).any { window ->
                window.zipWithNext().all { (a, b) -> a.plusWeeks(1) == b } &&
                window.all { sat -> sat.plusDays(1) in perfectSuns }
            }
        }

        is AchievementDefinition.TheDailyGrind -> {
            // 5 consecutive perfect days starting on a Monday (Mon → Fri).
            val sorted = perfectDays.sorted()
            sorted.size >= 5 && sorted.windowed(5).any { window ->
                window.first().dayOfWeek == DayOfWeek.MONDAY &&
                window.zipWithNext().all { (a, b) -> a.plusDays(1) == b }
            }
        }

        is AchievementDefinition.PerfectWeek -> {
            // Any 7 consecutive perfect days.
            val sorted = perfectDays.sorted()
            var run = if (sorted.isEmpty()) 0 else 1
            var best = run
            for (i in 1 until sorted.size) {
                run = if (sorted[i - 1].plusDays(1) == sorted[i]) run + 1 else 1
                if (run > best) best = run
            }
            best >= 7
        }

        // ── Group 6: Organization & Variety ──────────────────────────────────

        is AchievementDefinition.TheArchitect -> habits.size >= 5
        is AchievementDefinition.Visionary -> habits.size >= 10
        is AchievementDefinition.ColorfulLife ->
            habits.map { it.colorHex }.distinct().size >= 5
        // Not yet evaluable — deletion history / notes not in schema.
        is AchievementDefinition.SpringCleaning -> false
        is AchievementDefinition.Journalist -> false

        // ── Group 7: God Tier ─────────────────────────────────────────────────

        is AchievementDefinition.TheMachine -> {
            // 50 target-reached completions within any rolling 7-day window.
            val dates = byDate.keys.sorted()
            dates.isNotEmpty() && dates.any { start ->
                val end = start.plusDays(6)
                targetReached.count { c ->
                    !c.progressUpdate.toLocalDate().isBefore(start) &&
                    !c.progressUpdate.toLocalDate().isAfter(end)
                } >= 50
            }
        }

        is AchievementDefinition.AbsoluteZero -> {
            // 30 consecutive perfect days — any sliding window of 30 that forms an unbroken run.
            val sorted = perfectDays.sorted()
            sorted.size >= 30 && sorted.windowed(30).any { window ->
                window.zipWithNext().all { (a, b) -> a.plusDays(1) == b }
            }
        }

        // Always false here — evaluated separately in invoke() after counting nonMeta.
        is AchievementDefinition.PlatinumTrophy -> false
    }
}
