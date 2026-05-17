# PLAN.md — Chronological Execution Roadmap

**Project:** Habit Tracker 3 (Engineering Thesis)
**Stack:** Kotlin · Jetpack Compose · Room · MVVM · Coroutines/Flow · (later) Firebase
**Source of features:** `IDEAS.MD` · **Target structure:** `STRUCTURE.md`

This plan reorders the 7 thematic modules from `IDEAS.MD` into **dependency-driven phases**. Each phase contains feature blocks with `[ ]` MVVM checklists. Formal design patterns are annotated `(Pattern: …)` for thesis defense notes.

> Working principle: **Offline-first → Local correctness → Engagement layer → Intelligence layer → Cloud**. Every later phase consumes the data contract built in earlier phases, so refactors stay surgical.

---

## PHASE 0 — Foundation Audit & Cleanup
**Goal:** Establish a stable baseline before adding new modules. Verify existing MVVM contracts.

- [x] Populate root `.gitignore` with standard Android, Gradle, and VS Code exclusions to prevent committing build artifacts.
- [x] Create root README.md with project title, short description, thesis context, tech stack (Kotlin, Jetpack Compose, Room), and current status.
- [x] Audit current entities in `data/model/` (`HabitEntity.kt`, `HabitCompletitionEntity.kt`, `HabitFrequency.kt`) and confirm they match `STRUCTURE.md`.
- [x] Confirm `data/local/HabitDao.kt` exposes suspend/Flow functions (Pattern: **DAO / Repository contract**).
- [x] Verify `ui/viewmodel/HabitViewModel.kt` exposes `StateFlow<HabitUiState>` (Pattern: **Observer via Flow**, **MVVM**).
- [x] Confirm `navigation/NavGraph.kt` + `Screen.kt` route definitions are sealed/typed (Pattern: **Sealed Class State**).

---

## PHASE 1 — Data Integrity Hardening (Module 4 + 1.2 partial)
**Goal:** Make the local Room schema bulletproof before any UI/AI features depend on it. No cloud yet.

### 1.1 Unique habit names + over-completion support
- [x] **Model**
  - [x] Add `@Index(value=["name"], unique=true)` to `HabitEntity` in `data/model/HabitEntity.kt`.
  - [x] Allow completion count to exceed target (no DB constraint clamp) in `HabitCompletitionEntity.kt`.
  - [x] Bump DB version in `data/local/AppDatabase.kt` (no Migration code in dev — reinstall app, per workstyle).
  - [x] Add DAO query `findByNameIgnoreCase()` in `data/local/HabitDao.kt` for pre-insert validation (Pattern: **Repository / DAO**).
- [x] **ViewModel** (`ui/viewmodel/HabitViewModel.kt`)
  - [x] Add `validateName()` returning `Result<Unit>` exposed via `StateFlow<FormError?>`.
  - [x] Allow `progress > target` in state computation; expose `isOverCompleted: Boolean`.
- [x] **View**
  - [x] In `ui/screens/AddNewHabitScreen.kt` and `EditHabitScreen.kt`: show inline error on duplicate.
  - [x] In `ui/components/ProgressItem.kt`: render contrasting border when `isOverCompleted` (M3 `Card` + `Modifier.border`), replace counter with +N counter

### 1.2 Top bar unification
- [x] **View only** — Standardize `TopAppBar` across `MainScreen.kt`, `AddNewHabitScreen.kt`, `EditHabitScreen.kt`, `StatisticsScreen.kt`. Edit screen gets `Icons.Filled.Delete` + confirm `AlertDialog` (Pattern: **Composition over inheritance**).

---

## PHASE 2 — Habit Management UX (Module 4.2–4.5)
**Goal:** Round out CRUD UX. Pure local logic, depends only on Phase 1 schema.

