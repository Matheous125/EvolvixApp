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
│   │   ├── AppDatabase.kt       # Room Database setup (singleton via companion object); version = 20 after Phase 9.5 schema change (adds habit_skips table)
│   │   ├── Converters.kt        # Type converters for Room (LocalDate, LocalDateTime, etc.); Phase 9.5 adds SkipReason ↔ String converters
│   │   ├── DailySummaryDao.kt   # DAO for daily summary inbox cards; Flow-based reads for SummaryInboxViewModel + unread-badge count
│   │   ├── DatabaseSeeder.kt    # Dev-only seeder inserting 5 test habits (IDs 901–905) with realistic AI-scenario completion histories
│   │   ├── HabitDao.kt          # DAO for habits + completions (CRUD, streak queries, history queries); Phase 9.4 adds getCompletionsWithDifficulty(habitId) — WHERE perceivedDifficulty IS NOT NULL ORDER BY progressUpdate DESC; Phase 9.4 adds updateCompletion(completion) used by rateLastCompletion
│   │   ├── HabitSkipDao.kt      # DAO for the habit_skips table (Phase 9.5); insert(skip), getForHabit(habitId): Flow, getRecentForHabit(habitId, since): List, getAllRecent(since): List
│   │   ├── Migration.kt         # Room schema migrations
│   │   └── TargetHistoryDao.kt  # DAO for the HabitTargetHistoryEntity table (Phase 9.3); insert + Flow-based read of per-habit target-change history; used by TargetAdjustmentUseCase to derive previousDelta and periodsSinceLastChange features
│   └── model/              # Database entity + in-memory data classes
│       ├── AchievementEntity.kt      # Room entity for the achievements table
│       ├── DailySummaryEntity.kt     # Room entity for one daily-summary inbox card; unique index on date; tracks read/unread state (Phase 7.2)
│       ├── HabitCompletionEntity.kt  # Room entity for individual habit completion records; Phase 9.4 adds perceivedDifficulty: Int? (nullable, default null) for user star-chip ratings
│       ├── HabitEntity.kt            # Room entity for habits (name, target, frequency, category, color, reminderTime, etc.)
│       ├── HabitFrequency.kt         # Enum: DAILY / WEEKLY / MONTHLY
│       ├── HabitSkipEntity.kt        # Room entity for the habit_skips table (Phase 9.5); FK to HabitEntity (CASCADE); fields: id (PK autoGenerate), habitId, skippedAt: LocalDateTime, reason: SkipReason; indices on habitId and skippedAt
│       ├── HabitTargetHistoryEntity.kt # Room entity recording every change to a habit's target value (Phase 9.3); columns: id, habitId (FK CASCADE), oldTarget, newTarget, changedAt, version; inserted by HabitViewModel.updateHabit whenever HabitEntity.target changes
│       ├── HabitTemplate.kt          # In-memory value object for pre-built habit suggestions shown on AddNewHabitScreen; NOT a Room entity
│       └── SkipReason.kt             # 6-value enum (Phase 9.5): TOO_TIRED, TOO_BUSY, FORGOT, SICK, TRAVELING, NO_REASON; isInvoluntary: Boolean (SICK/TRAVELING=true, excluded from ResilienceScoreUseCase gap math); displayLabel: String for UI chips
│
├── domain/                 # Domain Layer - Business Logic
│   ├── ai/                 # AI / analytics abstraction (Strategy + DI pattern)
│   │   ├── AbandonmentFeatures.kt  # 7-field input feature vector for the HabitAbandonmentClassifier TFLite model (Phase 8.1); field order mirrors abandonment_scaler.json
│   │   ├── AiContainer.kt         # Process-wide singleton provider for HabitPredictor (analogous to AppDatabase.getDatabase); lazy init with double-checked locking
│   │   ├── ClusterFeatures.kt     # 5-field input feature vector for K-Means Behavioral Clustering (Phase 8.4); field order mirrors habit_clusters.json → feature_columns; null-substitution done by BehavioralClusterUseCase before construction
│   │   ├── DifficultyFeatures.kt  # 8-field input feature vector for the PerceivedDifficultyRegressor TFLite model (Phase 9.4); field order mirrors perceived_difficulty_scaler.json; fields: dayOfWeek, hourOfDay, currentStreak, completionRateLast7Days, completionRateLast30Days, habitAgeDays, targetCount, avgProgressRatio30d
│   │   ├── HabitFeatures.kt       # 7-field input feature vector for the HabitSuccessClassifier TFLite model; field order mirrors success_scaler.json
│   │   ├── HabitPredictor.kt      # Interface defining all predictive + passive-analytics contracts (success probability, optimal time, routine precision, behavioral clustering, etc.); Phase 9.4 adds predictPerceivedDifficulty(DifficultyFeatures): Float; Phase 9.5 adds predictSkipReason(SkipReasonFeatures): Map<SkipReason,Float>
│   │   ├── MathHabitPredictor.kt  # Rule-based / statistical implementation of HabitPredictor; pure Kotlin, no Android SDK, fully unit-testable; Phase 9.5 adds predictSkipReason: 6 logit priors softmax-normalised
│   │   ├── ReminderContext.kt     # 7-field input feature vector for the ReminderTemplateClassifier TFLite model; field order mirrors reminder_scaler.json
│   │   ├── ReminderLiftFeatures.kt # 8-field input feature vector for the ReminderLiftClassifier TFLite model (Phase 9.1); last field reminderSent ∈ {0,1} allows dual-inference for lift computation
│   │   ├── SkipReasonFeatures.kt  # 8-field input feature vector for the SkipReasonClassifier TFLite model (Phase 9.5); field order mirrors skip_reason_scaler.json; fields: habitAge, completionRateLast7Days, completionRateLast30Days, currentStreak, dayOfWeek, hourOfDay, frequencyOrdinal, recentSkipRate14d; toFloatArray() returns all 8 in exact scaler order
│   │   ├── SnoozeDisengagementFeatures.kt # 7-field input feature vector for the SnoozeDisengagementClassifier TFLite model (Phase 9.2); field order mirrors snooze_disengagement_scaler.json; avgSnoozeCountLast14Days + snoozeFrequencyLast14Days derived from HabitCompletionEntity.snoozeCount
│   │   ├── StreakBreakFeatures.kt  # 7-field input feature vector for the StreakBreakClassifier TFLite model (Phase 8.2); field order mirrors streak_break_scaler.json
│   │   ├── SpilloverFeatures.kt    # 5-field input feature vector for the SpilloverRegressor TFLite model (Phase 8.5); fields: rateA, rateB, hourACompleted, coOccurrenceRate, typicalGapHours
│   │   ├── TargetChangeFeatures.kt # 8-field input feature vector for the TargetAdjustmentRegressor TFLite model (Phase 9.3); predicts continuous delta ∈ [-2.0,+2.0]; field order mirrors target_change_scaler.json; fields: currentTarget, rate30d, rate7d, avgProgressRatio30d, currentStreak, habitAgeDays, previousDelta, periodsSinceLastChange
│   │   ├── TfliteHabitPredictor.kt # TFLite implementation of HabitPredictor; K-Means clustering via habit_clusters.json (nearest-centroid, no Interpreter); Phase 9.4 adds difficultyInterpreter; Phase 9.5 adds skipReasonInterpreter loading skip_reason_classifier.tflite + skip_reason_scaler.json; falls back to MathHabitPredictor
│   │   └── WeeklyForecastFeatures.kt # 12-field input feature vector for the WeeklyForecastRegressor TFLite model (Phase 8.3); toFloatArray() returns fields in scaler order
│   ├── auth/               # Authentication contracts (Dependency Inversion; swappable impl)
│   │   ├── AuthRepository.kt      # Interface defining all auth operations (login, register, resetPassword, changePassword, logout); returns Result<Unit>
│   │   └── FakeAuthRepository.kt  # In-memory stub implementation of AuthRepository used during development (Phase 9); replaced by FirebaseAuthRepository in Phase 10
│   ├── model/              # Domain models and state classes
│   │   ├── AbandonmentRisk.kt        # Output of AbandonmentRiskUseCase (Phase 8.1); wraps raw probability into a Rating tier (LOW/MEDIUM/HIGH/CRITICAL) + data-sufficiency flag
│   │   ├── AchievementDefinition.kt  # Sealed class hierarchy of all 50 achievement definitions (key, points, threshold, group)
│   │   ├── BehavioralCluster.kt      # Sealed class hierarchy for 4 K-Means behavioral tiers (Phase 8.4): EffortlessRoutine, ConsistentEffort, Struggling, Dormant; also contains HabitCluster wrapper
│   │   ├── DifficultyAdjustment.kt   # Output of AdaptiveDifficultyUseCase; bundles delta (+1/0/-1), rolling rate, current and suggested target
│   │   ├── PerceivedDifficultyEstimate.kt # Output of DifficultyEstimateUseCase (Phase 9.4); fields: predicted: Float [1,5], rounded: Int, rating: Rating (EASY/MODERATE/HARD/VERY_HARD), recentAvgRated: Float? (last 14d user ratings, null until MIN_RATINGS=5), hasSufficientData: Boolean (MIN_COMPLETIONS=10 guard)
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
│   │   ├── ReminderLift.kt           # Output of ReminderEffectivenessUseCase (Phase 9.1); holds baselineProb, withReminderProb, lift delta, recommendSend flag, and hasSufficientData guard
│   │   ├── ResilienceScore.kt        # Output of ResilienceScoreUseCase; avg missed periods per recovery event + qualitative rating
│   │   ├── RoutinePrecision.kt       # Output of RoutinePrecisionUseCase; stddev of completion times in minutes + qualitative rating
│   │   ├── SnoozeDisengagementRisk.kt # Output of SnoozeDisengagementUseCase (Phase 9.2); wraps raw snooze-disengagement probability into a Rating tier (LOW/MEDIUM/HIGH/CRITICAL) + hasSufficientData guard; shown in Snooze Drift ElevatedCard
│   │   ├── SkipReasonPrediction.kt    # Output of SkipReasonPredictorUseCase (Phase 9.5); fields: habitId, distribution: Map<SkipReason,Float> (softmax), topReason: SkipReason, topConfidence: Float, hasSufficientData: Boolean (MIN_SKIPS=3 guard); companion: LOW_CONFIDENCE_THRESHOLD=0.35f, fromSoftmax factory
│   │   ├── SortMode.kt               # Enum defining the 7 sort/group modes for the habit list
│   │   ├── SparklinePoint.kt         # Single chart data point (date + reached flag); output of SparklineUseCase
│   │   ├── SpilloverPair.kt          # Output of SpilloverUseCase (Phase 8.5); wraps liftDelta ∈ [-0.5,+0.5] into a Direction (BOOST/NEUTRAL/DRAG) for one ordered habit pair
│   │   ├── StreakBreakRisk.kt        # Output of StreakBreakUseCase (Phase 8.2); wraps raw streak-break probability into a Rating tier + data-sufficiency flag
│   │   ├── StreakResult.kt           # Holds current + best streak counts for a single habit; output of CalculateStreakUseCase
│   │   ├── StreakRiskAssessment.kt   # Output of StreakRecoveryUseCase; isAtRisk flag, specific at-risk weekdays, data-sufficiency flag
│   │   ├── SuccessPrediction.kt      # Output of SuccessProbabilityUseCase; probability [0.05–0.95] + five explicit input feature values
│   │   ├── TargetAdjustment.kt       # Output of TargetAdjustmentUseCase (Phase 9.3); fields: delta ∈ {-2,-1,0,+1,+2}, rawDelta: Float (unrounded), currentTarget, suggestedTarget, Confidence (HIGH/MEDIUM/LOW), hasSufficientData
│   │   ├── WeeklyForecast.kt         # Output of WeeklyForecastUseCase (Phase 8.3); predictedRate, lastWeekRate, Direction (UP/FLAT/DOWN), confidence, hasSufficientData
│   │   └── WeeklyOverview.kt         # 7-day aggregated summary (DaySummary list + week rate); output of WeeklyOverviewUseCase
│   └── usecase/
│       ├── AbandonmentRiskUseCase.kt       # Interactor: extracts 7 AbandonmentFeatures from Room data, delegates to HabitPredictor, maps probability → AbandonmentRisk (Phase 8.1)
│       ├── AdaptiveDifficultyUseCase.kt    # Interactor: suggests target +1/0/-1 based on 14-day rolling rate; delegates to HabitPredictor; Phase 9.4 adds optional predictedDifficulty: Float? nudge (≥4.0 suppresses increase, ≤2.0 suppresses decrease)
│       ├── BehavioralClusterUseCase.kt     # Interactor: extracts 5 ClusterFeatures (with training-median null substitution), checks 10-completion/14-day guards, delegates to HabitPredictor.classifyBehavioralCluster, resolves → HabitCluster (Phase 8.4)
│       ├── CalculateStreakUseCase.kt        # Interactor: computes current + best streak; pure function with injectable today date
│       ├── ComposeDailySummaryUseCase.kt   # Interactor: pure function composing today's raw data into a DailySummaryEntity for notification + inbox (Phase 7.2)
│       ├── DifficultyEstimateUseCase.kt    # Interactor: extracts 8 DifficultyFeatures from the 30 most-recent completions (MIN_COMPLETIONS=10 guard); computes recentAvgRated from last-14-day user ratings (MIN_RATINGS=5); delegates to HabitPredictor.predictPerceivedDifficulty; returns PerceivedDifficultyEstimate (Phase 9.4)
│       ├── EvaluateAchievementsUseCase.kt  # Interactor: (habits, completions) → Set<UnlockedAchievement>; runs all 50 achievement rules (Strategy pattern)
│       ├── ExportHistoryUseCase.kt         # Interactor: serializes a habit's completion history to JSON; triggers ACTION_CREATE_DOCUMENT
│       ├── HabitClashingUseCase.kt         # Interactor: detects negatively-correlated habit pairs via Pearson r; delegates to HabitPredictor
│       ├── HabitRecommendationUseCase.kt   # Interactor: recommends co-occurring related habits based on support-based association rules; delegates to HabitPredictor
│       ├── IconResolverUseCase.kt          # Interactor: resolves an emoji icon from a habit name; Tier 1 = keyword map, Tier 2 = TFLite icon classifier
│       ├── LifeBalanceUseCase.kt           # Interactor: groups habits by category and computes per-category completion rates (default 30-day window)
│       ├── MotivationMessageUseCase.kt     # Interactor: selects a context-aware motivation message key (streak + day-of-week); delegates to HabitPredictor
│       ├── OptimalTimeUseCase.kt           # Interactor: bins completions into a 24-bucket hour histogram; delegates top-hour ranking to HabitPredictor
│       ├── ProcrastinationIndexUseCase.kt  # Interactor: computes skewness of completion hour-of-day distribution; delegates to HabitPredictor
│       ├── ReminderEffectivenessUseCase.kt # Interactor: calls HabitPredictor.predictReminderCompletion twice (reminderSent=0 and 1), computes lift = P(1)−P(0), suppresses reminder if lift < 0.05 (Phase 9.1)
│       ├── ResilienceScoreUseCase.kt       # Interactor: measures bounce-back speed (avg missed periods per recovery event); Phase 9.5 adds involuntarySkips: List<HabitSkipEntity> parameter — SICK/TRAVELING skips are treated as virtual completions before gap math
│       ├── RoutinePrecisionUseCase.kt      # Interactor: computes stddev of completion times in minutes (clock-consistency); delegates to HabitPredictor
│       ├── ScheduleReminderUseCase.kt      # Interactor: schedules/cancels per-habit WorkManager reminder workers with personalised timing (optimalHours or user-set time)
│       ├── SkipReasonPredictorUseCase.kt   # Interactor (Phase 9.5): derives 8 SkipReasonFeatures from Room data, guards on MIN_SKIPS=3, delegates to HabitPredictor.predictSkipReason, converts map→FloatArray in SkipReason.entries order, returns SkipReasonPrediction via fromSoftmax factory
│       ├── SnoozeDisengagementUseCase.kt   # Interactor: extracts 7 SnoozeDisengagementFeatures from Room data (incl. avgSnoozeCountLast14Days + snoozeFrequencyLast14Days); guards on MIN_REMINDER_COMPLETIONS=5 in 30 days; delegates to HabitPredictor, maps probability → SnoozeDisengagementRisk (Phase 9.2)
│       ├── SparklineUseCase.kt             # Interactor: produces a List<SparklinePoint> (reached flag per calendar day) for a given habit and date range
│       ├── StreakBreakUseCase.kt           # Interactor: extracts 7 StreakBreakFeatures, guards against zero-streak, delegates to HabitPredictor, maps probability → StreakBreakRisk (Phase 8.2)
│       ├── SpilloverUseCase.kt             # Interactor: enumerates (A,B) habit pairs where A completed today, computes 5 SpilloverFeatures from 30-day history, delegates to HabitPredictor.predictSpillover, returns top-3 non-NEUTRAL SpilloverPairs (Phase 8.5)
│       ├── StreakRecoveryUseCase.kt        # Interactor: detects high-risk streak patterns and which specific weekdays are consistently missed
│       ├── SuccessProbabilityUseCase.kt    # Interactor: estimates today's completion probability via HabitPredictor (TFLite HabitSuccessClassifier)
│       ├── TargetAdjustmentUseCase.kt      # Interactor: derives 8 TargetChangeFeatures from Room data + TargetHistoryDao, enforces MIN_COMPLETIONS guard, delegates to HabitPredictor.predictTargetDelta, rounds raw output to nearest integer delta, returns TargetAdjustment (Phase 9.3)
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
│   ├── HabitActionReceiver.kt     # BroadcastReceiver for Done/Skip/Snooze taps on reminder notifications (Command pattern); writes to Room directly; Phase 9.5: ACTION_SKIP launches SkipReasonPickerActivity instead of inserting skip inline
│   ├── HabitReminderWorker.kt     # One-shot CoroutineWorker posting a per-habit reminder; selects message template via ReminderTemplateClassifier (TFLite)
│   ├── NotificationChannels.kt    # Centralised channel registry (singleton object); called before any post to guarantee channels exist
│   ├── OnboardingPreferences.kt   # SharedPreferences wrapper (singleton object) tracking whether the user has completed the onboarding flow; shared file with SummaryPreferences
│   ├── SkipReasonPickerActivity.kt # ComponentActivity (Phase 9.5): translucent ModalBottomSheet launched by HabitActionReceiver ACTION_SKIP and MainScreen context menu; shows 6 FilterChips (SkipReason.displayLabel); on selection → inserts HabitSkipEntity via habitSkipDao; on dismiss → inserts NO_REASON
│   ├── SnoozePreferences.kt       # SharedPreferences wrapper (singleton object) for per-habit snooze counter lifecycle (Phase 9.2); file = evolvix_snooze_prefs; key = snooze_count_<habitId>; reset on Done/Skip, increment on Snooze
│   ├── SummaryDismissReceiver.kt  # BroadcastReceiver tracking swipe-dismissals of the summary notification; auto-disables after 7 consecutive dismissals
│   └── SummaryPreferences.kt      # SharedPreferences wrapper for daily-summary state: dismissStreak counter + disabled flag
│
├── ui/                     # The "View" Layer (Jetpack Compose)
│   ├── components/         # Reusable Compose widgets
│   │   ├── AchievementBanner.kt   # Top-anchored sliding banner triggered by AchievementsViewModel.newlyUnlocked SharedFlow; overlays all screens via AppContent Box
│   │   ├── BarChart.kt            # Horizontally scrollable bar chart rendered with Canvas; used for the optimal-hours histogram on StatisticsScreen
│   │   ├── ConfettiOverlay.kt     # Full-screen Canvas confetti overlay with haptic feedback, triggered by HabitViewModel.celebrationEvent
│   │   ├── HabitContextMenu.kt    # Wraps content in a long-press–activated context menu for a single habit row; Phase 9.5 adds "Skip today" item (onSkip lambda) that launches SkipReasonPickerActivity
│   │   ├── PauseBottomSheet.kt    # Modal bottom sheet with date picker for habit pausing
│   │   ├── ProgressItem.kt        # Animated progress bar row (title + x/y count); Phase 9.4 adds onRateCompletion: ((Int)->Unit)? param; shows RatingChips (1–5 SuggestionChip row) for 5 s after each forward completion tap, keyed on currentClickCount delta; auto-hides on tap
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
│   │   ├── StatisticsScreen.kt    # Analytics: weekly overview, life balance, per-habit stats, AI insight cards; Phase 9.4 adds PerceivedDifficultyCard; Phase 9.5 adds SkipReasonForecastCard (ElevatedCard) showing topReason + confidence bar per habit with ≥3 skip records
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
│       ├── HabitViewModel.kt               # Central ViewModel: habit CRUD, streak recomputation, sort/filter state, pause, over-completion, form validation; Phase 9.4 adds rateLastCompletion(habitId, rating) — patches perceivedDifficulty on the most recent completion via updateCompletion
│       ├── HabitViewModelFactory.kt        # Factory for HabitViewModel (injects HabitDao)
│       ├── HistoryViewModel.kt             # Scoped to a single habit; exposes grouped completions, delete/update/retroactive-insert operations
│       ├── HistoryViewModelFactory.kt      # Factory for HistoryViewModel (injects HabitDao + habitId)
│       ├── SettingsViewModel.kt            # AndroidViewModel managing ThemeMode (SharedPreferences) and daily-summary WorkManager worker toggling
│       ├── SettingsViewModelFactory.kt     # Factory for SettingsViewModel (injects Application context)
│       ├── StatisticsViewModel.kt          # Combines habits+completions Flows; runs all analytics use cases; exposes overview, lifeBalance, perHabitStats, AI cards as StateFlows; Phase 9.4 adds difficultyEstimates; Phase 9.5 adds skipReasonPredictions: StateFlow<Map<Int, SkipReasonPrediction>>
│       ├── StatisticsViewModelFactory.kt   # Factory for StatisticsViewModel; Phase 9.5 adds habitSkipDao: HabitSkipDao parameter
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
├── generate_clustering_data.py     # Synthetic dataset for K-Means Behavioral Clustering (Phase 8.4); 10k rows, 4 archetypes, no label column (unsupervised)
├── diagnose_success.py              # Temporary diagnostic script for Phase 6.5.2 troubleshooting; prints positive rate and label distribution of success_dataset.csv; safe to delete once Model 1 passes acceptance thresholds
├── generate_icon_data.py           # Synthetic dataset for HabitIconClassifier
├── generate_reminder_data.py       # Synthetic dataset for ReminderTemplateClassifier
├── generate_reminder_lift_data.py  # Synthetic dataset for ReminderLiftClassifier (Phase 9.1); 80k rows, 8 FEATURE_COLUMNS (reminderSent as binary flag), label = completed_within_30min; +16.2% lift signal
├── generate_snooze_disengagement_data.py # Synthetic dataset for SnoozeDisengagementClassifier (Phase 9.2); 50k rows, 7 FEATURE_COLUMNS (avgSnoozeCountLast14Days + snoozeFrequencyLast14Days), label = disengaged; ~40.6% positive rate; logit-based priors
├── generate_streak_break_data.py   # Synthetic dataset for StreakBreakClassifier (Phase 8.2)
├── generate_success_data.py        # Synthetic dataset for HabitSuccessClassifier
├── generate_target_change_data.py   # Synthetic dataset for TargetAdjustmentRegressor (Phase 9.3); 8 FEATURE_COLUMNS matching TargetChangeFeatures, label = ideal_delta ∈ [-2.0,+2.0]
├── generate_weekly_forecast_data.py # Synthetic dataset for WeeklyForecastRegressor (Phase 8.3); 12 FEATURE_COLUMNS, label = next_week_rate
├── generate_difficulty_data.py      # Synthetic dataset for PerceivedDifficultyRegressor (Phase 9.4); 8 FEATURE_COLUMNS matching DifficultyFeatures, label = perceived_difficulty ∈ [1.0,5.0] (float)
├── generate_skip_reason_data.py     # Synthetic dataset for SkipReasonClassifier (Phase 9.5); 50k rows, 8 FEATURE_COLUMNS (habitAge→recentSkipRate14d), label = 0–5 (SkipReason ordinal); class imbalance: SICK/TRAVELING intentionally rare
├── generate_spillover_data.py       # Synthetic dataset for SpilloverRegressor (Phase 8.5); 50k rows, 5 FEATURE_COLUMNS, label = lift_delta ∈ [-0.5,+0.5]
├── train_abandonment_model.py      # Trains + exports habit_abandonment_classifier.tflite + abandonment_scaler.json
├── train_clustering_model.py       # Trains K-Means (sklearn, no TFLite) + exports habit_clusters.json with centroids, labels, scaler, training medians, silhouette score (Phase 8.4)
├── train_icon_model.py             # Trains + exports habit_icon_classifier.tflite + icon_vocab.json
├── train_reminder_model.py         # Trains + exports reminder_template_classifier.tflite + reminder_scaler.json
├── train_reminder_lift_model.py    # Trains + exports reminder_lift_classifier.tflite + reminder_lift_scaler.json (Phase 9.1); Dense(32,relu)→Dropout(0.2)→Dense(16,relu)→Dense(1,sigmoid); accuracy=80.3%, AUC=0.90, Lift MAE=0.105
├── train_snooze_disengagement_model.py # Trains + exports snooze_disengagement_classifier.tflite + snooze_disengagement_scaler.json (Phase 9.2); Dense(32,relu)→Dropout(0.2)→Dense(16,relu)→Dense(1,sigmoid); EarlyStopping on val_auc patience=8; threshold Macro F1 ≥ 0.75
├── train_streak_break_model.py     # Trains + exports streak_break_classifier.tflite + streak_break_scaler.json
├── train_success_model.py          # Trains + exports habit_success_classifier.tflite + success_scaler.json
├── train_weekly_forecast_model.py  # Trains + exports weekly_forecast_regressor.tflite + weekly_forecast_scaler.json; MAE loss, sigmoid output, threshold MAE ≤ 0.12 (Phase 8.3)
├── train_spillover_model.py        # Trains + exports spillover_regressor.tflite + spillover_scaler.json; MAE loss, tanh×0.5 output, threshold MAE ≤ 0.08 (Phase 8.5); achieved MAE 0.0426
├── train_target_change_model.py    # Trains + exports target_change_regressor.tflite + target_change_scaler.json (Phase 9.3); regression on ideal_delta ∈ [-2.0,+2.0]; Dense(64,relu)→Dropout(0.2)→Dense(32,relu)→Dense(1,linear); output coerced by caller to nearest int
├── train_difficulty_model.py       # Trains + exports perceived_difficulty_regressor.tflite + perceived_difficulty_scaler.json (Phase 9.4); Dense(64,relu)→Dropout(0.2)→Dense(32,relu)→Dense(1,linear); MAE loss, output coerced to [1.0,5.0]; threshold MAE ≤ 0.5
├── train_skip_reason_model.py      # Trains + exports skip_reason_classifier.tflite + skip_reason_scaler.json (Phase 9.5); 6-class softmax MLP: Dense(64,relu)→Dropout(0.2)→Dense(32,relu)→Dense(6,softmax); class_weight=balanced; threshold Macro F1 ≥ 0.35; achieved accuracy=49.3%, Macro F1=0.377
└── evaluate_models.py              # Thesis-grade evaluation report: loads .tflite artifacts + habit_clusters.json, reproduces test splits, computes metrics + silhouette + PCA scatter, saves plots to data/plots/; Phase 9.4 adds PerceivedDifficultyRegressor block; Phase 9.5 adds SkipReasonClassifier block (confusion matrix + per-class report, Macro F1 gate ≥ 0.35)
```