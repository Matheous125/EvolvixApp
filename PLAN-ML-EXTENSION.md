# PLAN-ML-EXTENSION.md — Future ML Feature Roadmap

**Scope:** Extension of `PLAN.md` Phase 6.5 — adds five no-new-data ML features and seven new data-collection upgrades that unlock further ML models.
**Conventions:** Inherits all rules from `PLAN.md`, `STRUCTURE.md`, and `.github/copilot-instructions.md`. All new TFLite work follows the **Strategy + Dependency Inversion** pattern already established in `domain/ai/`. All new Python training pipelines follow the `ml-training/` template.
**Status legend:** `[ ]` = not started · `[x]` = done (user marks manually).

---

## Global Standards (apply to every model in this document)

Every new ML feature MUST follow this checklist before it is considered "done":

- [ ] **Domain interface extension:** Add a new method to `domain/ai/HabitPredictor.kt` (interface). Both `MathHabitPredictor` and `TfliteHabitPredictor` must implement it (Strategy pattern). `Math*` provides a rule-based fallback usable when the `.tflite` asset fails to load.
- [ ] **Feature dataclass:** Add a `data class XxxFeatures` (or `XxxContext`) in `domain/ai/` mirroring the Python `feature_columns` order. Include a `toFloatArray()` helper.
- [ ] **Output domain model:** Add a result class in `domain/model/` (e.g. `AbandonmentRisk`, `WeeklyForecast`) so the View never sees raw `Float`/`Int` outputs.
- [ ] **Use case:** Add a `usecase/XxxUseCase.kt` interactor that extracts features from raw Room data and delegates the inference to the injected `HabitPredictor`.
- [ ] **ViewModel wiring:** Expose the result as a `StateFlow<…>` in `StatisticsViewModel` (or a new dedicated VM if it powers its own screen).
- [ ] **View card:** Add a Material 3 `ElevatedCard` to `StatisticsScreen.kt` (or the relevant screen) with a "not enough data" placeholder.
- [ ] **Python pipeline:** Add `ml-training/generate_<model>_data.py` (synthetic data) and `ml-training/train_<model>_model.py` (training + TFLite export + scaler JSON). Follow the structure of `train_success_model.py`.
- [ ] **Asset deployment:** Copy `<model>.tflite` and `<model>_scaler.json` into `app/src/main/assets/`. Update `TfliteHabitPredictor.init` to load the new interpreter defensively.
- [ ] **Evaluation:** Update `ml-training/evaluate_models.py` with the new model's metrics (accuracy / F1 / MAE depending on task). Save plots to `ml-training/data/plots/`.
- [ ] **KDoc:** Every new public class gets a `/** ... */` block explaining responsibility + pattern.

---

# PART 1 — ML Features on Already-Stored Data

These five features require **zero schema changes**. They consume only `HabitEntity`, `HabitCompletionEntity`, and `AchievementEntity`.

---

## Phase 8.1 — Habit Abandonment Predictor (binary classifier)

**Problem framing:** Given a habit's current state, predict the probability that it will receive **zero completions in the next 14 days**.
**Why distinct from Model 1:** Model 1 asks "will the user complete today?" — this asks "is the user about to quit entirely?" Different label, different time horizon, different intervention.

### 8.1.1 Data foundation (already exists)
- [x] Audit `HabitCompletionEntity` to confirm every required feature can be derived (habit age, days-since-last, 7-day rate, 30-day rate, current streak, total target reaches, frequency, pause history).

### 8.1.2 Python training pipeline (`ml-training/`)
- [x] `generate_abandonment_data.py` — synthesize ~50k habit-state snapshots. Each row = features at time T, label = `1` if no completions in (T, T+14d] else `0`. Bake in realistic priors: low recent rate + high days-since-last → high abandonment.
- [x] `train_abandonment_model.py` — small MLP (2 hidden layers, ReLU, sigmoid output). Save `habit_abandonment_classifier.tflite` + `abandonment_scaler.json` to `ml-training/models/`.
- [x] Update `evaluate_models.py` with precision/recall/F1; aim for F1 ≥ 0.75 on synthetic test split.

