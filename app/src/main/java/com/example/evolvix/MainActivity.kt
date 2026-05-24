package com.example.evolvix

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.scale
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.ai.AiContainer
import com.example.evolvix.domain.usecase.ScheduleReminderUseCase
import com.example.evolvix.navigation.HabitNavGraph
import com.example.evolvix.navigation.Screen
import com.example.evolvix.notifications.DailySummaryWorker
import com.example.evolvix.notifications.NotificationChannels
import com.example.evolvix.notifications.OnboardingPreferences
import com.example.evolvix.ui.components.AchievementBanner
import com.example.evolvix.ui.components.FullScreenConfettiOverlay
import com.example.evolvix.ui.theme.EvolvixTheme
import com.example.evolvix.ui.viewmodel.AchievementsViewModel
import com.example.evolvix.ui.viewmodel.AchievementsViewModelFactory
import com.example.evolvix.ui.viewmodel.AuthViewModel
import com.example.evolvix.ui.viewmodel.AuthViewModelFactory
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import com.example.evolvix.ui.viewmodel.SettingsViewModel
import com.example.evolvix.ui.viewmodel.SettingsViewModelFactory
import com.example.evolvix.ui.viewmodel.ThemeMode
import com.example.evolvix.domain.auth.FakeAuthRepository

/**
 * Main entry point for the application.
 * Sets up the theme and initial navigation structure.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Phase 6.5.6 — pre-warm the TFLite-backed predictor so the three Interpreter
        // instances and JSON normalization tables load once during activity startup,
        // not lazily on the first Statistics-screen recomposition. This is the wiring
        // point referenced by PLAN.md §6.5.6: TfliteHabitPredictor(applicationContext,
        // MathHabitPredictor()) is constructed exactly here (inside AiContainer).
        AiContainer.predictor(applicationContext)

        // Phase 7 wiring — runs once on every cold start.
        //  • create notification channels so workers never hit "channel missing"
        //  • request POST_NOTIFICATIONS on Android 13+ (no-op below)
        //  • enqueue the periodic DailySummaryWorker (idempotent — KEEP policy)
        //  • re-arm any per-habit reminders that were enabled before the last restart,
        //    since WorkManager loses non-persistent OneTimeWorkRequests across reinstalls
        NotificationChannels.ensureCreated(applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        // Re-schedule reminders + enqueue summary on a background dispatcher.
        // Phase 7.2v2: both calls are now suspend (they hit Room for personalized
        // timing), so they must run inside a coroutine.
        lifecycleScope.launch(Dispatchers.IO) {
            DailySummaryWorker.enqueue(applicationContext)
            val dao = AppDatabase.getDatabase(applicationContext).habitDao()
            val scheduler = ScheduleReminderUseCase(applicationContext)
            dao.getAllHabitsOnce()
                .filter { it.reminderEnabled }
                .forEach { scheduler.schedule(it) }
        }
        setContent {
            AppContent()
        }
    }
}

/**
 * Main composable container for the application.
 * Handles navigation setup and bottom bar implementation.
 * The [HabitViewModel] is created here at Activity scope so that both this composable
 * and [MainScreen] share the same instance — allowing [MainActivity] to observe
 * [HabitViewModel.reorderMode] and hide the FAB during drag-and-drop reorder.
 * [AchievementsViewModel] is also created here at Activity scope so that [AchievementsScreen]
 * and [AchievementBanner] share the same instance and the same [AchievementsViewModel.newlyUnlocked]
 * SharedFlow — ensuring the banner fires regardless of which screen the user is on.
 * [SettingsViewModel] is created here too so [SettingsScreen] and [AppContent] share the
 * same theme state: the [ThemeMode] StateFlow drives [EvolvixTheme] at this level,
 * meaning a theme change takes effect immediately without restarting the Activity.
 * (Pattern: shared ViewModel via Activity-scoped [viewModel()])
 */
