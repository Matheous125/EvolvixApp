package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.DatabaseSeeder
import com.example.evolvix.data.local.HabitDao
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.ai.HabitPredictor
import com.example.evolvix.domain.model.AbandonmentRisk
import com.example.evolvix.domain.model.HabitCluster
import com.example.evolvix.domain.model.HabitData
import com.example.evolvix.domain.model.StreakBreakRisk
import com.example.evolvix.domain.model.LifeBalanceEntry
import com.example.evolvix.domain.model.PerHabitStats
import com.example.evolvix.domain.model.WeeklyForecast
import com.example.evolvix.domain.model.WeeklyOverview
import com.example.evolvix.domain.model.SpilloverPair
import com.example.evolvix.domain.usecase.AbandonmentRiskUseCase
import com.example.evolvix.domain.usecase.BehavioralClusterUseCase
import com.example.evolvix.domain.usecase.CalculateStreakUseCase
import com.example.evolvix.domain.usecase.StreakBreakUseCase
import com.example.evolvix.domain.usecase.LifeBalanceUseCase
import com.example.evolvix.domain.usecase.SparklineUseCase
import com.example.evolvix.domain.usecase.SpilloverUseCase
import com.example.evolvix.domain.usecase.WeeklyForecastUseCase
import com.example.evolvix.domain.usecase.WeeklyOverviewUseCase
import java.time.LocalDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * ViewModel for the Statistics screen.
 *
 * Combines two Room Flows — [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] — and
 * runs the three Phase-5 use cases on every emission to produce fully reactive statistics.
 * Any DB write (completion logged, habit edited, history changed) automatically propagates
 * to all three [StateFlow] properties without manual refresh or polling.
 *
 * (Pattern: **MVVM + Observer via Flow** — `combine` merges two Room Flows; each mapped
 *  result is a distinct [StateFlow] driven by the same upstream pair)
 *
 * @property dao Room DAO used as the single source of truth for habits and completions.
 * @property predictor [HabitPredictor] strategy used for all predictive / passive-analytics
 *                     fields on [perHabitStats]. Injected so Phase 6.5.6 can swap in
 *                     [com.example.evolvix.domain.ai.TfliteHabitPredictor] without modifying
 *                     any ViewModel logic (Strategy + Dependency Inversion pattern).
 */
