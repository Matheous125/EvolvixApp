Here is a highly structured, comprehensively organized blueprint of your planned features. I have regrouped your numbered points logically by **Domain/Module** (e.g., Architecture, AI, UI/UX, Gamification) so that when you feed this to Claude, it can process the system architecture seamlessly without jumping between UI tasks and database tasks. 

I have preserved all of your specific details, requirements, and ASCII UI sketches. At the bottom, I have provided suggestions for your Empty States and listed some clarifying questions for you to consider before we generate the final roadmap.

---

# 🚀 HABIT TRACKER APP: MASTER FEATURE BLUEPRINT

## MODULE 1: Data Architecture & Cloud Infrastructure

### 1.1. Cloud Sync & Multi-Device Support (Final Stage)
*   **Goal:** Enable multi-device sync preserving offline privacy.
*   **Tech Stack:** Firebase Authentication + Cloud Firestore.
*   **Data Flow:** Room DB (Local) ↔ Sync Controller ↔ Firebase Cloud Firestore.
*   *Reference Architecture:*
```text
┌─────────────────────────────────────────────────────┐
│              User's Devices                         │
├─────────────────────────────────────────────────────┤
│  📱 Device A          📱 Device B       💻 Tablet   │
│  ┌─────────────┐     ┌─────────────┐   ┌──────────┐│
│  │ Room DB     │     │ Room DB     │   │ Room DB  ││
│  │ (Local)     │◄───►│ (Local)     │◄─►│ (Local)  ││
│  └──────┬──────┘     └──────┬──────┘   └────┬─────┘│
│         │                   │                │      │
│         └───────────────────┼────────────────┘      │
│                             ▼                       │
│                    ┌─────────────────┐              │
│                    │ Sync Controller │              │
│                    └────────┬────────┘              │
└─────────────────────────────┼───────────────────────┘
                              ▼
                    ┌──────────────────┐
                    │  Firebase Cloud  │
                    │   Firestore DB   │
                    │  (User's Data)   │
                    └──────────────────┘
```

### 1.2. Database & Entity Updates
*   **Unique Habit Names:** Habit names must be strictly unique (no duplicates allowed).
*   **Over-Completion Support:** The database and UI must support logging progress beyond the set target (e.g., 7/6). The UI should highlight this (e.g., a glowing border).

### 1.3. User Authentication Screens (Required for Firebase)
*   **Login Screen:** Email/Password fields. Links to reset password and register. Includes app value proposition (AI-powered) to encourage sign-ups. Acts as the initial screen for unauthenticated users.
*   **Registration Screen:** Form: Name, Email, Password, Confirm Password, "Sign Up" button.
*   **Reset Password Screen:** Input email -> Send button. Confirms an email was sent. Back button to login.
*   **Set New Password Screen:** Form: Current Password, New Password, Confirm New Password. "Save" button triggers a green `Snackbar` ("✅ Password successfully updated") and auto-navigates back to Settings/Login.

---

## MODULE 2: On-Device AI & Machine Learning Infrastructure
*   **Implementation Strategy:** Stage 1: Build the mathematical algorithms using standard Kotlin math/logic. Stage 2: Build the framework to swap this for a `.tflite` model (TensorFlow Lite) ensuring 100% on-device privacy.

### 2.1. Predictive & Suggestive AI
*   **Smart Success Prediction:** Analyzes features (Day, Hour, Streak, Past week, Time since last, Age) to output Success Probability, Confidence, and Recommendations.
```text
┌─────────────────────────────┐
│ 🎯 Success Prediction       │
│                             │
│ ████████░░ 82% likely       │
│                             │
│ 💡 Tip: You usually succeed │
│ when completing before 9 AM │
└─────────────────────────────┘
```
*   **Optimal Time Suggestions:** ML analyzes successful completion patterns and ranks the best time slots with a "Why" explanation.
*   **Intelligent Habit Recommendations:** Suggests complementary habits based on the user's existing list (e.g., Workout -> Drink Water).
*   **Smart Streak Recovery:** Detects high-risk patterns (e.g., missing Sundays) and preemptively sends a reminder.
*   **Adaptive Difficulty:** Evaluates progress logic (Hitting 100% -> Suggest increase; <50% -> Suggest decrease).
*   **Personalized Motivation Messages:** Context-aware string generation (e.g., "First week is hardest", "15 days momentum").

### 2.2. Advanced Passive Analytics (Derived from Timestamps)
Calculated quietly in the background without user input:
*   **Routine Precision (Clockwork Rating):** Analyzes TimeOfDay standard deviation.
*   **Resilience Score:** Analyzes average days taken to recover a broken streak.
*   **Habit Clashing:** Identifies inverse correlations between two habits.
*   **Procrastination Index:** Measures completion skewness toward the end of deadline cycles.

