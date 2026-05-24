# Project Structure & Architecture

**Base Package:** `app/src/main/java/com/example/evolvix`

This project follows a strict MVVM (Model-View-ViewModel) architecture. 
When creating NEW files, you MUST place them in the correct directory under the base package:

## Directory Map
```text
app/src/main/java/com/example/evolvix
├── data/                   # The "Model" Layer - Data & Persistence
│   ├── local/              # Room Database
│   │   ├── AchievementDao.kt    # DAO for achievement persistence; includes one-shot batch query for retraction
│   │   ├── AppDatabase.kt       # Room Database setup (singleton via companion object)
│   │   ├── Converters.kt        # Type converters for Room (LocalDate, LocalDateTime, etc.)
│   │   ├── DailySummaryDao.kt   # DAO for daily summary inbox cards; Flow-based reads for SummaryInboxViewModel + unread-badge count
│   │   ├── DatabaseSeeder.kt    # Dev-only seeder inserting 5 test habits (IDs 901–905) with realistic AI-scenario completion histories
│   │   ├── HabitDao.kt          # DAO for habits + completions (CRUD, streak queries, history queries)
│   │   └── Migration.kt         # Room schema migrations
│   └── model/              # Database entity + in-memory data classes
│       ├── AchievementEntity.kt      # Room entity for the achievements table
│       ├── DailySummaryEntity.kt     # Room entity for one daily-summary inbox card; unique index on date; tracks read/unread state (Phase 7.2)
│       ├── HabitCompletionEntity.kt  # Room entity for individual habit completion records
│       ├── HabitEntity.kt            # Room entity for habits (name, target, frequency, category, color, reminderTime, etc.)
│       ├── HabitFrequency.kt         # Enum: DAILY / WEEKLY / MONTHLY
│       └── HabitTemplate.kt          # In-memory value object for pre-built habit suggestions shown on AddNewHabitScreen; NOT a Room entity
│
├── domain/                 # Domain Layer - Business Logic
│   ├── ai/                 # AI / analytics abstraction (Strategy + DI pattern)
│   │   ├── AbandonmentFeatures.kt  # 7-field input feature vector for the HabitAbandonmentClassifier TFLite model (Phase 8.1); field order mirrors abandonment_scaler.json
│   │   ├── AiContainer.kt         # Process-wide singleton provider for HabitPredictor (analogous to AppDatabase.getDatabase); lazy init with double-checked locking
│   │   ├── HabitFeatures.kt       # 7-field input feature vector for the HabitSuccessClassifier TFLite model; field order mirrors success_scaler.json
│   │   ├── HabitPredictor.kt      # Interface defining all predictive + passive-analytics contracts (success probability, optimal time, routine precision, etc.)
│   │   ├── MathHabitPredictor.kt  # Rule-based / statistical implementation of HabitPredictor; pure Kotlin, no Android SDK, fully unit-testable
│   │   ├── ReminderContext.kt     # 7-field input feature vector for the ReminderTemplateClassifier TFLite model; field order mirrors reminder_scaler.json
│   │   ├── StreakBreakFeatures.kt  # 7-field input feature vector for the StreakBreakClassifier TFLite model (Phase 8.2); field order mirrors streak_break_scaler.json
│   │   ├── TfliteHabitPredictor.kt # TFLite implementation of HabitPredictor; falls back to MathHabitPredictor for non-ML methods (Strategy swap)
│   │   └── WeeklyForecastFeatures.kt # 12-field input feature vector for the WeeklyForecastRegressor TFLite model (Phase 8.3); toFloatArray() returns fields in scaler order
│   ├── auth/               # Authentication contracts (Dependency Inversion; swappable impl)
│   │   ├── AuthRepository.kt      # Interface defining all auth operations (login, register, resetPassword, changePassword, logout); returns Result<Unit>
│   │   └── FakeAuthRepository.kt  # In-memory stub implementation of AuthRepository used during development (Phase 9); replaced by FirebaseAuthRepository in Phase 10
│   ├── model/              # Domain models and state classes
│   │   ├── AbandonmentRisk.kt        # Output of AbandonmentRiskUseCase (Phase 8.1); wraps raw probability into a Rating tier (LOW/MEDIUM/HIGH/CRITICAL) + data-sufficiency flag
│   │   ├── AchievementDefinition.kt  # Sealed class hierarchy of all 50 achievement definitions (key, points, threshold, group)
│   │   ├── DifficultyAdjustment.kt   # Output of AdaptiveDifficultyUseCase; bundles delta (+1/0/-1), rolling rate, current and suggested target
│   │   ├── FormError.kt              # Domain model for inline form validation errors (e.g. duplicate habit name)
│   │   ├── HabitClash.kt             # Output of HabitClashingUseCase; a pair of negatively-correlated (Pearson r) habits
│   │   ├── HabitData.kt              # Lightweight domain model for a habit; decouples business logic from HabitEntity
│   │   ├── HabitRecommendation.kt    # Output of HabitRecommendationUseCase; co-occurring related habit names + data-sufficiency flag
│   │   ├── HabitUiState.kt           # Composite UI state data class consumed by HabitViewModel and MainScreen
│   │   ├── LifeBalanceEntry.kt       # Per-category completion rate + habit count; output of LifeBalanceUseCase
│   │   ├── MotivationMessage.kt      # Output of MotivationMessageUseCase; strings.xml plural key + streak count for View-layer resolution
│   │   ├── OptimalTimePrediction.kt  # Output of OptimalTimeUseCase; ranked top hours + 24-bucket histogram + data-sufficiency flag
│   │   ├── PerHabitStats.kt          # Bundles streak, 30-day sparkline, and completion rate for one habit; output of StatisticsViewModel.perHabitStats
│   │   ├── ProcrastinationIndex.kt   # Output of ProcrastinationIndexUseCase; skewness of completion hour-of-day distribution + qualitative rating
│   │   ├── ResilienceScore.kt        # Output of ResilienceScoreUseCase; avg missed periods per recovery event + qualitative rating
│   │   ├── RoutinePrecision.kt       # Output of RoutinePrecisionUseCase; stddev of completion times in minutes + qualitative rating
│   │   ├── SortMode.kt               # Enum defining the 7 sort/group modes for the habit list
│   │   ├── SparklinePoint.kt         # Single chart data point (date + reached flag); output of SparklineUseCase
│   │   ├── StreakBreakRisk.kt        # Output of StreakBreakUseCase (Phase 8.2); wraps raw streak-break probability into a Rating tier + data-sufficiency flag
│   │   ├── StreakResult.kt           # Holds current + best streak counts for a single habit; output of CalculateStreakUseCase
│   │   ├── StreakRiskAssessment.kt   # Output of StreakRecoveryUseCase; isAtRisk flag, specific at-risk weekdays, data-sufficiency flag
│   │   ├── SuccessPrediction.kt      # Output of SuccessProbabilityUseCase; probability [0.05–0.95] + five explicit input feature values
│   │   ├── WeeklyForecast.kt         # Output of WeeklyForecastUseCase (Phase 8.3); predictedRate, lastWeekRate, Direction (UP/FLAT/DOWN), confidence, hasSufficientData
│   │   └── WeeklyOverview.kt         # 7-day aggregated summary (DaySummary list + week rate); output of WeeklyOverviewUseCase
│   └── usecase/
│       ├── AbandonmentRiskUseCase.kt       # Interactor: extracts 7 AbandonmentFeatures from Room data, delegates to HabitPredictor, maps probability → AbandonmentRisk (Phase 8.1)
│       ├── AdaptiveDifficultyUseCase.kt    # Interactor: suggests target +1/0/-1 based on 14-day rolling rate; delegates to HabitPredictor
│       ├── CalculateStreakUseCase.kt        # Interactor: computes current + best streak; pure function with injectable today date
│       ├── ComposeDailySummaryUseCase.kt   # Interactor: pure function composing today's raw data into a DailySummaryEntity for notification + inbox (Phase 7.2)
│       ├── EvaluateAchievementsUseCase.kt  # Interactor: (habits, completions) → Set<UnlockedAchievement>; runs all 50 achievement rules (Strategy pattern)
│       ├── ExportHistoryUseCase.kt         # Interactor: serializes a habit's completion history to JSON; triggers ACTION_CREATE_DOCUMENT
│       ├── HabitClashingUseCase.kt         # Interactor: detects negatively-correlated habit pairs via Pearson r; delegates to HabitPredictor
│       ├── HabitRecommendationUseCase.kt   # Interactor: recommends co-occurring related habits based on support-based association rules; delegates to HabitPredictor
│       ├── IconResolverUseCase.kt          # Interactor: resolves an emoji icon from a habit name; Tier 1 = keyword map, Tier 2 = TFLite icon classifier
│       ├── LifeBalanceUseCase.kt           # Interactor: groups habits by category and computes per-category completion rates (default 30-day window)
│       ├── MotivationMessageUseCase.kt     # Interactor: selects a context-aware motivation message key (streak + day-of-week); delegates to HabitPredictor
│       ├── OptimalTimeUseCase.kt           # Interactor: bins completions into a 24-bucket hour histogram; delegates top-hour ranking to HabitPredictor
│       ├── ProcrastinationIndexUseCase.kt  # Interactor: computes skewness of completion hour-of-day distribution; delegates to HabitPredictor
│       ├── ResilienceScoreUseCase.kt       # Interactor: measures bounce-back speed (avg missed periods per recovery event); delegates to HabitPredictor
│       ├── RoutinePrecisionUseCase.kt      # Interactor: computes stddev of completion times in minutes (clock-consistency); delegates to HabitPredictor
│       ├── ScheduleReminderUseCase.kt      # Interactor: schedules/cancels per-habit WorkManager reminder workers with personalised timing (optimalHours or user-set time)
│       ├── SparklineUseCase.kt             # Interactor: produces a List<SparklinePoint> (reached flag per calendar day) for a given habit and date range
│       ├── StreakBreakUseCase.kt           # Interactor: extracts 7 StreakBreakFeatures, guards against zero-streak, delegates to HabitPredictor, maps probability → StreakBreakRisk (Phase 8.2)
│       ├── StreakRecoveryUseCase.kt        # Interactor: detects high-risk streak patterns and which specific weekdays are consistently missed
│       ├── SuccessProbabilityUseCase.kt    # Interactor: estimates today's completion probability via HabitPredictor (TFLite HabitSuccessClassifier)
│       ├── WeeklyForecastUseCase.kt        # Interactor: extracts 12 WeeklyForecastFeatures, checks data sufficiency, delegates to HabitPredictor, wraps output in WeeklyForecast (Phase 8.3)
│       └── WeeklyOverviewUseCase.kt        # Interactor: aggregates completions into a 7-day WeeklyOverview (daily counts + week completion rate)
│
├── navigation/             # Navigation Configuration
│   ├── NavGraph.kt         # Compose navigation graph setup
│   └── Screen.kt           # Screen route definitions
│
├── notifications/          # WorkManager Workers, BroadcastReceivers, notification helpers (Phase 7)
│   ├── DailySummaryWorker.kt      # CoroutineWorker: composes + posts the daily summary notification; self-reschedules for next day; uses ComposeDailySummaryUseCase
│   ├── DebugTriggers.kt           # DEBUG-only helper scheduling workers with 3 s delay for demo/thesis-defence without waiting for real scheduled slots
│   ├── HabitActionReceiver.kt     # BroadcastReceiver for Done/Skip/Snooze taps on reminder notifications (Command pattern); writes to Room directly
│   ├── HabitReminderWorker.kt     # One-shot CoroutineWorker posting a per-habit reminder; selects message template via ReminderTemplateClassifier (TFLite)
│   ├── NotificationChannels.kt    # Centralised channel registry (singleton object); called before any post to guarantee channels exist
│   ├── OnboardingPreferences.kt   # SharedPreferences wrapper (singleton object) tracking whether the user has completed the onboarding flow; shared file with SummaryPreferences
│   ├── SummaryDismissReceiver.kt  # BroadcastReceiver tracking swipe-dismissals of the summary notification; auto-disables after 7 consecutive dismissals
│   └── SummaryPreferences.kt      # SharedPreferences wrapper for daily-summary state: dismissStreak counter + disabled flag
│
├── ui/                     # The "View" Layer (Jetpack Compose)
│   ├── components/         # Reusable Compose widgets
│   │   ├── AchievementBanner.kt   # Top-anchored sliding banner triggered by AchievementsViewModel.newlyUnlocked SharedFlow; overlays all screens via AppContent Box
│   │   ├── BarChart.kt            # Horizontally scrollable bar chart rendered with Canvas; used for the optimal-hours histogram on StatisticsScreen
│   │   ├── ConfettiOverlay.kt     # Full-screen Canvas confetti overlay with haptic feedback, triggered by HabitViewModel.celebrationEvent
│   │   ├── HabitContextMenu.kt    # Wraps content in a long-press–activated context menu for a single habit row
│   │   ├── PauseBottomSheet.kt    # Modal bottom sheet with date picker for habit pausing
│   │   ├── ProgressItem.kt        # Animated progress bar row (title + x/y count)
│   │   └── Sparkline.kt           # Canvas-based mini bar sparkline for 30-day completion trend; used in collapsed habit cards on StatisticsScreen
│   ├── screens/            # Main UI screens
│   │   ├── AchievementsScreen.kt  # Achievement list with collapsible groups
│   │   ├── AddNewHabitScreen.kt   # Create habit form (name, target, frequency, category, color, reminder time, habit templates)
│   │   ├── auth/                  # Authentication screens (Phase 9)
│   │   │   ├── LoginScreen.kt         # Email + password login form; delegates to AuthViewModel
│   │   │   ├── RegisterScreen.kt      # Account creation form; delegates to AuthViewModel
│   │   │   ├── ResetPasswordScreen.kt # Request password-reset e-mail form; delegates to AuthViewModel
│   │   │   └── SetNewPasswordScreen.kt # Confirm new password entry (deep-link target); delegates to AuthViewModel
│   │   ├── EditHabitScreen.kt     # Edit habit details form
│   │   ├── HistoryScreen.kt       # Browse, edit, and add habit completion history
│   │   ├── MainScreen.kt          # Habit list with completion interaction, sort/filter, context menu, unread-badge bell icon
│   │   ├── OnboardingScreen.kt    # Single-page onboarding shown once on first launch; sets OnboardingPreferences flag on "Get Started" tap
│   │   ├── SettingsScreen.kt      # App settings: theme mode selector (Light/Dark/System), daily-summary toggle, reminder management
│   │   ├── StatisticsScreen.kt    # Analytics: weekly overview, life balance, per-habit stats, AI insight cards
│   │   └── SummaryInboxScreen.kt  # Scrollable inbox of daily summary cards; mark-read / dismiss actions; unread badge driven by SummaryInboxViewModel
│   ├── theme/              # Colors, Typography, Shapes
│   │   ├── Color.kt             # Color definitions
│   │   ├── HabitColorScheme.kt  # Habit color scheme helpers
│   │   ├── Theme.kt             # App theme configuration (supports ThemeMode: Light/Dark/System)
│   │   └── Type.kt              # Typography styles
│   └── viewmodel/          # The "ViewModel" Layer - UI Logic
│       ├── AchievementsViewModel.kt        # Observes habits+completions Flow, runs EvaluateAchievementsUseCase, persists deltas; emits newlyUnlocked SharedFlow for AchievementBanner
│       ├── AchievementsViewModelFactory.kt # Factory for AchievementsViewModel (injects HabitDao + AchievementDao)
│       ├── AuthViewModel.kt               # State holder for all auth screens; exposes AuthUiState (isLoading, isAuthenticated, error, resetEmailSent); delegates to AuthRepository
│       ├── AuthViewModelFactory.kt        # Factory for AuthViewModel (injects AuthRepository)
│       ├── HabitViewModel.kt               # Central ViewModel: habit CRUD, streak recomputation, sort/filter state, pause, over-completion, form validation
│       ├── HabitViewModelFactory.kt        # Factory for HabitViewModel (injects HabitDao)
│       ├── HistoryViewModel.kt             # Scoped to a single habit; exposes grouped completions, delete/update/retroactive-insert operations
│       ├── HistoryViewModelFactory.kt      # Factory for HistoryViewModel (injects HabitDao + habitId)
│       ├── SettingsViewModel.kt            # AndroidViewModel managing ThemeMode (SharedPreferences) and daily-summary WorkManager worker toggling
│       ├── SettingsViewModelFactory.kt     # Factory for SettingsViewModel (injects Application context)
│       ├── StatisticsViewModel.kt          # Combines habits+completions Flows; runs all analytics use cases; exposes overview, lifeBalance, perHabitStats, AI cards as StateFlows
│       ├── StatisticsViewModelFactory.kt   # Factory for StatisticsViewModel (injects HabitDao)
│       └── SummaryInboxViewModel.kt        # AndroidViewModel exposing DailySummaryEntity list + unreadCount StateFlow; resets dismissStreak on open
│
└── MainActivity.kt         # Entry point; sets up NavGraph, AiContainer, NotificationChannels; handles deep-link from summary notification
```

