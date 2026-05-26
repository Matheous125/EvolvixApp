package com.example.evolvix.data.local

import com.example.evolvix.data.model.AppSessionEntity
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitSkipEntity
import com.example.evolvix.data.model.SkipReason
import java.time.LocalDate

/**
 * Development-only database seeder.
 *
 * Inserts 6 habits (IDs 901–906) with realistic completion histories designed to
 * exercise every ML card on the Statistics screen simultaneously:
 *
 * | Habit               | ID  | Key ML scenario                                          |
 * |---------------------|-----|----------------------------------------------------------|
 * | Morning Run         | 901 | tight routine (~7 min stddev), high success probability, |
 * |                     |     | relatedHabits; perceivedDifficulty=2 (EASY, last 14d);   |
 * |                     |     | fromReminder + snoozeCount=0 (LOW snooze risk shown in   |
 * |                     |     | Smart Reminders)                                         |
 * | Read 30 min         | 902 | moderate rate, evening optimalHours, co-occurrence       |
 * | Meditate            | 903 | isStreakAtRisk=true (misses Tuesdays); perceivedDifficulty|
 * |                     |     | =3 (MODERATE, last 14d)                                  |
 * | Drink 2L Water      | 904 | targetDelta=+2 (97% rate, 5 completions/day, apr=1.25)   |
 * | Evening Journal     | 905 | targetDelta=-1 (rate=0%, partial completions, apr=0.5)   |
 * | Wake Up No Phone    | 906 | Snooze Drift CRITICAL (snoozeCount=3, rate7d=14%);       |
 * |                     |     | Skip Reason Forecast (5 skip records); perceivedDifficulty|
 * |                     |     | =5 (VERY_HARD)                                           |
 *
 * Additionally inserts 16 [AppSessionEntity] records (evening cluster ~20:00) so
 * [EngagementWindowUseCase] passes its MIN_SESSIONS=14 guard and predicts
 * hour ~20 with high confidence.
 *
 * Using explicit IDs 901–906 avoids conflicts with user-created habits.
 * Re-seeding is safe: [HabitDao.insertHabit] uses REPLACE, which triggers the
 * ON DELETE CASCADE on `habit_completions`, `habit_skips`, and `habit_target_history`,
 * wiping those rows before inserting fresh ones.
 * AppSession rows are not CASCADE-deleted (no habit FK); they accumulate on
 * repeated seeds but the use case reads only the 100 most-recent, so the signal
 * remains stable.
 *
 * Call from a coroutine on the IO dispatcher (e.g. [kotlinx.coroutines.Dispatchers.IO]).
 */
object DatabaseSeeder {

    /**
     * Inserts all seed habits, completion histories, skip records, and app sessions.
     *
     * @param dao            DAO for habits and completions.
     * @param skipDao        DAO for habit skip records (Phase 9.5 Skip Reason Forecast).
     * @param sessionDao     DAO for app sessions (Phase 9.6 Engagement Window).
     * @param targetHistoryDao DAO for target-change history (Phase 9.3 Target Calibration).
     */
    suspend fun seed(
        dao: HabitDao,
        skipDao: HabitSkipDao,
        sessionDao: AppSessionDao,
        targetHistoryDao: TargetHistoryDao
    ) {
        val today = LocalDate.now()
        insertMorningRun(dao, today)
        insertReadThirtyMin(dao, today)
        insertMeditate(dao, today)
        insertDrinkWater(dao, today)
        insertEveningJournal(dao, today)
        insertWakeUpNoPhone(dao, skipDao, today)
        insertAppSessions(sessionDao, today)
    }

