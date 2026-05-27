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
import androidx.compose.material3.HorizontalDivider
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
import com.example.evolvix.domain.model.EngagementWindow
import com.example.evolvix.domain.model.HabitCluster
import com.example.evolvix.domain.model.LifeBalanceEntry
import com.example.evolvix.domain.model.ReminderLift
import com.example.evolvix.domain.model.SnoozeDisengagementRisk
import com.example.evolvix.domain.model.SpilloverPair
import com.example.evolvix.domain.model.StreakBreakRisk
import com.example.evolvix.domain.model.PerceivedDifficultyEstimate
import com.example.evolvix.domain.model.SkipReasonPrediction
import com.example.evolvix.domain.model.TargetAdjustment
import com.example.evolvix.domain.model.PerHabitStats
import com.example.evolvix.domain.model.WeeklyForecast
import com.example.evolvix.domain.model.WeeklyOverview
import com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase
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
            predictor = com.example.evolvix.domain.ai.AiContainer.predictor(LocalContext.current),
            // Phase 9.3: pass the DAO so TargetAdjustmentUseCase can read the audit log.
            targetHistoryDao = AppDatabase.getDatabase(LocalContext.current).targetHistoryDao(),
            // Phase 9.5: pass the DAO so SkipReasonPredictorUseCase can read skip history.
            habitSkipDao = AppDatabase.getDatabase(LocalContext.current).habitSkipDao(),
            // Phase 9.6: pass the DAO so EngagementWindowUseCase can read session logs.
            appSessionDao = AppDatabase.getDatabase(LocalContext.current).appSessionDao()
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
    val spilloverInsights by viewModel.spilloverInsights.collectAsState()
    val reminderLifts by viewModel.reminderLifts.collectAsState()
    val snoozeDisengagementRisks by viewModel.snoozeDisengagementRisks.collectAsState()
    val targetAdjustments by viewModel.targetAdjustments.collectAsState()
    // Phase 9.4: predicted difficulty per habit from DifficultyEstimateUseCase.
    val difficultyEstimates by viewModel.difficultyEstimates.collectAsState()
    // Phase 9.5: predicted skip reason per habit from SkipReasonPredictorUseCase.
    val skipReasonPredictions by viewModel.skipReasonPredictions.collectAsState()
    // Phase 9.6: predicted most-likely active hour from EngagementWindowUseCase.
    val engagementWindow by viewModel.engagementWindow.collectAsState()

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

            // Phase 8.1 — Abandonment Risk card (shown when ≥1 habit has HIGH or CRITICAL risk)
            val atRiskEntries = abandonmentRisks.values
                .filter {
                    it.hasSufficientData &&
                        (it.rating == AbandonmentRisk.Rating.HIGH ||
                            it.rating == AbandonmentRisk.Rating.CRITICAL)
                }
                .sortedByDescending { it.probability }
                .mapNotNull { risk ->
                    val name = perHabit.find { it.habit.id == risk.habitId }?.habit?.name
                        ?: return@mapNotNull null
                    name to risk
                }
            if (atRiskEntries.isNotEmpty()) {
                item { AtRiskCard(entries = atRiskEntries) }
            }

            // Phase 8.4 + 8.5 — Behavioral Tiers + Spillover card (shown when either has data)
            if (behavioralClusters.isNotEmpty() || spilloverInsights.isNotEmpty()) {
                item {
                    BehavioralTiersCard(
                        clusters = behavioralClusters,
                        habitNames = perHabit.associate { it.habit.id to it.habit.name },
                        spilloverInsights = spilloverInsights
                    )
                }
            }

            // Phase 9.1 — Smart Reminders card (shown when ≥1 habit has sufficient data)
            if (reminderLifts.values.any { it.hasSufficientData }) {
                item {
                    SmartRemindersCard(
                        reminderLifts = reminderLifts,
                        habitNames = perHabit.associate { it.habit.id to it.habit.name }
                    )
                }
            }

            // Phase 9.2 — Snooze Drift card (shown when ≥1 habit has HIGH or CRITICAL risk)
            val snoozeDriftEntries = snoozeDisengagementRisks.values
                .filter {
                    it.hasSufficientData &&
                        (it.rating == SnoozeDisengagementRisk.Rating.HIGH ||
                            it.rating == SnoozeDisengagementRisk.Rating.CRITICAL)
                }
                .sortedByDescending { it.probability }
                .mapNotNull { risk ->
                    val name = perHabit.find { it.habit.id == risk.habitId }?.habit?.name
                        ?: return@mapNotNull null
                    name to risk
                }
            if (snoozeDriftEntries.isNotEmpty()) {
                item { SnoozeDriftCard(entries = snoozeDriftEntries) }
            }

            // Phase 9.3 — Target Calibration card (shown when ≥1 habit has a non-zero suggestion)
            val targetAdjustmentEntries = targetAdjustments.entries
                .filter { (_, adj) -> adj.hasSufficientData && adj.delta != 0 }
                .sortedByDescending { (_, adj) -> kotlin.math.abs(adj.delta) }
                .mapNotNull { (habitId, adj) ->
                    val name = perHabit.find { it.habit.id == habitId }?.habit?.name
                        ?: return@mapNotNull null
                    name to adj
                }
            item {
                TargetCalibrationCard(entries = targetAdjustmentEntries)
            }

            // Phase 9.4 — Perceived Difficulty card (always shown; empty state handled inside)
            item {
                PerceivedDifficultyCard(
                    estimates = difficultyEstimates,
                    habitNames = perHabit.associate { it.habit.id to it.habit.name }
                )
            }

            // Phase 9.5 — Skip Reason Forecast card (shown when ≥1 habit has ≥3 skip records)
            if (skipReasonPredictions.values.any { it.hasSufficientData }) {
                item {
                    SkipReasonForecastCard(
                        predictions = skipReasonPredictions,
                        habitNames = perHabit.associate { it.habit.id to it.habit.name }
                    )
                }
            }

            // Phase 9.6 — Engagement Window card (always shown; cold-start placeholder inside)
            item {
                EngagementWindowCard(window = engagementWindow)
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
 *  SMART REMINDERS CARD (Phase 9.1)
 * ------------------------------------------------------------------------------------- */

/**
 * Displays predicted reminder effectiveness per habit (Phase 9.1).
 *
 * Shows the estimated lift — the difference in completion probability when a reminder
 * is sent versus not sent. Results are observational (correlational), not causal.
 *
 * Only habits where [ReminderLift.hasSufficientData] is true are shown, sorted by
 * lift descending so the most impactful reminders appear first.
 */
@Composable
private fun SmartRemindersCard(
    reminderLifts: Map<Int, ReminderLift>,
    habitNames: Map<Int, String>
) {
    val habitsWithData = reminderLifts.values
        .filter { it.hasSufficientData }
        .sortedByDescending { it.lift }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.smart_reminders_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.smart_reminders_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            habitsWithData.forEachIndexed { index, lift ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val name = habitNames[lift.habitId] ?: return@forEachIndexed
                val liftPct = (lift.lift * 100).roundToInt()
                val liftLabel = if (liftPct >= 0) "+${liftPct}%" else "${liftPct}%"
                val statusColor = if (lift.recommendSend) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
                val statusText = if (lift.recommendSend) {
                    stringResource(R.string.smart_reminders_send)
                } else {
                    stringResource(R.string.smart_reminders_suppress)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = liftLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  SNOOZE DRIFT CARD (Phase 9.2)
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard listing habits with a HIGH or CRITICAL snooze-disengagement risk.
 *
 * Shows the probability as a percentage and a colour-coded rating chip per habit,
 * sorted descending by probability. Only rendered when [entries] is non-empty
 * (caller guards this).
 *
 * ⚠ The risk score is observational — high snooze counts correlate with dropout but
 * do not cause it. The subtitle surfaces this caveat for the thesis defence.
 *
 * @param entries (habitName, [SnoozeDisengagementRisk]) pairs for HIGH/CRITICAL habits only.
 */
@Composable
private fun SnoozeDriftCard(entries: List<Pair<String, SnoozeDisengagementRisk>>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.snooze_drift_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.snooze_drift_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            entries.forEach { (name, risk) ->
                SnoozeDriftRow(habitName = name, risk = risk)
            }
        }
    }
}

/**
 * Single row inside [SnoozeDriftCard]: habit name, a colour-coded rating chip, and
 * the raw snooze-disengagement probability as a percentage.
 *
 * CRITICAL uses the `error` token (deep red); HIGH uses `errorContainer` (lighter red)
 * matching the visual severity hierarchy used in [AtRiskRow].
 */
@Composable
private fun SnoozeDriftRow(habitName: String, risk: SnoozeDisengagementRisk) {
    val (chipLabel, chipColor, chipContentColor) = when (risk.rating) {
        SnoozeDisengagementRisk.Rating.CRITICAL -> Triple(
            stringResource(R.string.label_rating_critical),
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError
        )
        SnoozeDisengagementRisk.Rating.HIGH -> Triple(
            stringResource(R.string.label_rating_high),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        else -> return  // Only HIGH/CRITICAL reach this composable
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

/* -------------------------------------------------------------------------------------
 *  TARGET CALIBRATION CARD (Phase 9.3)
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard displaying ML-suggested target changes for habits with sufficient history.
 *
 * When [entries] is non-empty (≥1 habit with `hasSufficientData = true` and `delta ≠ 0`),
 * each row shows the habit name, a delta arrow ("↑ +2" / "↓ -1"), the "current → suggested"
 * target count, and a confidence chip.  When [entries] is empty, a placeholder encouraging
 * more logging is displayed instead.
 *
 * ⚠ Recommendations are observational — a higher 30-day rate correlates with room to
 * raise targets, but individual habits vary.  The subtitle surfaces this for the thesis.
 *
 * @param entries (habitName, [TargetAdjustment]) pairs sorted by |delta| descending.
 */
@Composable
private fun TargetCalibrationCard(entries: List<Pair<String, TargetAdjustment>>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.target_adjustment_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.target_adjustment_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.target_adjustment_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEach { (name, adj) ->
                    TargetCalibrationRow(habitName = name, adjustment = adj)
                }
            }
        }
    }
}

/**
 * Single row inside [TargetCalibrationCard]: habit name, delta arrow label,
 * "current → suggested" target values, and a confidence chip.
 *
 * Confidence colour mapping (M3 tokens):
 * - HIGH   → primary / onPrimary
 * - MEDIUM → secondary / onSecondary
 * - LOW    → surfaceVariant / onSurfaceVariant
 *
 * @param habitName  Display name of the habit.
 * @param adjustment [TargetAdjustment] containing delta, currentTarget, suggestedTarget, and confidence.
 */
@Composable
private fun TargetCalibrationRow(habitName: String, adjustment: TargetAdjustment) {
    val arrowLabel = if (adjustment.delta > 0) {
        stringResource(R.string.target_adjustment_arrow_up, adjustment.delta)
    } else {
        stringResource(R.string.target_adjustment_arrow_down, adjustment.delta)
    }
    val arrowColor = if (adjustment.delta > 0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val (chipLabel, chipColor, chipContentColor) = when (adjustment.confidence) {
        TargetAdjustment.Confidence.HIGH -> Triple(
            "HIGH",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary
        )
        TargetAdjustment.Confidence.MEDIUM -> Triple(
            "MEDIUM",
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSecondary
        )
        TargetAdjustment.Confidence.LOW -> Triple(
            "LOW",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
        Text(
            text = stringResource(
                R.string.target_adjustment_current_to_suggested,
                adjustment.currentTarget,
                adjustment.suggestedTarget
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = arrowLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = arrowColor
        )
        Spacer(Modifier.width(6.dp))
        AssistChip(
            onClick = {},
            label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = chipColor,
                labelColor = chipContentColor
            )
        )
    }
}

/* -------------------------------------------------------------------------------------
 *  BEHAVIORAL TIERS CARD (Phase 8.4)
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard that groups all habits by their K-Means behavioral tier (Phase 8.4).
 *
 * Tier display order: Effortless Routine → Consistent Effort → Struggling → Dormant.
 * Each tier header is rendered in the tier’s representative colour; habits within each
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
    habitNames: Map<Int, String>,
    spilloverInsights: List<SpilloverPair>
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

            // ── Phase 8.5 Spillover section (shown only when today produced results) ──
            if (spilloverInsights.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.spillover_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.spillover_section_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                spilloverInsights.forEach { pair ->
                    val pct = (kotlin.math.abs(pair.liftDelta) * 100).roundToInt()
                    val (color, text) = when (pair.direction) {
                        SpilloverPair.Direction.BOOST -> MaterialTheme.colorScheme.primary to
                            stringResource(R.string.spillover_boost, pair.habitAName, pair.habitBName, pct)
                        SpilloverPair.Direction.DRAG  -> MaterialTheme.colorScheme.error to
                            stringResource(R.string.spillover_drag, pair.habitAName, pair.habitBName, pct)
                        // NEUTRAL pairs are filtered by SpilloverUseCase; defensive fallback only.
                        SpilloverPair.Direction.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant to ""
                    }
                    if (text.isNotEmpty()) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  PERCEIVED DIFFICULTY CARD (Phase 9.4)
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard showing the predicted perceived difficulty for each habit (Phase 9.4).
 *
 * Uses [DifficultyEstimateUseCase] results exposed by [StatisticsViewModel.difficultyEstimates].
 * When a habit has [PerceivedDifficultyEstimate.hasSufficientData] = false the row shows
 * a "Collecting data…" note instead of a prediction. When the user has provided ≥5 ratings
 * via [ProgressItem]'s star-chip row, the row also shows [PerceivedDifficultyEstimate.recentAvgRated].
 *
 * ⚠ Observational caveat (thesis): predictions reflect correlation, not causation.
 * The subtitle surfaces this for graders.
 *
 * @param estimates   Map of habitId → [PerceivedDifficultyEstimate] from [StatisticsViewModel].
 * @param habitNames  Map of habitId → display name; built from [StatisticsViewModel.perHabitStats].
 */
@Composable
private fun PerceivedDifficultyCard(
    estimates: Map<Int, PerceivedDifficultyEstimate>,
    habitNames: Map<Int, String>,
) {
    // Build display list aligned with habitNames — only habits present in both maps.
    val entries: List<Pair<String, PerceivedDifficultyEstimate>> = habitNames.entries
        .sortedBy { (_, name) -> name }
        .mapNotNull { (id, name) -> estimates[id]?.let { name to it } }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.difficulty_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.difficulty_card_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.difficulty_no_data),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.forEach { (name, est) ->
                    DifficultyRow(habitName = name, estimate = est)
                }
            }
        }
    }
}

