# Evolvix — Habit Tracker

An Android habit-tracking application developed as an **engineering thesis project**.

## Background

This application is the continuation and evolution of an earlier habit tracker built for university coursework (not publicly available). The thesis version expands on that foundation with a production-grade architecture, on-device AI analytics, gamification, and cloud synchronization — moving from a class exercise to a fully defensible engineering artifact.

## Description

Evolvix allows users to build and maintain daily habits through a clean, data-driven interface. The app tracks completion history, computes streaks, evaluates achievements, and applies statistical models to predict success probability and suggest optimal routines — all processed on-device.

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose · Material Design 3 |
| Architecture | MVVM · StateFlow · Coroutines |
| Persistence | Room (SQLite) |
| On-device ML | TensorFlow Lite 2.14 (14 trained models) |
| ML training pipeline | Python 3.10 · Keras · scikit-learn (in `ml-training/`) |
| Background work | WorkManager |
| Cloud sync | Firebase Auth · Firestore (bidirectional Room ↔ Firestore via SyncController) |
| Build system | Gradle (Kotlin DSL) |

## Architecture Overview

The project follows a strict **MVVM + Clean Architecture** layering:

```
ui/          — Compose screens and components (View layer)
ui/viewmodel — ViewModels exposing StateFlow<UiState> (ViewModel layer)
domain/      — Use cases, domain models, and AI predictor interfaces (business logic, no Android deps)
domain/ai/   — HabitPredictor interface + MathHabitPredictor (math) + TfliteHabitPredictor (ML)
domain/sync/ — SyncController (Room ↔ Firestore mediator) + SyncState sealed class
data/        — Room entities, DAOs, and repositories (data layer)
navigation/  — Sealed-class route definitions and NavGraph
notifications/ — WorkManager workers, BroadcastReceivers, session tracking
ml-training/ — Standalone Python pipeline: data generation, Keras training, TFLite export
```

Design patterns used throughout: **Repository**, **Observer** (via Flow), **Use Case / Interactor**, **Strategy + Dependency Inversion** (ML predictor swap), **Sealed Class state**, **Event Bus** (via SharedFlow), **Mediator** (SyncController).

## Current Status

> **All planned phases complete** — Phases 0–9.6, ML Extension (8.1–8.5, 9.1–9.6), Model Retraining (R1–R10), and Firebase sync foundation (Phase 10)

All phases are fully implemented:

- **Phases 0–4:** Room schema hardening, full CRUD UX, habit history, streak engine, achievements with unlock/retraction, gamification animations.
- **Phase 5:** Statistics screen with global overview, life-balance chart, per-habit sparklines and bar charts.
- **Phase 6:** On-device AI analytics layer — success probability, optimal timing, streak recovery, adaptive difficulty, motivation messages, routine precision, resilience score, habit clashing detection, and procrastination index.
- **Phase 6.5:** Three initial TFLite on-device ML models: `HabitSuccessClassifier` (binary, ROC-AUC ≥ 0.88), `HabitIconClassifier` (17-class, top-1 ≥ 0.75), and `ReminderTemplateClassifier` (15-class, top-1 ≥ 0.70). All predictions route through `TfliteHabitPredictor` with graceful math fallback.
- **Phase 7:** Push notifications system — per-habit `HabitReminderWorker`, daily summary `DailySummaryWorker`, `HabitActionReceiver` with Done/Snooze/Skip actions, `SkipReasonPickerActivity` with 6-reason bottom sheet, `SummaryInboxScreen` with unread-badge tracking, dismissal counting, and `DebugTriggers` for thesis defence.
- **Phase 8 (ML Extension — no new data required):** Five additional TFLite models trained on already-stored data: `HabitAbandonmentClassifier` (binary, F1 ≥ 0.75), `StreakBreakClassifier` (binary), `WeeklyForecastRegressor` (MAE ≤ 0.12), `BehavioralClustering` (K-Means, 4 clusters via JSON centroids, silhouette 0.45), and `SpilloverRegressor` (directional habit-pair lift, MAE ≤ 0.08). All surface as `ElevatedCard` widgets on `StatisticsScreen`.
- **Phase 9 (Data collection + new ML models):** Six schema extensions unlocking six additional models: `fromReminder: Boolean` → `ReminderLiftClassifier` (Phase 9.1); `snoozeCount: Int` → `SnoozeDisengagementClassifier` (Phase 9.2); `targetVersion + HabitTargetHistoryEntity` → `TargetAdjustmentRegressor` (Phase 9.3); `perceivedDifficulty: Int?` + star-chip rating UI → `PerceivedDifficultyRegressor` (Phase 9.4); `HabitSkipEntity + SkipReason enum` + skip picker → `SkipReasonClassifier` (Phase 9.5); `AppSessionEntity + SessionTracker` + screen visit logging → `EngagementWindowRegressor` (Phase 9.6). Total: **14 TFLite / JSON models** deployed to `app/src/main/assets/`.
- **Model Retraining R1–R10:** All ten sequential model retrain sessions complete. Key improvements: Model 3 gains `snoozeCount` (R1) and continuous `abandonmentProbability` stacking (R3); Abandonment model excludes involuntary skips (R2); K-Means gains skip-rate dimensions (R4, K=4 retained by silhouette gate); StreakBreak excludes travel/sickness + adds perceived difficulty (R5); Model 1 gains `recentAvgDifficulty` (R6, AUC 0.894) then `spilloverLiftAggregate` (R7, AUC 0.901); ReminderLift gains snooze + difficulty features (R8); TargetChange gains difficulty grinding-suppressor (R9); WeeklyForecast gains cluster proportions + mean abandonment risk (R10, MAE 0.050).
- **Phase 10 (Firebase sync foundation):** `SyncController` mediator implementing bidirectional habit/completion sync (timestamp-union merge, zero data-loss guarantee) + `SyncWorker` (periodic + on-reconnect WorkManager scheduling) + `SyncState` sealed class observed in `MainScreen` top bar. Auth layer (`AuthRepository` interface + `FakeAuthRepository` stub) ready for `FirebaseAuthRepository` swap.

See [`PLAN.md`](PLAN.md) for the full phased roadmap.

## Running the Project

1. Clone the repository.
2. Open in **Android Studio Hedgehog** or newer.
3. Sync Gradle and run on an emulator or physical device (API 26+).
4. No external API keys are required for the current phase.

## Thesis Context

**Institution:** Poznan University of Technology  
**Degree programme:** Computer Science (Engineering)  
**Academic year:** 2025/2026  
**Supervisor:** Ariel Antonowicz, PhD  
**Assistant Supervisor:** Mateusz Leszek, MSc
