# PLAN — Polish Pass (Post-Phase 9 / Pre-Defense)

> One feature per session. Tick boxes manually after manual emulator verification.
> Sonnet executes; **Opus action required** is flagged per item.
> No external doc files created during execution — all explanation lives in KDoc.

---

## Legend

- **Scope tag:** `[FIX]` quick patch · `[IMPROVE]` deeper work · `[FEATURE]` net-new · `[REFACTOR]` UI restructure · `[SEED]` data only · `[OPTIONAL]` skip if time-short
- **Risk:** L / M / H (chance of breaking unrelated screens)
- **Opus?** Yes = needs Opus model (architectural reasoning, ML/Firebase design); No = Sonnet handles it
- **Verify:** manual UI check on emulator (per `copilot-instructions.md`, no Espresso/UI tests)

---

## Group A — Quick UI Fixes (do first, lowest risk, builds momentum)

### A1. `[FIX]` Non-actionable chips → unclickable · Risk: L · Opus? No
Currently chips for Perceived Difficulty (easy / very hard) and the per-habit Success Rate chip use `AssistChip` / `SuggestionChip`, which render with a click ripple and look interactive.
- **Files:** `StatisticsScreen.kt` — find every `AssistChip` / `SuggestionChip` / `FilterChip` whose `onClick` is empty (`{}`) or purely cosmetic.
- **Change:** replace with a non-interactive `Surface` + `Text` styled to mimic chip appearance (rounded corner, container color, label small). Match current `Streak / Best` styling (`StatBox`).
- **Verify:** difficulty chip and success-rate chip no longer show ripple on tap; streak chips unchanged.

### A2. `[FIX]` Remove "AI Insight" dead box from Weekly Insight · Risk: L · Opus? No
- **Files:** `StatisticsScreen.kt` — locate `AiPlaceholderBox` inside `LifeBalanceCard` (~line 1011), delete the composable call AND the `AiPlaceholderBox` declaration if unused elsewhere.
- **Strings:** remove `R.string.ai_insight_*` if no other reference (grep first).
- **Verify:** no empty space gap in `SummaryGroupCard`.

### A3. `[FIX]` HabitScreen TopAppBar order: Sort · Inbox · Settings · Debug · Risk: L · Opus? No
- **Files:** `HabitScreen.kt` (or `MainScreen.kt` wherever the AppBar is composed — grep `TopAppBar` + `actions`).
- **Change:** reorder the four `IconButton` entries; no behavior change.
- **Verify:** visual order on emulator.

### A4. `[FIX]` DailySummary — duplicate subject in body · Risk: L · Opus? No
- **Files:** Daily summary notification builder (grep `Today's wins` or the resource id used for the title); also the path that writes the same body into the inbox (`InboxRepository` / `InboxEntity` insertion site).
- **Change:** strip the subject prefix from the body string. Likely a `setContentText("$subject\n$body")` pattern — drop the `"$subject\n"`. Verify the inbox writer is reading the same body, not re-prepending.
- **Verify:** trigger a daily summary (use debug menu if available, or wait); inspect notification text + inbox entry.

### A5. `[FEATURE-REMOVAL]` Remove "Skip today" from habit context menu · Risk: L · Opus? No
- **Files:** the long-press / context menu on `HabitListItem` (grep `Skip today` or `R.string.menu_skip*`).
- **Change:** delete the `DropdownMenuItem`. Do NOT delete the underlying `skipHabit` / `HabitSkipEntity` plumbing — it stays for the smart-reminder "Skip" action.
- **Verify:** long-press menu no longer shows Skip; skip via reminder notification still works.

---

## Group B — Implementation Improvements