### 8.1.3 Android integration
- [x] **Model:** New `domain/ai/AbandonmentFeatures.kt` (8 fields).
- [x] **Interface:** Add `fun predictAbandonment(features: AbandonmentFeatures): Float` to `HabitPredictor`.
- [x] **Math fallback:** Rule chain in `MathHabitPredictor.predictAbandonment`: `daysSinceLastCompletion >= 7 && rate7d < 0.2` → 0.85; tune to match training priors.
- [x] **TFLite impl:** `TfliteHabitPredictor.predictAbandonment` — load and run the new interpreter; fall back to `mathFallback` on failure.
- [x] **Domain model:** `domain/model/AbandonmentRisk.kt` — `probability: Float`, `rating: { LOW, MEDIUM, HIGH, CRITICAL }`, `hasSufficientData: Boolean`.
- [x] **Use case:** `domain/usecase/AbandonmentRiskUseCase.kt` — extracts features, delegates to predictor, maps probability → rating thresholds.
- [x] **ViewModel:** Expose `abandonmentRisks: StateFlow<Map<Int, AbandonmentRisk>>` from `StatisticsViewModel`.
- [x] **View:** New "At Risk" `ElevatedCard` in `StatisticsScreen` listing habits with `rating >= HIGH`.
- [x] **Notification hook (optional):** Feed `AbandonmentRisk.rating == CRITICAL` into `ReminderContext.isAtRisk` so Model 3 picks `gentle_nudge_at_risk` more aggressively.

---

## Phase 8.2 — Streak Break Predictor (binary classifier)

**Problem framing:** Given an active streak, predict whether it will end within the next N periods (N = 3 for daily, 2 for weekly).
**Why distinct from `StreakRecoveryUseCase`:** Current logic is a hard rule (3 of last 4 same-weekday misses). This is a learned model picking up subtler signals (e.g. mature long streaks fail differently than young short streaks).

### 8.2.1 Python pipeline
- [x] `generate_streak_break_data.py` — synthesize streak-state snapshots labeled with "did streak survive next N periods." Features: streak length, habit age, 7-day rate, day-of-week, hour-of-day, recent gap pattern (avg gap last 30d).
- [x] `train_streak_break_model.py` — same architecture template as Model 1. Export `streak_break_classifier.tflite` + scaler JSON.

### 8.2.2 Android integration
- [x] **Model:** `domain/ai/StreakBreakFeatures.kt` (6–7 fields).
- [x] **Interface:** `fun predictStreakBreak(features: StreakBreakFeatures): Float`.
- [x] **Math fallback:** Reuse current `isStreakAtRisk` rule + boost from short streak + low rate.
- [x] **TFLite impl + use case + domain model** (`StreakBreakRisk`) following the standard checklist.
- [x] **View:** Extend the existing streak risk card in `StatisticsScreen` to show probability bar instead of boolean.

---

## Phase 8.3 — Weekly Performance Forecaster (regression)

**Problem framing:** Predict next 7-day completion rate ∈ [0.0, 1.0] for the entire user (not per habit).
**Architecture note:** This is a *regression* model, not classification — output layer is a single linear neuron (or sigmoid for [0,1] bounded output).

### 8.3.1 Python pipeline
- [x] `generate_weekly_forecast_data.py` — synthesize week-pairs. Features: last week's rate, current streak avg across habits, habit count, day-of-week-specific rates (7 features), week-of-year sin/cos (seasonality encoding). Label: next-week rate.
- [x] `train_weekly_forecast_model.py` — MLP with linear output + MAE loss. Export `.tflite` + scaler.

### 8.3.2 Android integration
- [x] **Model:** `domain/ai/WeeklyForecastFeatures.kt` (~11 fields including 7-day rate vector).
- [x] **Interface:** `fun predictWeeklyRate(features: WeeklyForecastFeatures): Float`.
- [x] **Math fallback:** Naive blend: `0.7 × lastWeekRate + 0.3 × monthRate`.
- [x] **Domain model:** `WeeklyForecast.kt` — `predictedRate`, `lastWeekRate`, `direction: { UP, FLAT, DOWN }`, `confidence`.
- [x] **Use case:** `WeeklyForecastUseCase.kt`.
- [x] **ViewModel + View:** Add forecast strip to `StatisticsScreen` weekly card.
- [x] **Summary integration:** Update `ComposeDailySummaryUseCase` (Sunday only) to include "Next week looks ↑/↓".

---

## Phase 8.4 — Habit Behavioral Clustering (unsupervised K-Means)

