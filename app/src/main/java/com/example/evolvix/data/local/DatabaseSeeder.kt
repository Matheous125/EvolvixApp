package com.example.evolvix.data.local

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitFrequency
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Development-only database seeder.
 *
 * Inserts 5 habits (IDs 901–905) with realistic completion histories designed to
 * exercise every Phase-6 AI card scenario simultaneously:
 *
 * | Habit            | ID  | Key AI scenario                          |
 * |------------------|-----|------------------------------------------|
 * | Morning Run      | 901 | tight routine (routinePrecision ~7 min), |
 * |                  |     | high success probability, relatedHabits  |
 * | Read 30 min      | 902 | moderate rate, evening optimalHours      |
 * | Meditate         | 903 | isStreakAtRisk=true (misses Tuesdays)    |
 * | Drink 2L Water   | 904 | targetDelta=+1 (too easy, 97% rate)      |
 * | Evening Journal  | 905 | targetDelta=-1 (too hard, 29% rate)      |
 *
 * Using explicit IDs 901–905 avoids conflicts with user-created habits.
 * Re-seeding is safe: [HabitDao.insertHabit] uses REPLACE, which triggers
 * the ON DELETE CASCADE on `habit_completions`, wiping old completions for
 * those IDs before inserting fresh ones.
 *
 * Call from a coroutine on the IO dispatcher (e.g. [kotlinx.coroutines.Dispatchers.IO]).
 */
object DatabaseSeeder {

    /** Inserts all seed habits and their completion histories. */
    suspend fun seed(dao: HabitDao) {
        val today = LocalDate.now()
        insertMorningRun(dao, today)
        insertReadThirtyMin(dao, today)
        insertMeditate(dao, today)
        insertDrinkWater(dao, today)
        insertEveningJournal(dao, today)
    }

    // ── Habit 901 — "Morning Run" ─────────────────────────────────────────────
    //
    // AI scenarios exercised:
    //  • successProbabilityToday: HIGH — 27/30 days completed (90% base rate)
    //  • optimalHours: [7] — all completions 7:00–7:20 AM
    //  • routinePrecision: ~7 min — very tight morning window
    //  • resilience: ~1 period — recovers next day after each gap
    //  • relatedHabits: "Read 30 min", "Evening Journal" — both co-occur strongly
    //  • targetDelta: 0 — well-calibrated (90% → borderline; keep as-is)
    //  • isStreakAtRisk: false — no weekday shows 3+ misses
    //
    private suspend fun insertMorningRun(dao: HabitDao, today: LocalDate) {
        val skippedDays = setOf(6, 14, 22) // 3 missed days → 27 completions / 30 days
        dao.insertHabit(
            HabitEntity(
                id = 901,
                name = "Morning Run",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = 27,
                totalTargetReaches = 27,
                colorHex = "#FF5722",
                categories = listOf("Fitness", "Health"),
                sortOrder = 0
            )
        )
        // Cycle through minutes [0,5,10,15,20] to keep the spread tight (stddev ~7 min).
        val minutes = listOf(0, 10, 5, 20, 15, 0, 10, 5, 20, 15)
        var completionIndex = 0
        (1..30).forEach { daysAgo ->
            if (daysAgo !in skippedDays) {
                val date = today.minusDays(daysAgo.toLong())
                val minute = minutes[completionIndex % minutes.size]
                dao.insertCompletion(
                    HabitCompletionEntity(
                        habitId = 901,
                        progressUpdate = date.atTime(7, minute),
                        isTargetReached = true
                    )
                )
                completionIndex++
            }
        }
    }

