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
| On-device ML | TensorFlow Lite 2.14 (3 trained models) |
| ML training pipeline | Python 3.10 · Keras · scikit-learn (in `ml-training/`) |
| Background work | WorkManager |
| Cloud (planned) | Firebase Auth · Firestore |
| Build system | Gradle (Kotlin DSL) |

## Architecture Overview

The project follows a strict **MVVM + Clean Architecture** layering:

```
ui/          — Compose screens and components (View layer)
ui/viewmodel — ViewModels exposing StateFlow<UiState> (ViewModel layer)
domain/      — Use cases, domain models, and AI predictor interfaces (business logic, no Android deps)
domain/ai/   — HabitPredictor interface + MathHabitPredictor (math) + TfliteHabitPredictor (ML)
data/        — Room entities, DAOs, and repositories (data layer)
navigation/  — Sealed-class route definitions and NavGraph
ml-training/ — Standalone Python pipeline: data generation, Keras training, TFLite export
```

Design patterns used throughout: **Repository**, **Observer** (via Flow), **Use Case / Interactor**, **Strategy + Dependency Inversion** (ML predictor swap), **Sealed Class state**, **Event Bus** (via SharedFlow).

## Current Status

> **PHASE 6.5 complete — moving to Phase 7 (Notifications & Widgets)**

Phases 0–6.5 are fully implemented:

- **Phases 0–4:** Room schema hardening, full CRUD UX, habit history, streak engine, achievements with unlock/retraction, gamification animations.
- **Phase 5:** Statistics screen with global overview, life-balance chart, per-habit sparklines and bar charts.
- **Phase 6:** On-device AI analytics layer — success probability, optimal timing, streak recovery, adaptive difficulty, motivation messages, routine precision, resilience score, habit clashing detection, and procrastination index.
- **Phase 6.5:** Three TFLite on-device ML models trained via a standalone Python pipeline (`ml-training/`): `HabitSuccessClassifier` (binary, ROC-AUC ≥ 0.88), `HabitIconClassifier` (17-class text, top-1 ≥ 0.75), and `ReminderTemplateClassifier` (15-class, top-1 ≥ 0.70). All predictions now run through `TfliteHabitPredictor`, which composes over the math layer and falls back to it gracefully when a model asset is unavailable.

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