    // ── Habit 901 — "Morning Run" ─────────────────────────────────────────────
    //
    // ML scenarios exercised:
    //  • successProbabilityToday: HIGH — 27/30 days completed (90% base rate)
    //  • optimalHours: [7] — all completions 7:00–7:20 AM
    //  • routinePrecision: ~7 min — very tight morning window
    //  • resilience: ~1 period — recovers next day after each gap
    //  • relatedHabits: "Read 30 min", "Evening Journal" — both co-occur strongly
    //  • targetDelta: 0 — well-calibrated (90% → borderline; keep as-is)
    //  • isStreakAtRisk: false — no weekday shows 3+ misses
    //  • Phase 9.4 perceivedDifficulty=2 (EASY) on last 14 days → recentAvgRated=2.0
    //  • Phase 9.1 fromReminder=true + snoozeCount=0 on last 14 days →
    //    Smart Reminders shows positive lift; LOW snooze risk (won't show in Snooze Drift)
    //
    private suspend fun insertMorningRun(dao: HabitDao, today: LocalDate) {
        val skippedDays = setOf(6, 14, 22) // 3 missed days → 27 completions / 30 days
        // Days within the past 14 days that have completions (used for reminder/difficulty data).
        val reminderDays = (1..14).filter { it !in skippedDays }.toSet()
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
                reminderEnabled = true,
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
                // Completions within the last 14 days carry reminder metadata and a user
                // difficulty rating of 2 (EASY) — satisfies MIN_RATINGS=5 for recentAvgRated.
                // snoozeCount=0 → snoozeFrequencyLast14Days=0 → LOW snooze risk (correct for
                // a habit the user finishes immediately after the reminder fires).
                val isRecentReminderDay = daysAgo in reminderDays
                dao.insertCompletion(
                    HabitCompletionEntity(
                        habitId = 901,
                        progressUpdate = date.atTime(7, minute),
                        isTargetReached = true,
                        fromReminder = isRecentReminderDay,
                        snoozeCount = if (isRecentReminderDay) 0 else null,
                        perceivedDifficulty = if (isRecentReminderDay) 2 else null
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
    // ML scenarios exercised:
    //  • isStreakAtRisk: TRUE — misses 3 of the last 4 Tuesdays.
    //    The risk check inspects w=1..4 Tuesdays via minusWeeks(w).with(TUESDAY):
    //      w=1 → 2026-05-05 (daysAgo 12) — SKIPPED
    //      w=2 → 2026-04-28 (daysAgo 19) — SKIPPED
    //      w=3 → 2026-04-21 (daysAgo 26) — SKIPPED
    //      w=4 → 2026-04-14 (daysAgo 33) — COMPLETED → 3/4 missed → at risk
    //  • successProbabilityToday: HIGH overall (32/35 days), moderate on Tuesdays
    //  • resilience: ~1 period — snaps back next day after each missed Tuesday
    //  • Phase 9.4 perceivedDifficulty=3 (MODERATE) on last 14 days → recentAvgRated=3.0
    //
    private suspend fun insertMeditate(dao: HabitDao, today: LocalDate) {
        val skippedDays = setOf(12, 19, 26) // the 3 most-recent Tuesdays
        val totalCompletions = (1..35).count { it !in skippedDays } // = 32
        // Days within the past 14 days that have completions (for perceivedDifficulty data).
        val recentDays = (1..14).filter { it !in skippedDays }.toSet()
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
                // Recent completions carry a difficulty rating of 3 (MODERATE), satisfying
                // the MIN_RATINGS=5 guard so recentAvgRated is surfaced in the UI.
                dao.insertCompletion(
                    HabitCompletionEntity(
                        habitId = 903,
                        progressUpdate = date.atTime(7, 0),
                        isTargetReached = true,
                        perceivedDifficulty = if (daysAgo in recentDays) 3 else null
                    )
                )
            }
        }
    }

    // ── Habit 904 — "Drink 2L Water" ──────────────────────────────────────────
    //
    // AI scenarios exercised:
    //  • targetDelta: +2 — 29/30 days target reached (97% rate) AND avg progress
    //    ratio = 5/4 = 1.25 (over-completion). Both thresholds satisfied:
    //    rate30d ≥ 0.90 AND avgProgressRatio30d ≥ 1.20 AND habitAge ≥ 21
    //    → MathHabitPredictor rule 1 fires → delta=+2
    //  • optimalHours: [8, 12, 16, 20, 22] — completions spread across the day
    //  • routinePrecision: ~268 min — large spread (correct for a hydration habit)
    //  • Demonstrates over-completion support (5 completions per day, target=4)
    //
    private suspend fun insertDrinkWater(dao: HabitDao, today: LocalDate) {
        val skippedDay = 15 // one missed day → 29 target-reached days
        // 5 completions per day: target (4) is reached at 20:00, then one extra at 22:00.
        // The 5th completion is NOT target-reached (target already hit at 20:00).
        // avgProgressRatio30d = 5/4 = 1.25, which clears the ≥ 1.20 threshold.
        val hoursPerDay = listOf(8, 12, 16, 20, 22)
        dao.insertHabit(
            HabitEntity(
                id = 904,
                name = "Drink 2L Water",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 4,
                totalProgressUpdates = 29 * 5, // 145 (5 completions × 29 days)
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
                            // Target (4 glasses) is reached at the 4th completion (20:00).
                            // The 5th completion at 22:00 is an extra drink — not target-reached.
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
    //  • targetDelta: -1 — rate30d=0.0 (≤ 0.40) AND avgProgressRatio30d=0.5 (≤ 0.72)
    //    The habit has target=2 but the user only logs 1 of 2 steps each done-day
    //    (partial progress, isTargetReached=false). This pushes the avg ratio below the
    //    0.72 threshold → MathHabitPredictor rule 4 fires → delta=-1.
    //    Historical days 34 and 38 have full completions (isTargetReached=true) to show
    //    the habit was feasible in the past.
    //  • isStreakAtRisk (fallback): streak=0 (yesterday missed) → StreakBreakUseCase
    //    returns hasSufficientData=false → UI falls back to MathHabitPredictor.isStreakAtRisk.
    //    The irregular every-3-day completion pattern triggers the weekday-miss detection.
    //  • optimalHours: [21] — all completions at 21:30
    //  • routinePrecision: 0 min — perfectly consistent time (stddev = 0)
    //  • resilience: ~3 periods — irregular habit with multi-day gaps
    //  • relatedHabits (via Read 30 min): {2,5,8,11,18} ∩ Read set = 5 shared days ✓
    //
    private suspend fun insertEveningJournal(dao: HabitDao, today: LocalDate) {
        // Within-30-day window: 8 days with 1 partial completion each (isTargetReached=false).
        // This keeps rate30d=0/30=0.0 and avgProgressRatio30d=1/2=0.5, satisfying rule 4.
        // Historical days 34 and 38 (outside 30-day window): 2 full completions each.
        val partialDaysAgo   = listOf(2, 5, 8, 11, 18, 22, 26, 30) // within 30 days, partial
        val historicalDaysAgo = listOf(34, 38)                       // outside 30 days, full
        dao.insertHabit(
            HabitEntity(
                id = 905,
                name = "Evening Journal",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 2,
                totalProgressUpdates = partialDaysAgo.size * 1 + historicalDaysAgo.size * 2, // 12
                totalTargetReaches = historicalDaysAgo.size, // 2 (historical full completions only)
                colorHex = "#FF9800",
                categories = listOf("Mindfulness"),
                sortOrder = 4
            )
        )
        // Recent/within-window days: only 1 of 2 steps completed → isTargetReached=false.
        // avgProgressRatio30d = 8 × (1/2) / 8 = 0.5 → satisfies the ≤ 0.72 threshold.
        partialDaysAgo.forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 905,
                    progressUpdate = date.atTime(21, 30),
                    isTargetReached = false
                )
            )
        }
        // Historical days: 2 full completions (step 1 + step 2) to show the habit is doable.
        historicalDaysAgo.forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 905,
                    progressUpdate = date.atTime(21, 0),
                    isTargetReached = false  // step 1 of 2
                )
            )
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 905,
                    progressUpdate = date.atTime(21, 30),
                    isTargetReached = true   // step 2 of 2 → target reached
                )
            )
        }
    }

    // ── Habit 906 — "Wake Up No Phone" ────────────────────────────────────────
    //
    // ML scenarios exercised:
    //  • Phase 9.2 Snooze Drift — CRITICAL risk (probability ≈ 0.85):
    //      avgSnoozeCountLast14Days = 3.0 (≥ 2), completionRateLast7Days = 0.14 (< 0.30)
    //      → MathHabitPredictor rule 1 fires → 0.85 (CRITICAL)
    //      hasSufficientData = true: 8 fromReminder completions with snoozeCount
    //      in past 30 days (≥ MIN_REMINDER_COMPLETIONS=5)
    //  • Phase 9.5 Skip Reason Forecast — 5 skip records (≥ MIN_SKIPS=3):
    //      distribution: FORGOT(×2), TOO_TIRED(×1), TOO_BUSY(×1), SICK(×1)
    //  • Phase 9.4 perceivedDifficulty=5 (VERY_HARD) on all completions
    //      → recentAvgRated=5.0, predicted difficulty VERY_HARD tier
    //  • Behavioral Clustering — dormant/struggling tier (low 7d rate)
    //  • successProbabilityToday: LOW (sparse completions, no active streak)
    //
    private suspend fun insertWakeUpNoPhone(dao: HabitDao, skipDao: HabitSkipDao, today: LocalDate) {
        // 8 completions spread over 28 days — only 1 in the past 7 days (day 2)
        // giving rate7d ≈ 1/7 ≈ 0.14, which triggers CRITICAL snooze drift.
        val completedDaysAgo = listOf(2, 7, 10, 14, 18, 22, 25, 28)
        dao.insertHabit(
            HabitEntity(
                id = 906,
                name = "Wake Up No Phone",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = completedDaysAgo.size,
                totalTargetReaches = completedDaysAgo.size,
                colorHex = "#607D8B",
                categories = listOf("Mindfulness", "Health"),
                reminderEnabled = true,
                sortOrder = 5
            )
        )
        // All completions are reminder-driven with 3 snoozes each.
        // snoozeCount=3 throughout entire 30-day window → hasSufficientData=true (8 ≥ 5).
        // snoozeFrequencyLast14Days=1.0, avgSnoozeCountLast14Days=3.0 → CRITICAL.
        completedDaysAgo.forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 906,
                    progressUpdate = date.atTime(6, 30),
                    isTargetReached = true,
                    fromReminder = true,
                    snoozeCount = 3,
                    perceivedDifficulty = 5
                )
            )
        }

        // ── Skip records — drives the Skip Reason Forecast card ─────────────────
        // 5 skip events (≥ MIN_SKIPS=3) with varied reasons. Skips on days the habit
        // was not completed so the data is consistent with the completion timeline.
        val skipDaysAndReasons = listOf(
            1  to SkipReason.TOO_TIRED,
            3  to SkipReason.FORGOT,
            5  to SkipReason.TOO_BUSY,
            8  to SkipReason.SICK,
            12 to SkipReason.FORGOT
        )
        skipDaysAndReasons.forEach { (daysAgo, reason) ->
            skipDao.insert(
                HabitSkipEntity(
                    habitId = 906,
                    skippedAt = today.minusDays(daysAgo.toLong()).atTime(6, 30),
                    reason = reason
                )
            )
        }
    }

    // ── App Sessions — Engagement Window (Phase 9.6) ──────────────────────────
    //
    // Inserts 16 completed app sessions (≥ MIN_SESSIONS=14) with a consistent
    // evening start cluster (~20:00 ± 30 min) over the past 30 days.
    //
    // EngagementWindowUseCase derives:
    //  • recentAvgStartHour14d ≈ 20.0 (tight evening cluster)
    //  • recentStddevStartHour14d ≈ 0.25 (low → high confidence)
    //  • sessionCountLast7d = 3 (3 sessions within past 7 days)
    //  • avgSessionLengthMinutes ≈ 15 min
    //  • daysSinceFirstSession = 30 (first session 30 days ago)
    //  • prevSessionStartHour = 20 (most-recent prior session)
    //
    // Expected model output: predicted engagement hour ≈ 20 (8:00 PM) with HIGH confidence.
    // AppSession rows accumulate on repeated seeds — the use case reads only the 100
    // most recent, so the signal remains stable regardless of how many times seed() is called.
    //
    private suspend fun insertAppSessions(sessionDao: AppSessionDao, today: LocalDate) {
        // Session distribution: 2 sessions in past 7 days, spread further for older ones.
        // Start hours alternate between 20:00 and 20:30 to produce a tight cluster.
        val sessionSchedule = listOf(
            2 to 20, 5 to 20, 7 to 20,   // past 7 days: 3 sessions
            9 to 20, 11 to 20, 13 to 20,  // days 8–14
            15 to 20, 17 to 20, 19 to 20, // days 15–19
            21 to 20, 23 to 20, 25 to 20, // days 21–25
            27 to 20, 28 to 20, 29 to 20, // days 27–29
            30 to 20                       // day 30 (earliest — sets daysSinceFirstSession)
        )
        sessionSchedule.forEach { (daysAgo, startHour) ->
            val startedAt = today.minusDays(daysAgo.toLong()).atTime(startHour, 0)
            val endedAt   = startedAt.plusMinutes(15)
            sessionDao.insert(
                AppSessionEntity(
                    startedAt = startedAt,
                    endedAt = endedAt,
                    screensVisited = listOf("StatisticsScreen")
                )
            )
        }
    }
}