    // ── Habit 902 — "Read 30 min" ─────────────────────────────────────────────
    //
    // AI scenarios exercised:
    //  • optimalHours: [21] — all completions at 9:00–9:20 PM
    //  • relatedHabits: "Morning Run" shares 18/18 days (rate 1.0), "Evening Journal" 5 days
    //  • successProbabilityToday: MODERATE — 18/30 (60%)
    //  • targetDelta: 0 — mid-range rate
    //  • routinePrecision: ~7 min — consistent evening session
    //
    private suspend fun insertReadThirtyMin(dao: HabitDao, today: LocalDate) {
        // Days 1–20 excluding {6, 14} → 18 completions.
        // ALL 18 are also in Morning Run's completed set → strong co-occurrence.
        val completedDays = (1..20).filter { it !in setOf(6, 14) }
        dao.insertHabit(
            HabitEntity(
                id = 902,
                name = "Read 30 min",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = 18,
                totalTargetReaches = 18,
                colorHex = "#2196F3",
                categories = listOf("Learning"),
                sortOrder = 1
            )
        )
        val minutes = listOf(0, 10, 20, 5, 15, 0, 10, 20, 5, 15)
        completedDays.forEachIndexed { index, daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 902,
                    progressUpdate = date.atTime(21, minutes[index % minutes.size]),
                    isTargetReached = true
                )
            )
        }
    }

    // ── Habit 903 — "Meditate" ────────────────────────────────────────────────
    //
    // AI scenarios exercised:
    //  • isStreakAtRisk: TRUE — misses 3 of the last 4 Tuesdays.
    //    The risk check inspects w=1..4 Tuesdays via minusWeeks(w).with(TUESDAY):
    //      w=1 → 2026-05-05 (daysAgo 12) — SKIPPED
    //      w=2 → 2026-04-28 (daysAgo 19) — SKIPPED
    //      w=3 → 2026-04-21 (daysAgo 26) — SKIPPED
    //      w=4 → 2026-04-14 (daysAgo 33) — COMPLETED → 3/4 missed → at risk
    //  • successProbabilityToday: HIGH overall (32/35 days), moderate on Tuesdays
    //  • resilience: ~1 period — snaps back next day after each missed Tuesday
    //
    private suspend fun insertMeditate(dao: HabitDao, today: LocalDate) {
        val skippedDays = setOf(12, 19, 26) // the 3 most-recent Tuesdays
        val totalCompletions = (1..35).count { it !in skippedDays } // = 32
        dao.insertHabit(
            HabitEntity(
                id = 903,
                name = "Meditate",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = totalCompletions,
                totalTargetReaches = totalCompletions,
                colorHex = "#9C27B0",
                categories = listOf("Mindfulness"),
                sortOrder = 2
            )
        )
        (1..35).forEach { daysAgo ->
            if (daysAgo !in skippedDays) {
                val date = today.minusDays(daysAgo.toLong())
                dao.insertCompletion(
                    HabitCompletionEntity(
                        habitId = 903,
                        progressUpdate = date.atTime(7, 0),
                        isTargetReached = true
                    )
                )
            }
        }
    }

    // ── Habit 904 — "Drink 2L Water" ──────────────────────────────────────────
    //
    // AI scenarios exercised:
    //  • targetDelta: +1 — 29/30 days target reached (97% → ≥ 90% threshold)
    //  • optimalHours: [8, 12, 16, 20] — completions spread across the day
    //  • routinePrecision: ~268 min — large spread (correct for a hydration habit)
    //  • Demonstrates over-completion support (4 completions per day, target=4)
    //
    private suspend fun insertDrinkWater(dao: HabitDao, today: LocalDate) {
        val skippedDay = 15 // one missed day → 29 target-reached days
        val hoursPerDay = listOf(8, 12, 16, 20)
        dao.insertHabit(
            HabitEntity(
                id = 904,
                name = "Drink 2L Water",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 4,
                totalProgressUpdates = 29 * 4, // 116
                totalTargetReaches = 29,
                colorHex = "#00BCD4",
                categories = listOf("Health"),
                sortOrder = 3
            )
        )
        (1..30).forEach { daysAgo ->
            if (daysAgo != skippedDay) {
                val date = today.minusDays(daysAgo.toLong())
                hoursPerDay.forEach { hour ->
                    dao.insertCompletion(
                        HabitCompletionEntity(
                            habitId = 904,
                            progressUpdate = date.atTime(hour, 0),
                            // Target is reached only after the 4th completion (20:00).
                            isTargetReached = (hour == 20)
                        )
                    )
                }
            }
        }
    }

    // ── Habit 905 — "Evening Journal" ─────────────────────────────────────────
    //
    // AI scenarios exercised:
    //  • targetDelta: -1 — only 4/14 completions in last 14 days (29% → ≤ 40% threshold)
    //  • optimalHours: [21] — all completions at 21:30
    //  • routinePrecision: 0 min — perfectly consistent time (stddev = 0)
    //  • resilience: ~3 periods — irregular habit with multi-day gaps
    //  • relatedHabits (via Read 30 min): {2,5,8,11,18} ∩ Read set = 5 shared → rate 5/10 = 0.5 ✓
    //
    private suspend fun insertEveningJournal(dao: HabitDao, today: LocalDate) {
        // daysAgo pattern: 4 completions in last 14 days, 6 spread further out.
        // Overlap with Read 30 min (which covers days 1-20 excl {6,14}):
        //   shared = {2,5,8,11,18} = 5 days → co-occurrence rate 5/10 = 0.5 ✓
        val completedDays = listOf(2, 5, 8, 11, 18, 22, 26, 30, 34, 38)
        dao.insertHabit(
            HabitEntity(
                id = 905,
                name = "Evening Journal",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = completedDays.size,
                totalTargetReaches = completedDays.size,
                colorHex = "#FF9800",
                categories = listOf("Mindfulness"),
                sortOrder = 4
            )
        )
        completedDays.forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 905,
                    progressUpdate = date.atTime(21, 30),
                    isTargetReached = true
                )
            )
        }
    }
}