### 2.1 Reduced-friction Add/Edit forms
- [x] **Model:** New `data/model/HabitTemplate.kt` (in-memory list, no Room) seeded from `HABIT-TEMPLATES.MD`.
- [x] **Model:** Add `categories: List<String>` + `colorHex: String` + `iconKey: String?` columns (bump DB version).
- [x] **Model (layering fix):** Remove `HabitEntity`'s import of `HabitColorScheme` from `ui/theme/` — replacing it with the new `colorHex: String` primitive eliminates a data→UI layer dependency violation found during Phase 0 audit.
- [x] **ViewModel:** Extend `HabitUiState` with `templates`, `selectedCategories`, `selectedColor`, `frequencyN`, `frequencyUnit`, `targetCount` (Pattern: **State Holder / Unidirectional Data Flow**).
- [x] **View:** Refactor `AddNewHabitScreen.kt` into sections: Templates row · Name · Frequency builder · Target · Categories chips (`FilterChip`) · Color picker · Reminder switch.
- [x] **View:** `EditHabitScreen.kt` mirrors form but swaps Templates row for Icon picker.

### 2.2 Pause system
- [x] **Model:** Add `pausedUntil: Long?` (nullable = not paused; `Long.MAX_VALUE` = indefinite) to `HabitEntity`. Bump DB version.
- [x] **Model:** DAO query `getActiveHabits(now: Long)` filters pausedUntil.
- [x] **ViewModel:** `pauseHabit(id, until)` / `resumeHabit(id)` in `viewModelScope.launch`.
- [x] **View:** New `ui/components/PauseBottomSheet.kt` using `ModalBottomSheet` (M3) — options: indefinite / date picker.
- [x] **View:** `ProgressItem.kt` renders dimmed + pause icon when paused.

### 2.3 Long-press context menu
- [x] **View:** `ui/components/HabitContextMenu.kt` using `DropdownMenu` triggered by `Modifier.combinedClickable(onLongClick=…)`. 7 actions per `IDEAS.MD §4.4`.
- [x] **ViewModel:** Add `markProgressOnce(id)` reusing existing increment logic.

### 2.4 Reordering, grouping, filtering
- [x] **Model:** Add `sortOrder: Int` and `categoryGroup: String?` columns. Bump DB version.
- [x] **Model:** DAO updates: `getHabitsSorted(SortMode)` returning `Flow<List<HabitEntity>>`.
- [x] **ViewModel:** Expose `sortMode: StateFlow<SortMode>` and `activeFilters: StateFlow<Set<String>>` (Pattern: **Observer**).
- [x] **View:** `MainScreen.kt` — filter chip row + search field; collapsible group headers via `LazyColumn` `stickyHeader`.
- [x] **View:** Drag & drop using community-standard `Modifier.pointerInput` with index swap (Pattern: **Command pattern** for swap action).

---

## PHASE 3 — Habit History & Streak Engine (Module 5.1 + 1.2 streak math)
**Goal:** Establish the canonical timestamp log that every later module (achievements, AI, sync) reads from.

### 3.1 History screen
- [x] **Model:** DAO queries: `getCompletionsForHabit(id): Flow<List<HabitCompletionEntity>>`, `updateCompletion(...)`, `deleteCompletion(id)`, `insertRetroactive(...)`.
- [x] **ViewModel:** New `HistoryViewModel.kt` in `ui/viewmodel/` — exposes `groupedByYearMonth: StateFlow<Map<Year, Map<Month, List<Entry>>>>` (Pattern: **MVVM + State Holder**).
- [x] **View:** New `ui/screens/HistoryScreen.kt` — `LazyColumn` with collapsible Year > Month sections, edit/delete icons, FAB for retroactive add (Compose `DatePicker` + `TimePicker`).
- [x] **Navigation:** Add `Screen.History(habitId)` to `navigation/Screen.kt` and route in `NavGraph.kt`.

