package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.ui.components.HabitContextMenu
import com.example.evolvix.ui.components.ProgressItem
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory

/**
 * Main screen of the application displaying the list of habits.
 * Supports tap to increment progress and long press to edit.
 *
 * @param modifier Modifier for screen customization
 * @param onNavigateToAddHabit Callback for navigation to add habit screen
 * @param onNavigateToEditHabit Callback for navigation to edit habit screen
 * @param onNavigateToSettings Callback for navigation to settings screen (not yet implemented)
 * @param onNavigateToStatistics Callback for navigation to statistics screen
 * @param onNavigateToHistory Callback for navigation to history screen (Phase 3.1 stub)
 * @param onTriggerReorder Callback to activate drag & drop reorder mode (Phase 2.4 stub)
 * @param habitViewModel ViewModel for habit operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onNavigateToAddHabit: () -> Unit = {},
    onNavigateToEditHabit: (Int) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToHistory: (Int) -> Unit = {},
    onTriggerReorder: () -> Unit = {},
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
) {
    // Check and reset progress on screen launch
    LaunchedEffect(Unit) {
        habitViewModel.checkAndResetProgress()
    }

    val allHabitsUiState by habitViewModel.allHabits.collectAsState()

    Scaffold(
        topBar = {
            // TopAppBar acts as the standardized header across all screens (Composition over inheritance)
            TopAppBar(
                title = { Text("My Habits") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0),
                actions = {
                    // Placeholder for future Settings screen navigation
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        // Scrollable list of habits
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(allHabitsUiState, key = { it.id }) { habit ->
                // HabitContextMenu wraps each row — single tap = mark progress,
                // long press = opens the 7-action DropdownMenu (IDEAS.MD §4.4)
                HabitContextMenu(
                    habit = habit,
                    onMarkProgress = { habitViewModel.incrementHabitCompletion(habit.id) },
                    onNavigateToStatistics = onNavigateToStatistics,
                    onPauseUntil = { until -> habitViewModel.pauseHabit(habit.id, until) },
                    onResume = { habitViewModel.resumeHabit(habit.id) },
                    onNavigateToHistory = { onNavigateToHistory(habit.id) },
                    onNavigateToEdit = { onNavigateToEditHabit(habit.id) },
                    onDelete = {
                        habitViewModel.deleteHabit(habit.id, onSuccess = {}, onError = {})
                    },
                    onTriggerReorder = onTriggerReorder
                ) {
                    ProgressItem(
                        title = habit.name,
                        maxClicks = habit.target,
                        currentClickCount = habit.currentCount,
                        colorScheme = HabitColorScheme.fromHex(habit.colorHex),
                        isOverCompleted = habit.isOverCompleted,
                        isPaused = habit.pausedUntil != null,
                        isSystemInDarkTheme = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}