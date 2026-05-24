package com.example.evolvix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.annotation.PluralsRes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.R
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.model.AbandonmentRisk
import com.example.evolvix.domain.model.BehavioralCluster
import com.example.evolvix.domain.model.HabitCluster
import com.example.evolvix.domain.model.LifeBalanceEntry
import com.example.evolvix.domain.model.StreakBreakRisk
import com.example.evolvix.domain.model.PerHabitStats
import com.example.evolvix.domain.model.WeeklyForecast
import com.example.evolvix.domain.model.WeeklyOverview
import com.example.evolvix.ui.components.BarChartDay
import com.example.evolvix.ui.components.ScrollableBarChart
import com.example.evolvix.ui.components.Sparkline
import com.example.evolvix.ui.viewmodel.StatisticsViewModel
import com.example.evolvix.ui.viewmodel.StatisticsViewModelFactory
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Selectable date ranges for the per-habit bar chart in the expanded card.
 *
 * Mirrors STAT-SCREN-SUMMARY.MD: 7D = current week (rolling 7-day window),
 * 30D = current month, 3M = current + previous two months, ALL = full habit history.
 *
 * Each entry exposes a [label] used by the tab UI and a [window] helper that produces
 * the inclusive (from, to) date pair ending at "today". Using a domain enum here (rather
 * than raw integers) keeps the chart caller readable and extensible.
 */
private enum class ChartRange(val label: String) {
    SEVEN_DAYS("7D"),
    THIRTY_DAYS("30D"),
    THREE_MONTHS("3M"),
    ALL("ALL");

    /**
     * Returns the inclusive (from, to) window for this range. ALL is delegated to the
     * caller's earliest known completion date so the enum stays stateless.
     */
    fun window(today: LocalDate, earliest: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
        SEVEN_DAYS -> today.minusDays(6) to today
        THIRTY_DAYS -> today.minusDays(29) to today
        THREE_MONTHS -> today.minusDays(89) to today
        ALL -> earliest to today
    }
}

/**
 * Statistics screen (Phase 5 of PLAN.md).
 *
 * Layout (top to bottom):
 *  1. Global Overview card — week completion rate + trend vs previous week + progress bar.
 *  2. Life Balance card — per-category completion bars + AI insight placeholder.
 *  3. Per-habit cards — collapsed by default; tap to expand. Expanded view shows a
 *     scrollable bar chart with 7D/30D/3M/ALL tabs plus three AI placeholder blocks.
 *
 * The screen is purely declarative: it observes StateFlows from [StatisticsViewModel]
 * and binds them to Composables. No business logic lives here (Pattern: MVVM).
 *
 * AI sections (Smart Insight, Optimal Timing, Behavioral Patterns, Success Prediction,
 * Life Balance Insight) render static placeholder text because the on-device AI layer
 * is scheduled for Phase 6 in PLAN.md. The placeholder surfaces match the future real
 * cards so wiring them up later is a string swap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatisticsViewModel = viewModel(
        factory = StatisticsViewModelFactory(
            dao = AppDatabase.getDatabase(LocalContext.current).habitDao(),
            // Phase 6.5.6: inject the TFLite-backed predictor via the process-wide
            // singleton so all ViewModels share one Interpreter instance.
            predictor = com.example.evolvix.domain.ai.AiContainer.predictor(LocalContext.current)
        )
    )
) {
    // Observe the Phase-5 StateFlows. Each emission re-renders the affected card.
    val overview by viewModel.overview.collectAsState()
    val prevRate by viewModel.previousWeekRate.collectAsState()
    val lifeBalance by viewModel.lifeBalance.collectAsState()
    val perHabit by viewModel.perHabitStats.collectAsState()
    val allCompletions by viewModel.allCompletions.collectAsState()
    val abandonmentRisks by viewModel.abandonmentRisks.collectAsState()
    val streakBreakRisks by viewModel.streakBreakRisks.collectAsState()
    val weeklyForecast by viewModel.weeklyForecast.collectAsState()
    val behavioralClusters by viewModel.behavioralClusters.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_statistics_title)) },
                actions = {
                    // Dev-only seed button: inserts 5 test habits (IDs 901–905).
                    // Safe to tap multiple times — re-seeds cleanly.
                    IconButton(onClick = { viewModel.seedDatabase() }) {
                        Icon(
                            imageVector = Icons.Filled.Science,
                            contentDescription = stringResource(R.string.cd_seed_test_data)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // Top summary is a single collapsible group so the user can hide it once they
                // scroll into the per-habit cards. Default state: expanded.
                SummaryGroupCard(
                    overview = overview,
                    previousWeekRate = prevRate,
                    lifeBalance = lifeBalance,
                    weeklyForecast = weeklyForecast
                )
            }

            // Phase 8.4 — Behavioral Tiers card (shown whenever clusters map is non-empty)
            if (behavioralClusters.isNotEmpty()) {
                item {
                    BehavioralTiersCard(
                        clusters = behavioralClusters,
                        habitNames = perHabit.associate { it.habit.id to it.habit.name }
                    )
                }
            }

            if (perHabit.isEmpty()) {
                item { EmptyHabitsHint() }
            } else {
                items(perHabit, key = { it.habit.id }) { stats ->
                    // Pre-filter the per-habit completion list once per emission of allCompletions
                    // so the expanded chart doesn't refilter on every tab change.
                    val perHabitCompletions = remember(allCompletions, stats.habit.id) {
                        allCompletions.filter { it.habitId == stats.habit.id }
                    }
                    HabitStatsCard(
                        stats = stats,
                        completionsForHabit = perHabitCompletions,
                        streakBreakRisk = streakBreakRisks[stats.habit.id]
                    )
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  SUMMARY GROUP CARD (collapsible: Overview + Life Balance)
 * ------------------------------------------------------------------------------------- */