### 3.2 Streak engine (pure logic)
- [x] **Domain:** New `domain/usecase/CalculateStreakUseCase.kt` — returns `StreakResult(current, best)` (Pattern: **Use Case / Interactor**, single-responsibility).
- [x] **Domain:** New `domain/model/StreakResult.kt`.
- [x] **ViewModel:** Recompute streaks reactively by combining `HabitFlow` + `CompletionsFlow` with `combine(...)`.
- [x] **Unit Tests (allowed, JUnit only):** Test `CalculateStreakUseCase` with synthetic timestamp lists (per workstyle, only when explicitly requested).

### 3.3 JSON export of history
- [x] **Domain:** New `domain/usecase/ExportHistoryUseCase.kt` using `kotlinx.serialization`.
- [x] **View:** `IconButton` in `HistoryScreen.kt` top bar → `Intent.ACTION_CREATE_DOCUMENT`.

---

## PHASE 4 — Gamification: Streaks & Achievements (Module 3)
**Goal:** Build the engagement layer on top of the streak engine.

### 4.1 Achievement domain model
- [x] **Model:** New `data/model/AchievementEntity.kt` (id, key, unlockedAt, progress) + `AchievementDao.kt`. Bump DB version.
- [x] **Domain:** New `domain/model/AchievementDefinition.kt` — sealed hierarchy of 50 achievements per `ACHIEVEMENTS.MD` (Pattern: **Strategy / Sealed Class polymorphism**).
- [x] **Domain:** New `domain/usecase/EvaluateAchievementsUseCase.kt` — pure function `(habits, completions) -> Set<UnlockedAchievement>` (Pattern: **Strategy + Pure Function**).

### 4.2 Achievement reactivity & retraction
- [x] **ViewModel:** New `AchievementsViewModel.kt` in `ui/viewmodel/` — observes habits+completions Flow, runs evaluator, persists deltas.
- [x] **ViewModel:** Retraction — re-running evaluator after history edits revokes rows where requirement no longer holds (Pattern: **Observer + idempotent reducer**).

### 4.3 Achievement UI
- [x] **View:** New `ui/screens/AchievementsScreen.kt` — total points header, "Latest" section, collapsible category groups, progress bars on locked items.
- [x] **View:** New `ui/components/AchievementBanner.kt` — top-anchored sliding `Snackbar`-like Composable triggered by `SharedFlow<AchievementUnlocked>` (Pattern: **Event Bus via Flow**).
- [x] **Navigation:** Add `Screen.Achievements`; reorder `BottomNavigationBar` to `[Achievements, Habits, Statistics]` in `NavGraph.kt`.

### 4.4 Main screen visual rewards
- [x] **View:** `ProgressItem.kt` — milestone mini-celebrations at 25/50/75% (only when target makes them mathematically distinct), confetti on 100% (lightweight Compose animation).

---

## PHASE 5 — Statistics Screen Overhaul (Module 5.2)
**Goal:** Surface the data already collected. No AI yet — placeholders for AI sections.

- [x] **Domain:** New use cases in `domain/usecase/`: `WeeklyOverviewUseCase`, `LifeBalanceUseCase`, `SparklineUseCase` (Pattern: **Use Case per query**).
- [x] **ViewModel:** New `StatisticsViewModel.kt` exposes `overview`, `lifeBalance`, `perHabitStats: StateFlow<…>`.
- [x] **View:** Refactor `ui/screens/StatisticsScreen.kt` — Global Overview card, Life Balance card, collapsed/expanded habit cards with sparkline + bar chart tabs (7D/30D/3M/ALL).
- [x] **View:** New `ui/components/Sparkline.kt` and `ui/components/BarChart.kt` (Canvas-based, no third-party chart lib needed).

---

## PHASE 6 — On-Device AI Layer (Module 2)
**Goal:** Stage 1 = pure Kotlin math. Architected so a `.tflite` model can be swapped in later (Pattern: **Strategy + Dependency Inversion**).

### 6.1 AI infrastructure
- [x] **Domain:** New package `domain/ai/` with interface `HabitPredictor` (abstraction).
- [x] **Domain:** Implementation `MathHabitPredictor` (rule-based / statistical).
- [x] **Domain:** Stub `TfliteHabitPredictor` for future swap.