**Problem framing:** Group habits into N=4 behavioral tiers ("effortless routine," "consistent effort," "struggling," "dormant") based on 5 behavioral features.
**Architecture note:** K-Means is not a deep model — it doesn't need TensorFlow. Export the trained centroids as a plain JSON file; classification on-device is a simple nearest-centroid lookup. **No `.tflite` file needed.**

### 8.4.1 Python pipeline
- [x] `generate_clustering_data.py` — synthesize ~10k habit-state vectors covering the full behavioral spectrum.
- [x] `train_clustering_model.py` — `sklearn.cluster.KMeans(n_clusters=4)`. Use silhouette score to validate. Export centroids + per-cluster label names to `habit_clusters.json`.
- [x] **No TFLite export** — JSON only.

### 8.4.2 Android integration
- [x] **Model:** `domain/ai/ClusterFeatures.kt` (5 fields: rate30d, routinePrecisionStddev, procrastinationSkew, habitAge, resilienceAvgGap).
- [x] **Interface:** `fun classifyBehavioralCluster(features: ClusterFeatures): String`.
- [x] **Implementation:** Load `habit_clusters.json` in `TfliteHabitPredictor.init`. Classification = argmin of Euclidean distance to centroids. Math fallback = threshold-based bucketing on rate30d alone.
- [x] **Domain model:** `BehavioralCluster.kt` — sealed class with 4 cases, each carrying a localized description string key.
- [x] **Use case + ViewModel + View card** following the standard checklist.

---

## Phase 8.5 — Cross-Habit Spillover Model (regression)

**Problem framing:** Given habit A was completed at hour H today, by how much does that change the probability of completing habit B today? Output: delta ∈ [-0.5, +0.5].
**Why distinct from `HabitClashingUseCase`:** Pearson r captures linear correlation, not directional / temporal lift. This model is asymmetric (A→B may lift, B→A may not) and time-aware.

### 8.5.1 Python pipeline
- [x] `generate_spillover_data.py` — for each habit-pair, build per-day rows: features = (rate of A, rate of B, hour A completed, time gap to potential B), label = "was B completed within the rest of that day."
- [x] `train_spillover_model.py` — small MLP outputting the lift delta. Export `.tflite` + scaler.

### 8.5.2 Android integration
- [ ] **Model:** `domain/ai/SpilloverFeatures.kt` (5 fields).
- [ ] **Interface:** `fun predictSpillover(features: SpilloverFeatures): Float`.
- [ ] **Math fallback:** Co-occurrence-rate-based heuristic (extension of existing `relatedHabits` logic).
- [ ] **Domain model:** `SpilloverPair.kt` — `habitA`, `habitB`, `liftDelta`, `direction`.
- [ ] **Use case:** `SpilloverUseCase.kt` evaluates all habit pairs after habit A is completed today.
- [ ] **View:** Append to `StatisticsScreen` AI-insights card with text like "Doing X in the morning makes Y 23% more likely."
- [ ] **Reminder integration:** When a habit A with strong positive spillover to B is completed, optionally trigger an earlier reminder for B via `ScheduleReminderUseCase`.

---

# PART 2 — Features Requiring New Data Collection

Each sub-phase here has TWO halves: (a) **Data collection** (schema migration + UI tap to capture the signal) and (b) **ML feature** that the new data unlocks. Ship (a) first, accumulate data for a real test cohort, then train (b).

---

## Phase 9.1 — `fromReminder: Boolean` → Reminder Effectiveness Model

### 9.1.1 Data collection
- [ ] **Model:** Add `val fromReminder: Boolean = false` to `HabitCompletionEntity`. Bump DB version (no migration code — per workstyle, reinstall).
- [ ] **Notification flow:** In `HabitActionReceiver`, when the "Done" action is tapped, pass `fromReminder = true` through the Intent extra into the new completion row.
- [ ] **Default value:** Manual taps in `MainScreen` / `ProgressItem` insert `false`.

### 9.1.2 ML feature: Reminder Effectiveness Predictor
- [ ] **Goal:** Given a habit's profile + time slot, predict P(completion within 30 min | reminder sent) vs P(completion within 30 min | no reminder). If the lift is < ε, suppress that reminder.
- [ ] **Python pipeline:** `generate_reminder_lift_data.py` + `train_reminder_lift_model.py`. Causal-style framing: train two models (with/without reminder), output lift as their difference.
- [ ] **Android integration:** Standard checklist. Plug result into `ScheduleReminderUseCase` to skip low-lift reminders.