class StatisticsViewModel(
    private val dao: HabitDao,
    private val predictor: HabitPredictor
) : ViewModel() {

    // Pure-function interactors — stateless, safe to reuse across Flow emissions.
    // (Pattern: Use Case / Interactor — each encapsulates exactly one query type)
    private val weeklyOverviewUseCase = WeeklyOverviewUseCase()
    private val lifeBalanceUseCase = LifeBalanceUseCase()
    private val sparklineUseCase = SparklineUseCase()
    private val calculateStreakUseCase = CalculateStreakUseCase()
    private val abandonmentRiskUseCase = AbandonmentRiskUseCase(predictor)
    private val streakBreakUseCase = StreakBreakUseCase(predictor)
    private val weeklyForecastUseCase = WeeklyForecastUseCase(predictor)
    private val behavioralClusterUseCase = BehavioralClusterUseCase(predictor)
    private val spilloverUseCase = SpilloverUseCase(predictor)

    /**
     * 7-day rolling overview: daily completion counts, today's completed habits count,
     * and an aggregate week-completion rate.
     * Drives the "Global Overview" card in StatisticsScreen.
     * (Pattern: Observer via StateFlow — recomputed on every habits/completions emission)
     */
    val overview: StateFlow<WeeklyOverview> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        weeklyOverviewUseCase(habits, completions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WeeklyOverview(
            dailySummaries = emptyList(),
            totalActiveHabits = 0,
            todayCompletedHabits = 0,
            weekCompletionRate = 0f
        )
    )

    /**
     * Per-category completion rates over the last 30 days, sorted alphabetically.
     * Drives the "Life Balance" card in StatisticsScreen.
     * Habits with no assigned category appear under the "Other" bucket.
     * (Pattern: Observer via StateFlow — recomputed on every habits/completions emission)
     */
    val lifeBalance: StateFlow<List<LifeBalanceEntry>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        lifeBalanceUseCase(habits, completions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * One [PerHabitStats] entry per habit, bundling streak metrics, a 30-day sparkline,
     * and a 30-day completion rate. Drives the per-habit collapsed/expanded cards in
     * StatisticsScreen (7D/30D/3M/ALL chart tabs are rendered from this sparkline data).
     *
     * The 30-day sparkline is precomputed here because it covers the most common chart tab.
     * Wider ranges (3M, ALL) are derived by the View from the same [PerHabitStats.habit]
     * and [PerHabitStats.streak] data by calling [SparklineUseCase] directly with a wider window.
     *
     * (Pattern: Observer via StateFlow — one `combine` emission recomputes all habits at once)
     */
    val perHabitStats: StateFlow<List<PerHabitStats>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val today = LocalDate.now()
        val from30d = today.minusDays(29) // 30 inclusive days: today − 29 .. today

        // Build domain-model list once per emission — needed by predictor.relatedHabits().
        val allHabitData: List<HabitData> = habits.map { h ->
            HabitData(
                id = h.id, name = h.name, currentCount = h.currentCount,
                frequency = h.frequency, target = h.target,
                totalProgressUpdates = h.totalProgressUpdates,
                totalTargetReaches = h.totalTargetReaches, lastResetDate = h.lastResetDate
            )
        }
        val now = LocalDateTime.now()

        habits.map { habit ->
            val habitCompletions = completions.filter { it.habitId == habit.id }
            val streak = calculateStreakUseCase(habitCompletions, habit.frequency, today)
            val sparkline = sparklineUseCase(habitCompletions, from30d, today)
            val rate = sparkline.count { it.reached }.toFloat() / 30f
            // User override (iconKey) takes priority; otherwise the ML-backed predictor
            // (Phase 6.5.8) classifies the habit name into one of the 17 trained
            // categories, and the category is mapped to its display emoji. This replaces
            // the Tier-1 keyword map (IconResolverUseCase) on the Statistics path.
            val resolvedIcon = habit.iconKey?.takeIf { it.isNotBlank() }
                ?: CATEGORY_EMOJI[predictor.classifyIcon(habit.name)] ?: FALLBACK_ICON_EMOJI
            // Map entity → domain model for the AI predictor (no Android SDK dependency).
            val habitData = HabitData(
                id = habit.id, name = habit.name, currentCount = habit.currentCount,
                frequency = habit.frequency, target = habit.target,
                totalProgressUpdates = habit.totalProgressUpdates,
                totalTargetReaches = habit.totalTargetReaches, lastResetDate = habit.lastResetDate
            )
            PerHabitStats(
                habit = habit,
                streak = streak,
                sparkline30d = sparkline,
                completionRate30d = rate.coerceIn(0f, 1f),
                resolvedIconEmoji = resolvedIcon,
                successProbabilityToday = predictor.successProbability(
                    habitData, habitCompletions, now.dayOfWeek.value, now.hour
                ),
                optimalHours = predictor.optimalHours(habitData, habitCompletions),
                relatedHabitNames = predictor.relatedHabits(habitData, allHabitData, completions),
                isStreakAtRisk = predictor.isStreakAtRisk(habitData, habitCompletions),
                targetDelta = predictor.suggestTargetDelta(habitData, habitCompletions),
                motivationMessageKey = predictor.motivationMessageKey(
                    habitData, habitCompletions, streak.current, now.dayOfWeek.value
                ),
                routinePrecision = predictor.computeRoutinePrecision(habitCompletions),
                resilience = predictor.computeResilience(habitData, habitCompletions)
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * Week-completion rate for the previous 7-day window (today − 13 .. today − 7).
     * Used by the Global Overview card to render a trend indicator (▲ / ▼ vs last week).
     * Range: [0.0, 1.0].
     * (Pattern: Observer via StateFlow — reuses [WeeklyOverviewUseCase] with a shifted `today`)
     */
    val previousWeekRate: StateFlow<Float> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        // Run the same use case shifted 7 days back; only need the aggregate rate.
        weeklyOverviewUseCase(habits, completions, LocalDate.now().minusDays(7)).weekCompletionRate
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0f
    )

    /**
     * Raw stream of all [HabitCompletionEntity] records.
     * Exposed so the Statistics screen can build per-habit bar charts that need raw
     * per-day completion counts (not just target-reached flags). The bar chart uses
     * counts because the spec shows the number of completions per day in the y-axis.
     * (Pattern: Observer via StateFlow — single source of truth shared across cards)
     */
    val allCompletions: StateFlow<List<HabitCompletionEntity>> = dao.getAllCompletions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Abandonment-risk score for every habit, keyed by habit ID.
     *
     * Combines [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] with
     * [AbandonmentRiskUseCase] to produce a probability and a [AbandonmentRisk.Rating]
     * tier for each habit. Habits with insufficient data (< 3 completions or age < 7 days)
     * are included with [AbandonmentRisk.hasSufficientData] = false so the UI can
     * skip them cleanly.
     *
     * The "At Risk" card in StatisticsScreen filters this map for entries where
     * [AbandonmentRisk.rating] is HIGH or CRITICAL.
     *
     * (Pattern: Observer via StateFlow — same upstream pair as [perHabitStats]; streak
     *  is re-derived here so [abandonmentRisks] is independently collectible)
     */
    val abandonmentRisks: StateFlow<Map<Int, AbandonmentRisk>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val today = LocalDate.now()
        habits.associate { habit ->
            val habitCompletions = completions.filter { it.habitId == habit.id }
            val streak = calculateStreakUseCase(habitCompletions, habit.frequency, today)
            val habitData = HabitData(
                id = habit.id, name = habit.name, currentCount = habit.currentCount,
                frequency = habit.frequency, target = habit.target,
                totalProgressUpdates = habit.totalProgressUpdates,
                totalTargetReaches = habit.totalTargetReaches, lastResetDate = habit.lastResetDate
            )
            habit.id to abandonmentRiskUseCase(habitData, habitCompletions, streak.current, today)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap()
    )

    /**
     * Streak-break risk score for every habit with an active streak, keyed by habit ID.
     *
     * Combines [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] with
     * [StreakBreakUseCase] to produce a probability and a [StreakBreakRisk.Rating] tier
     * for each habit. Habits with no active streak or insufficient data are included
     * with [StreakBreakRisk.hasSufficientData] = false so the UI can render a neutral
     * placeholder instead of a probability bar.
     *
     * (Pattern: Observer via StateFlow — mirrors [abandonmentRisks]; streak is re-derived
     *  so this flow is independently collectible without coupling to [perHabitStats])
     */
    val streakBreakRisks: StateFlow<Map<Int, StreakBreakRisk>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val today = LocalDate.now()
        habits.associate { habit ->
            val habitCompletions = completions.filter { it.habitId == habit.id }
            val streak = calculateStreakUseCase(habitCompletions, habit.frequency, today)
            val habitData = HabitData(
                id = habit.id, name = habit.name, currentCount = habit.currentCount,
                frequency = habit.frequency, target = habit.target,
                totalProgressUpdates = habit.totalProgressUpdates,
                totalTargetReaches = habit.totalTargetReaches, lastResetDate = habit.lastResetDate
            )
            habit.id to streakBreakUseCase(habitData, habitCompletions, streak.current, today)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap()
    )

    /**
     * Next-week completion rate forecast for the entire user (all active habits).
     *
     * Combines [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] with
     * [WeeklyForecastUseCase] to produce a [WeeklyForecast] carrying the predicted rate,
     * trend direction (UP/FLAT/DOWN), and a confidence score derived from history depth.
     * [WeeklyForecast.hasSufficientData] is false when there are fewer than 2 active
     * habits or less than 7 days of completion history — the View renders a placeholder.
     *
     * (Pattern: Observer via StateFlow — same upstream pair as [overview]; streak map is
     *  re-derived here so this flow is independently collectible)
     */
    val weeklyForecast: StateFlow<WeeklyForecast> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val today = LocalDate.now()
        val allHabitData: List<HabitData> = habits.map { h ->
            HabitData(
                id = h.id, name = h.name, currentCount = h.currentCount,
                frequency = h.frequency, target = h.target,
                totalProgressUpdates = h.totalProgressUpdates,
                totalTargetReaches = h.totalTargetReaches, lastResetDate = h.lastResetDate
            )
        }
        // Pre-compute current streaks for all habits (same approach as streakBreakRisks).
        val currentStreaks: Map<Int, Int> = habits.associate { habit ->
            val habitCompletions = completions.filter { it.habitId == habit.id }
            habit.id to calculateStreakUseCase(habitCompletions, habit.frequency, today).current
        }
        weeklyForecastUseCase(allHabitData, completions, currentStreaks, today)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WeeklyForecast(
            predictedRate = 0f,
            lastWeekRate = 0f,
            direction = WeeklyForecast.Direction.FLAT,
            confidence = 0f,
            hasSufficientData = false
        )
    )

    /**
     * Behavioral tier classification for every habit, keyed by habit ID (Phase 8.4).
     *
     * Combines [HabitDao.getAllHabits] and [HabitDao.getAllCompletions] with
     * [BehavioralClusterUseCase] to assign each habit to one of four K-Means archetypes:
     * Effortless Routine, Consistent Effort, Struggling, or Dormant.
     *
     * Habits with fewer than 10 completions or less than 14 days of history are included
     * with [HabitCluster.hasSufficientData] = false so the UI shows a placeholder
     * prompting the user to keep tracking.
     *
     * (Pattern: Observer via StateFlow — mirrors [abandonmentRisks]; independently
     *  collectible from [perHabitStats] so the Behavioral Tiers card can be skipped
     *  when data is unavailable without blocking other statistics)
     */
    val behavioralClusters: StateFlow<Map<Int, HabitCluster>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val today = LocalDate.now()
        habits.associate { habit ->
            val habitCompletions = completions.filter { it.habitId == habit.id }
            val habitData = HabitData(
                id = habit.id, name = habit.name, currentCount = habit.currentCount,
                frequency = habit.frequency, target = habit.target,
                totalProgressUpdates = habit.totalProgressUpdates,
                totalTargetReaches = habit.totalTargetReaches, lastResetDate = habit.lastResetDate
            )
            habit.id to behavioralClusterUseCase(habitData, habitCompletions, today)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyMap()
    )

    /**
     * Cross-habit spillover insights for today (Phase 8.5).
     *
     * For each habit A completed today, [SpilloverUseCase] evaluates all (A, B) ordered
     * pairs using a 30-day co-occurrence history and the TFLite spillover regressor to
     * estimate how completing A tends to lift (or drag) adherence to B.
     *
     * Returns up to 3 non-NEUTRAL [SpilloverPair]s sorted by |liftDelta| descending.
     * Emits an empty list when fewer than 2 habits exist or nothing was completed today.
     *
     * ⚠ **Observational, not causal:** the View should use hedged language
     *   (e.g. "tends to boost").
     *
     * (Pattern: Observer via StateFlow — same upstream pair as [behavioralClusters];
     *  independently collectible so the spillover card can be hidden when list is empty)
     */
    val spilloverInsights: StateFlow<List<SpilloverPair>> = combine(
        dao.getAllHabits(),
        dao.getAllCompletions()
    ) { habits, completions ->
        val allHabitData: List<HabitData> = habits.map { h ->
            HabitData(
                id = h.id, name = h.name, currentCount = h.currentCount,
                frequency = h.frequency, target = h.target,
                totalProgressUpdates = h.totalProgressUpdates,
                totalTargetReaches = h.totalTargetReaches, lastResetDate = h.lastResetDate
            )
        }
        spilloverUseCase(allHabitData, completions)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    /**
     * Inserts 5 seed habits (IDs 901–905) with realistic completion histories.
     * Safe to call repeatedly — the REPLACE strategy cascade-deletes old completions
     * for those IDs before inserting fresh ones.
     * For development use only; remove before release.
     */
    fun seedDatabase() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            DatabaseSeeder.seed(dao)
        }
    }

    companion object {
        /**
         * Emoji shown when the predictor returns the `"other"` category or an unknown label.
         * Matches the fallback used by [com.example.evolvix.domain.usecase.IconResolverUseCase].
         */
        private const val FALLBACK_ICON_EMOJI = "⭐"

        /**
         * Maps the 17 category labels emitted by [HabitPredictor.classifyIcon] (defined
         * in `ml-training/generate_icon_data.py`) to a single display emoji each.
         *
         * Keeping this map in the ViewModel layer is intentional: emoji selection is a UI
         * concern, while [HabitPredictor] stays UI-agnostic (it only emits semantic labels).
         */
        private val CATEGORY_EMOJI: Map<String, String> = mapOf(
            "fitness"      to "💪",
            "health"       to "❤️",
            "learning"     to "📚",
            "mindfulness"  to "🧘",
            "creative"     to "🎨",
            "social"       to "💬",
            "productivity" to "📅",
            "finance"      to "💰",
            "food"         to "🍎",
            "sleep"        to "😴",
            "cleaning"     to "🧹",
            "nature"       to "🌳",
            "pet"          to "🐶",
            "music"        to "🎵",
            "reading"      to "📖",
            "writing"      to "✍️",
            "other"        to FALLBACK_ICON_EMOJI
        )
    }
}
