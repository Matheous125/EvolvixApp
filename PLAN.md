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
- [ ] **View:** `EditHabitScreen.kt` mirrors form but swaps Templates row for Icon picker.

### 2.2 Pause system
- [ ] **Model:** Add `pausedUntil: Long?` (nullable = not paused; `Long.MAX_VALUE` = indefinite) to `HabitEntity`. Bump DB version.
- [ ] **Model:** DAO query `getActiveHabits(now: Long)` filters pausedUntil.
- [ ] **ViewModel:** `pauseHabit(id, until)` / `resumeHabit(id)` in `viewModelScope.launch`.
- [ ] **View:** New `ui/components/PauseBottomSheet.kt` using `ModalBottomSheet` (M3) — options: indefinite / date picker.
- [ ] **View:** `ProgressItem.kt` renders dimmed + pause icon when paused.

### 2.3 Long-press context menu
- [ ] **View:** `ui/components/HabitContextMenu.kt` using `DropdownMenu` triggered by `Modifier.combinedClickable(onLongClick=…)`. 8 actions per `IDEAS.MD §4.4`.
- [ ] **ViewModel:** Add `duplicateHabit(id)` (suffix `" - copy"` / `" - copy (n)"`) — uses unique-name logic from Phase 1.
- [ ] **ViewModel:** Add `markProgressOnce(id)` reusing existing increment logic.

### 2.4 Reordering, grouping, filtering
- [ ] **Model:** Add `sortOrder: Int` and `categoryGroup: String?` columns. Bump DB version.
- [ ] **Model:** DAO updates: `getHabitsSorted(SortMode)` returning `Flow<List<HabitEntity>>`.
- [ ] **ViewModel:** Expose `sortMode: StateFlow<SortMode>` and `activeFilters: StateFlow<Set<String>>` (Pattern: **Observer**).
- [ ] **View:** `MainScreen.kt` — filter chip row + search field; collapsible group headers via `LazyColumn` `stickyHeader`.
- [ ] **View:** Drag & drop using community-standard `Modifier.pointerInput` with index swap (Pattern: **Command pattern** for swap action).

---

## PHASE 3 — Habit History & Streak Engine (Module 5.1 + 1.2 streak math)
**Goal:** Establish the canonical timestamp log that every later module (achievements, AI, sync) reads from.

### 3.1 History screen
- [ ] **Model:** DAO queries: `getCompletionsForHabit(id): Flow<List<HabitCompletionEntity>>`, `updateCompletion(...)`, `deleteCompletion(id)`, `insertRetroactive(...)`.
- [ ] **ViewModel:** New `HistoryViewModel.kt` in `ui/viewmodel/` — exposes `groupedByYearMonth: StateFlow<Map<Year, Map<Month, List<Entry>>>>` (Pattern: **MVVM + State Holder**).
- [ ] **View:** New `ui/screens/HistoryScreen.kt` — `LazyColumn` with collapsible Year > Month sections, edit/delete icons, FAB for retroactive add (Compose `DatePicker` + `TimePicker`).
- [ ] **Navigation:** Add `Screen.History(habitId)` to `navigation/Screen.kt` and route in `NavGraph.kt`.

### 3.2 Streak engine (pure logic)
- [ ] **Domain:** New `domain/usecase/CalculateStreakUseCase.kt` — returns `StreakResult(current, best)` (Pattern: **Use Case / Interactor**, single-responsibility).
- [ ] **Domain:** New `domain/model/StreakResult.kt`.
- [ ] **ViewModel:** Recompute streaks reactively by combining `HabitFlow` + `CompletionsFlow` with `combine(...)`.
- [ ] **Unit Tests (allowed, JUnit only):** Test `CalculateStreakUseCase` with synthetic timestamp lists (per workstyle, only when explicitly requested).

### 3.3 JSON export of history
- [ ] **Domain:** New `domain/usecase/ExportHistoryUseCase.kt` using `kotlinx.serialization`.
- [ ] **View:** `IconButton` in `HistoryScreen.kt` top bar → `Intent.ACTION_CREATE_DOCUMENT`.

---

## PHASE 4 — Gamification: Streaks & Achievements (Module 3)
**Goal:** Build the engagement layer on top of the streak engine.

### 4.1 Achievement domain model
- [ ] **Model:** New `data/model/AchievementEntity.kt` (id, key, unlockedAt, progress) + `AchievementDao.kt`. Bump DB version.
- [ ] **Domain:** New `domain/model/AchievementDefinition.kt` — sealed hierarchy of 50 achievements per `ACHIEVEMENTS.MD` (Pattern: **Strategy / Sealed Class polymorphism**).
- [ ] **Domain:** New `domain/usecase/EvaluateAchievementsUseCase.kt` — pure function `(habits, completions) -> Set<UnlockedAchievement>` (Pattern: **Strategy + Pure Function**).