---

## Phase 9.2 — `snoozeCount: Int` → Snooze Behavior Predictor

### 9.2.1 Data collection
- [ ] **Storage choice:** Per-reminder counter in `SharedPreferences` (key: `snooze_count_<habitId>`); reset when a completion or skip is logged for that habit. Persist the **final** `snoozeCount` onto the eventual completion row via a new nullable `val snoozeCount: Int? = null` column. Bump DB version.
- [ ] **Snooze flow:** Update `HabitActionReceiver` to increment the SharedPreferences counter when "Snooze" is tapped, then reschedule the reminder via `ScheduleReminderUseCase`.

### 9.2.2 ML feature: Snooze Disengagement Predictor
- [ ] **Goal:** Predict whether the user will abandon the habit within 7 days given the recent snooze pattern.
- [ ] **Python pipeline + Android integration:** Standard checklist. Use the output to soften reminder templates (Model 3 retrain with `snoozeCount` as an 8th input feature — non-trivial: requires retraining Model 3 too).

---

## Phase 9.3 — `targetVersion: Int` + `HabitTargetHistoryEntity` → Target Change Effectiveness Model

These two are deeply linked; ship them together.

### 9.3.1 Data collection
- [ ] **Model:** Add `val targetVersion: Int = 1` to `HabitCompletionEntity`. Bump DB version.
- [ ] **New entity:** `HabitTargetHistoryEntity(habitId, oldTarget, newTarget, changedAt, version)`. New `TargetHistoryDao` with `insert` + `getForHabit(habitId): Flow<List<…>>`.
- [ ] **Edit flow:** In `HabitViewModel.updateHabit`, when target changes, insert a history row and increment `targetVersion`. New completions inherit the latest version.

### 9.3.2 ML feature: Target Adjustment Recommender
- [ ] **Goal:** Replace the hard-coded ±1 rule in `AdaptiveDifficultyUseCase` with a learned regressor predicting the optimal new target given current target + 30d rate + perceived difficulty signal.
- [ ] **Python pipeline + integration:** Standard checklist. Output: integer delta in {-2, -1, 0, +1, +2}.

---

## Phase 9.4 — `perceivedDifficulty: Int?` → Difficulty-Aware Models

### 9.4.1 Data collection
- [ ] **Model:** Add `val perceivedDifficulty: Int? = null` to `HabitCompletionEntity`. Range 1–5. Bump DB version.
- [ ] **View:** Add a small star-rating row to `ProgressItem` that appears for ~5 seconds after a completion. Tap to set, otherwise stays null. No friction if ignored.
- [ ] **DAO:** Add `getCompletionsWithDifficulty(habitId)` for analytics queries.

### 9.4.2 ML feature: True Difficulty Estimator + Adaptive Difficulty v2
- [ ] **Goal:** Train a regressor predicting expected `perceivedDifficulty` given the habit's profile, day, hour, streak, and energy state. Combine with `targetVersion` model to suggest target changes weighted by user-reported difficulty, not just completion rate.
- [ ] **Python pipeline + integration:** Standard checklist. Model 1 retrain optional: add `recentAvgDifficulty` as an 8th feature for improved success prediction.

---

## Phase 9.5 — `HabitSkipEntity` table → Skip Reason Classifier

### 9.5.1 Data collection
- [ ] **Model:** New entity `HabitSkipEntity(id, habitId, skippedAt, reason: SkipReason)`. New enum `SkipReason { TOO_TIRED, TOO_BUSY, FORGOT, SICK, TRAVELING, NO_REASON }`. New `HabitSkipDao` with insert + flow queries.
- [ ] **Notification flow:** Update `HabitActionReceiver` Skip action — open a tiny bottom-sheet activity with 6 reason chips. Default = `NO_REASON` if dismissed.
- [ ] **In-app:** Add a "Skip" item to `HabitContextMenu` with the same reason picker.