### 6.2 Predictive features
- [x] `SuccessProbabilityUseCase` — features: day, hour, streak, recent week, age.
- [x] `OptimalTimeUseCase` — bins completions by hour, ranks slots.
- [x] `HabitRecommendationUseCase` — co-occurrence rules from habit names/categories.
- [x] `StreakRecoveryUseCase` — detects high-risk patterns (e.g., missing Sundays).
- [x] `AdaptiveDifficultyUseCase` — suggests target up/down based on rolling completion rate.
- [x] `MotivationMessageUseCase` — context-aware string templates with `<plurals>`.

### 6.3 Passive analytics
- [x] `RoutinePrecisionUseCase` (stddev of timestamps).
- [x] `ResilienceScoreUseCase` (avg recovery days).
- [x] `HabitClashingUseCase` (Pearson correlation across habits).
- [x] `ProcrastinationIndexUseCase` (skewness within deadline cycle).

### 6.4 AI icon selection
- [x] **Domain:** `IconResolverUseCase` — Tier 1 keyword map (covers ~70%); Tier 2 stub for ML.
- [x] **ViewModel:** Resolve icon when rendering `StatisticsScreen`; persist user override from `EditHabitScreen` icon picker.

### 6.5 Wire AI (math layer) into Statistics
- [ ] **View:** Fill the placeholders from Phase 5: `🎯 Success Prediction`, `🕒 Optimal Timing`, `🧠 Behavioral Patterns`, `✨ Smart Insight` cards — backed by `MathHabitPredictor` at this stage.

---

## PHASE 6.5 — On-Device Machine Learning Models (Stage 2 — TFLite)
**Goal:** Replace the rule-based / statistical predictions from Phase 6 with three genuine on-device ML models (TensorFlow Lite). Achieved through the **Strategy + Dependency Inversion** abstraction already in place — only `TfliteHabitPredictor` changes, no ViewModel refactors. This phase contains the full ML pipeline (data generation → training → export → integration → validation) and is required for the thesis ML chapter.

> **Architecture note:** `TfliteHabitPredictor` *contains* a `MathHabitPredictor` instance and only overrides the three ML methods (`predictSuccess`, `findOptimalHours`, `classifyIcon`, `selectReminderTemplate`). All Tier-B statistical analytics (clashing, resilience, routine precision, procrastination) remain pure Kotlin math and are delegated through. This is **composition over inheritance** — defensible Liskov substitution.

### 6.5.1 Python training project setup (outside Android module)
- [ ] Create top-level folder `ml-training/` (sibling of `app/`) — **excluded from `.gitignore` builds**, included in source control.
- [ ] Add `ml-training/requirements.txt` with: `tensorflow==2.14.0`, `pandas`, `numpy`, `scikit-learn`, `matplotlib`.
- [ ] Add `ml-training/README.md` documenting how to set up a Python 3.10 venv and run each training script. **(This is the only external doc file allowed — it is required to defend the ML pipeline reproducibility in the thesis.)**
- [ ] Folder structure inside `ml-training/`:
  ```
  ml-training/
    requirements.txt
    README.md
    data/                          ← generated CSVs go here (gitignored output)
    models/                        ← exported .tflite files (committed)
    generate_success_data.py
    generate_icon_data.py
    generate_reminder_data.py
    train_success_model.py
    train_icon_model.py
    train_reminder_model.py
    evaluate_models.py             ← produces thesis metrics tables + plots
  ```

### 6.5.2 Model 1 — HabitSuccessClassifier (binary classification)
**Powers:** `🎯 Success Prediction` card, `🕒 Optimal Timing` card, smart notification scheduling (Phase 7).

