# GitHub Copilot / Claude Instructions

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding
**Don't assume. Don't hide confusion. Surface tradeoffs.**
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First
**Minimum code that solves the problem. Nothing speculative.**
- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- If you write 200 lines and it could be 50, rewrite it.
Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes
**Touch only what you must. Clean up only your own mess.**
- Don't "improve" adjacent code, comments, or formatting.
- Match existing style, even if you'd do it differently.
- Remove imports/variables/functions that YOUR changes made unused.
The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution & Academic Testing
**Define success criteria. Balance academic rigor with token efficiency.**
- **STRICT RESTRICTION:** Do NOT write Android UI Tests (Espresso/Compose UI testing) or Room Database instrumentation tests. Rely on manual UI verification via the Android Emulator.
- **Unit Tests:** Only write standard Unit Tests (JUnit) for pure business logic (ViewModels, AI Math, Streak Calculations) **when explicitly asked**. 
- For multi-step tasks, state a brief plan:
  1. [Step] → verify: [check]
  2. [Step] → verify: [check]

## 5. Android Architecture & UI (Thesis Standard)
**Follow standard MVVM architecture and Material Design 3.**
- Code must look like it was written by a solid CS student, not a veteran trying to show off. Keep Kotlin logic readable and easy to explain.
- **Compose UI:** Keep it strictly declarative. No business logic in the UI. 
- **Strict Material Design 3:** For all Compose UI, strictly use standard M3 components (`Scaffold`, `ElevatedCard`, `TopAppBar`, etc.). Rely on `MaterialTheme` for colors and typography. Do NOT build custom elaborate UI shapes.
- **ViewModel & DB:** Manage state using standard `StateFlow`. Use standard DAOs and Coroutines (`viewModelScope.launch`). 

## 6. Thesis-Level Documentation & Explainability
**The user is a CS student building an engineering thesis. Every line must be defendable.**
- **KDoc:** Add brief, professional KDoc (`/** ... */`) comments to all classes, ViewModels, and major functions. 
- **Inline Comments:** Briefly explain *why* a specific Android component is used.
- When providing code, explain it in standard CS terms (e.g., "This acts as a Singleton," or "This is an Observer pattern").

## 7. Token Conservation & Workflow Guardrails
**Maximize monthly token limits and avoid path hallucinations.**
- **No Path Guessing:** Before creating *any* new file, you must silently check `STRUCTURE.md` to ensure you are placing the file in the correct nested Android package directory and using the correct `package com...` declaration at the top of the file.
- **Room Database Migrations:** During development, do NOT write Room `Migration` code. Instruct the user to increment the database `version = X` and reinstall the app.
- Always consult `PLAN.md` before coding to maintain context.
- **Fail Fast:** If code fails twice in a row, STOP. Do not attempt a third fix. Advise the user to `git reset --hard` and rethink the approach.
- **No External Documentation Files:** Do NOT create, suggest, or update external documentation files like `etapy-wyjasnienia.md` or `roadmap.md`. All documentation must be strictly in-code using KDoc and inline comments.
- **Read-Only Plan:** `PLAN.md` is strictly read-only for the AI. Do NOT attempt to automatically edit `PLAN.md` to check off boxes (`[x]`). The user will manually track their own progress.