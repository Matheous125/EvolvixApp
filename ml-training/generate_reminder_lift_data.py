"""
generate_reminder_lift_data.py — Synthetic dataset for Phase 9.1 (ReminderLiftClassifier).

PLAN-ML-EXTENSION.md §9.1.2.

This is the **Reminder Effectiveness** binary classification task: given a habit's state
and the reminder slot under consideration, predict P(completion within 30 min | context).
The model is called **twice** at inference time — once with reminderSent=0 (baseline)
and once with reminderSent=1 (treatment) — and the caller computes:
    lift = P(complete | sent=1) - P(complete | sent=0)
If lift < SUPPRESS_THRESHOLD the reminder is suppressed via ScheduleReminderUseCase.

  ⚠ THESIS NOTE — CAUSAL CAVEAT:
  This is a *predicted lift estimator*, NOT a causal treatment-effect model.
  From observational data alone (which days a reminder was sent vs not) we can only
  learn correlational associations.  High-streak, high-rate users who receive reminders
  may appear to complete often because both the engagement AND the reminder delivery
  share a common driver.  The model should be framed in the thesis as "predicted lift"
  rather than "causal effect", and the suppression threshold should be conservative
  (ε = 0.05) to avoid false suppressions harming active users.

Features per row (field order MUST exactly mirror ReminderLiftFeatures.kt
→ toFloatArray() and reminder_lift_scaler.json → feature_columns):

    1. habitAge                int     days since first completion (1..730)
    2. completionRateLast7Days float   0.0..1.0
    3. completionRateLast30Days float  0.0..1.0
    4. currentStreak           int     0..200
    5. hourOfDay               int     0..23  (the reminder slot hour)
    6. dayOfWeekOrdinal        int     0=Mon .. 6=Sun
    7. frequencyOrdinal        int     0=DAILY, 1=WEEKLY, 2=MONTHLY
    8. snoozeCountToday        int     0..6   (R8: how many times user snoozed today)
    9. recentAvgDifficulty     float   1.0..5.0 (R8: rolling avg perceivedDifficulty,
                                       last 14 completions; default 3.0 = neutral)
   10. reminderSent            int     0 or 1  (the treatment variable, always last)

Label:
    1  if the habit is completed within 30 min of the reminder slot (or, for
       reminderSent=0 rows, within 30 min of the scheduled slot window).
    0  otherwise.

Generative model (logit-based, matching generate_abandonment_data.py convention):
    Start from a neutral logit = 0 and apply signed behavioral nudges:

        BASE SIGNALS (apply regardless of reminderSent):
        +2.0  completionRateLast7Days >= 0.8   (active user in recent window)
        +1.5  currentStreak >= 14              (strong momentum)
        +1.0  completionRateLast7Days >= 0.5   (moderate engagement)
        +0.5  completionRateLast30Days >= 0.7  (established habit)
        -1.5  completionRateLast7Days < 0.2    (low engagement)
        -2.0  completionRateLast7Days < 0.1    (near-dormant)
        -1.0  daysSinceContext > 3 (proxied via low rate7d < 0.3)

        HOUR-OF-DAY SIGNALS (slot matches typical activity window):
        +0.5  hourOfDay in [6..9] or [17..21]  (morning/evening activity peaks)
        -0.5  hourOfDay in [0..5] or [22..23]  (late night / early morning = no-show)

        DAY-OF-WEEK SIGNALS:
        +0.3  dayOfWeekOrdinal in {5, 6}       (weekend = more free time)
        -0.3  dayOfWeekOrdinal in {0, 1}       (Monday/Tuesday = busy start of week)

        R8 — SNOOZE + DIFFICULTY SIGNALS (base; applied regardless of reminderSent):
        -0.5  snoozeCountToday >= 2  (user is actively deferring the habit)
        -0.3  recentAvgDifficulty >= 4.0  (habit consistently feels hard)

        REMINDER TREATMENT (this is the lift signal the model must learn):
        Applied only when reminderSent == 1.
        Lift is LARGER for struggling users (reminder helps most when engagement is low).
        Lift is SMALLER for high-streak users (reminder is redundant when habit is automatic).
        R8 — SUPPRESSION: when snoozeCountToday >= 3 AND recentAvgDifficulty >= 4.0,
            the reminder_boost is set to 0 (no lift). The user is both actively avoiding
            the reminder (snoozing heavily) and rating the habit as very hard, so another
            reminder won't change behaviour.
        reminder_boost = base_boost * engagement_penalty  (unless suppressed, see above)
        where:
            base_boost = +1.2
            engagement_penalty = 1.0 - clamp(completionRateLast7Days, 0, 1) * 0.7
            → ranges from 1.0 (rate7d=0) to 0.30 (rate7d=1.0)
            So: low-rate user gets +1.2 boost; high-rate user gets +0.36 boost.
            This bakes in the realistic prior that reminders help struggling habits
            more than well-established ones.

    Logit is scaled by 1.5 before sigmoid (same rationale as generate_success_data.py:
    avoids clustering samples near p=0.5 so the MLP gets a learnable signal).

Positive rate target: ~45–60 % (slightly higher for reminderSent=1 rows by design).

Usage:
    python generate_reminder_lift_data.py
    python generate_reminder_lift_data.py --rows 50000

Output:
    ml-training/data/reminder_lift_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors ReminderLiftFeatures.kt → toFloatArray() and
# reminder_lift_scaler.json → feature_columns.  DO NOT reorder.
# R8 (2026-05-27): added snoozeCountToday (idx 7) and recentAvgDifficulty (idx 8);
# reminderSent moved from idx 7 to idx 9 (treatment variable always last).
FEATURE_COLUMNS: list[str] = [
    "habitAge",
    "completionRateLast7Days",
    "completionRateLast30Days",
    "currentStreak",
    "hourOfDay",
    "dayOfWeekOrdinal",
    "frequencyOrdinal",
    "snoozeCountToday",       # R8: how many times user snoozed today (0..6)
    "recentAvgDifficulty",    # R8: rolling avg perceivedDifficulty last 14 completions (1.0..5.0)
    "reminderSent",           # treatment variable — always last so lift probe logic stays simple
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §9.1.2 generative model.

    Each row simulates one (habit-state, reminder-slot, treatment) observation.
    Rows are split ~50/50 between reminderSent=0 and reminderSent=1 so the model
    sees a balanced treatment distribution and can learn the lift signal.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # habitAge: most habits are young; long tail of established habits.
    habit_age = np.clip(
        rng.exponential(scale=60.0, size=rows) + 1, 1, 730
    ).astype(np.int16)

    # frequencyOrdinal: 70% DAILY, 20% WEEKLY, 10% MONTHLY (realistic split).
    frequency_ordinal = rng.choice(
        [0, 1, 2], size=rows, p=[0.70, 0.20, 0.10]
    ).astype(np.int8)

    # Shared latent engagement: drives both rate windows.
    # Beta(2, 2) is symmetric around 0.5, giving a realistic mix of engaged
    # and struggling users and avoiding the >85% positive-rate trap that
    # arises when most simulated users are high-engagement.
    engagement = rng.beta(a=2.0, b=2.0, size=rows).astype(np.float64)

    rate_7d = np.clip(
        engagement + rng.normal(0.0, 0.12, size=rows), 0.0, 1.0
    ).astype(np.float32)

    rate_30d = np.clip(
        engagement + rng.normal(0.0, 0.08, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # currentStreak: correlated with engagement; Poisson with lambda scaled by rate.
    streak_lambda = (engagement * 25.0).clip(0.5, 200.0)
    current_streak = np.clip(
        rng.poisson(lam=streak_lambda, size=rows), 0, 200
    ).astype(np.int16)

    # hourOfDay: bimodal (morning peak 6-9 + evening peak 17-21) + uniform tail.
    peak_mask = rng.random(rows) < 0.55
    peak_hours = rng.choice(
        list(range(6, 10)) + list(range(17, 22)), size=rows
    ).astype(np.int8)
    random_hours = rng.integers(0, 24, size=rows).astype(np.int8)
    hour_of_day = np.where(peak_mask, peak_hours, random_hours).astype(np.int8)

    # dayOfWeekOrdinal: uniform across the week (0=Mon .. 6=Sun).
    day_of_week = rng.integers(0, 7, size=rows).astype(np.int8)

    # reminderSent: balanced 50/50 treatment assignment (mimics A/B style split).
    reminder_sent = rng.integers(0, 2, size=rows).astype(np.int8)

    # R8: snoozeCountToday — Poisson(λ=0.8) clipped to [0, 6].
    # Most reminder cycles are not snoozed (mode = 0); occasional heavy snoozers
    # follow the tail of the distribution. λ=0.8 gives realistic marginal rates.
    snooze_count_today = np.clip(
        rng.poisson(lam=0.8, size=rows), 0, 6
    ).astype(np.int8)

    # R8: recentAvgDifficulty — Beta(2, 4) scaled to [1.0, 5.0].
    # Beta(2, 4) is right-skewed (mode ≈ 0.25), mapping most habits to the 1–3
    # difficulty range, with a long tail of genuinely hard habits reaching 4–5.
    recent_avg_difficulty = (
        rng.beta(a=2.0, b=4.0, size=rows) * 4.0 + 1.0
    ).astype(np.float32)  # maps [0,1] → [1.0, 5.0]

    # ── Label generation (logit-based) ─────────────────────────────────────

    # Baseline negative logit: completing a habit *within a specific 30-min
    # window* is a rare event even for engaged users — the prior leans against
    # it so the positive signals have meaningful leverage.
    logit = np.full(rows, -1.2, dtype=np.float64)

    # BASE SIGNALS — engagement / streak
    logit += np.where(rate_7d >= 0.8, 2.0, 0.0)
    logit += np.where(current_streak >= 14, 1.5, 0.0)
    logit += np.where((rate_7d >= 0.5) & (rate_7d < 0.8), 1.0, 0.0)
    logit += np.where(rate_30d >= 0.7, 0.5, 0.0)
    logit += np.where(rate_7d < 0.2, -1.0, 0.0)
    logit += np.where(rate_7d < 0.1, -1.0, 0.0)   # stacks with above for near-dormant

    # HOUR-OF-DAY SIGNALS
    in_active_hours = np.isin(hour_of_day, list(range(6, 10)) + list(range(17, 22)))
    in_sleep_hours = np.isin(hour_of_day, list(range(0, 6)) + list(range(22, 24)))
    logit += np.where(in_active_hours, 0.5, 0.0)
    logit += np.where(in_sleep_hours, -0.5, 0.0)

    # DAY-OF-WEEK SIGNALS
    logit += np.where(np.isin(day_of_week, [5, 6]), 0.3, 0.0)
    logit += np.where(np.isin(day_of_week, [0, 1]), -0.3, 0.0)

    # R8 — SNOOZE + DIFFICULTY BASE SIGNALS (apply before the treatment)
    logit += np.where(snooze_count_today >= 2, -0.5, 0.0)        # user is actively deferring
    logit += np.where(recent_avg_difficulty >= 4.0, -0.3, 0.0)   # habit feels hard

    # REMINDER TREATMENT — only when reminderSent == 1.
    # Boost is larger for struggling habits (engagement_penalty down-weights high-rate).
    engagement_penalty = 1.0 - np.clip(rate_7d.astype(np.float64), 0.0, 1.0) * 0.7
    reminder_boost = 1.2 * engagement_penalty                    # range: 0.36 .. 1.20

    # R8 — SUPPRESS boost when snoozeCountToday >= 3 AND recentAvgDifficulty >= 4.0.
    # Both conditions together signal the user is avoiding a genuinely hard habit;
    # another reminder yields zero lift in this regime.
    suppress_mask = (snooze_count_today >= 3) & (recent_avg_difficulty >= 4.0)
    reminder_boost = np.where(suppress_mask, 0.0, reminder_boost)

    logit += reminder_sent.astype(np.float64) * reminder_boost   # zero for sent=0 rows

    # Scale logit before sigmoid to spread probability mass away from 0.5.
    p = 1.0 / (1.0 + np.exp(-logit * 1.5))

    # Bernoulli draw: label=1 if completed within 30 min, else 0.
    label = (rng.random(rows) < p).astype(np.int8)

    return pd.DataFrame(
        {
            "habitAge": habit_age,
            "completionRateLast7Days": rate_7d,
            "completionRateLast30Days": rate_30d,
            "currentStreak": current_streak,
            "hourOfDay": hour_of_day,
            "dayOfWeekOrdinal": day_of_week,
            "frequencyOrdinal": frequency_ordinal,
            "snoozeCountToday": snooze_count_today,       # R8
            "recentAvgDifficulty": recent_avg_difficulty,  # R8
            "reminderSent": reminder_sent,
            "completed_within_30min": label,
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/reminder_lift_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "reminder_lift_dataset.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rows",
        type=int,
        default=50_000,
        help="Number of synthetic rows to generate (default: 50000).",
    )
    args = parser.parse_args()

    path = output_path()
    df = generate(rows=args.rows)

    df.to_csv(path, index=False)

    pos_all = df["completed_within_30min"].mean()
    pos_sent = df[df["reminderSent"] == 1]["completed_within_30min"].mean()
    pos_base = df[df["reminderSent"] == 0]["completed_within_30min"].mean()

    print(f"Generated {len(df):,} rows → {path}")
    print(f"  Overall completion rate   : {pos_all:.1%}")
    print(f"  Completion rate (sent=1)  : {pos_sent:.1%}")
    print(f"  Completion rate (sent=0)  : {pos_base:.1%}")
    print(f"  Synthetic lift (sent=1 - sent=0) : {pos_sent - pos_base:+.1%}")


if __name__ == "__main__":
    main()