- [ ] **Python — data generation** (`ml-training/generate_success_data.py`):
  - [ ] Generate 30,000 synthetic rows with features: `dayOfWeek (1-7)`, `hourOfDay (0-23)`, `currentStreak (0-200)`, `completionRateLast7Days (0.0-1.0)`, `habitAge (1-730 days)`, `hoursSinceLastCompletion (0-336)`, `targetCount (1-20)`.
  - [ ] Bake behavioral rules into label probabilities (not deterministic):
    - Mornings (6–10 AM) → +0.25 base probability
    - `currentStreak > 7` → +0.20
    - `completionRateLast7Days < 0.3` → −0.30
    - `habitAge > 30` → +0.10
    - Weekend evenings → −0.15
    - Then sample label ∈ {0,1} from the resulting probability (adds realistic noise).
  - [ ] Output: `ml-training/data/success_dataset.csv`.
- [ ] **Python — training** (`ml-training/train_success_model.py`):
  - [ ] 80/20 train/test split via `sklearn.model_selection.train_test_split`.
  - [ ] Fit `StandardScaler` on training features; **save `mean` and `scale` to `models/success_scaler.json`** (Android must apply identical normalization at inference).
  - [ ] Keras model: `Dense(32, relu) → Dropout(0.2) → Dense(16, relu) → Dense(1, sigmoid)`.
  - [ ] Compile: `optimizer=adam`, `loss=binary_crossentropy`, `metrics=[accuracy, AUC]`.
  - [ ] Train 50 epochs, validation_split=0.1.
  - [ ] **Acceptance threshold:** test accuracy ≥ 0.82 AND ROC-AUC ≥ 0.88. If not met, increase dataset size to 50k and re-run.
- [ ] **Python — export:**
  - [ ] `tf.lite.TFLiteConverter.from_keras_model(model)` with `Optimize.DEFAULT` (quantization).
  - [ ] Write to `ml-training/models/habit_success_classifier.tflite`.

### 6.5.3 Model 2 — HabitIconClassifier (text classification)
**Powers:** Automatic icon resolution on Statistics screen (replaces `IconResolverUseCase` Tier-1 keyword map).

- [ ] **Python — labeled dataset** (`ml-training/generate_icon_data.py`):
  - [ ] Hand-write ~500 labeled `(habit_name, icon_category)` pairs across the 17 categories: `fitness, health, learning, mindfulness, creative, social, productivity, finance, food, sleep, cleaning, nature, pet, music, reading, writing, other`.
  - [ ] Augment via simple synonyms (e.g. "run" → "jog", "running", "morning run") to reach ~2,000 examples.
  - [ ] Output: `ml-training/data/icon_dataset.csv` with columns `name, label`.
- [ ] **Python — training** (`ml-training/train_icon_model.py`):
  - [ ] Tokenize names via `tf.keras.layers.TextVectorization` (char n-grams, output_mode='tf-idf', max_tokens=2000).
  - [ ] Save vectorizer vocabulary to `models/icon_vocab.json` (Android will replicate tokenization).
  - [ ] Keras model: `TextVectorization → Dense(32, relu) → Dense(17, softmax)`.
  - [ ] Train with `sparse_categorical_crossentropy`, 30 epochs.
  - [ ] **Acceptance threshold:** top-1 accuracy ≥ 0.75, top-3 accuracy ≥ 0.92.
- [ ] **Python — export:**
  - [ ] Convert to `ml-training/models/habit_icon_classifier.tflite`.
  - [ ] Persist vocabulary as JSON alongside.

### 6.5.4 Model 3 — ReminderTemplateClassifier (multi-class classification)
**Powers:** AI-driven notification text selection (Phase 7), in-app `MotivationMessageUseCase`.

- [ ] **Python — synthetic data** (`ml-training/generate_reminder_data.py`):
  - [ ] Features: `currentStreak`, `completionRateLast7Days`, `daysSinceLastCompletion`, `dayOfWeek`, `hourOfDay`, `isAtRisk (0/1)`, `targetReachedToday (0/1)`.
  - [ ] Label = index into ~15 template categories (`cheer_streak_milestone`, `gentle_nudge_at_risk`, `celebrate_consistency`, `recovery_encouragement`, `morning_optimistic`, `evening_reflection`, `comeback_after_break`, `weekend_warrior`, `first_week_support`, `cold_start`, `streak_save`, `target_smashed`, `category_balance`, `pace_yourself`, `quiet_encouragement`).
  - [ ] Generate 10,000 rows with rule-based label assignment + 10% noise.