@Composable
fun AppContent() {
    val context = LocalContext.current

    // Activity-scoped settings VM — theme preference is read here before the theme wrapper.
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(
            application = context.applicationContext as android.app.Application
        )
    )
    val themeMode by settingsViewModel.themeMode.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> isSystemDark
    }

    EvolvixTheme(darkTheme = useDarkTheme) {

    // Activity-scoped ViewModel — shared with MainScreen via HabitNavGraph.
    val habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            application = context.applicationContext as android.app.Application,
            habitDao = AppDatabase.getDatabase(context).habitDao()
        )
    )
    // Activity-scoped ViewModel — shared with AchievementsScreen and AchievementBanner.
    val achievementsViewModel: AchievementsViewModel = viewModel(
        factory = AchievementsViewModelFactory(
            habitDao = AppDatabase.getDatabase(context).habitDao(),
            achievementDao = AppDatabase.getDatabase(context).achievementDao()
        )
    )

    // Activity-scoped ViewModel for all authentication screens (Phase 9).
    // Phase 9 uses FakeAuthRepository (in-memory). Phase 10 swaps the factory
    // argument to FirebaseAuthRepository — no ViewModel code changes needed
    // (Pattern: Strategy + Dependency Inversion).
    val authViewModel: AuthViewModel = viewModel(
        factory = AuthViewModelFactory(FakeAuthRepository())
    )
    val reorderMode by habitViewModel.reorderMode.collectAsState()

    // Pulsing FAB state — true until the user taps the FAB for the first time.
    // Read synchronously from SharedPreferences (cached in-memory, no disk I/O).
    var fabHintShown by remember { mutableStateOf(OnboardingPreferences.fabHintShown(context)) }

    // infiniteTransition drives a gentle scale oscillation (1.0 → 1.13 → 1.0).
    // The transition is only composed when the hint is not yet shown; stopping it
    // avoids wasting animation frames after first use.
    val fabPulseTransition = rememberInfiniteTransition(label = "fabPulse")
    val fabPulseScale by fabPulseTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.13f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fabScale"
    )

    // Full-screen confetti visibility — set to true by the celebrationEvent collector below.
    var showCelebration by remember { mutableStateOf(false) }

    // Subscribes to the ViewModel's fire-and-forget SharedFlow. Each emission means a habit
    // just hit its target for the first time this cycle — trigger the confetti overlay.
    // (Pattern: Event Bus via SharedFlow — same as AchievementBanner / newlyUnlocked)
    LaunchedEffect(Unit) {
        habitViewModel.celebrationEvent.collect {
            showCelebration = true
        }
    }

    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Determine the start destination once at composition time.
    // Priority: Onboarding (first launch) → Login (not authenticated) → Habits.
    // SharedPreferences + StateFlow.value are both synchronous reads safe in remember{}.
    // (Pattern: Preferences as Repository — storage concern stays out of the View)
    val startDestination = remember {
        when {
            !OnboardingPreferences.isCompleted(context) -> Screen.Onboarding.route
            !authViewModel.uiState.value.isAuthenticated -> Screen.Login.route
            else -> Screen.Habits.route
        }
    }

    // Phase 7.2v2 — handle notification deep-link from DailySummaryWorker. When the
    // user taps the summary notification, MainActivity is (re)launched with an extra
    // that routes us to the inbox screen and marks that summary as read. We also
    // reset the dismiss-streak counter because a tap is the strongest "engaged" signal.
    val activity = context as? android.app.Activity
    LaunchedEffect(activity?.intent) {
        val intent = activity?.intent ?: return@LaunchedEffect
        val openInbox = intent.getBooleanExtra(
            com.example.evolvix.notifications.DailySummaryWorker.EXTRA_OPEN_SUMMARY_INBOX, false
        )
        if (openInbox) {
            intent.removeExtra(
                com.example.evolvix.notifications.DailySummaryWorker.EXTRA_OPEN_SUMMARY_INBOX
            )
            com.example.evolvix.notifications.SummaryPreferences
                .resetDismissStreak(context.applicationContext)
            val rowId = intent.getIntExtra(
                com.example.evolvix.notifications.DailySummaryWorker.EXTRA_SUMMARY_ROW_ID, -1
            )
            if (rowId > 0) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(context.applicationContext)
                        .dailySummaryDao().markRead(rowId)
                }
            }
            navController.navigate(Screen.SummaryInbox.route)
        }
    }
    // Determine the selected item based on the current route
    val selectedItem = when (currentRoute) {
        Screen.Achievements.route -> 0
        Screen.Habits.route       -> 1
        Screen.Statistics.route   -> 2
        else -> 1 // Default to Habits for sub-screens (AddHabit, EditHabit, History)
    }

    // Navigation items configuration — order: Achievements | Habits | Statistics
    val navLabels = listOf(
        stringResource(R.string.nav_achievements),
        stringResource(R.string.nav_habits),
        stringResource(R.string.nav_statistics)
    )
    val icons = listOf(Icons.Filled.EmojiEvents, Icons.Filled.Home, Icons.Filled.BarChart)

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Bottom navigation is hidden on the Onboarding screen and all auth screens
            // so the user cannot reach main destinations before authenticating.
            val noNavBarRoutes = setOf(
                Screen.Onboarding.route,
                Screen.Login.route,
                Screen.Register.route,
                Screen.ResetPassword.route,
                Screen.SetNewPassword.route
            )
            if (currentRoute !in noNavBarRoutes) {
            NavigationBar {
                navLabels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedItem == index,
                        onClick = {
                            val destinationScreen = when (index) {
                                0 -> Screen.Achievements
                                1 -> Screen.Habits
                                2 -> Screen.Statistics
                                else -> Screen.Habits
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
            } // end onboarding guard
        },
        floatingActionButton = {
            // FAB is visible only on the Habits screen and hidden during reorder mode
            // so the user cannot open AddHabit while rearranging the list.
            if (currentRoute == Screen.Habits.route && !reorderMode) {
                // Apply a gentle pulse scale on first launch until the user taps the FAB.
                // Compose `scale` modifier is cheap — the FAB is the only scaled element.
                FloatingActionButton(
                    onClick = {
                        if (!fabHintShown) {
                            fabHintShown = true
                            OnboardingPreferences.markFabHintShown(context)
                        }
                        navController.navigate(Screen.AddNewHabit.route)
                    },
                    modifier = Modifier.scale(if (fabHintShown) 1f else fabPulseScale)
                ) {
                    Icon(Icons.Filled.Add, "Add new habit")
                }
            }
        }
    ) { innerPadding ->
        HabitNavGraph(
            navController         = navController,
            modifier              = Modifier.padding(innerPadding),
            habitViewModel        = habitViewModel,
            achievementsViewModel = achievementsViewModel,
            settingsViewModel     = settingsViewModel,
            authViewModel         = authViewModel,
            onDismissFabHint      = {
                fabHintShown = true
                OnboardingPreferences.markFabHintShown(context)
            },
            startDestination      = startDestination
        )
    }
    // AchievementBanner overlays the entire app — shown on any screen when an
    // achievement is unlocked. Placed after Scaffold in the Box so it draws on top.
    AchievementBanner(viewModel = achievementsViewModel)

    // Full-screen confetti fires from the bottom of the screen when any habit reaches
    // its daily target. Placed last in the Box so it draws above the banner.
    FullScreenConfettiOverlay(
        visible   = showCelebration,
        onFinished = { showCelebration = false },
    )
    } // end Box
    } // end EvolvixTheme
}

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    AppContent()
}