### 2.3. AI Icon Selection (Statistics Screen Only)
*   **Functionality:** Automatically assigns an appropriate icon to a habit on the Statistics screen based on its name/category. Users do not select this during creation.
*   **Implementation:** Tier 1: Rule-Based Matching (covers 70%). Tier 2: On-Device ML (covers remaining 30%). Learns from user manual corrections in the Edit screen.

---

## MODULE 3: Gamification & Engagement

### 3.1. Streak System
*   **Logic:** Tracks `Current Streak` (consecutive goals met) and `Best Streak` (all-time highest).
*   **UI Representation:** Current streak = 🔥 Flame icon + number. Best streak = 🏆 Cup icon + number.

### 3.2. Achievement System
*   **Implementation Base:** 50 achievements across 7 categories (Getting Started, Streaks, Milestones, Time of Day, Weekly Warriors, Organization, God Tier). Refer to `ACHIEVEMENTS.MD`.
*   **User Ranking:** Based on points (Novice: 0-99, Apprentice: 100-499, Tracker: 500-1499, Habit Master: 1500-3999, Legend: 4000+). Displayed in the Settings screen.
*   **Achievemenet description**: below the name and points the achievemenet description should be included.
*   **Achievement progress visualisation:** if achievement requires X of specific action (e.g. x times progress marked), a progress bar and a counter should be placed below habit description to visualise progress.
*   **In-App Banner (Toast):** Non-interruptive custom Composable triggered via ViewModel `StateFlow`. Slides down from the top, lasts 3-4 seconds, triggers haptics.
```text
  [ Screen Top ]
  ╭────────────────────────────────────────╮
  │ 🌟 ACHIEVEMENT UNLOCKED!               │
  │ 🚀 Unstoppable                 +50 pts │
  ╰────────────────────────────────────────╯
```
*   **Achievements Screen Architecture:**
    ```text
    ┌────────────────────────────────────────────────────────┐
    │ [<]                 Achievements                       │
    ├────────────────────────────────────────────────────────┤
    │                     🌟 1,250 🌟                        │ 
    │                     TOTAL POINTS                       │ 
    ├────────────────────────────────────────────────────────┤
    │ ⏳ LATEST ACHIEVEMENTS                                 │
    │  ╭──────────────────────────────────────────────────╮  │
    │  │ 🏆  Unstoppable                           +50 pts│  │ <- Full color
    │  │      Reached a 7-day streak.                     │  │
    │  ╰──────────────────────────────────────────────────╯  │
    ├────────────────────────────────────────────────────────┤
    │  STREAKS & CONSISTENCY (2/5)                       [▼] │ <- Collapsed Group
    ├────────────────────────────────────────────────────────┤
    │  MILESTONES (1/3)                                  [▲] │ <- Expanded Group
    │  ╭──────────────────────────────────────────────────╮  │
    │  │ 🏁  First Step                            +10 pts│  │ <- Completed (Top)
    │  ╰──────────────────────────────────────────────────╯  │
    │  ╭──────────────────────────────────────────────────╮  │
    │  │ 🥈  Halfway There                         +50 pts│  │ <- Locked (Grayed out)
    │  │      Complete 50 habits total.                   |  |
    |  |     ████░░░░                               25/50 │  │ <- Progress bar added  
    │  ╰──────────────────────────────────────────────────╯  │
    └────────────────────────────────────────────────────────┘
    ```

*   **Retraction Logic:** If a user deletes a habit history entry that triggered an achievement, the achievement must be revoked if requirements are no longer met.

### 3.3. Main Screen Visual Rewards
*   **Milestones:** Mini-celebrations at 25%, 50%, and 75% progress (only if mathematically logical based on the target).
*   **Target Reached:** Full animated confetti.

---

## MODULE 4: Habit Management (CRUD & Core UX)

### 4.1. Top Bar Unification
*   **Main Screen:** Title + Settings (⚙️) icon.
*   **Add Habit:** Back arrow + Title.
*   **Edit Habit:** Back arrow + Title + Icons.Filled.Delete icon (triggers confirmation dialog).
*   The idea is that all screens have unified header style, however header should not take too much screen space.