- [ ] **Python — training** (`ml-training/train_reminder_model.py`):
  - [ ] Same StandardScaler approach as Model 1; save `models/reminder_scaler.json`.
  - [ ] Keras model: `Dense(24, relu) → Dense(15, softmax)`.
  - [ ] **Acceptance threshold:** top-1 accuracy ≥ 0.70 (multi-class on 15 labels is harder; AUC is per-class).
- [ ] **Python — export:** `ml-training/models/reminder_template_classifier.tflite`.

### 6.5.5 Thesis evaluation report (`ml-training/evaluate_models.py`)
- [ ] Produce per-model:
  - [ ] Confusion matrix (PNG saved to `ml-training/data/plots/`).
  - [ ] ROC curve for Model 1 (binary).
  - [ ] Classification report (precision/recall/F1 per class) for Models 2 & 3.
  - [ ] Calibration plot for Model 1 (predicted vs actual probability).
- [ ] Output a Markdown table summarizing all metrics — **paste into thesis ML chapter**.

### 6.5.6 Android — TFLite integration
- [ ] **Gradle:** Add to `app/build.gradle.kts`:
  ```kotlin
  implementation("org.tensorflow:tensorflow-lite:2.14.0")
  implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
  ```
- [ ] **Assets:** Copy the following from `ml-training/models/` into `app/src/main/assets/`:
  - `habit_success_classifier.tflite` + `success_scaler.json`
  - `habit_icon_classifier.tflite` + `icon_vocab.json`
  - `reminder_template_classifier.tflite` + `reminder_scaler.json`
- [ ] **Domain:** Expand `domain/ai/HabitPredictor.kt` interface with:
  - `fun predictSuccess(features: HabitFeatures): Float`
  - `fun findOptimalHours(features: HabitFeatures): List<Int>` (returns top 3 hours)
  - `fun classifyIcon(habitName: String): String`
  - `fun selectReminderTemplate(features: ReminderContext): String`
- [ ] **Domain:** New model `domain/ai/HabitFeatures.kt` and `domain/ai/ReminderContext.kt` — pure data classes matching Python feature vectors.
- [ ] **Domain:** Replace stub `TfliteHabitPredictor.kt` with full implementation:
  - [ ] Constructor `(context: Context, mathFallback: MathHabitPredictor)`.
  - [ ] Load three `Interpreter` instances from assets in `init`.
  - [ ] Load scaler/vocab JSON files into `FloatArray` / `Map<String, Int>` fields.
  - [ ] `predictSuccess()` — normalize via scaler, run interpreter, return sigmoid output.
  - [ ] `findOptimalHours()` — call `predictSuccess()` 24 times (one per hour), return top 3 indices.
  - [ ] `classifyIcon()` — tokenize via vocab map, run interpreter, return label string from argmax.
  - [ ] `selectReminderTemplate()` — normalize features, run interpreter, map argmax to template key.
  - [ ] All math methods (`computeRoutinePrecision`, `computeResilience`, `detectClashes`, `computeProcrastination`) delegate to `mathFallback`.
- [ ] **DI/Wiring:** In `MainActivity` (or wherever ViewModels are created), inject `TfliteHabitPredictor(applicationContext, MathHabitPredictor())` instead of `MathHabitPredictor()` directly. **No ViewModel code changes** — this is the payoff of Strategy + DI.

### 6.5.7 Android — validation tests (JUnit, no emulator)
- [ ] **Test:** `app/src/test/java/.../TfliteHabitPredictorTest.kt`:
  - [ ] `predictSuccess` returns > 0.7 for "ideal" feature vector (Mon 7AM, 20-day streak, high rate).
  - [ ] `predictSuccess` returns < 0.3 for "doomed" vector (Sun midnight, 0 streak, low rate).
  - [ ] `classifyIcon("morning run")` returns `"fitness"`.
  - [ ] `classifyIcon("meditate 10 min")` returns `"mindfulness"`.
  - [ ] `findOptimalHours` returns 3 distinct integers in [0, 23].
