"""
generate_difficulty_data.py — Synthetic dataset for Phase 9.4 (PerceivedDifficultyRegressor).

PLAN-ML-EXTENSION.md §9.4.2.

This is the **Perceived Difficulty Estimator** regression task: given a habit's current
state (day, time, streak, completion rates, target, progress ratio), predict the
user's subjective difficulty rating for that session on a 1–5 scale.

The label mimics `HabitCompletionEntity.perceivedDifficulty` once real users start
rating completions.  Until then (or in parallel) the regressor is used by
`DifficultyEstimateUseCase` to impute expected difficulty for habits with fewer than
`MIN_RATINGS` actual user-provided ratings.

  ⚠ THESIS NOTE — OBSERVATIONAL CAVEAT:
  This model is an *observational estimator*, not a causal model of true objective
  difficulty.  Perceived difficulty is self-reported and subjective; what the model
  learns are correlates of difficulty (low completion rate, high streak count breaks,
  etc.), NOT a causal mechanism.  Frame in the thesis as "predicted perceived
  difficulty estimate" — never as "objective task difficulty."

  ⚠ COLD-START NOTE:
  `recentAvgDifficulty` is unavailable until the user has rated at least
  MIN_RATINGS=5 completions for a habit.  The DifficultyEstimateUseCase enforces
  this guard at runtime.  This data generator does NOT include recentAvgDifficulty
  as an input feature (a circular dependency): the model predicts the rating that
  would have been given, not a summary of past ratings.

Features per row (field order MUST exactly mirror DifficultyFeatures.kt
→ toFloatArray() and perceived_difficulty_scaler.json → feature_columns):

    1. dayOfWeek               int     1 (Mon) … 7 (Sun)
    2. hourOfDay               int     0 … 23
    3. currentStreak           int     0 … 200
    4. completionRateLast7Days float   0.0 … 1.0
    5. completionRateLast30Days float  0.0 … 1.0
    6. habitAgeDays            int     1 … 1800
    7. targetCount             int     1 … 20
    8. avgProgressRatio30d     float   0.0 … 3.0
                                       (completions / target per period, last 30 d;
                                        values > 1.0 indicate over-completion)

Label:
    perceived_difficulty ∈ [1.0, 5.0]  (continuous; caller rounds to int after inference)

Generative model (baked-in behavioral priors):

    BASE difficulty = 3.0  (neutral midpoint of the 1–5 scale)

    HARDER signals (positive adjustment, pushing toward 5):
      +1.5   completionRateLast7Days < 0.25  AND  currentStreak == 0
             (struggling, no active streak — session feels very hard)
      +1.0   completionRateLast30Days < 0.35
             (chronically low performer — the habit rarely feels easy)
      +0.5   avgProgressRatio30d < 0.6
             (user barely reaches the target; each session requires real effort)
      +0.5   targetCount >= 7
             (high target count — more repetitions = more perceived effort)
      +0.3   hourOfDay >= 21  OR  hourOfDay <= 5
             (late-night / early-morning sessions tend to feel harder)

    EASIER signals (negative adjustment, pushing toward 1):
      -1.5   currentStreak >= 21
             (long active streak — habit is now automatic; very easy)
      -1.0   completionRateLast30Days >= 0.85
             (habituated, consistently high performer)
      -0.5   avgProgressRatio30d >= 1.2
             (user routinely over-completes; target feels trivially easy)
      -0.5   habitAgeDays >= 180  AND  completionRateLast30Days >= 0.7
             (mature + well-maintained habit — deeply ingrained)
      -0.3   currentStreak >= 7  (early-stage streak benefit)

    Noise: N(0, 0.45) — matches approximate inter-rater variability in
           self-reported Likert items.  Clipped to [1.0, 5.0].

Positive rate: N/A (regression task — label is continuous in [1, 5]).
Target MAE ≤ 0.55 on the test split (acceptable for a 5-point scale with
inherent subjectivity noise).

Usage:
    python generate_difficulty_data.py
    python generate_difficulty_data.py --rows 60000

Output:
    ml-training/data/difficulty_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors DifficultyFeatures.kt → toFloatArray() and
# perceived_difficulty_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "dayOfWeek",
    "hourOfDay",
    "currentStreak",
    "completionRateLast7Days",
    "completionRateLast30Days",
    "habitAgeDays",
    "targetCount",
    "avgProgressRatio30d",
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a ``rows``-long DataFrame following the §9.4.2 generative model.

    All distributions reflect a realistic habit-tracker population where
    newer, struggling habits feel harder and mature, high-performing habits
    feel easy.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # dayOfWeek: uniform 1..7 (Mon–Sun).
    day_of_week = rng.integers(1, 8, size=rows).astype(np.int8)

    # hourOfDay: bimodal — morning (6–9) and evening (18–22) are peak completion times.
    morning = rng.integers(6, 10, size=rows)
    evening = rng.integers(18, 23, size=rows)
    other = rng.integers(0, 24, size=rows)
    hour_choice = rng.choice([0, 1, 2], size=rows, p=[0.35, 0.40, 0.25])
    hour_of_day = np.where(
        hour_choice == 0, morning, np.where(hour_choice == 1, evening, other)
    ).astype(np.int8)

    # Shared latent "engagement" variable: Beta(2.5, 1.5) skews most users toward
    # the upper half (active tracker users are moderately engaged by default).
    engagement = rng.beta(a=2.5, b=1.5, size=rows).astype(np.float64)

    # currentStreak: Poisson; correlated with engagement.
    streak_lambda = np.clip(engagement * 20.0, 0.5, 20.0)
    current_streak = np.clip(
        rng.poisson(lam=streak_lambda, size=rows), 0, 200
    ).astype(np.int16)

    # completionRateLast7Days and completionRateLast30Days: derived from engagement + noise.
    rate_7d = np.clip(
        engagement + rng.normal(0.0, 0.13, size=rows), 0.0, 1.0
    ).astype(np.float32)
    rate_30d = np.clip(
        engagement + rng.normal(0.0, 0.09, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # habitAgeDays: log-normal; new habits through long-established ones.
    habit_age = np.clip(
        rng.lognormal(mean=4.2, sigma=1.1, size=rows).astype(int), 1, 1800
    ).astype(np.int16)

    # targetCount: most habits have low integer targets; occasional high ones.
    # Weighted choice: 55% in [1,3], 30% in [4,6], 15% in [7,20].
    tier = rng.choice([0, 1, 2], size=rows, p=[0.55, 0.30, 0.15])
    t_low = rng.integers(1, 4, size=rows)
    t_mid = rng.integers(4, 7, size=rows)
    t_high = rng.integers(7, 21, size=rows)
    target_count = np.where(
        tier == 0, t_low, np.where(tier == 1, t_mid, t_high)
    ).astype(np.int8)

    # avgProgressRatio30d: mean(completions / target) per period over last 30 days.
    # High-engagement users regularly over-complete; low-engagement users fall short.
    high_perf = rate_30d >= 0.75
    apr_high = np.clip(rng.normal(loc=1.12, scale=0.18, size=rows), 0.0, 3.0)
    apr_low = rate_30d.astype(np.float64) * rng.uniform(0.50, 0.88, size=rows)
    avg_progress_ratio = np.where(high_perf, apr_high, apr_low).astype(np.float32)

    # ── Label generation using additive difficulty signals ─────────────────

    difficulty = np.full(rows, 3.0, dtype=np.float64)  # neutral baseline

    # HARDER signals
    difficulty += np.where(
        (rate_7d < 0.25) & (current_streak == 0), 1.5, 0.0
    )
    difficulty += np.where(rate_30d < 0.35, 1.0, 0.0)
    difficulty += np.where(avg_progress_ratio < 0.6, 0.5, 0.0)
    difficulty += np.where(target_count >= 7, 0.5, 0.0)
    difficulty += np.where(
        (hour_of_day >= 21) | (hour_of_day <= 5), 0.3, 0.0
    )

    # EASIER signals
    difficulty += np.where(current_streak >= 21, -1.5, 0.0)
    difficulty += np.where(rate_30d >= 0.85, -1.0, 0.0)
    difficulty += np.where(avg_progress_ratio >= 1.2, -0.5, 0.0)
    difficulty += np.where(
        (habit_age >= 180) & (rate_30d >= 0.70), -0.5, 0.0
    )
    difficulty += np.where(
        (current_streak >= 7) & (current_streak < 21), -0.3, 0.0
    )

    # Gaussian noise reflecting subjective variability in Likert ratings.
    difficulty += rng.normal(0.0, 0.45, size=rows)

    perceived_difficulty = np.clip(difficulty, 1.0, 5.0).astype(np.float32)

    return pd.DataFrame(
        {
            "dayOfWeek": day_of_week.astype(np.int8),
            "hourOfDay": hour_of_day.astype(np.int8),
            "currentStreak": current_streak.astype(np.int16),
            "completionRateLast7Days": rate_7d.astype(np.float32),
            "completionRateLast30Days": rate_30d.astype(np.float32),
            "habitAgeDays": habit_age.astype(np.int16),
            "targetCount": target_count.astype(np.int8),
            "avgProgressRatio30d": avg_progress_ratio.astype(np.float32),
            "perceived_difficulty": perceived_difficulty,
        }
    )


def output_path() -> Path:
    """Resolve ``ml-training/data/difficulty_dataset.csv`` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "difficulty_dataset.csv"
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

    mean_diff = float(df["perceived_difficulty"].mean())
    std_diff = float(df["perceived_difficulty"].std())
    print(f"Wrote {len(df):,} rows to {out}")
    print(
        f"Label stats: mean={mean_diff:.3f}  std={std_diff:.3f}  "
        f"min={df['perceived_difficulty'].min():.2f}  "
        f"max={df['perceived_difficulty'].max():.2f}"
    )

    # ── Sanity checks: verify key priors are honoured ──────────────────────

    struggling = df[(df["completionRateLast7Days"] < 0.25) & (df["currentStreak"] == 0)]
    if len(struggling) > 0:
        print(
            f"Sanity — struggling (rate7d<0.25 & streak=0): "
            f"mean difficulty = {struggling['perceived_difficulty'].mean():.2f} "
            f"(expected > 3.5)"
        )

    thriving = df[(df["currentStreak"] >= 21) & (df["completionRateLast30Days"] >= 0.85)]
    if len(thriving) > 0:
        print(
            f"Sanity — thriving (streak≥21 & rate30d≥0.85): "
            f"mean difficulty = {thriving['perceived_difficulty'].mean():.2f} "
            f"(expected < 2.5)"
        )


if __name__ == "__main__":
    main()