### 9.5.2 ML feature: Skip Reason Predictor + Resilience v2
- [ ] **Goal A:** Given habit state + time, predict the most likely upcoming skip reason. Used proactively (e.g. on a Friday evening for a Saturday gym habit, predict `TOO_TIRED` and pre-send a gentle reminder).
- [ ] **Goal B:** Upgrade `ResilienceScoreUseCase` — exclude `SICK` and `TRAVELING` skips from gap math; only "voluntary" skips count against resilience.
- [ ] **Python pipeline:** Multi-class classifier (6 classes). Standard checklist.

---

## Phase 9.6 — `AppSessionEntity` table → Engagement Window Predictor

### 9.6.1 Data collection
- [ ] **Model:** New entity `AppSessionEntity(id, startedAt, endedAt, screensVisited: List<String>)`. New `AppSessionDao`.
- [ ] **Lifecycle hook:** In `MainActivity`, observe `ProcessLifecycleOwner` — log `startedAt` on `ON_START` and update `endedAt` + screensVisited on `ON_STOP`. Track screen visits via a `NavController.OnDestinationChangedListener` writing to a `currentScreensVisited: MutableList<String>` held in a singleton `SessionTracker`.

### 9.6.2 ML feature: Engagement Window Predictor
- [ ] **Goal:** Predict the user's natural daily app-open window. Use it to schedule reminders just before that window so the user sees the notification when they would have checked the app anyway.
- [ ] **Python pipeline:** Regression model (output: hour-of-day with highest open probability). Standard checklist.
- [ ] **Integration:** Optional override in `ScheduleReminderUseCase` — if engagement window is well-defined, use it instead of `findOptimalHours`.

---

# Final Advice

## Are these features actually possible with ML, or AI hallucination?

**All twelve features are genuinely implementable with standard supervised / unsupervised ML.** Specifically:

- **Phases 8.1, 8.2, 9.1, 9.2, 9.4, 9.5** are classic binary or multi-class classification problems — small MLPs trained on tabular features. Exactly the same shape and size as the existing Models 1 and 3.
- **Phase 8.3 (Weekly Forecaster), 8.5 (Spillover), 9.3 (Target Recommender)** are tabular regression problems. Same training pipeline template, only the output layer and loss function change.
- **Phase 8.4 (Clustering)** is textbook unsupervised K-Means with `sklearn` — no neural network, no TensorFlow Lite, just a JSON of centroids loaded at app start.
- **Phase 9.6 (Engagement Window)** is a per-user time-series problem; even a simple Gaussian mixture over open-hour timestamps would work without TensorFlow.

**One honest caveat:** during development you will train these models on **synthetic data** (just like `generate_success_data.py` already does), because real user data does not yet exist. This is a well-established practice for engineering theses — defensible as long as you clearly state in the thesis that the priors baked into the data generators encode behavioral hypotheses, not measured ground truth. The Strategy + math-fallback architecture means the rule-based predictor is *always* a safety net, so a poorly-trained ML model cannot break the app.

Two features need extra honesty in the thesis: **9.1 (Reminder Effectiveness)** is fundamentally a causal-inference problem, and from observational data alone you can only estimate correlational lift — frame it as a "predicted lift estimator" not a "causal effect estimator." **8.5 (Spillover)** has the same caveat.

## Claude Opus vs Claude Sonnet

**Use Opus for planning and architecture, Sonnet for implementation.** This is precisely the workflow this plan was written for:

- **Opus is best at:** the kind of plan this document represents — multi-feature roadmaps, choosing where Strategy pattern fits, deciding which models share a feature vector, identifying the causal-inference caveats above, and writing the math derivations for the thesis chapter.
- **Sonnet is good enough for:** every individual checkbox in this plan. Adding a column to a Room entity, writing a Python `generate_*_data.py` mirroring an existing template, implementing a `XxxUseCase.kt` that follows the same shape as `AdaptiveDifficultyUseCase.kt`, wiring a new `StateFlow` into a ViewModel, building an `ElevatedCard` in Compose — these are well-defined mechanical translations of a clear plan into code, which Sonnet executes accurately when given precise file paths and patterns to follow.

**Concrete workflow recommendation:** use Opus to start each new phase (re-read this plan, confirm the design, sketch the feature dataclass and use case shape). Switch to Sonnet for the per-file implementation steps. Switch back to Opus only when (a) the model evaluation metrics are unexpectedly poor and need debugging, (b) you need to write the thesis ML chapter prose, or (c) you're integrating across more than three files at once. This minimizes Opus token spend (which is significant) while keeping the architectural decisions sharp.
