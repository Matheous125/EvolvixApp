package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.ui.components.ProgressItem
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Main screen of the application displaying the list of habits.
 * Supports tap to increment progress and long press to edit.
 *
 * @param modifier Modifier for screen customization
 * @param onNavigateToAddHabit Callback for navigation to add habit screen
 * @param onNavigateToEditHabit Callback for navigation to edit habit screen
 * @param habitViewModel ViewModel for habit operations
 */
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onNavigateToAddHabit: () -> Unit = {},
    onNavigateToEditHabit: (Int) -> Unit = {},
    
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

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Screen title
        Text(
            text = "My Habits",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.headlineMedium
        )

         // Scrollable list of habits
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(allHabitsUiState, key = { it.id }) { habit ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    habitViewModel.incrementHabitCompletion(habit.id)
                                },
                                onLongPress = {
                                    onNavigateToEditHabit(habit.id)
                                }
                            )
                        }
                ) {
                    ProgressItem(
                        title = habit.name,
                        maxClicks = habit.target,
                        currentClickCount = habit.currentCount,
                        colorScheme = habit.colorScheme,
                        isSystemInDarkTheme = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}