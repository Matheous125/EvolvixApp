# Project Structure & Architecture

**Base Package:** `app/src/main/java/com/example/evolvix`

This project follows a strict MVVM (Model-View-ViewModel) architecture. 
When creating NEW files, you MUST place them in the correct directory under the base package:

## Directory Map
```text
app/src/main/java/com/example/evolvix
├── data/                   # The "Model" Layer - Data & Persistence
│   ├── local/              # Room Database
│   │   ├── AppDatabase.kt  # Room Database setup
│   │   ├── Converters.kt   # Type converters for Room
│   │   ├── HabitDao.kt     # Database Access Object for habits + completion queries (CRUD)
│   │   └── Migration.kt    # Database schema migrations
│   └── model/              # Database entity classes
│       ├── HabitEntity.kt  # Habit table entity
│       ├── HabitCompletionEntity.kt  # Habit completion records
│       └── HabitFrequency.kt  # Habit frequency enum/model
│
├── domain/                 # Domain Layer - Business Logic
│   └── model/              # Domain models and state classes
│       ├── FormError.kt    # Domain model for Form Errors
│       ├── HabitData.kt    # Domain model for Habit data
│       └── HabitUiState.kt # UI state data class
│
├── navigation/             # Navigation Configuration
│   ├── NavGraph.kt         # Compose navigation graph setup
│   └── Screen.kt           # Screen route definitions
│
├── ui/                     # The "View" Layer (Jetpack Compose)
│   ├── components/         # Reusable Compose widgets
│       ├── PauseBottomSheet.kt    # Modal bottom sheet with date picker for habit pausing
│   │   └── ProgressItem.kt # Animated progress bar row (title + x/y)
│   ├── screens/            # Main UI screens (e.g., HomeScreen.kt)
│   │   ├── AddNewHabitScreen.kt  # Create habit form
│   │   ├── EditHabitScreen.kt  # Edit habit details form
│   │   ├── MainScreen.kt  # Habit list with interactions
│   │   └── StatisticsScreen.kt  # Analytics and charts
│   ├── theme/              # Colors, Typography, Shapes
│   │   ├── Color.kt  # Color definitions
│   │   ├── HabitColorScheme.kt  # Habit color scheme helpers
│   │   ├── Theme.kt  # App theme configuration
│   │   └── Type.kt  # Typography styles
│   └── viewmodel/          # The "ViewModel" Layer - UI Logic
│       ├── HabitViewModel.kt  # Business logic & state
│       └── HabitViewModelFactory.kt  # ViewModel creation
│
└── MainActivity.kt         # Entry point
```