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

/**
 * Main navigation graph for the application.
 * Defines all possible screen destinations and their connections.
 *
 * @param navController Controller that handles navigation between screens
 * @param modifier Optional modifier for the navigation host
 */
@Composable
fun HabitNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Habits.route,
        modifier = modifier
    ) {

    // Main screen with habit list    
        composable(route = Screen.Habits.route) {
            MainScreen(
                onNavigateToAddHabit = {
                    navController.navigate(Screen.AddNewHabit.route)
                },
                onNavigateToEditHabit = { habitId ->
                    navController.navigate(Screen.EditHabit.createRoute(habitId))
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
    }
}