### B1. `[IMPROVE]` ML-driven emoji for the 🏷️ category badge · Risk: L · Opus? No
- **Approach (already analyzed last session):**
  1. In `StatisticsViewModel.Companion`, add:
     ```kotlin
     private val BUILTIN_CATEGORY_EMOJI = mapOf(
         "Health" to "❤️", "Fitness" to "💪", "Learning" to "📚",
         "Mindfulness" to "🧘", "Productivity" to "📅",
         "Social" to "💬", "Finance" to "💰"
     )
     ```
  2. Add field `resolvedCategoryEmojis: List<Pair<String, String>>` to `PerHabitStats` (display name + emoji).
  3. In the `habits.map { … }` loop, build it via `BUILTIN_CATEGORY_EMOJI[cat] ?: CATEGORY_EMOJI[predictor.classifyIcon(cat)] ?: "🏷️"`.
  4. In `StatisticsScreen.kt`, replace the hardcoded `"🏷️ ${categoryDisplayName(cat)}"` with the resolved pair.
- **Notes:** keep `categoryDisplayName(cat)` for the display name (handles localization). Only the emoji is ML-driven for custom categories.
- **Verify:** create a habit with custom category `"Self-care"` → emoji should resolve to `❤️` or `🧘`; `"Health"` → `❤️`; built-ins unchanged.

### B2. `[FIX]` Change-Password screen — old + new + confirm · Risk: M · Opus? No
- **Files:** `SetNewPasswordScreen.kt` (or whatever the route is called — grep `changePassword`), `AuthViewModel`, `AuthRepository.changePassword`.
- **Changes:**
  - UI: three fields — `oldPassword`, `newPassword`, `confirmPassword`. Validation: new == confirm, length rule, old non-blank.
  - `AuthRepository.changePassword(oldPassword, newPassword)` — extend signature.
  - `FakeAuthRepository`: store the current password locally; on change, compare and reject `Result.failure(WrongPasswordException)` if mismatch.
  - `FirebaseAuthRepository` (when introduced in Phase 10): perform `reauthenticateWithCredential(EmailAuthProvider.getCredential(email, oldPassword))` before `updatePassword(newPassword)`.
- **Verify:** wrong old password → red error under field; matching old + matching new pair → success snackbar.

### B3. `[IMPROVE]` Engagement Window — StatisticsScreen retention correlation · Risk: M · Opus? **Yes (light)**
The `screensVisited` field is logged but never read. Need to surface the correlation between StatisticsScreen visits and 7-day retention.
- **Opus action:** decide the metric. Suggested simple version (defensible for thesis):
  - For each user-session window of last 30 days, label sessions as `analyticsViewer = "StatisticsScreen" in screensVisited`.
  - Compute `retentionWithViewer = uniqueActiveDays(analyticsViewer=true) / 30`
  - Compute `retentionWithoutViewer = uniqueActiveDays(analyticsViewer=false) / 30`
  - Compute `lift = retentionWithViewer - retentionWithoutViewer` (Bernoulli-style).
  - Show only if ≥10 sessions of each kind (otherwise hide for insufficient data).
- **Sonnet execution after Opus signs off:**
  - New use case `AnalyticsEngagementUseCase` in `domain/usecase/`. Pure Kotlin, reads `AppSessionDao.getAll()`.
  - Returns `data class AnalyticsEngagement(lift: Float, hasSufficientData: Boolean, viewerActiveDays: Int, nonViewerActiveDays: Int)`.
  - Inject into `StatisticsViewModel`, expose via `uiState`.
  - Render at the top of `SummaryGroupCard` as one line: "Analytics viewers stay active +X days/month longer (lift Y%)."
- **Seeder:** see C-section.
- **Verify:** seed shows the headline; emulator session without StatisticsScreen visits over 7 days hides it.

### B4. `[IMPROVE]` Better-explained Smart Reminders, Snooze Drift, Target Confidence · Risk: L · Opus? No
- **Files:** `StatisticsScreen.kt` — `SmartRemindersCard`, `SnoozeDriftRow`, `TargetCalibrationRow`. Strings live in `values/strings.xml` and `values-pl/strings.xml`.
- **Pattern to copy:** Phase 6 `suggestTargetDelta` shows full sentence "📈 You're consistently hitting the target — consider raising it by 1." Mirror this verbosity:
  - Smart Reminders: "🔔 Sending reminders for *Morning Run* lifts completion by ~18% vs. silent days — recommend ON." vs. "🔕 Reminders for *Morning Run* show no measurable boost (~+2%) — recommend OFF."
  - Snooze Drift: "⚠️ *Wake Up No Phone* — High snooze rate (avg 3×/day) and only 1/7 completions this week. 85% chance of zero completions in the next 7 days."
  - Target Calibration: "🎯 *Meditate* — Suggest target 14 → 15 (HIGH confidence, model is decisive). Habit is consistently hit."  vs. "(LOW confidence — model is uncertain, treat as a hint, not a directive.)"
