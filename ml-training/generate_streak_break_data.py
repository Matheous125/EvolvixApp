"""
generate_streak_break_data.py — Synthetic dataset for Phase 8.2 (StreakBreakClassifier).

PLAN-ML-EXTENSION.md §8.2.1.

Features per row (field order must exactly mirror StreakBreakFeatures.kt
→ toFloatArray() and streak_break_scaler.json → feature_columns):
    currentStreak             0..200  (consecutive periods the habit was reached)
    habitAge                  1..730  (days since first completion)
    completionRateLast7Days   0.0..1.0
    dayOfWeek                 1=Mon … 7=Sun  (moment of evaluation)
    hourOfDay                 0..23           (moment of evaluation)
    recentAvgGapDays          0.0..30.0  (mean gap between target-reached dates, last 30 d)
    frequencyOrdinal          0=DAILY, 1=WEEKLY, 2=MONTHLY
    involuntarySkipDays7d     0..7   (R5: distinct days in last 7d with SICK/TRAVELING skip)
    recentAvgDifficulty       1.0..5.0  (R5: rolling avg of perceivedDifficulty; 3.0 = neutral)

Label:
    1  if the active streak ends within the next N periods (streak breaks)
    0  if the streak survives all N periods

    N per frequency (matches PLAN-ML-EXTENSION.md §8.2):
        DAILY   → N = 3  (will the user miss ANY of the next 3 days?)
        WEEKLY  → N = 2  (will the user miss ANY of the next 2 weeks?)
        MONTHLY → N = 1  (will the user miss the next month?)

    Because we are generating synthetic data, N is not simulated explicitly.
    Instead, the logit priors encode the same probability structure: a user
    with a strong recent pattern is unlikely to break, regardless of N.
    The frequencyOrdinal lets the model learn that WEEKLY/MONTHLY habits
    naturally have larger acceptable gaps (same confound-correction trick as
    generate_abandonment_data.py §8.1.2).

Label generation strategy (logit-based, matching the Phase 8.1 convention):
    Start from a neutral logit=0 baseline and apply signed behavioral nudges:

    HIGH BREAK RISK signals (positive logit → label → 1):
    +3.0  currentStreak <= 2  AND  completionRateLast7Days < 0.30  (nascent streak already struggling)
    +2.0  recentAvgGapDays >= 4  AND  completionRateLast7Days < 0.40  (widening gaps + poor rate)
    +1.5  completionRateLast7Days < 0.20  (very low engagement, independent of streak)
    +1.0  currentStreak <= 5  AND  completionRateLast7Days < 0.50  (young streak, moderate struggle)
    +0.5  dayOfWeek in {6, 7}  AND  completionRateLast7Days < 0.50  (weekend fragility signal)
    +0.5  recentAvgDifficulty >= 4.0                               (R5: high perceived difficulty → fragile)

    LOW BREAK RISK signals (negative logit → label → 0):
    -3.0  currentStreak >= 30  AND  completionRateLast7Days >= 0.80  (mature, consistent streak)
    -2.0  completionRateLast7Days >= 0.85                            (excellent recent engagement)
    -1.5  currentStreak >= 14  AND  completionRateLast7Days >= 0.60  (healthy established streak)
    -1.0  recentAvgGapDays <= 1.5  AND  completionRateLast7Days >= 0.60  (regular, no gaps)
    -0.5  habitAge >= 90  AND  completionRateLast7Days >= 0.50      (mature habit momentum)
    -0.5  involuntarySkipDays7d >= 3                                (R5: many involuntary skips → not disengaged)

    Logit is then scaled by 1.8 before sigmoid (identical to generate_abandonment_data.py
    rationale: moves samples away from the 0.4–0.6 dead zone, raising the Bayes
    accuracy ceiling and making the ≥ 0.75 F1 target achievable).

Positive rate target: ~30–40 % (most active streaks survive; users with an active
    streak are already a self-selected engaged population).

Usage:
    python generate_streak_break_data.py
    python generate_streak_break_data.py --rows 50000

Output:
    ml-training/data/streak_break_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors StreakBreakFeatures.kt → toFloatArray() and
# streak_break_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "currentStreak",
    "habitAge",
    "completionRateLast7Days",
    "dayOfWeek",
    "hourOfDay",
    "recentAvgGapDays",
    "frequencyOrdinal",
    "involuntarySkipDays7d",   # R5: distinct SICK/TRAVELING days in last 7d (0..7)
    "recentAvgDifficulty",     # R5: rolling avg of perceivedDifficulty (1.0..5.0; 3.0 = neutral)
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §8.2.1 generative model.

    All distributions are chosen to represent an *active-streak* population:
    these are habits that currently have a streak > 0, so the base engagement
    level is already higher than the general-population prior used in Phase 8.1.
    The tail of struggling / short-streak habits introduces the positive class.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # frequencyOrdinal: 70 % DAILY, 20 % WEEKLY, 10 % MONTHLY (realistic split)
    frequency_ordinal = rng.choice(
        [0, 1, 2], size=rows, p=[0.70, 0.20, 0.10]
    ).astype(np.int8)

    # currentStreak: exponential with long tail; most active streaks are short
    # (even engaged users often restart; cap at 200 for training stability).
    current_streak = np.clip(
        rng.exponential(scale=10.0, size=rows) + 1, 1, 200
    ).astype(np.int16)

    # habitAge: typically longer than streak (you can only have a streak if the
    # habit has been active a while). Use an exponential shifted above streak.
    habit_age = np.clip(
        current_streak + rng.exponential(scale=30.0, size=rows) + 7, 7, 730
    ).astype(np.int16)

    # dayOfWeek: uniform 1–7 (Mon–Sun) — the moment when the assessment is made.
    day_of_week = rng.integers(1, 8, size=rows).astype(np.int8)

    # hourOfDay: bimodal — morning peak (6–10) and evening peak (18–22),
    # reflecting when users typically interact with a habit-tracker app.
    morning_mask = rng.random(size=rows) < 0.55
    morning_hours = np.clip(rng.normal(8.0, 2.0, size=rows), 5, 11)
    evening_hours = np.clip(rng.normal(20.0, 2.0, size=rows), 17, 23)
    hour_of_day = np.where(morning_mask, morning_hours, evening_hours).astype(np.int8)

    # completionRateLast7Days: skewed toward high values for an active-streak
    # population (Beta with a > b → right-skewed toward 1.0).
    completion_rate_7d = rng.beta(a=3.0, b=1.5, size=rows).astype(np.float32)

    # recentAvgGapDays: average calendar gap between target-reached days in the
    # last 30 d.  For DAILY habits this should be close to 1; for WEEKLY ~7;
    # for MONTHLY ~30.  Add per-habit noise so the model can distinguish an
    # inconsistent DAILY habit (gap=3) from a typical WEEKLY habit (gap=7).
    base_gap = np.where(
        frequency_ordinal == 0, 1.0,
        np.where(frequency_ordinal == 1, 7.0, 28.0)
    )
    recent_avg_gap = np.clip(
        base_gap * rng.lognormal(mean=0.0, sigma=0.5, size=rows),
        0.0, 30.0
    ).astype(np.float32)

    # R5 — involuntarySkipDays7d: distinct SICK/TRAVELING days in the last 7 days.
    # Most users have 0; a long-trip / illness tail reaches up to 7 days.
    # Distribution: ~80 % zero, geometric tail for the rest.
    involuntary_skip_days_7d = np.clip(
        rng.geometric(p=0.6, size=rows) - 1,  # geometric(p=0.6) − 1: mode=0
        0, 7
    ).astype(np.int8)
    # Force to 0 for 80 % of the population (most users have no involuntary skips).
    involuntary_skip_days_7d = np.where(
        rng.random(size=rows) < 0.80, 0, involuntary_skip_days_7d
    ).astype(np.int8)

    # R5 — recentAvgDifficulty: rolling average of perceivedDifficulty (1–5 scale).
    # Most users report near-neutral (3); high-difficulty tail peaks at 4–5 and
    # correlates weakly with struggling habits (those users also have lower rates).
    recent_avg_difficulty = np.clip(
        rng.normal(loc=2.8, scale=0.8, size=rows), 1.0, 5.0
    ).astype(np.float32)
    # Bias: struggling habits (low rate) tend to report higher difficulty.
    recent_avg_difficulty = np.clip(
        recent_avg_difficulty + np.where(completion_rate_7d < 0.40, 0.5, 0.0),
        1.0, 5.0
    ).astype(np.float32)

    # ── Logit-based label generation ───────────────────────────────────────

    score = np.zeros(rows, dtype=np.float64)

    # HIGH BREAK RISK signals — positive logit
    score += np.where(
        (current_streak <= 2) & (completion_rate_7d < 0.30), 3.0, 0.0
    )
    score += np.where(
        (recent_avg_gap >= 4.0) & (completion_rate_7d < 0.40), 2.0, 0.0
    )
    score += np.where(completion_rate_7d < 0.20, 1.5, 0.0)
    score += np.where(
        (current_streak <= 5) & (completion_rate_7d < 0.50), 1.0, 0.0
    )
    # Weekend fragility: mild positive signal when engagement is already borderline
    score += np.where(
        (day_of_week >= 6) & (completion_rate_7d < 0.50), 0.5, 0.0
    )
    # R5: high perceived difficulty is a leading indicator of streak fragility
    score += np.where(recent_avg_difficulty >= 4.0, 0.5, 0.0)

    # LOW BREAK RISK signals — negative logit
    score += np.where(
        (current_streak >= 30) & (completion_rate_7d >= 0.80), -3.0, 0.0
    )
    score += np.where(completion_rate_7d >= 0.85, -2.0, 0.0)
    score += np.where(
        (current_streak >= 14) & (completion_rate_7d >= 0.60), -1.5, 0.0
    )
    score += np.where(
        (recent_avg_gap <= 1.5) & (completion_rate_7d >= 0.60), -1.0, 0.0
    )
    # Mature-habit momentum: established routines break less often
    score += np.where(
        (habit_age >= 90) & (completion_rate_7d >= 0.50), -0.5, 0.0
    )
    # R5: many involuntary skips (SICK/TRAVELING) reduce the "true" disengagement signal
    score += np.where(involuntary_skip_days_7d >= 3, -0.5, 0.0)

    # ── Scale and sigmoid ──────────────────────────────────────────────────

    # Scale 1.8: pushes sigmoid output away from the 0.4–0.6 ambiguous zone,
    # raising the Bayes accuracy ceiling so the ≥ 0.75 macro-F1 target is
    # achievable.  Identical rationale to generate_abandonment_data.py.
    score *= 1.8

    probability = 1.0 / (1.0 + np.exp(-score))
    probability = np.clip(probability, 0.05, 0.95)

    label = (rng.uniform(0.0, 1.0, size=rows) < probability).astype(np.int8)

    return pd.DataFrame(
        {
            "currentStreak": current_streak.astype(np.int16),
            "habitAge": habit_age.astype(np.int16),
            "completionRateLast7Days": completion_rate_7d.astype(np.float32),
            "dayOfWeek": day_of_week.astype(np.int8),
            "hourOfDay": hour_of_day.astype(np.int8),
            "recentAvgGapDays": recent_avg_gap.astype(np.float32),
            "frequencyOrdinal": frequency_ordinal.astype(np.int8),
            "involuntarySkipDays7d": involuntary_skip_days_7d.astype(np.int8),   # R5
            "recentAvgDifficulty": recent_avg_difficulty.astype(np.float32),     # R5
            "label": label,
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/streak_break_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "streak_break_dataset.csv"
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
    parser.add_argument(
        "--seed",
        type=int,
        default=SEED,
        help=f"Random seed (default: {SEED}).",
    )
    args = parser.parse_args()

    df = generate(rows=args.rows, seed=args.seed)
    out = output_path()
    df.to_csv(out, index=False)

    pos_rate = float(df["label"].mean())
    print(f"Generated {len(df):,} rows → {out}")
    print(f"Positive rate (streak breaks): {pos_rate:.3f}  (target: 0.30–0.40)")
    print(df.describe().to_string())


if __name__ == "__main__":
    main()
