# Project Structure & Architecture

**Base Package:** `app/src/main/java/com/example/evolvix`

This project follows a strict MVVM (Model-View-ViewModel) architecture. 
When creating NEW files, you MUST place them in the correct directory under the base package:

## Directory Map
```text
app/src/main/java/com/example/evolvix
├── data/                   # The "Model" Layer - Data & Persistence
│   ├── local/              # Room Database
│   │   ├── AchievementDao.kt  # Data Access Object for achievement persistence; includes one-shot batch query for retraction
│   │   ├── AppDatabase.kt  # Room Database setup
│   │   ├── Converters.kt   # Type converters for Room
│   │   ├── HabitDao.kt     # Database Access Object for habits + completion queries (CRUD)
│   │   └── Migration.kt    # Database schema migrations
│   └── model/              # Database entity classes
│       ├── AchievementEntity.kt  # Achievement table entity
│       ├── HabitEntity.kt  # Habit table entity
│       ├── HabitCompletionEntity.kt  # Habit completion records
│       └── HabitFrequency.kt  # Habit frequency enum/model
│
├── domain/                 # Domain Layer - Business Logic
│   ├── ai/                 # AI / analytics abstraction (Strategy + DI pattern)
│   │   ├── HabitPredictor.kt      # Interface defining all predictive (6.2) and passive-analytics (6.3) contracts; expanded in Phase 6.5 with TFLite methods
│   │   ├── MathHabitPredictor.kt  # Rule-based / statistical implementation of HabitPredictor; pure Kotlin, no Android SDK, fully unit-testable
│   │   └── TfliteHabitPredictor.kt # Stub implementation delegating to MathHabitPredictor; Phase 6.5 will override ML methods with TFLite interpreters (Strategy swap, no ViewModel changes)
│   ├── model/              # Domain models and state classes
│   │   ├── AchievementDefinition.kt  # Sealed class hierarchy of all 50 achievement definitions (key, points, threshold, group)
│   │   ├── FormError.kt              # Domain model for inline form validation errors (e.g. duplicate habit name)
│   │   ├── HabitData.kt              # Lightweight domain model for a habit; decouples business logic from HabitEntity
│   │   ├── HabitUiState.kt           # Composite UI state data class consumed by HabitViewModel and the main habit list screen
│   │   ├── LifeBalanceEntry.kt       # Per-category completion rate + habit count; output of LifeBalanceUseCase
│   │   ├── PerHabitStats.kt          # Bundles streak, 30-day sparkline, and completion rate for one habit; output of StatisticsViewModel.perHabitStats
│   │   ├── SortMode.kt               # Enum defining the 7 sort/group modes for the habit list
│   │   ├── SparklinePoint.kt         # Single chart data point (date + reached flag); output of SparklineUseCase
│   │   ├── StreakResult.kt           # Holds current + best streak counts for a single habit; output of CalculateStreakUseCase
│   │   └── WeeklyOverview.kt         # 7-day aggregated summary (DaySummary list + week rate); output of WeeklyOverviewUseCase
│   └── usecase/
│       ├── CalculateStreakUseCase.kt   # Interactor: computes current + best streak from a flat completion list; pure function, injectable today date for testing
│       ├── EvaluateAchievementsUseCase.kt # Interactor: pure function (habits, completions) → Set<UnlockedAchievement>; runs all 50 achievement rules (Strategy pattern)
│       ├── ExportHistoryUseCase.kt    # Interactor: serializes a habit's completion history to JSON via kotlinx.serialization; triggers ACTION_CREATE_DOCUMENT
│       ├── WeeklyOverviewUseCase.kt   # Interactor: aggregates completions into a 7-day WeeklyOverview (daily counts + week completion rate)
│       ├── LifeBalanceUseCase.kt      # Interactor: groups habits by category and computes per-category completion rates over a rolling window (default 30 days)
        ├── SparklineUseCase.kt        # Interactor: produces a List<SparklinePoint> (reached flag per calendar day) for a given habit and date range
        └── IconResolverUseCase.kt     # Interactor: resolves an emoji icon from a habit name; Tier 1 = keyword map (~70% coverage), Tier 2 = stub for Phase 6.5 TFLite classifier
|
├── navigation/             # Navigation Configuration
│   ├── NavGraph.kt         # Compose navigation graph setup
│   └── Screen.kt           # Screen route definitions
│
├── ui/                     # The "View" Layer (Jetpack Compose)
│   ├── components/         # Reusable Compose widgets
│       ├── AchievementBanner.kt   # Top-anchored sliding banner triggered by AchievementsViewModel.newlyUnlocked SharedFlow; overlays all screens via AppContent Box
│       ├── ConfettiOverlay.kt  # Full-screen Canvas confetti overlay with haptic feedback, triggered by HabitViewModel.celebrationEvent when a habit hits its daily target
│       ├── HabitContextMenu.kt    # Wraps [content] in a long-press–activated context menu for a single habit row
│       ├── PauseBottomSheet.kt    # Modal bottom sheet with date picker for habit pausing
│   │   └── ProgressItem.kt # Animated progress bar row (title + x/y)
│   ├── screens/            # Main UI screens (e.g., HomeScreen.kt)
│   │   ├── AchievementsScreen.kt  # Achievement list with colapsible groups
│   │   ├── AddNewHabitScreen.kt  # Create habit form
│   │   ├── EditHabitScreen.kt  # Edit habit details form
│   │   ├── HistoryScreen.kt  # Brows, edit, add habit history
│   │   ├── MainScreen.kt  # Habit list with interactions
│   │   └── StatisticsScreen.kt  # Analytics and charts
│   ├── theme/              # Colors, Typography, Shapes
│   │   ├── Color.kt  # Color definitions
│   │   ├── HabitColorScheme.kt  # Habit color scheme helpers
│   │   ├── Theme.kt  # App theme configuration
│   │   └── Type.kt  # Typography styles
│   └── viewmodel/          # The "ViewModel" Layer - UI Logic
│       ├── AchievementsViewModel.kt       # Observes habits+completions Flow, runs EvaluateAchievementsUseCase, persists unlock/progress deltas reactively; emits newlyUnlocked SharedFlow for AchievementBanner
│       ├── AchievementsViewModelFactory.kt # Factory for AchievementsViewModel (injects HabitDao + AchievementDao)
│       ├── HabitViewModel.kt              # Central ViewModel: habit CRUD, streak recomputation, sort/filter state, pause, over-completion, form validation
│       ├── HabitViewModelFactory.kt       # Factory for HabitViewModel (injects HabitDao)
│       ├── HistoryViewModel.kt            # Scoped to a single habit; exposes grouped completions, delete/update/retroactive-insert operations
│       ├── HistoryViewModelFactory.kt     # Factory for HistoryViewModel (injects HabitDao + habitId)
│       ├── StatisticsViewModel.kt         # Combines habits+completions Flows and runs WeeklyOverviewUseCase, LifeBalanceUseCase, SparklineUseCase + CalculateStreakUseCase to expose overview, lifeBalance, perHabitStats StateFlows
│       └── StatisticsViewModelFactory.kt  # Factory for StatisticsViewModel (injects HabitDao)
│
└── MainActivity.kt         # Entry point
```