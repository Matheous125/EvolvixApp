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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.evolvix.navigation.Screen
import com.example.evolvix.ui.theme.HabitTracker3Theme
import com.example.evolvix.navigation.HabitNavGraph

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
 */
@Composable
fun AppContent() {
    val navController = rememberNavController() // Get a NavController
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
            // Show FAB only on the "Habits" screen (selectedItem == 1)
            if (currentRoute == Screen.Habits.route) {
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
            modifier = Modifier.padding(innerPadding)
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