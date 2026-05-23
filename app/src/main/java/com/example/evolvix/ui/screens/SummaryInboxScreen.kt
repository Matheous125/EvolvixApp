package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.R
import com.example.evolvix.data.model.DailySummaryEntity
import com.example.evolvix.ui.viewmodel.SummaryInboxViewModel
import com.example.evolvix.ui.viewmodel.SummaryInboxViewModelFactory
import java.time.format.DateTimeFormatter

/**
 * Inbox-style screen that lists every persisted [DailySummaryEntity] in reverse-
 * chronological order (Phase 7.2 v2).
 *
 * Strict M3: `Scaffold` + `TopAppBar` + `ElevatedCard` per row. Read items are
 * rendered with `alpha 0.6` to communicate state without using a custom shape or
 * colour outside the theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryInboxScreen(
    onNavigateBack: () -> Unit,
    viewModel: SummaryInboxViewModel = viewModel(
        factory = SummaryInboxViewModelFactory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext
                as android.app.Application
        )
    )
) {
    val summaries by viewModel.summaries.collectAsState()
    val unread by viewModel.unreadCount.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_summaries_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    if (unread > 0) {
                        IconButton(onClick = { viewModel.markAllRead() }) {
                            Icon(
                                Icons.Filled.DoneAll,
                                contentDescription = stringResource(R.string.btn_mark_all_read)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { padding ->
        if (summaries.isEmpty()) {
            EmptyInbox(padding)
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(summaries, key = { it.id }) { row ->
                    SummaryCard(row, onClick = { viewModel.markRead(row.id) })
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(row: DailySummaryEntity, onClick: () -> Unit) {
    val dateFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
    val localizedTitle = localizedSummaryTitle(row)
    val localizedBody = localizedSummaryBody(row)
    ElevatedCard(
        modifier = Modifier
            .fillMaxSize()
            .alpha(if (row.isRead) 0.6f else 1f),
        onClick = onClick
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = row.date.format(dateFmt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = localizedTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = localizedBody,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** Mirrors [DailySummaryWorker.localizedTitle] but via Compose string resources. */
@Composable
private fun localizedSummaryTitle(row: DailySummaryEntity): String = when {
    row.todayTargetReaches > 0 && row.todayTargetReaches == row.totalActiveHabits ->
        stringResource(R.string.summary_title_perfect_day)
    row.todayTargetReaches > 0 ->
        stringResource(R.string.summary_title_todays_wins)
    row.todayProgressUpdates > 0 ->
        stringResource(R.string.summary_title_some_progress)
    else ->
        stringResource(R.string.summary_title_fresh_start)
}

/** Mirrors [DailySummaryWorker.localizedBody] but via Compose string resources. */
@Composable
private fun localizedSummaryBody(row: DailySummaryEntity): String {
    val title = localizedSummaryTitle(row)
    val parts = remember(row) { mutableListOf<String>() }.also { it.clear() }

    val habitsTargetLine = if (row.todayTargetReaches > 0)
        stringResource(R.string.summary_line_habits_target, row.todayTargetReaches, row.totalActiveHabits)
    else if (row.totalActiveHabits > 0)
        stringResource(R.string.summary_line_no_target, row.totalActiveHabits)
    else null

    habitsTargetLine?.let { parts += it }

    val extraCheckins = row.todayProgressUpdates - row.todayTargetReaches
    if (extraCheckins > 0) {
        parts += stringResource(R.string.summary_line_checkins, extraCheckins)
    }

    if (row.achievementsUnlockedToday > 0) {
        parts += pluralStringResource(
            R.plurals.summary_line_achievements,
            row.achievementsUnlockedToday,
            row.achievementsUnlockedToday
        )
    }

    parts += stringResource(R.string.summary_line_week_pct, row.weekCompletionPct)

    val encouragement = when {
        row.todayTargetReaches == row.totalActiveHabits && row.totalActiveHabits > 0 ->
            stringResource(R.string.summary_enc_perfect)
        row.todayTargetReaches > 0 ->
            stringResource(R.string.summary_enc_good_work, row.todayTargetReaches)
        row.todayProgressUpdates > 0 ->
            stringResource(R.string.summary_enc_moved_needle)
        else ->
            stringResource(R.string.summary_enc_no_completions)
    }

    return buildString {
        appendLine(title)
        appendLine()
        parts.forEach { appendLine("• $it") }
        appendLine()
        append(encouragement)
    }.trimEnd()
}

@Composable
private fun EmptyInbox(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.empty_summaries),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