### 4.2 Achievement reactivity & retraction
- [ ] **ViewModel:** New `AchievementsViewModel.kt` in `ui/viewmodel/` — observes habits+completions Flow, runs evaluator, persists deltas.
- [ ] **ViewModel:** Retraction — re-running evaluator after history edits revokes rows where requirement no longer holds (Pattern: **Observer + idempotent reducer**).

### 4.3 Achievement UI
- [ ] **View:** New `ui/screens/AchievementsScreen.kt` — total points header, "Latest" section, collapsible category groups, progress bars on locked items.
- [ ] **View:** New `ui/components/AchievementBanner.kt` — top-anchored sliding `Snackbar`-like Composable triggered by `SharedFlow<AchievementUnlocked>` (Pattern: **Event Bus via Flow**).
- [ ] **Navigation:** Add `Screen.Achievements`; reorder `BottomNavigationBar` to `[Achievements, Habits, Statistics]` in `NavGraph.kt`.

### 4.4 Main screen visual rewards
- [ ] **View:** `ProgressItem.kt` — milestone mini-celebrations at 25/50/75% (only when target makes them mathematically distinct), confetti on 100% (lightweight Compose animation).

---

## PHASE 5 — Statistics Screen Overhaul (Module 5.2)
**Goal:** Surface the data already collected. No AI yet — placeholders for AI sections.

- [ ] **Domain:** New use cases in `domain/usecase/`: `WeeklyOverviewUseCase`, `LifeBalanceUseCase`, `SparklineUseCase` (Pattern: **Use Case per query**).
- [ ] **ViewModel:** New `StatisticsViewModel.kt` exposes `overview`, `lifeBalance`, `perHabitStats: StateFlow<…>`.
- [ ] **View:** Refactor `ui/screens/StatisticsScreen.kt` — Global Overview card, Life Balance card, collapsed/expanded habit cards with sparkline + bar chart tabs (7D/30D/3M/ALL).
- [ ] **View:** New `ui/components/Sparkline.kt` and `ui/components/BarChart.kt` (Canvas-based, no third-party chart lib needed).

---

## PHASE 6 — On-Device AI Layer (Module 2)
**Goal:** Stage 1 = pure Kotlin math. Architected so a `.tflite` model can be swapped in later (Pattern: **Strategy + Dependency Inversion**).

### 6.1 AI infrastructure
- [ ] **Domain:** New package `domain/ai/` with interface `HabitPredictor` (abstraction).
- [ ] **Domain:** Implementation `MathHabitPredictor` (rule-based / statistical).
- [ ] **Domain:** Stub `TfliteHabitPredictor` for future swap.

### 6.2 Predictive features
- [ ] `SuccessProbabilityUseCase` — features: day, hour, streak, recent week, age.
- [ ] `OptimalTimeUseCase` — bins completions by hour, ranks slots.
- [ ] `HabitRecommendationUseCase` — co-occurrence rules from habit names/categories.
- [ ] `StreakRecoveryUseCase` — detects high-risk patterns (e.g., missing Sundays).
- [ ] `AdaptiveDifficultyUseCase` — suggests target up/down based on rolling completion rate.
- [ ] `MotivationMessageUseCase` — context-aware string templates with `<plurals>`.

### 6.3 Passive analytics
- [ ] `RoutinePrecisionUseCase` (stddev of timestamps).
- [ ] `ResilienceScoreUseCase` (avg recovery days).
- [ ] `HabitClashingUseCase` (Pearson correlation across habits).
- [ ] `ProcrastinationIndexUseCase` (skewness within deadline cycle).

### 6.4 AI icon selection
- [ ] **Domain:** `IconResolverUseCase` — Tier 1 keyword map (covers ~70%); Tier 2 stub for ML.
- [ ] **ViewModel:** Resolve icon when rendering `StatisticsScreen`; persist user override from `EditHabitScreen` icon picker.

### 6.5 Wire AI into Statistics
- [ ] **View:** Fill the placeholders from Phase 5: `🎯 Success Prediction`, `🕒 Optimal Timing`, `🧠 Behavioral Patterns`, `✨ Smart Insight` cards.

---

## PHASE 7 — Notifications & Widgets (Module 7)
**Goal:** Extend reach beyond the app. Depends on AI for smart timing/text.

### 7.1 Reminders
- [ ] **Model:** Add `reminderTime: Long?` to `HabitEntity`. Bump DB version.
- [ ] **Domain:** `ScheduleReminderUseCase` using `WorkManager` (Pattern: **Command pattern via WorkRequest**).
- [ ] **System:** New `notifications/HabitReminderWorker.kt` outside MVVM packages — posts `NotificationCompat` with action buttons (Done / Skip / Snooze).
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
                                 │     └─► P6 AI layer
                                 │           └─► P7 Notifications & Widgets
                                 └─► P8 Global UX/Theming/Locale
                                       └─► P9 Auth UI (fake repo)
                                             └─► P10 Firebase Sync
```

Each arrow = "consumes the data contract / abstraction defined upstream". Reversing any arrow forces a rewrite — that is why this ordering is the architecturally minimal one.

---
