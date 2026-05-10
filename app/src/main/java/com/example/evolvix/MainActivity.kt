package com.example.evolvix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.navigation.HabitNavGraph
import com.example.evolvix.navigation.Screen
import com.example.evolvix.ui.theme.HabitTracker3Theme
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory

/**
 * Main entry point for the application.
 * Sets up the theme and initial navigation structure.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HabitTracker3Theme {
                AppContent()
            }
        }
    }
}

/**
 * Main composable container for the application.
 * Handles navigation setup and bottom bar implementation.
 * The [HabitViewModel] is created here at Activity scope so that both this composable
 * and [MainScreen] share the same instance — allowing [MainActivity] to observe
 * [HabitViewModel.reorderMode] and hide the FAB during drag-and-drop reorder.
 * (Pattern: shared ViewModel via Activity-scoped [viewModel()])
 */
@Composable
fun AppContent() {
    val context = LocalContext.current
    // Activity-scoped ViewModel — shared with MainScreen via HabitNavGraph.
    val habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            application = context.applicationContext as android.app.Application,
            habitDao = AppDatabase.getDatabase(context).habitDao()
        )
    )
    val reorderMode by habitViewModel.reorderMode.collectAsState()

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Determine the selected item based on the current route
    val selectedItem = when (currentRoute) {
        Screen.Habits.route -> 0
        Screen.Statistics.route -> 1
        else -> 0 // Default to Habits screen if route is unknown (or AddNewHabit)
    }

    // Navigation items configuration
    val items = listOf("Habits", "Statistics")
    val icons = listOf(Icons.Filled.Home, Icons.Filled.BarChart)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = item) },
                        label = { Text(item) },
                        selected = selectedItem == index,
                        onClick = {
                            val destinationScreen = when (index) {
                                0 -> Screen.Habits
                                1 -> Screen.Statistics
                                else -> Screen.Habits // Default or handle error
                            }

                            if (currentRoute != destinationScreen.route) {
                                navController.navigate(destinationScreen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    restoreState = true
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            // FAB is visible only on the Habits screen and hidden during reorder mode
            // so the user cannot open AddHabit while rearranging the list.
            if (currentRoute == Screen.Habits.route && !reorderMode) {
                FloatingActionButton(onClick = {
                    navController.navigate(Screen.AddNewHabit.route)
                }) {
                    Icon(Icons.Filled.Add, "Add new habit")
                }
            }
        }
    ) { innerPadding ->
        HabitNavGraph(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
            habitViewModel = habitViewModel
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    HabitTracker3Theme {
        AppContent()
    }
}