/**
 * Combined collapsible card containing the Global Overview and Life Balance sections.
 *
 * The user can collapse the whole summary group to focus on per-habit cards below.
 * The collapsed state shows only the header bar ("📊 Overview" + week % chip) so the
 * key metric remains visible even when the body is hidden.
 */
@Composable
private fun SummaryGroupCard(
    overview: WeeklyOverview,
    previousWeekRate: Float,
    lifeBalance: List<LifeBalanceEntry>,
    weeklyForecast: WeeklyForecast
) {
    // Local UI state — expansion is purely a View concern, so it stays out of the VM.
    var expanded by remember { mutableStateOf(true) }
    val pct = (overview.weekCompletionRate * 100).roundToInt()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- Header row: title + week % badge + expand toggle ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.card_overview_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.label_this_week_pct, pct),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) stringResource(R.string.cd_collapse_summary) else stringResource(R.string.cd_expand_summary)
                    )
                }
            }

            // ---- Body: visible only when expanded ----
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                GlobalOverviewBody(overview = overview, previousWeekRate = previousWeekRate)
                Spacer(Modifier.height(12.dp))
                WeeklyForecastStrip(forecast = weeklyForecast)
                Spacer(Modifier.height(16.dp))
                LifeBalanceBody(entries = lifeBalance)
            }
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  WEEKLY FORECAST STRIP (Phase 8.3)
 * ------------------------------------------------------------------------------------- */

/**
 * Forecast strip rendered inside [SummaryGroupCard] between the overview body and the
 * life-balance section.
 *
 * Shows the ML-predicted next-week completion rate, a direction icon (▲/▬/▼), and a
 * confidence indicator bar.  When [WeeklyForecast.hasSufficientData] is false the strip
 * renders a single muted "not enough data" hint instead.
 *
 * Direction icon reuses the already-imported TrendingUp/Flat/Down AutoMirrored vectors
 * so no new icon dependency is needed.
 *
 * @param forecast [WeeklyForecast] emitted by [StatisticsViewModel.weeklyForecast].
 */