### 4.2. Improved Add/Edit Habit Forms
*   **Reduced Friction Add Form:**
    *   **Templates Row:** Quick-add templates (Drink Water, Exercise, etc. per `HABIT-TEMPLATES.MD`).
    *   **Name:** Text field.
    *   **Frequency:** Natural language builder: `Repeat every [ 1 ] [ day ▼ ]`
    *   **Target:** Natural language builder: `Target: [ 1 ] times`
    *   **Categories:** Multi-select chips with `[+ Add]` option.
    *   **Color Picker:** Single-select chip (already implemented), random default selected on load from 10 specific colors.
    *   **Reminders:** Simple ON/OFF switch.
*   **Edit Form:** Identical to Add form, but replaces the Templates row with an **Icon Picker** (to override the AI-selected icon for the Stats screen).

### 4.3. Habit Reordering, Grouping & Filtering
*   **Category Filters:** Scrollable filter chips below the top bar for active categories. Preceded by a magnifying glass icon for text search.
*   **Automatic Sorting Options:** Custom, Alphabetical (Asc/Desc), Frequency Ascending (Daily->Yearly), Frequency Descending (Yearly->Daily).
*   **Manual Reordering:** Drag & Drop support.
```text
┌────────────────────────────────────────────────────────┐
│                 My Habits                         ⚙️   │
├────────────────────────────────────────────────────────┤
│  Health                                            [▼] │ <- Collapsed Group
├────────────────────────────────────────────────────────┤
│  Fitness                                           [▲] │ <- Expanded Group
│  ╭──────────────────────────────────────────────────╮  │
│  │                  habit entity                    │  │ 
│  ╰──────────────────────────────────────────────────╯  │
└────────────────────────────────────────────────────────┘
```

### 4.4. Long-Press Context Menu (Hold Gesture)
Replaces immediate edit. Opens a menu with:
1.  **Mark progress:** Simulates a single tap (+1).
2.  **Go to statistics:** Opens stats tab, automatically expanding this habit's card.
3.  **Pause / Cancel Pause:** Pauses habit indefinitely or until a date.
4.  **View history:** Navigates to history screen.
5.  **Edit habit:** Navigates to edit screen.
6.  **Duplicate:** Creates a copy appended with " - copy", " - copy (1)", etc.
7.  **Delete:** Triggers delete confirmation.
8.  **Reorder habits:** Triggers drag & drop edit mode.

### 4.5. Pause System
*   **Functionality:** Freezes the habit. Does not break streak, does not require completion.
*   **Modal Bottom Sheet:** Options to "Pause indefinitely" or "Pause until a date".
*   **UI Representation:** Dimmed bar, Pause icon instead of counter. Moved to a temporary "Paused" category filter. Stats screen displays "Paused until X". Resumes automatically on target date or manually from context menu if set to indefinite pause.

---

## MODULE 5: Analytics & Habit History

### 5.1. Habit History Screen
*   **Functionality:** View, edit, delete, and add retroactive completion timestamps.
*   **UI Elements:** LazyColumn grouped by Year > Month (collapsible). Each entry shows Date/Time with Edit (Date/Time picker) and Delete (Dialog) icons. FAB to add retroactive entry. JSON export button in the top bar.
*   **UI/UX Sketch:**
```text
┌────────────────────────────────────────────────────────┐
│ [<]                Habit History                     🖨️│ <- Print/Export to JSON
└────────────────────────────────────────────────────────┘
▼ 2023
  ▼ October
    Oct 14, 08:30 AM    [✏️ Edit] [🗑️ Delete]
    Oct 13, 09:15 AM    [✏️ Edit] [🗑️ Delete]

(+) FAB (Opens Date/Time picker to manually log a past completion)
```

### 5.2. Enhanced Statistics Screen
*   **Global Overview Card:**
```text
┌─────────────────────────┐
│ This Week: 82%  📈 +5%  │
│ ████████████░░░░        │
│ 23/28 habits completed  │
└─────────────────────────┘
┌──────────────────────────────────────────────────┐
│ ⚖️ LIFE BALANCE (This Week)                      │
│  💪 Health        ████████████░░░░ 75%           │
│  💼 Productivity  ████████████████ 95%           │
│  🧘 Mindfulness   ████░░░░░░░░░░░░ 25%           │
│ 💡 AI Insight: You are crushing Work, but...     │
└──────────────────────────────────────────────────┘
```
*   **Collapsed Habit Cards:**
```text
┌────────────────────────────────────────────────────────┐
│ 💧 Drink Water                             🎯 82% [▼] │ <- AI Predictor
│  🔥 12 Current   |   🏆 24 Best   |   📊 85% Total    │
│  📈 Trend: ▂▃▅▆▇▇▇ (Last 7 Days)                 │ <- Sparkline
└────────────────────────────────────────────────────────┘
```
*   **Expanded Habit Cards (Progressive Disclosure):**
```text
┌────────────────────────────────────────────────────────┐
│ 🏋️ Workout                     🏷️ Health, Fitness [▲] │
│ ────────────────────────────────────────────────────── │
│ [Core Stats & Sparkline]                               │
│ ────────────────────────────────────────────────────── │
│ [Bar Chart: 7D, 30D, 3M, ALL Tabs]                     │
│ ────────────────────────────────────────────────────── │
│ ✨ AI SMART INSIGHT                                    │
│ [Dynamic rotating recommendation text + Action Button] │
│ ────────────────────────────────────────────────────── │
│ 🕒 OPTIMAL TIMING                                      │
│ [Morning/Afternoon/Evening Probability Bars]           │
│ ────────────────────────────────────────────────────── │
│ 🧠 BEHAVIORAL PATTERNS                                 │
│ [Category Rank, Habit Stack, Routine Strength, etc.]   │
└────────────────────────────────────────────────────────┘
```