- [ ] **Cross-validation test:** Feed the same 20 synthetic feature vectors to both `MathHabitPredictor` and `TfliteHabitPredictor`. Assert their Spearman rank correlation across success probabilities is > 0.7 — proves the ML model learned the same domain logic the math model encodes. **This is the thesis killer test.**
- [ ] Note: per project rules, do **NOT** write instrumented (`androidTest/`) tests. JVM-only JUnit.

### 6.5.8 Rewire Phase 6 UI to ML-backed predictor
- [ ] Replace `MathHabitPredictor` injection sites with `TfliteHabitPredictor` in:
  - `StatisticsViewModel` (Success Prediction card, Optimal Timing card)
  - Icon resolution path on Statistics screen (replaces Phase 6.4 Tier-1 keyword map)
- [ ] Manual emulator verification (per project rules — no UI tests): launch app, confirm Statistics cards show non-zero probabilities and an icon resolves for each habit name.

---

## PHASE 7 — Notifications & Widgets (Module 7)
**Goal:** Extend reach beyond the app. Depends on AI for smart timing/text.

### 7.1 Reminders
- [ ] **Model:** Add `reminderTime: Long?` to `HabitEntity`. Bump DB version.
- [ ] **Domain:** `ScheduleReminderUseCase` using `WorkManager` (Pattern: **Command pattern via WorkRequest**). Uses `HabitPredictor.findOptimalHours()` to choose the best slot when `reminderTime` is null (smart scheduling).
- [ ] **System:** New `notifications/HabitReminderWorker.kt` outside MVVM packages — posts `NotificationCompat` with action buttons (Done / Skip / Snooze). Notification text is selected via `HabitPredictor.selectReminderTemplate()` and resolved through `strings.xml` (so Polish/English plurals are honored).
- [ ] **System:** `notifications/HabitActionReceiver.kt` (`BroadcastReceiver`) writes completion via Repository.

### 7.2 Daily summary
- [ ] **System:** Periodic `WorkManager` job composing summary text from `WeeklyOverviewUseCase`.

### 7.3 Glance widgets
- [ ] **System:** New package `widgets/` — `SmallHabitWidget.kt` (single habit) and `MediumHabitListWidget.kt` (scrollable today list) using **Jetpack Glance** (per `IDEAS.MD`).
- [ ] Widget data flows through the same Repository (Pattern: **Single Source of Truth**).

---

## PHASE 8 — Global UX, Theming, Localization, Onboarding (Module 6)
**Goal:** Polish before cloud. Settings depend on most prior modules existing.

- [ ] **View:** New `ui/screens/SettingsScreen.kt` — profile header (Name + Rank from achievements), theme selector, language selector, JSON export, Help, Feedback (`Intent.ACTION_SENDTO`), Change Password placeholder, Login/Logout placeholder.
- [ ] **Theme:** Update `ui/theme/Theme.kt` — full Light/Dark/Auto. Status bar icon contrast handled via `WindowCompat`.
- [ ] **Localization:** Add `res/values-pl/strings.xml`. Convert all natural-language builders to `<plurals>` (1 dzień / 2 dni / 5 dni).
- [ ] **Onboarding:** New `ui/screens/OnboardingScreen.kt` — 3-tab demo with dummy data, shown once via `DataStore` flag (Pattern: **Preferences as Repository**).
- [ ] **UI hints:** Pulsing FAB / tap-to-complete glow on first launch (Compose `infiniteTransition`).
- [ ] **Empty states:** Implement copy from `IDEAS.MD §6.4` for Home, Statistics, Achievements.

---