@Composable
private fun WeeklyForecastStrip(forecast: WeeklyForecast) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.card_forecast_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            if (!forecast.hasSufficientData) {
                Text(
                    text = stringResource(R.string.label_forecast_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            val predictedPct = (forecast.predictedRate * 100).roundToInt()
            val confidencePct = (forecast.confidence * 100).roundToInt()

            // Direction icon + color — mirrors the GlobalOverviewBody trend indicator.
            val (directionIcon, directionColor) = when (forecast.direction) {
                WeeklyForecast.Direction.UP ->
                    Icons.AutoMirrored.Filled.TrendingUp to MaterialTheme.colorScheme.primary
                WeeklyForecast.Direction.DOWN ->
                    Icons.AutoMirrored.Filled.TrendingDown to MaterialTheme.colorScheme.error
                WeeklyForecast.Direction.FLAT ->
                    Icons.AutoMirrored.Filled.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = directionIcon,
                    contentDescription = stringResource(R.string.cd_forecast_direction),
                    tint = directionColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.label_forecast_predicted, predictedPct),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = directionColor
                )
            }

            Spacer(Modifier.height(6.dp))
            // Confidence progress bar — thin, muted; conveys data-volume proxy to the user.
            LinearProgressIndicator(
                progress = { forecast.confidence.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.label_forecast_confidence, confidencePct),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Body of the Global Overview section (formerly its own card).
 *
 * Renders the current 7-day completion rate, a delta vs the previous 7-day rate
 * (▲ +5% / ▼ -3% / ▬ 0%), a horizontal progress bar, and a "X/Y habits completed today"
 * subline. Note: completion = `isTargetReached` on the day. Logging progress that does
 * NOT reach the daily target does not move this percentage — by design, the screen
 * tracks days where the habit's full goal was met.
 */
@Composable
private fun GlobalOverviewBody(overview: WeeklyOverview, previousWeekRate: Float) {
    val rate = overview.weekCompletionRate
    val delta = rate - previousWeekRate
    val pct = (rate * 100).roundToInt()
    val deltaPct = (delta * 100).roundToInt()

    // Pick icon + color based on trend sign so the indicator reads at a glance.
    val (trendIcon, trendColor) = when {
        deltaPct > 0 -> Icons.AutoMirrored.Filled.TrendingUp to MaterialTheme.colorScheme.primary
        deltaPct < 0 -> Icons.AutoMirrored.Filled.TrendingDown to MaterialTheme.colorScheme.error
        else -> Icons.AutoMirrored.Filled.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val trendText = when {
        deltaPct > 0 -> "+$deltaPct%"
        deltaPct < 0 -> "$deltaPct%"
        else -> "0%"
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.label_this_week_pct, pct),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = trendIcon,
                contentDescription = stringResource(R.string.label_trend_7d),
                tint = trendColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(text = trendText, style = MaterialTheme.typography.labelLarge, color = trendColor)
        }
        Spacer(Modifier.height(8.dp))
        // Linear progress bar — visual analog of the percentage above.
        LinearProgressIndicator(
            progress = { rate.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.label_today_habits, overview.todayCompletedHabits, overview.totalActiveHabits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/* -------------------------------------------------------------------------------------
 *  LIFE BALANCE BODY
 * ------------------------------------------------------------------------------------- */

/**
 * Per-category completion-rate widget body. One bar per category sourced from
 * [LifeBalanceEntry] data, plus an AI-insight placeholder block (real text will be
 * supplied by Phase 6's MotivationMessageUseCase).
 *
 * Rendered inside [SummaryGroupCard] so it shares the parent's collapse state.
 */
@Composable
private fun LifeBalanceBody(entries: List<LifeBalanceEntry>) {
    Column {
        Text(
            text = stringResource(R.string.card_life_balance_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_life_balance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            entries.forEach { entry ->
                CategoryRow(entry)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(4.dp))
        AiPlaceholderBox(
            title = stringResource(R.string.card_ai_insight_title),
            body = stringResource(R.string.card_ai_insight_placeholder)
        )
    }
}

/**
 * Single category row inside [LifeBalanceCard]: label + progress bar + percentage text.
 */
@Composable
private fun CategoryRow(entry: LifeBalanceEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = categoryDisplayName(entry.category),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp)
        )
        LinearProgressIndicator(
            progress = { entry.completionRate.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(entry.completionRate * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(40.dp)
        )
    }
}

/* -------------------------------------------------------------------------------------
 *  PER-HABIT CARD (collapsed / expanded)
 * ------------------------------------------------------------------------------------- */

/**
 * Card for a single habit. Collapsed by default; the user taps the toggle to expand.
 * Holds its own [expanded] state because expansion is purely a View concern.
 *
 * @param stats Aggregated stats from the ViewModel (streak, 30-day sparkline, rate).
 * @param completionsForHabit Raw completion rows for this habit — required by the
 *   expanded bar chart, which needs raw counts per day, not just target-reached flags.
 */
@Composable
private fun HabitStatsCard(
    stats: PerHabitStats,
    completionsForHabit: List<HabitCompletionEntity>,
    streakBreakRisk: StreakBreakRisk?
) {
    var expanded by remember { mutableStateOf(false) }
    val habitColor = remember(stats.habit.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(stats.habit.colorHex)) }
            .getOrDefault(Color(0xFF4CAF50))
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- Header row: icon · name · AI-prediction placeholder · expand toggle ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                // resolvedIconEmoji is always non-null: user override takes priority,
                // then IconResolverUseCase auto-resolves from the habit name (Phase 6.4).
                Text(
                    text = stats.resolvedIconEmoji,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stats.habit.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                // Success probability from MathHabitPredictor for today's day and hour.
                AssistChip(
                    onClick = { },
                    label = { Text("🎯 ${(stats.successProbabilityToday * 100).roundToInt()}%") },
                    colors = AssistChipDefaults.assistChipColors()
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand)
                    )
                }
            }

            // ---- Category chips — helps identify habits at a glance ----
            if (stats.habit.categories.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.habit.categories.take(3).forEach { cat ->
                        Text(
                            text = "🏷️ ${categoryDisplayName(cat)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Three stat boxes: current streak / best streak / completion rate ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox(
                    emoji = "🔥",
                    value = "${stats.streak.current}",
                    label = stringResource(R.string.label_stat_current),
                    modifier = Modifier.weight(1f)
                )
                StatBox(
                    emoji = "🏆",
                    value = "${stats.streak.best}",
                    label = stringResource(R.string.label_stat_best),
                    modifier = Modifier.weight(1f)
                )
                StatBox(
                    emoji = "📊",
                    value = "${(stats.completionRate30d * 100).roundToInt()}%",
                    label = stringResource(R.string.label_stat_total),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ---- 7-day sparkline (visible in both collapsed and expanded views) ----
            Text(
                text = stringResource(R.string.label_trend_7d),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Sparkline(
                // The 30-day sparkline already carries the most recent week as its tail.
                reachedFlags = stats.sparkline30d.takeLast(7).map { it.reached },
                color = habitColor
            )

            // ---- Expanded sections ----
            if (expanded) {
                Spacer(Modifier.height(16.dp))
                ExpandedSection(
                    stats = stats,
                    completions = completionsForHabit,
                    color = habitColor,
                    streakBreakRisk = streakBreakRisk
                )
            }
        }
    }
}

/**
 * Single statistics tile rendered inside [HabitStatsCard]'s three-up row.
 */
@Composable
private fun StatBox(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$emoji $value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Expanded-card content: range-tab selector → bar chart → completion summary,
 * followed by three AI cards (Smart Insight, Optimal Timing, Behavioral Patterns)
 * backed by [MathHabitPredictor] data surfaced through [PerHabitStats].
 */
@Composable
private fun ExpandedSection(stats: PerHabitStats, completions: List<HabitCompletionEntity>, color: Color, streakBreakRisk: StreakBreakRisk?) {
    var range by remember { mutableStateOf(ChartRange.SEVEN_DAYS) }

    val today = remember { LocalDate.now() }
    // ALL needs the earliest completion date; if there are none, fall back to today.
    val earliest = remember(completions) {
        completions.minOfOrNull { it.progressUpdate.toLocalDate() } ?: today
    }
    val (from, to) = remember(range, earliest) { range.window(today, earliest) }

    // Build BarChartDay list: one entry per calendar day in [from..to] with raw count.
    val days: List<BarChartDay> = remember(completions, from, to) {
        val countsByDate = completions
            .groupingBy { it.progressUpdate.toLocalDate() }
            .eachCount()
        val list = mutableListOf<BarChartDay>()
        var d = from
        while (!d.isAfter(to)) {
            list.add(BarChartDay(date = d, count = countsByDate[d] ?: 0))
            d = d.plusDays(1)
        }
        list
    }

    Column {
        // ---- Range tabs (7D / 30D / 3M / ALL) ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartRange.values().forEach { option ->
                FilterChip(
                    selected = option == range,
                    onClick = { range = option },
                    label = { Text(option.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Chart ----
        ScrollableBarChart(days = days, color = color, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.label_completions_range, days.sumOf { it.count }),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))
        SmartInsightCard(stats = stats, streakBreakRisk = streakBreakRisk)
        Spacer(Modifier.height(12.dp))
        OptimalTimingCard(optimalHours = stats.optimalHours)
        Spacer(Modifier.height(12.dp))
        BehavioralPatternsCard(
            relatedHabitNames = stats.relatedHabitNames,
            routinePrecision = stats.routinePrecision,
            resilience = stats.resilience
        )
    }
}

/* -------------------------------------------------------------------------------------
 *  AI CARDS (Phase 6 — MathHabitPredictor)
 * ------------------------------------------------------------------------------------- */

/**
 * Maps a motivation message key (returned by [MathHabitPredictor.motivationMessageKey])
 * to the corresponding plurals resource ID so [pluralStringResource] can resolve the
 * locale-correct text. Using a `when` keeps the mapping explicit and avoids
 * [Resources.getIdentifier] reflection, which is slower and lint-unsafe.
 *
 * The returned ID belongs to [R.plurals] (not [R.string]); callers must pass the
 * streak count as both the quantity selector and the format argument so that Polish
 * can select the correct one/few/many/other form and embed the count in
 * `motivation_streak_milestone` (e.g. "Niesamowita passa 7 dni!").
 */
@PluralsRes
private fun motivationKeyToRes(key: String): Int = when (key) {
    "motivation_streak_milestone"      -> R.plurals.motivation_streak_milestone
    "motivation_gentle_nudge"          -> R.plurals.motivation_gentle_nudge
    "motivation_celebrate_consistency" -> R.plurals.motivation_celebrate_consistency
    "motivation_recovery_encouragement"-> R.plurals.motivation_recovery_encouragement
    "motivation_morning_optimistic"    -> R.plurals.motivation_morning_optimistic
    "motivation_evening_reflection"    -> R.plurals.motivation_evening_reflection
    "motivation_weekend_warrior"       -> R.plurals.motivation_weekend_warrior
    "motivation_cold_start"            -> R.plurals.motivation_cold_start
    else                               -> R.plurals.motivation_quiet_encouragement
}

/** Formats a 0–23 hour int to a human-readable 12-hour string (e.g. 14 → "2:00 PM"). */
private fun formatHour(hour: Int): String {
    val suffix = if (hour < 12) "AM" else "PM"
    val h = when {
        hour == 0  -> 12
        hour > 12  -> hour - 12
        else       -> hour
    }
    return "$h:00 $suffix"
}

/**
 * Styled container for real AI-backed content. Uses [MaterialTheme.colorScheme.surfaceContainer]
 * (slightly different from the placeholder's [surfaceVariant]) so users can distinguish
 * live data from stubs at a glance.
 */
@Composable
private fun AiDataCard(title: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/**
 * `✨ AI Smart Insight` card — shows the motivation message, streak-risk warning, and
 * an adaptive difficulty suggestion, all derived from [MathHabitPredictor].
 */
@Composable
private fun SmartInsightCard(stats: PerHabitStats, streakBreakRisk: StreakBreakRisk?) {
    AiDataCard(title = stringResource(R.string.card_smart_insight_title)) {
        Text(
            // Pass streak.current as both the quantity selector (selects one/few/many/other)
            // and the format arg so motivation_streak_milestone can embed the count ("%d days").
            // For messages without %d in the format string, the extra arg is safely ignored.
            text = pluralStringResource(
                id    = motivationKeyToRes(stats.motivationMessageKey),
                count = stats.streak.current,
                stats.streak.current
            ),
            style = MaterialTheme.typography.bodySmall
        )
        // Phase 8.2: ML-backed streak-break probability bar; falls back to the
        // rule-based Boolean when the model has insufficient data for this habit.
        if (streakBreakRisk != null && streakBreakRisk.hasSufficientData) {
            Spacer(Modifier.height(6.dp))
            val ratingLabel = streakBreakRisk.rating.name
            val barColor = when (streakBreakRisk.rating) {
                StreakBreakRisk.Rating.LOW      -> MaterialTheme.colorScheme.primary
                StreakBreakRisk.Rating.MEDIUM   -> MaterialTheme.colorScheme.tertiary
                StreakBreakRisk.Rating.HIGH,
                StreakBreakRisk.Rating.CRITICAL -> MaterialTheme.colorScheme.error
            }
            Text(
                text = "⚠️ Streak break risk: ${(streakBreakRisk.probability * 100).roundToInt()}% ($ratingLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = barColor
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { streakBreakRisk.probability },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        } else if (stats.isStreakAtRisk) {
            // Fallback: rule-based Boolean flag when ML data is not yet sufficient.
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.insight_streak_at_risk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(6.dp))
        val targetText = when {
            stats.targetDelta > 0 -> stringResource(R.string.insight_raise_target)
            stats.targetDelta < 0 -> stringResource(R.string.insight_lower_target)
            else                  -> stringResource(R.string.insight_target_calibrated)
        }
        Text(text = targetText, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * `🕒 Optimal Timing` card — shows the top hours of the day at which the user historically
 * completes the habit, ranked by [MathHabitPredictor.optimalHours].
 */
@Composable
private fun OptimalTimingCard(optimalHours: List<Int>) {
    AiDataCard(title = stringResource(R.string.card_optimal_timing_title)) {
        if (optimalHours.isEmpty()) {
            Text(
                text = stringResource(R.string.optimal_no_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.optimal_best_times, optimalHours.joinToString(" · ") { formatHour(it) }),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.optimal_based_on_history),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * `🧠 Behavioral Patterns` card — surfaces co-occurring habits, routine consistency
 * (std-dev in minutes), and resilience (average recovery gap), all from [MathHabitPredictor].
 */
@Composable
private fun BehavioralPatternsCard(
    relatedHabitNames: List<String>,
    routinePrecision: Double?,
    resilience: Double?
) {
    AiDataCard(title = stringResource(R.string.card_behavioral_patterns_title)) {
        if (relatedHabitNames.isNotEmpty()) {
            Text(
                text = stringResource(R.string.patterns_often_together, relatedHabitNames.take(3).joinToString(", ")),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
        }
        val precisionText = routinePrecision?.let {
            stringResource(R.string.patterns_routine_window, it.roundToInt())
        } ?: stringResource(R.string.patterns_no_routine)
        Text(text = precisionText, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        val resilienceText = resilience?.let {
            stringResource(R.string.patterns_recovery_speed, it.roundToInt())
        } ?: stringResource(R.string.patterns_no_recovery)
        Text(text = resilienceText, style = MaterialTheme.typography.bodySmall)
    }
}

/* -------------------------------------------------------------------------------------
 *  PLACEHOLDERS & EMPTY STATES
 * ------------------------------------------------------------------------------------- */

/**
 * Generic styled box used for all AI-driven sections until the AI layer ships in Phase 6.
 * Kept visually distinct from real data cards by using [MaterialTheme.colorScheme.surfaceVariant]
 * so it's obvious that the content is intentionally a stub.
 */
@Composable
private fun AiPlaceholderBox(title: String, body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  PHASE 8.1 — AT RISK CARD
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard listing habits whose abandonment probability is HIGH or CRITICAL.
 *
 * Sorted by descending probability so the most urgent habits appear first.
 * Only rendered when [entries] is non-empty (caller guards this).
 *
 * @param entries (habitName, AbandonmentRisk) pairs for HIGH/CRITICAL habits only.
 */
@Composable
private fun AtRiskCard(entries: List<Pair<String, AbandonmentRisk>>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.card_at_risk_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            entries.forEach { (name, risk) ->
                AtRiskRow(habitName = name, risk = risk)
            }
        }
    }
}

/**
 * Single row inside [AtRiskCard]: habit name, a colour-coded rating chip, and the
 * raw probability as a percentage.
 *
 * CRITICAL uses the full `error` token (deep red); HIGH uses the lighter
 * `errorContainer` token so the visual hierarchy matches severity.
 */
@Composable
private fun AtRiskRow(habitName: String, risk: AbandonmentRisk) {
    // Map rating to chip colours; early-return for ratings below HIGH (should not occur).
    val (chipLabel, chipColor, chipContentColor) = when (risk.rating) {
        AbandonmentRisk.Rating.CRITICAL -> Triple(
            stringResource(R.string.label_rating_critical),
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError
        )
        AbandonmentRisk.Rating.HIGH -> Triple(
            stringResource(R.string.label_rating_high),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        else -> return
    }
    val pct = (risk.probability * 100).roundToInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = habitName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        AssistChip(
            onClick = {},
            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = chipColor,
                labelColor = chipContentColor
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.label_risk_pct, pct),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Friendly hint shown when the user has no habits yet — keeps the screen from feeling
 * broken before any data exists.
 */
@Composable
private fun EmptyHabitsHint() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = stringResource(R.string.empty_no_habits_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.empty_no_habits_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  BEHAVIORAL TIERS CARD (Phase 8.4)
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard that groups all habits by their K-Means behavioral tier (Phase 8.4).
 *
 * Tier display order: Effortless Routine → Consistent Effort → Struggling → Dormant.
 * Each tier header is rendered in the tier's representative colour; habits within each
 * tier are displayed as [AssistChip] rows so the user can scan them at a glance.
 *
 * When none of the [clusters] entries has [HabitCluster.hasSufficientData] = true the
 * card shows a single placeholder string instead of tier sections.
 *
 * @param clusters    Map of habitId → [HabitCluster] emitted by [StatisticsViewModel.behavioralClusters].
 * @param habitNames  Map of habitId → habit display name, pre-built from [StatisticsViewModel.perHabitStats].
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BehavioralTiersCard(
    clusters: Map<Int, HabitCluster>,
    habitNames: Map<Int, String>
) {
    // Tier display order: best to worst, matching the four archetype labels.
    val tierOrder = listOf(
        BehavioralCluster.EffortlessRoutine,
        BehavioralCluster.ConsistentEffort,
        BehavioralCluster.Struggling,
        BehavioralCluster.Dormant
    )

    // Habits with sufficient data, grouped by tier.
    val byTier: Map<BehavioralCluster, List<String>> = tierOrder.associateWith { tier ->
        clusters.values
            .filter { it.hasSufficientData && it.cluster == tier }
            .mapNotNull { habitNames[it.habitId] }
            .sorted()
    }
    // Habits still collecting data — shown in a separate footer section.
    val insufficientNames: List<String> = clusters.values
        .filter { !it.hasSufficientData }
        .mapNotNull { habitNames[it.habitId] }
        .sorted()
    val hasAnyData = byTier.values.any { it.isNotEmpty() }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.card_cluster_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.card_cluster_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (!hasAnyData && insufficientNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.cluster_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tierOrder.forEach { tier ->
                    val habits = byTier[tier] ?: return@forEach
                    if (habits.isEmpty()) return@forEach

                    val tierLabel = when (tier) {
                        is BehavioralCluster.EffortlessRoutine -> stringResource(R.string.cluster_effortless)
                        is BehavioralCluster.ConsistentEffort  -> stringResource(R.string.cluster_consistent)
                        is BehavioralCluster.Struggling        -> stringResource(R.string.cluster_struggling)
                        is BehavioralCluster.Dormant           -> stringResource(R.string.cluster_dormant)
                    }
                    val tierDesc = when (tier) {
                        is BehavioralCluster.EffortlessRoutine -> stringResource(R.string.cluster_effortless_desc)
                        is BehavioralCluster.ConsistentEffort  -> stringResource(R.string.cluster_consistent_desc)
                        is BehavioralCluster.Struggling        -> stringResource(R.string.cluster_struggling_desc)
                        is BehavioralCluster.Dormant           -> stringResource(R.string.cluster_dormant_desc)
                    }
                    val tierColor = when (tier) {
                        is BehavioralCluster.EffortlessRoutine -> MaterialTheme.colorScheme.primary
                        is BehavioralCluster.ConsistentEffort  -> MaterialTheme.colorScheme.tertiary
                        is BehavioralCluster.Struggling        -> MaterialTheme.colorScheme.error
                        is BehavioralCluster.Dormant           -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Text(
                        text = tierLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tierColor
                    )
                    Text(
                        text = tierDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    // FlowRow wraps chips automatically — handles any number of habits.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        habits.forEach { name ->
                            AssistChip(
                                onClick = {},
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = tierColor.copy(alpha = 0.10f),
                                    labelColor = tierColor
                                ),
                                border = AssistChipDefaults.assistChipBorder(enabled = true,
                                    borderColor = tierColor.copy(alpha = 0.35f))
                            )
                        }
                    }
                }

                // Show habits that haven't yet accumulated enough data.
                if (insufficientNames.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.cluster_insufficient_section),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.cluster_no_data),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        insufficientNames.forEach { name ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(name, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                border = AssistChipDefaults.assistChipBorder(enabled = false)
                            )
                        }
                    }
                }
            }
        }
    }
}