---

## MODULE 6: Global App UX & User Settings

### 6.1. Navigation & Theming
*   **Navigation Bar:** Add the new "Achievements" tab alongside Habits and Statistics. Navigation bar sequence: achievements, habits (home), statistics.
*   **Theming:** Implement Light Mode. Status bar icons must correctly adapt (black text on light backgrounds, white text on dark backgrounds).
*   **Localization:** Add full Polish localization alongside English.

### 6.2. Settings Screen
*   **Profile Header:** User Name + Achievement Rank.
*   **Options:** Theme Selector (Light, Dark, Auto), Language Selector (Modal), Export JSON data, Help (Detailed onboarding text), Send Feedback (Opens email client formatted to xyz@habit.com), Change Password, Login/Logout Button.

### 6.3. Onboarding Experience
*   **First Launch Flow:** A short preview/demo of the 3 main tabs (dummy habits, fake stats card, a few fake achievements) to show value immediately.
*   **UI Tooltip Strategy (Avoiding Fatigue):**
    *   *Pulsing UI Hints:* Use subtle glowing animations for primary actions (e.g., FAB button, tap to complete) on first launch.
    *   *Deep Dive:* Detailed text explanations remain in the Settings > "Help" section.

### 6.4. Empty States (With Suggestions)
*   **Home Screen:**
    *   *Your text:* "Start building better habits today!" + [Create First Habit]
    *   *AI Suggestion:* "A blank canvas! 🎨 Let's build your first routine."
*   **Statistics Screen:**
    *   *Your text:* "No habits created yet."
    *   *AI Suggestion:* "Nothing to analyze yet! 📊 Complete a few habits, and your AI coach will start finding patterns."
*   **Achievements Screen:**
    *   *AI Suggestion:* "Your trophy case is empty! 🏆 Complete your first habit to unlock your first reward."

---

## MODULE 7: Notifications & Widgets

### 7.1. Smart Notifications
*   **Actionable Reminders:** Notifications include "Done" (+1 progress), "Skip" (dismiss), and "Snooze" (10 minutes). Text and timing to be eventually driven by AI ML models.
*   **Daily Summary:** Sent daily (e.g., "3 habits pending today").

### 7.2. Home Screen Widgets (Android App Widgets)
*   **Small Widget:** Single habit with progress bar.
*   **Medium Widget:** Scrollable list of today's pending habits.


### 🚀 ADDITIONAL CLARIFICATION

**1. Database & Core Logic:**
*   **Unique Names:** Habit names are strict primary/unique keys (no duplicates).
*   **Over-Completion:** Target can be exceeded (e.g., 7/6). Completion rates on stats screens can visually exceed 100%. UI will glow to indicate over-completion.
*   **History & Streaks:** Streaks are calculated dynamically based on a log of timestamps. If a user retroactively adds or deletes a timestamp in the History screen, the Room DB must recalculate Current/Best streaks and automatically trigger or revoke Achievements.
*   **Localization Setup:** Polish and English. We will heavily utilize Android `<plurals>` strings for natural language builders to handle complex Polish grammar (e.g., 1 dzień, 2 dni).

**2. Architecture & Cloud (Final Stage):**
*   **Offline-First:** Room DB is the single source of truth. 
*   **Sync Logic:** Firebase Firestore + Auth. Sync conflicts are resolved by **Merging Timestamps** (since every completion is an individual timestamp, merging lists across devices guarantees no data loss).

**3. Widgets:**
*   Built entirely with **Jetpack Glance** to stay in line with the Compose ecosystem.

---