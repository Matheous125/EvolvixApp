package com.example.evolvix.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.evolvix.ui.screens.*
import androidx.navigation.NavType
import androidx.navigation.navArgument
import android.util.Log
import com.example.evolvix.ui.viewmodel.HabitViewModel

/**
 * Main navigation graph for the application.
 * Defines all possible screen destinations and their connections.
 *
 * @param navController Controller that handles navigation between screens
 * @param modifier Optional modifier for the navigation host
 * @param habitViewModel Activity-scoped ViewModel passed down from [AppContent] so that
 *   [MainScreen] and [MainActivity] share the same instance.
 */
@Composable
fun HabitNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Habits.route,
        modifier = modifier
    ) {

    // Main screen with habit list    
        composable(route = Screen.Habits.route) {
            MainScreen(
                habitViewModel = habitViewModel,
                onNavigateToAddHabit = {
                    navController.navigate(Screen.AddNewHabit.route)
                },
                onNavigateToEditHabit = { habitId ->
                    navController.navigate(Screen.EditHabit.createRoute(habitId))
                },
                onNavigateToSettings = {
                    // Settings screen not yet implemented — placeholder
                },
                onNavigateToStatistics = {
                    // Use the same tab-switch pattern as the BottomNav so the
                    // back stack stays flat (habits is not stacked under statistics).
                    navController.navigate(Screen.Statistics.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onNavigateToHistory = { habitId, habitName ->
                    navController.navigate(Screen.History.createRoute(habitId, habitName))
                }
            )
        }

        // Add new habit screen
        composable(route = Screen.AddNewHabit.route) {
            AddNewHabitScreen(
                onNavigateBack = {
                    navController.navigateUp()
                }
            )
        }

        // Statistics screen
        composable(route = Screen.Statistics.route) {
            StatisticsScreen()
        }

        // Edit habit screen with habitId parameter
        composable(
            route = Screen.EditHabit.route,
            arguments = listOf(
                navArgument("habitId") {
                    type = NavType.IntType
                }
            )
        ) { backStackEntry ->
            val habitId = backStackEntry.arguments?.getInt("habitId")
            Log.d("NavGraph", "Received habitId: $habitId")
            if (habitId != null) {
                EditHabitScreen(
                    habitId = habitId,
                    onNavigateBack = {
                        navController.navigateUp()
                    }
                )
            }
        }

        // History screen — shows completion log for a single habit.
        // habitName is URL-encoded in Screen.History.createRoute() to safely
        // carry display strings (spaces, special chars) through the route string.
        composable(
            route = Screen.History.route,
            arguments = listOf(
                navArgument("habitId") { type = NavType.IntType },
                navArgument("habitName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val habitId = backStackEntry.arguments?.getInt("habitId")
            val habitName = backStackEntry.arguments?.getString("habitName")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: ""
            if (habitId != null) {
                HistoryScreen(
                    habitId = habitId,
                    habitName = habitName,
                    onNavigateUp = { navController.navigateUp() }
                )
            }
        }
    }
}