- **Verify:** read each card aloud — a non-developer should understand decision + reason + confidence.

---

## Group C — DatabaseSeeder Expansion (one combined session)

> Goal: every analytical card on StatisticsScreen has at least one habit that triggers it, AND BehavioralTiers shows both BOOST and DRAG.

### C1. `[SEED]` Add habits + tweak existing to demonstrate every feature · Risk: M · Opus? **Yes (one-shot data design)**
- **Opus action:** design the seed habits matrix on paper before coding. Per habit, declare: id, name, frequency, target, category, completion pattern (days-ago list), `fromReminder`, `snoozeCount`, `perceivedDifficulty`, expected card(s) it triggers.
- **Required coverage matrix (each row must have ≥1 habit):**
  | Card | Currently | Action |
  |---|---|---|
  | Weekly Forecast | covered | keep |
  | Life Balance | covered | keep |
  | EngagementWindow (active hours) | covered | keep |
  | Analytics Retention (NEW B3) | partial — `screensVisited=listOf("StatisticsScreen")` only one row | seed ~20 sessions over 30 days: half with `screensVisited=listOf("HabitScreen", "StatisticsScreen", ...)`, half without; pattern: viewer days should have ~30% more habit completions to surface a positive lift |
  | AtRiskCard (abandonment) | covered by 905 | keep |
  | SnoozeDriftCard | covered by 906 | keep |
  | BehavioralTiers — BOOST pair | **missing** | add habit 907 "Stretch 5 min" co-occurring with 901 Morning Run ≥80% of days → BOOST ≈ +0.15 |
  | BehavioralTiers — DRAG pair | only 901→903 ≈ −0.06 (weak) | strengthen: add habit 908 "Late-night Doomscroll" with completions on days 902 Read 30 min was NOT done → DRAG ≈ −0.20 |
  | Behavioral Clustering | covered (Thriving + Struggling) | add 1 habit clearly in "Moderate" tier so all 3 cluster tiers visible |
  | Smart Reminders (lift positive) | covered by 901 | keep |
  | Smart Reminders (lift negative / suppress) | likely missing | add habit 909 with `fromReminder=true` completions but rate equivalent to spontaneous days → lift ≈ 0, recommendSend=false |
  | Target Calibration HIGH confidence | covered by 904 (+2) | keep |
  | Target Calibration LOW confidence | likely missing | add a habit with rawDelta ≈ 0.5 (ambiguous) |
  | Perceived Difficulty | partially covered | ensure ≥1 habit with diverse 1–5 ratings across recent completions |
  | Skip Reason forecast | check — need ≥1 habit with several `HabitSkipEntity` rows with varied `SkipReason` |
- **Sonnet execution:** after Opus matrix:
  1. Extend `DatabaseSeeder.kt` — add habits 907–912 (or however many the matrix needs).
  2. Add seeded `AppSessionEntity` rows for B3 retention (loop generating 30 days).
  3. Add seeded `HabitSkipEntity` rows where missing.
  4. Update `STRUCTURE.md` for any new entity additions (no new entities expected, just rows).
- **Verify:** open emulator, hit "Seed" → scroll StatisticsScreen → every card from the matrix is visible and shows a meaningful state (not "insufficient data").

---

## Group D — StatisticsScreen Reorganization (single big session)