## ml-training/

Off-device Python training pipeline. Scripts are run once on a developer machine;
outputs (`.tflite` + scaler JSON) are copied into `app/src/main/assets/`.

```
ml-training/
├── generate_abandonment_data.py    # Synthetic dataset for HabitAbandonmentClassifier (Phase 8.1)
├── generate_icon_data.py           # Synthetic dataset for HabitIconClassifier
├── generate_reminder_data.py       # Synthetic dataset for ReminderTemplateClassifier
├── generate_streak_break_data.py   # Synthetic dataset for StreakBreakClassifier (Phase 8.2)
├── generate_success_data.py        # Synthetic dataset for HabitSuccessClassifier
├── generate_weekly_forecast_data.py # Synthetic dataset for WeeklyForecastRegressor (Phase 8.3); 12 FEATURE_COLUMNS, label = next_week_rate
├── train_abandonment_model.py      # Trains + exports habit_abandonment_classifier.tflite + abandonment_scaler.json
├── train_icon_model.py             # Trains + exports habit_icon_classifier.tflite + icon_vocab.json
├── train_reminder_model.py         # Trains + exports reminder_template_classifier.tflite + reminder_scaler.json
├── train_streak_break_model.py     # Trains + exports streak_break_classifier.tflite + streak_break_scaler.json
├── train_success_model.py          # Trains + exports habit_success_classifier.tflite + success_scaler.json
├── train_weekly_forecast_model.py  # Trains + exports weekly_forecast_regressor.tflite + weekly_forecast_scaler.json; MAE loss, sigmoid output, threshold MAE ≤ 0.12 (Phase 8.3)
└── evaluate_models.py              # Thesis-grade evaluation report: loads .tflite artifacts, reproduces test split, computes metrics, saves plots to data/plots/
```