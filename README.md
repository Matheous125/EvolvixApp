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
| Background work | WorkManager |
| Cloud (planned) | Firebase Auth · Firestore |
| Build system | Gradle (Kotlin DSL) |

## Architecture Overview

The project follows a strict **MVVM + Clean Architecture** layering:

```
ui/          — Compose screens and components (View layer)
ui/viewmodel — ViewModels exposing StateFlow<UiState> (ViewModel layer)
domain/      — Use cases and domain models (business logic, no Android deps)
data/        — Room entities, DAOs, and repositories (data layer)
navigation/  — Sealed-class route definitions and NavGraph
```

Design patterns used throughout: **Repository**, **Observer** (via Flow), **Use Case / Interactor**, **Strategy**, **Sealed Class state**, **Event Bus** (via SharedFlow).

## Current Status

> PHASE 6 — On-Device AI Layer (In progress)

The core MVVM skeleton is in place: Room schema, DAOs, a `HabitViewModel` exposing `StateFlow<HabitUiState>`, and a sealed-class navigation graph. Active development is following a dependency-driven roadmap from data integrity hardening through gamification, AI analytics, notifications, and finally cloud sync.

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