### D1. `[REFACTOR]` Three collapsible global blocks + self-contained habit cards · Risk: H · Opus? **Yes (review final layout before merge)**
- **Opus action up-front:** review the wireframe below + confirm collapse/expand state strategy (LocalSavedState? hoisted? remembered in ViewModel for cross-process?). Suggested: per-block `rememberSaveable { mutableStateOf(true) }` — simple, survives config change, no persistence across process death (acceptable).
- **New LazyColumn order:**
  ```
  Block 1 — "Your Week" (collapsible, default EXPANDED)
    ├── Analytics Retention headline (B3) — single line above forecast strip
    ├── Weekly Forecast strip
    ├── Life Balance categories
    └── EngagementWindow "When you're most active"
  Block 2 — "⚠ Attention Needed" (collapsible, default EXPANDED — only renders if any entry exists)
    ├── AtRiskCard entries (CRITICAL/HIGH)
    └── SnoozeDriftCard entries
  Block 3 — "Habit Interactions" (collapsible, default COLLAPSED — heavy reading)
    ├── BehavioralTiersCard (clustering)
    └── Spillover BOOST/DRAG pairs
  Per-habit HabitStatsCard (current expanded design)
    ├── Replace Phase 6 hint with Phase 9.3 result (rawDelta + arrow + confidence chip)
    ├── Add collapsed "Smart Reminders" row (per-habit lift % + ON/OFF recommendation)
    ├── Add collapsed "Predicted skip reason" row
    └── Add collapsed "Difficulty estimate" row
  ```
- **Files:** mostly `StatisticsScreen.kt`. `StatisticsViewModel` already exposes all data per-habit — only mapping changes.
- **Composable to introduce:** `CollapsibleBlock(title, icon, defaultExpanded, content: @Composable () -> Unit)` reused 3×.
- **Removed:** the standalone global `SmartRemindersCard`, `SnoozeDriftCard` (when not in Attention block), `TargetCalibrationCard`, `PerceivedDifficultyCard`, `SkipReasonForecastCard` — their data moves into per-habit cards. Confirm by grep that no other screen depends on them.
- **Strings:** new `R.string.block_your_week`, `block_attention_needed`, `block_habit_interactions`. Polish translations too.
- **Apply B4 verbose explanations** within the per-habit Smart Reminders / Snooze / Target rows here.
- **Verify:** manual smoke test of every block; expand all per-habit cards; confirm all data still reachable; confirm Phase 9.3 Target Adjustment is the visible result (not Phase 6 fallback) on habits that have it.

---

## Group E — Additional Features

### E1. `[FEATURE]` Edit Habit → "Reset Progress" implementation · Risk: M · Opus? No
- **Files:**
  - `EditHabitScreen.kt` — already has the `DropdownMenuItem` calling some no-op. Wire to `viewModel.resetProgress(habitId)`.
  - `HabitViewModel` — add `fun resetProgress(habitId: Int)` (different from `checkAndResetProgress` which is periodic).
  - `HabitDao` — add a transaction method `resetHabitProgress(habitId: Int)`:
    - `DELETE FROM habit_completions WHERE habitId = :habitId`
    - `DELETE FROM habit_skips WHERE habitId = :habitId`
    - `DELETE FROM habit_target_history WHERE habitId = :habitId` (optional — discuss)
    - `UPDATE habits SET currentCount = 0, totalProgressUpdates = 0, totalTargetReaches = 0, lastResetDate = :today WHERE id = :habitId`
- **Preserve:** all `AchievementEntity` rows (achievements are user-level, not habit-level — confirm by grep on `AchievementEntity` foreign key; if any FK to habit exists, decide explicitly).
- **Confirmation dialog:** AlertDialog "This will erase progress for *Morning Run* but keep your achievements. Continue?"
- **Verify:** create habit, complete 5 times, reset → completions gone, streak 0, achievements remain.

