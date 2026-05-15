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
│   ├── model/              # Domain models and state classes
│   │   ├── AchievementDefinition.kt    # Sealed class hierarchy of all 50 achievement definitions (key, points, threshold, group)
│   │   ├── FormError.kt    # Domain model for Form Errors
│   │   ├── HabitData.kt    # Domain model for Habit data
│   │   ├── HabitUiState.kt # UI state data class
│   │   ├── SortMode.kt     # Enum defining sort options
│   │   └── StreakResult.kt # Domain model holding the computed streak metrics for a single habit
│   └── usecase/
│       ├──CalculateStreakUseCase.kt # Interactor responsible for computing streak metrics from a flat list of completion records.
│       EvaluateAchievementsUseCase.kt # Interactor responsible for evaluating which of the 50 achievements the user has earned
│   │   └──ExportHistoryUseCase.kt # Interactor responsible for serializing a habit's full completion history
|
├── navigation/             # Navigation Configuration
│   ├── NavGraph.kt         # Compose navigation graph setup
│   └── Screen.kt           # Screen route definitions
│
├── ui/                     # The "View" Layer (Jetpack Compose)
│   ├── components/         # Reusable Compose widgets
│       ├── HabitContextMenu.kt    # Wraps [content] in a long-press–activated context menu for a single habit row
│       ├── PauseBottomSheet.kt    # Modal bottom sheet with date picker for habit pausing
│   │   └── ProgressItem.kt # Animated progress bar row (title + x/y)
│   ├── screens/            # Main UI screens (e.g., HomeScreen.kt)
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
│       ├── AchievementsViewModel.kt  # Observes habits+completions Flow, runs EvaluateAchievementsUseCase, persists unlock/progress deltas reactively
│       ├── AchievementsViewModelFactory.kt  # Factory for AchievementsViewModel creation
│       ├── HabitViewModel.kt  # Business logic & state
│       ├── HabitViewModelFactory.kt  # ViewModel creation
│       ├── HistoryViewModel.kt  # History logic & state
│       └── HistoryViewModelFactory.kt  # ViewModel creation
│
└── MainActivity.kt         # Entry point
```