/**
 * Single row inside [PerceivedDifficultyCard].
 *
 * Shows habit name, optional user-reported average ("You: 3.5★"), predicted score
 * ("3.2★"), and an [AssistChip] coloured by the [PerceivedDifficultyEstimate.rating] enum.
 * When [PerceivedDifficultyEstimate.hasSufficientData] is false, the prediction and chip
 * are replaced by a "Collecting data…" note (cold-start safe).
 *
 * Rating → M3 colour mapping:
 * - EASY        → primary / onPrimary
 * - MODERATE    → secondary / onSecondary
 * - HARD        → error / onError
 * - VERY_HARD   → errorContainer / onErrorContainer
 *
 * @param habitName Display name of the habit.
 * @param estimate  [PerceivedDifficultyEstimate] containing predicted score and metadata.
 */
@Composable
private fun DifficultyRow(
    habitName: String,
    estimate: PerceivedDifficultyEstimate,
) {
    val (chipLabel, chipColor, chipContentColor) = when (estimate.rating) {
        PerceivedDifficultyEstimate.Rating.EASY -> Triple(
            "EASY",
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary
        )
        PerceivedDifficultyEstimate.Rating.MODERATE -> Triple(
            "MODERATE",
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSecondary
        )
        PerceivedDifficultyEstimate.Rating.HARD -> Triple(
            "HARD",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError
        )
        PerceivedDifficultyEstimate.Rating.VERY_HARD -> Triple(
            "VERY HARD",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = habitName,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!estimate.hasSufficientData) {
                Text(
                    text = stringResource(R.string.difficulty_no_data_inline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                estimate.recentAvgRated?.let { avg ->
                    Text(
                        text = "You: ${"%.1f".format(avg)}★",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (estimate.hasSufficientData) {
            Text(
                text = "${"%.1f".format(estimate.predicted)}★",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            AssistChip(
                onClick = {},
                label = { Text(chipLabel, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = chipColor,
                    labelColor = chipContentColor,
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = chipColor.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

/* -------------------------------------------------------------------------------------
 *  SKIP REASON FORECAST CARD (Phase 9.5)
 * ------------------------------------------------------------------------------------- */

/**
 * ElevatedCard listing the predicted most-likely skip reason for each habit that has
 * accumulated at least [SkipReasonPredictorUseCase.MIN_SKIPS] skip records.
 *
 * The card is conditionally shown from [StatisticsScreen] only when at least one habit
 * satisfies the data-sufficiency threshold. Predictions come from
 * [com.example.evolvix.ui.viewmodel.StatisticsViewModel.skipReasonPredictions], which
 * delegates to [com.example.evolvix.domain.usecase.SkipReasonPredictorUseCase] and
 * ultimately to [com.example.evolvix.domain.ai.HabitPredictor.predictSkipReason].
 *
 * Observational caveat (thesis): the classifier learns correlations between behavioural
 * context features and recorded skip reasons — it does NOT establish causality.
 *
 * @param predictions Map of habitId → [SkipReasonPrediction] for all tracked habits.
 * @param habitNames  Map of habitId → display name used for row labels.
 */
@Composable
private fun SkipReasonForecastCard(
    predictions: Map<Int, SkipReasonPrediction>,
    habitNames: Map<Int, String>,
) {
    // Filter to only habits with sufficient skip data, sorted by confidence descending.
    val entries = predictions.entries
        .filter { (_, pred) -> pred.hasSufficientData }
        .sortedByDescending { (_, pred) -> pred.topConfidence }
        .mapNotNull { (habitId, pred) ->
            val name = habitNames[habitId] ?: return@mapNotNull null
            Pair(name, pred)
        }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Skip Reason Forecast",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Predicted most likely skip reason per habit based on behavioural context (Phase 9.5).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Text(
                    text = "Keep logging skips — predictions appear once a habit has at least " +
                        "${SkipReasonPredictorUseCase.MIN_SKIPS} skip records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEachIndexed { index, (name, prediction) ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    SkipReasonForecastRow(habitName = name, prediction = prediction)
                }
            }
        }
    }
}

/**
 * Single row inside [SkipReasonForecastCard].
 *
 * Shows the habit name, predicted top reason label, a confidence progress bar, and
 * a "(low confidence)" note when [SkipReasonPrediction.topConfidence] is below
 * [SkipReasonPrediction.LOW_CONFIDENCE_THRESHOLD].
 *
 * @param habitName  Display name of the habit.
 * @param prediction [SkipReasonPrediction] containing the top reason and softmax distribution.
 */
@Composable
private fun SkipReasonForecastRow(
    habitName: String,
    prediction: SkipReasonPrediction,
) {
    val isLowConfidence = prediction.topConfidence < SkipReasonPrediction.LOW_CONFIDENCE_THRESHOLD

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = habitName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = prediction.topReason.displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isLowConfidence)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = if (isLowConfidence)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
        // Confidence bar — visual indicator of model certainty for the top reason.
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { prediction.topConfidence },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(2.dp))
        val confidencePct = (prediction.topConfidence * 100).roundToInt()
        Text(
            text = if (isLowConfidence) "$confidencePct% confidence (low — multiple reasons likely)"
                   else "$confidencePct% confidence",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* -------------------------------------------------------------------------------------
 *  ENGAGEMENT WINDOW CARD (Phase 9.6)
 * ------------------------------------------------------------------------------------- */

/**
 * Displays the user's predicted most-likely app-open hour from the
 * [com.example.evolvix.domain.usecase.EngagementWindowUseCase].
 *
 * Two states:
 * - **Cold-start ([EngagementWindow.hasSufficientData] = false):** shows a friendly
 *   message asking the user to keep using the app until
 *   [EngagementWindow.MIN_SESSIONS] sessions have been recorded.
 * - **Data available:** shows the predicted hour (formatted as 24-hour clock or AM/PM)
 *   and a confidence bar.
 *
 * ⚠ **Observational caveat:** the label uses hedged language ("usually active around")
 * so the user understands this reflects *observation*, not a prescription.
 *
 * (Pattern: stateless, pure composable — all state lives in [StatisticsViewModel])
 *
 * @param window The [EngagementWindow] domain object produced by [EngagementWindowUseCase].
 */
@Composable
private fun EngagementWindowCard(window: EngagementWindow) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Your Active Hour",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "When you’re most likely to open the app, based on your session history (Phase 9.6).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (!window.hasSufficientData) {
                Text(
                    text = "Not enough data yet — keep using Evolvix! " +
                        "Predictions appear after ${EngagementWindow.MIN_SESSIONS} sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Format as HH:00 (24-hour) — simple and unambiguous for a thesis demo.
                val hourLabel = String.format("%02d:00", window.predictedHour)
                Text(
                    text = "Usually active around $hourLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(8.dp))
                // Confidence bar
                LinearProgressIndicator(
                    progress = { window.confidence },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.height(2.dp))
                val confidencePct = (window.confidence * 100).roundToInt()
                Text(
                    text = "$confidencePct% confidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}