### E2. `[FEATURE]` Email change · Risk: M · Opus? **Yes (Firebase reauth design)**
Firebase Auth supports it but requires **recent sign-in or reauthentication** to call `user.updateEmail()`. Newer Firebase SDKs require `verifyBeforeUpdateEmail()` (sends verification to new address).
- **Opus action:** confirm which Firebase Auth flow (legacy `updateEmail` vs. `verifyBeforeUpdateEmail`) and update `PLAN.md` Phase 10 accordingly. Decide whether to ship this pre-Phase-10 against `FakeAuthRepository` only.
- **Sonnet execution after sign-off:**
  - Extend `AuthRepository` with `suspend fun changeEmail(currentPassword: String, newEmail: String): Result<Unit>`.
  - `FakeAuthRepository`: store current email; verify password; update email.
  - `FirebaseAuthRepository` (Phase 10): reauthenticate → `verifyBeforeUpdateEmail(newEmail)`.
  - New screen `ChangeEmailScreen.kt` (mirror `ChangePasswordScreen` structure): fields `currentPassword`, `newEmail`, `confirmNewEmail`.
  - Settings: add entry "Change e-mail address" below "Change password". Update string `R.string.settings_account_section`.
  - Nav graph: new route `change_email`.
- **Verify:** with fake repo, change email → settings shows new email; with wrong password → error.

---

## Group F — `[OPTIONAL]` Debug visibility toggles

### F1. `[OPTIONAL]` Settings → hide debug controls for screenshots · Risk: L · Opus? No
- **Files:**
  - `SettingsScreen.kt` — add a "Developer" section under "Support" with two `Switch` rows: "Show debug button on Habits" and "Show seeder on Statistics".
  - `SettingsRepository` (or `DataStore`-backed user prefs — grep to see what exists) — two new `Boolean` keys, default `true`.
  - `HabitScreen` AppBar Debug button — wrap in `if (showDebugOnHabits) { IconButton(...) }`.
  - `StatisticsScreen` AppBar Seeder button — same pattern.
- **Persistence:** must survive app restart → use existing DataStore. If no DataStore exists yet, this is bigger than "optional" — defer.
- **Verify:** toggle off → buttons disappear; restart app → still hidden.

---

## Recommended Execution Order

1. **A1–A5** (quick wins, ~1 session each, builds confidence and unblocks screenshots)
2. **B1** (small, clean, ML-touching — good warm-up)
3. **B4** (string-only verbosity pass; do BEFORE D1 so D1 inherits the new wording)
4. **C1** (needs Opus matrix design; do BEFORE D1 so reorganized screen has demoable data)
5. **D1** (big refactor; needs C1 in place to verify visually)
6. **B3** (depends on A2 + B4 wording + D1 layout slot)
7. **B2** (change-password 3-field, independent)
8. **E1** (Reset Progress, independent)
9. **E2** (Email change — needs Opus design for Firebase flow + PLAN.md update)
10. **F1** (optional, last)

---

## Opus-required Touchpoints Summary

| Item | Opus action |
|---|---|
| B3 | Decide retention metric (uniqueActiveDays viewer vs. non-viewer; sufficiency threshold) |
| C1 | Design seeded-data matrix covering every analytical card before any code is written |
| D1 | Approve final LazyColumn layout + collapse-state persistence strategy |
| E2 | Choose Firebase Auth email flow (`updateEmail` vs. `verifyBeforeUpdateEmail`) and update `PLAN.md` Phase 10 |

Everything else is mechanical and safe for Sonnet.

---

## Cross-cutting Guardrails (apply to every session)

- Before creating any file: read `STRUCTURE.md`, confirm package path + matching `package …` declaration.
- No Room migrations during development — bump DB `version = X` and reinstall.
- No external doc files (no `roadmap.md`, no `etapy-wyjasnienia.md` — those are global guidelines, but reaffirming).
- No Espresso / UI tests / Room instrumentation tests — manual emulator verification only.
- Flag (do not write) unit tests for new pure-business-logic functions: `AnalyticsEngagementUseCase` (B3), `resetProgress` use case if extracted (E1), email/password validation in `AuthViewModel`.
- VS Code red underlines on `android.*` / `androidx.*` / `kotlinx.*` = false positive, ignore.
- After two failed fix attempts on the same item → STOP, advise `git reset --hard`.
- `PLAN.md` and this file: AI does not auto-tick boxes. User ticks manually.
