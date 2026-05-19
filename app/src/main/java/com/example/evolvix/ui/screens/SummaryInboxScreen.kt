package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
                title = { Text("Daily summaries") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (unread > 0) {
                        TextButton(onClick = { viewModel.markAllRead() }) {
                            Text("Mark all read")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
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
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = row.body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
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
            text = "No summaries yet.\nCheck back tomorrow.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
