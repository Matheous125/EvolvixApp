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
 * Inserts 9 habits (IDs 901–909) with realistic completion histories designed to
 * exercise every ML card on the Statistics screen simultaneously:
 *
 * | Habit               | ID  | Key ML scenario                                          |
 * |---------------------|-----|----------------------------------------------------------|
 * | Morning Run         | 901 | tight routine (~7 min stddev), high success probability, |
 * |                     |     | relatedHabits; perceivedDifficulty=2 (EASY, last 14d);   |
 * |                     |     | fromReminder + snoozeCount=0 (LOW snooze risk shown in   |
 * |                     |     | Smart Reminders); Behavioral cluster EffortlessRoutine   |
 * | Read 30 min         | 902 | moderate rate, evening optimalHours, co-occurrence;      |
 * |                     |     | today completed → enables 902→908 DRAG pair              |
 * | Meditate            | 903 | isStreakAtRisk=true (misses Tuesdays); perceivedDifficulty|
 * |                     |     | =3 (MODERATE, last 14d); cluster EffortlessRoutine       |
 * | Drink 2L Water      | 904 | targetDelta=+2 HIGH confidence (97% rate, apr=1.25)      |
 * | Evening Journal     | 905 | targetDelta=-1 (rate=0%, partial completions, apr=0.5);  |
 * |                     |     | cluster Dormant (rate30d=0)                              |
 * | Wake Up No Phone    | 906 | Snooze Drift CRITICAL (snoozeCount=3, rate7d=14%);       |
 * |                     |     | Skip Reason Forecast (5 skip records); perceivedDifficulty|
 * |                     |     | =5 (VERY_HARD); cluster Struggling                       |
 * | Stretch 5 min       | 907 | Spillover BOOST pair with 901 (coOcc~0.89, gap~30 min);  |
 * |                     |     | cluster ConsistentEffort (rate30d=0.83); diverse 1–5     |
 * |                     |     | difficulty ratings on last 14 days                       |
 * | Late-night Doomscroll | 908 | Spillover DRAG pair with 902 (coOcc=0, gap~1 h);       |
 * |                     |     | cluster Struggling (rate30d=0.33)                        |
 * | Take vitamins       | 909 | Target Calibration LOW confidence (rate30d≈0.73, apr≈1.0)|
 * |                     |     | borderline rawDelta; Smart Reminders near-zero/negative  |
 * |                     |     | lift demo (high rate + fromReminder=true)                |
 *
 * Additionally inserts 20 [AppSessionEntity] records (evening cluster ~20:00) so
 * [EngagementWindowUseCase] passes its MIN_SESSIONS=14 guard and predicts
 * hour ~20 with high confidence. 12 sessions carry `StatisticsScreen` in their
 * [AppSessionEntity.screensVisited] list (viewer sessions) and 8 do not
 * (non-viewer sessions). This yields lift = (12 − 8) / 30 ≈ +13% for the B3
 * Analytics Retention headline ("Analytics viewers stay active +4 days/month longer").
 *
 * Using explicit IDs 901–909 avoids conflicts with user-created habits.
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
        insertStretchFiveMin(dao, today)
        insertLateNightDoomscroll(dao, today)
        insertTakeVitamins(dao, today)
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
                totalProgressUpdates = 28,
                totalTargetReaches = 28,
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
        // Today's completion (daysAgo=0): required so SpilloverUseCase finds a non-empty
        // completedTodayIds set. This triggers the 901→903 pair (DRAG, ≈ −0.06) because
        // Meditate's unconditional rate30d (0.90) exceeds the conditional co-occurrence
        // rate given Morning Run (0.857), reflecting that Meditate happens on some days
        // Morning Run is skipped. |liftDelta| > NEUTRAL_THRESHOLD=0.05 → card shows.
        dao.insertCompletion(
            HabitCompletionEntity(
                habitId = 901,
                progressUpdate = today.atTime(7, 0),
                isTargetReached = true,
                fromReminder = true,
                snoozeCount = 0,
                perceivedDifficulty = 2
            )
        )
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
        // Days 1–20 excluding {6, 14} → 18 historical completions, plus today.
        // ALL 18 historical days are also in Morning Run's completed set → strong co-occurrence.
        // Today's completion is required so SpilloverUseCase considers (A=902, B=908) and
        // surfaces the DRAG pair documented on habit 908.
        val completedDays = (1..20).filter { it !in setOf(6, 14) }
        dao.insertHabit(
            HabitEntity(
                id = 902,
                name = "Read 30 min",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = 19, // 18 historical + 1 today
                totalTargetReaches = 19,
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
        // Today's completion at 21:00 — enables the 902→908 DRAG spillover pair.
        dao.insertCompletion(
            HabitCompletionEntity(
                habitId = 902,
                progressUpdate = today.atTime(21, 0),
                isTargetReached = true
            )
        )
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
        // 10 completions spread over 28 days — only 1 within the strict 7-day window
        // (day 2; day 7 is excluded by the > boundary), giving rate7d ≈ 1/7 ≈ 0.14.
        // rate30d = 10/30 ≈ 0.33 → Struggling cluster tier, satisfying the
        // BehavioralClusterUseCase MIN_COMPLETIONS=10 guard.
        // avgSnoozeCountLast14Days = 3.0 (≥ 2) AND rate7d < 0.30 → CRITICAL snooze drift.
        val completedDaysAgo = listOf(2, 7, 9, 10, 13, 14, 18, 22, 25, 28)
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

    // ── Habit 907 — "Stretch 5 min" ───────────────────────────────────────────
    //
    // ML scenarios exercised:
    //  • Spillover BOOST pair with 901 Morning Run:
    //      Completed on 24 of 901's 27 historical done-days + today → coOcc ≈ 0.89,
    //      rateA(901) ≈ 0.93, rateB(907) ≈ 0.83, gap 30 min (right after run).
    //      Math fallback: liftDelta ≈ 0.060 * 0.88 * 0.98 * 1.6 ≈ +0.083 → BOOST
    //      (exceeds the ±0.05 NEUTRAL_THRESHOLD).
    //  • Behavioral cluster — ConsistentEffort (rate30d = 25/30 ≈ 0.833,
    //      inside the 0.55–0.85 band documented in BehavioralCluster KDoc).
    //  • Phase 9.4 PerceivedDifficulty — diverse 1–5 ratings on the last 14 recent
    //      completions (cycle 1,2,3,4,5) → recentAvgRated ≈ 3.0, MODERATE.
    //
    private suspend fun insertStretchFiveMin(dao: HabitDao, today: LocalDate) {
        // 901's historical completed days within 30 = {1..30} − {6,14,22}.
        // 907 done on 901's set minus {1, 8, 15} = 24 historical days → preserves high coOcc
        // while keeping rate30d strictly under 0.85 so the cluster lands in ConsistentEffort.
        val historicalDays: List<Int> = (1..30)
            .filter { it !in setOf(6, 14, 22, 1, 8, 15) }
        // = {2,3,4,5,7,9,10,11,12,13,16,17,18,19,20,21,23,24,25,26,27,28,29,30} (24 days)
        val totalCompletions = historicalDays.size + 1 // + today
        dao.insertHabit(
            HabitEntity(
                id = 907,
                name = "Stretch 5 min",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = totalCompletions,
                totalTargetReaches = totalCompletions,
                colorHex = "#8BC34A",
                categories = listOf("Fitness", "Health"),
                sortOrder = 6
            )
        )
        // Diverse 1–5 difficulty ratings cycle on the most recent 14 days (sorted desc)
        // so recentAvgRated ≈ 3.0 and the chip uses every value in the scale.
        val diverseRatings = listOf(1, 2, 3, 4, 5)
        // Sort historicalDays ascending by daysAgo to make rating assignment deterministic.
        val sortedHistorical = historicalDays.sorted()
        sortedHistorical.forEachIndexed { index, daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            // Only the 14 most-recent completions carry a difficulty rating
            // (MIN_RATINGS=5 guard easily satisfied; diverse 1–5 cycle).
            val isRecent = daysAgo <= 14
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 907,
                    progressUpdate = date.atTime(7, 30),
                    isTargetReached = true,
                    perceivedDifficulty = if (isRecent) diverseRatings[index % diverseRatings.size] else null
                )
            )
        }
        // Today's completion at 7:30 (right after 901 at 7:00) — closes the spillover gap.
        dao.insertCompletion(
            HabitCompletionEntity(
                habitId = 907,
                progressUpdate = today.atTime(7, 30),
                isTargetReached = true,
                perceivedDifficulty = 2
            )
        )
    }

    // ── Habit 908 — "Late-night Doomscroll" ───────────────────────────────────
    //
    // ML scenarios exercised:
    //  • Spillover DRAG pair with 902 Read 30 min:
    //      908 done days {14, 21, 22, 24, 25, 26, 27, 28, 29, 30} share ZERO days with
    //      902's done days {1–20}∖{6,14} → coOcc = 0.0.
    //      rateA(902) ≈ 0.63, rateB(908) ≈ 0.33, baseLift = 0 − 0.33 = −0.33.
    //      Gap (902 at 21:00 → 908 at 22:00) = 1 h → gapFactor ≈ 0.96.
    //      Math fallback: −0.33 * 0.45 * 0.96 * 1.6 ≈ −0.23 → DRAG (well past ±0.05).
    //  • Behavioral cluster — Struggling (rate30d = 10/30 ≈ 0.333, in 0.15–0.55 band).
    //      Habit age = 30 days ≥ MIN_HISTORY_DAYS=14. 10 completions = MIN_COMPLETIONS.
    //  • Demonstrates a "bad" habit the user explicitly tracks to discourage.
    //
    private suspend fun insertLateNightDoomscroll(dao: HabitDao, today: LocalDate) {
        val completedDaysAgo = listOf(14, 21, 22, 24, 25, 26, 27, 28, 29, 30) // 10 days
        dao.insertHabit(
            HabitEntity(
                id = 908,
                name = "Late-night Doomscroll",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = completedDaysAgo.size,
                totalTargetReaches = completedDaysAgo.size,
                colorHex = "#9E9E9E",
                categories = listOf("Other"),
                sortOrder = 7
            )
        )
        completedDaysAgo.forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 908,
                    progressUpdate = date.atTime(22, 0),
                    isTargetReached = true
                )
            )
        }
    }

    // ── Habit 909 — "Take vitamins" ───────────────────────────────────────────
    //
    // ML scenarios exercised:
    //  • Phase 9.3 Target Calibration — LOW confidence:
    //      rate30d = 22/30 ≈ 0.73, avgProgressRatio30d ≈ 1.0 (one completion per day,
    //      target=1). All MathHabitPredictor rules sit *just* outside their thresholds
    //      (rate < 0.78 OR apr < 1.02), so the math fallback returns rawDelta = 0.0.
    //      The TFLite regressor on this same feature vector typically lands between
    //      0.3 and 0.6 — a rounding residual ≥ 0.35 → Confidence.LOW.
    //  • Phase 9.1 Smart Reminders — near-zero / negative lift demo:
    //      All 22 completions are fromReminder=true with snoozeCount=0.
    //      High rate7d (≈0.86) + high streak means the trained model expects only a
    //      small additive boost; combined with the R8 difficulty multiplier
    //      (recentAvgDifficulty = 1 → easy), TFLite often outputs lift < 0.05 →
    //      recommendSend=false. Math fallback boost ≈ 0.114 still recommends ON; the
    //      negative-lift outcome relies on TFLite as documented in PLAN-POLISH-PASS.md.
    //  • Behavioral cluster — ConsistentEffort (rate30d ≈ 0.73, inside 0.55–0.85).
    //
    private suspend fun insertTakeVitamins(dao: HabitDao, today: LocalDate) {
        // 22 done days within the last 30 days; 8 missed days produce the 0.73 rate30d
        // that sits between MathHabitPredictor target-delta rules → LOW confidence.
        val missedDays = setOf(3, 7, 11, 15, 19, 23, 27, 29)
        val completedDaysAgo = (1..30).filter { it !in missedDays }
        // rate7d window (last 7 days, excludes today). Of {1..7} ∖ {3,7} = 5 days → ≈ 0.71.
        dao.insertHabit(
            HabitEntity(
                id = 909,
                name = "Take vitamins",
                currentCount = 0,
                frequency = HabitFrequency.Daily,
                target = 1,
                totalProgressUpdates = completedDaysAgo.size,
                totalTargetReaches = completedDaysAgo.size,
                colorHex = "#FFEB3B",
                categories = listOf("Health"),
                reminderEnabled = true,
                sortOrder = 8
            )
        )
        completedDaysAgo.forEach { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            // Last 14 days carry a difficulty rating of 1 (EASY) and the reminder flag
            // so the Smart Reminders card has data to evaluate; older completions are
            // plain entries to give the cluster use case enough history (30-day window).
            val isRecent = daysAgo <= 14
            dao.insertCompletion(
                HabitCompletionEntity(
                    habitId = 909,
                    progressUpdate = date.atTime(9, 0),
                    isTargetReached = true,
                    fromReminder = isRecent,
                    snoozeCount = if (isRecent) 0 else null,
                    perceivedDifficulty = if (isRecent) 1 else null
                )
            )
        }
    }

    // ── App Sessions — Engagement Window (Phase 9.6) + Analytics Retention (B3) ──
    //
    // Inserts 20 completed app sessions (≥ MIN_SESSIONS=14) with a consistent
    // evening start cluster (~20:00) over the past 30 days.
    //
    // EngagementWindowUseCase derives:
    //  • recentAvgStartHour14d ≈ 20.0 (tight evening cluster)
    //  • recentStddevStartHour14d = 0.0 (perfect consistency → HIGH confidence)
    //  • sessionCountLast7d = 7 (days 1–7 all present)
    //  • avgSessionLengthMinutes = 15 min
    //  • daysSinceFirstSession = 30
    //  • prevSessionStartHour = 20
    //
    // B3 Analytics Retention:
    //  • 12 viewer sessions (StatisticsScreen in screensVisited) on unique days
    //  • 8 non-viewer sessions on distinct unique days
    //  → lift = (12 − 8) / 30 ≈ +13%  (positive — analytics viewers are more active)
    //  → diffDays = +4, headline shows "+4 days/month longer (lift +13%)"
    //  Both buckets satisfy MIN_SESSIONS_PER_BUCKET=5.
    //
    // Expected model output: predicted engagement hour ≈ 20 (8:00 PM) with HIGH confidence.
    // AppSession rows accumulate on repeated seeds — the use case reads only the 100
    // most recent, so the signal remains stable regardless of how many times seed() is called.
    //
    private suspend fun insertAppSessions(sessionDao: AppSessionDao, today: LocalDate) {
        // Triple<daysAgo, startHour, isViewerSession>.
        // 12 viewer days (odd days + selected even days) spread evenly across the window;
        // 8 non-viewer days fill the remaining slots.
        // All at hour 20 so avgStartHour14d = 20.0 and stddev = 0 → confidence = 1.0.
        val sessionSchedule: List<Triple<Int, Int, Boolean>> = listOf(
            Triple(1,  20, true),   // viewer  — within 7-day window
            Triple(2,  20, false),  // non-viewer
            Triple(3,  20, true),   // viewer
            Triple(4,  20, false),  // non-viewer
            Triple(5,  20, true),   // viewer
            Triple(6,  20, false),  // non-viewer
            Triple(7,  20, true),   // viewer
            Triple(9,  20, true),   // viewer
            Triple(10, 20, false),  // non-viewer
            Triple(11, 20, true),   // viewer
            Triple(13, 20, true),   // viewer  — day 13 is last within 14-day filter
            Triple(14, 20, false),  // non-viewer
            Triple(15, 20, true),   // viewer
            Triple(19, 20, true),   // viewer
            Triple(20, 20, false),  // non-viewer
            Triple(23, 20, true),   // viewer
            Triple(25, 20, false),  // non-viewer
            Triple(27, 20, true),   // viewer
            Triple(28, 20, false),  // non-viewer — earliest non-viewer day
            Triple(30, 20, true)    // viewer   — earliest day, sets daysSinceFirstSession=30
        )
        sessionSchedule.forEach { (daysAgo, startHour, isViewer) ->
            val startedAt = today.minusDays(daysAgo.toLong()).atTime(startHour, 0)
            val endedAt   = startedAt.plusMinutes(15)
            val screens = if (isViewer) listOf("HabitScreen", "StatisticsScreen")
                          else          listOf("HabitScreen")
            sessionDao.insert(
                AppSessionEntity(
                    startedAt = startedAt,
                    endedAt = endedAt,
                    screensVisited = screens
                )
            )
        }
    }
}