## PHASE 9 — Authentication Screens (Module 1.3) — local-only first
**Goal:** Build the auth UI surface against a fake repository. Real Firebase wiring happens in Phase 10. Decoupling keeps auth UI testable.

- [ ] **Domain:** Interface `AuthRepository` in `domain/auth/` — methods `login`, `register`, `resetPassword`, `changePassword`, `logout` (Pattern: **Repository + Dependency Inversion**).
- [ ] **Domain:** `FakeAuthRepository` for dev.
- [ ] **ViewModel:** `AuthViewModel.kt` exposes `AuthUiState` `StateFlow`.
- [ ] **View:** New screens in `ui/screens/auth/` — `LoginScreen.kt`, `RegisterScreen.kt`, `ResetPasswordScreen.kt`, `SetNewPasswordScreen.kt`.
- [ ] **Navigation:** Auth nav graph guards main graph based on `isAuthenticated` flag.

---

## PHASE 10 — Cloud Sync (Module 1.1) — final phase
**Goal:** Multi-device sync without breaking offline-first guarantee. Room remains **single source of truth**.

### 10.1 Firebase wiring
- [ ] Add Firebase Auth + Firestore Gradle deps in `app/build.gradle.kts`.
- [ ] Replace `FakeAuthRepository` with `FirebaseAuthRepository` (Pattern: **Liskov substitution** — same interface).

### 10.2 Sync controller
- [ ] **Domain:** New `domain/sync/SyncController.kt` — coordinates Room ↔ Firestore (Pattern: **Mediator**).
- [ ] **Domain:** Conflict resolution = **timestamp merge** (each completion is a unique `Long`, union of sets — guarantees no loss, per `IDEAS.MD §3 clarification`).
- [ ] **System:** `WorkManager` periodic + on-network-available sync trigger.
- [ ] **Model:** Each entity gains `lastModified: Long` and `syncedAt: Long?` (bump DB version).

### 10.3 Settings integration
- [ ] Wire real Login/Logout/Change Password buttons in `SettingsScreen.kt` to `FirebaseAuthRepository`.
- [ ] Show sync status indicator in `MainScreen.kt` top bar (Pattern: **Observer** of `SyncState` Flow).

---

## Cross-Phase Conventions (Thesis-defendable)

| Concern | Pattern | Where it lives |
| --- | --- | --- |
| Single source of truth | **Repository** | `data/local/` DAO + future `data/repository/` |
| UI state exposure | **Observer** via `StateFlow` | `ui/viewmodel/` |
| Screen routes | **Sealed Class** state | `navigation/Screen.kt` |
| Business logic isolation | **Use Case / Interactor** | `domain/usecase/` |
| Pluggable AI / Auth | **Strategy + DI** | `domain/ai/`, `domain/auth/` |
| ML model lifecycle | External Python pipeline → TFLite asset | `ml-training/` (Python) + `app/src/main/assets/` (binaries) |
| Achievement rules | **Strategy** over sealed hierarchy | `domain/model/AchievementDefinition.kt` |
| One-shot UI events | **Event via SharedFlow** | ViewModels |
| Background work | **Command** via `WorkRequest` | `notifications/`, `domain/sync/` |
| DB schema evolution | Bump version + reinstall (dev policy) | `data/local/AppDatabase.kt` |

---

## Dependency Graph (why this order)

```
P0 Audit
  └─► P1 Schema integrity
        └─► P2 CRUD UX  ──┐
        └─► P3 History+Streaks ──┐
                                 ├─► P4 Achievements
                                 ├─► P5 Statistics
                                 │     └─► P6 AI layer (math, Stage 1)
                                 │           └─► P6.5 ML models (TFLite, Stage 2)
                                 │                 └─► P7 Notifications & Widgets
                                 └─► P8 Global UX/Theming/Locale
                                       └─► P9 Auth UI (fake repo)
                                             └─► P10 Firebase Sync
```

Each arrow = "consumes the data contract / abstraction defined upstream". Reversing any arrow forces a rewrite — that is why this ordering is the architecturally minimal one.

---
