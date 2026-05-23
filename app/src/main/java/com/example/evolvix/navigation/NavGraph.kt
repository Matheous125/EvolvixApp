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
import androidx.compose.ui.platform.LocalContext
import com.example.evolvix.notifications.OnboardingPreferences
import com.example.evolvix.ui.viewmodel.AchievementsViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.SettingsViewModel

/**
 * Main navigation graph for the application.
 * Defines all possible screen destinations and their connections.
 *
 * @param navController Controller that handles navigation between screens
 * @param modifier Optional modifier for the navigation host
 * @param habitViewModel Activity-scoped ViewModel passed down from [AppContent] so that
 *   [MainScreen] and [MainActivity] share the same instance.
 * @param achievementsViewModel Activity-scoped ViewModel shared with [AchievementsScreen] and
 *   [AchievementBanner] so both read the same [AchievementsViewModel.newlyUnlocked] SharedFlow.
 * @param settingsViewModel    Activity-scoped ViewModel that manages theme, language, and
 *   notification preferences — also shared with [SettingsScreen].
 */
@Composable
fun HabitNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel,
    achievementsViewModel: AchievementsViewModel,
    settingsViewModel: SettingsViewModel,
    /** Stops the pulsing FAB animation; called when the user navigates to AddNewHabit
     *  via any path (FAB tap or empty-state CTA). Hoisted to [AppContent] which owns the state. */
    onDismissFabHint: () -> Unit = {},
    /** Start destination determined once by [AppContent] from [OnboardingPreferences]. */
    startDestination: String = Screen.Habits.route
) {
    val context = LocalContext.current
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {

    // Main screen with habit list    
        composable(route = Screen.Habits.route) {
            MainScreen(
                habitViewModel = habitViewModel,
                onDismissFabHint = onDismissFabHint,
                onNavigateToAddHabit = {
                    navController.navigate(Screen.AddNewHabit.route)
                },
                onNavigateToEditHabit = { habitId ->
                    navController.navigate(Screen.EditHabit.createRoute(habitId))
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
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
                },
                onNavigateToInbox = {
                    navController.navigate(Screen.SummaryInbox.route)
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

        // Achievements screen — displays all 50 achievements grouped by category.
        composable(route = Screen.Achievements.route) {
            AchievementsScreen(viewModel = achievementsViewModel)
        }

        // Daily-summary inbox (Phase 7.2 v2)
        composable(route = Screen.SummaryInbox.route) {
            SummaryInboxScreen(onNavigateBack = { navController.navigateUp() })
        }

        // Settings screen (Phase 8)
        composable(route = Screen.Settings.route) {
            SettingsScreen(
                settingsViewModel   = settingsViewModel,
                achievementsViewModel = achievementsViewModel,
                onNavigateBack      = { navController.navigateUp() }
            )
        }

        // Onboarding screen — shown once on first launch (Phase 8).
        // On "Get Started": persist the completion flag and navigate to Habits,
        // popping the onboarding entry so Back never returns here.
        composable(route = Screen.Onboarding.route) {
            OnboardingScreen(
                onGetStarted = {
                    OnboardingPreferences.setCompleted(context)
                    navController.navigate(Screen.Habits.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
    }
}