"""
generate_weekly_forecast_data.py — Synthetic dataset for Phase 8.3 (WeeklyForecastRegressor).

PLAN-ML-EXTENSION.md §8.3.1.

This is the first **regression** task in the project: predict the user's overall
completion rate for the next 7 days, given the last week's behaviour.

Features per row (field order MUST exactly mirror WeeklyForecastFeatures.kt
→ toFloatArray() and weekly_forecast_scaler.json → feature_columns):

     1. lastWeekRate            0.0..1.0  overall completion rate of the trailing 7 days
     2. avgCurrentStreak        0..200    mean of current streaks across all active habits
     3. habitCount              1..30     number of active (non-paused) habits
     4. rateMon                 0.0..1.0  rate on Mondays over the last 4 weeks
     5. rateTue                 0.0..1.0
     6. rateWed                 0.0..1.0
     7. rateThu                 0.0..1.0
     8. rateFri                 0.0..1.0
     9. rateSat                 0.0..1.0
    10. rateSun                 0.0..1.0
    11. weekOfYearSin           sin(2*pi*weekOfYear/52)   — seasonality encoding
    12. weekOfYearCos           cos(2*pi*weekOfYear/52)

Label:
    next_week_rate ∈ [0.0, 1.0]   — overall completion rate over the following 7 days.

Generative model (priors encoded as deterministic transforms + Gaussian noise):

    Let week_mean = mean(rateMon..rateSun) so the weekday pattern grounds the user's
    "structural" behaviour beyond just the last 7 days.

    base = 0.65 * lastWeekRate + 0.35 * week_mean

    Adjustments:
      +0.05  if avgCurrentStreak >= 14   (streak momentum lifts next week)
      +0.03  if avgCurrentStreak >= 30   (additive, mature-habit bonus)
      -0.05  if habitCount       >= 12   (cognitive overload depresses next week)
      -0.10  if lastWeekRate     <  0.20 (already-falling trajectory)
      +0.04  if lastWeekRate     >  0.80 (already-rising trajectory)

    seasonality_bump = 0.02 * weekOfYearSin   (mild "January motivation" curve;
                                               peaks near the new year)

    next_week_rate = clip(base + adjustments + seasonality_bump + N(0, 0.06),
                          0.0, 1.0)

Gaussian noise std=0.06 keeps the Bayes-optimal MAE ≈ 0.045–0.055; the network
must learn the structural signal to beat the naive lastWeekRate-only baseline
(MAE ~0.08 on this generator).

Usage:
    python generate_weekly_forecast_data.py
    python generate_weekly_forecast_data.py --rows 50000

Output:
    ml-training/data/weekly_forecast_dataset.csv
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors WeeklyForecastFeatures.kt → toFloatArray() and
# weekly_forecast_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "lastWeekRate",
    "avgCurrentStreak",
    "habitCount",
    "rateMon",
    "rateTue",
    "rateWed",
    "rateThu",
    "rateFri",
    "rateSat",
    "rateSun",
    "weekOfYearSin",
    "weekOfYearCos",
]


def output_path() -> Path:
    here = Path(__file__).resolve().parent
    out = here / "data"
    out.mkdir(parents=True, exist_ok=True)
    return out / "weekly_forecast_dataset.csv"


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §8.3.1 generative model.

    Each row simulates ONE user-week snapshot: the input vector is the state at
    the end of that week, and the label is the completion rate over the next 7
    days conditioned on the same user's behavioural priors.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # lastWeekRate — Beta(2, 2) keeps mass spread across [0,1] (realistic mix
    # of struggling, average, and consistent users).
    last_week_rate = rng.beta(a=2.0, b=2.0, size=rows).astype(np.float32)

    # avgCurrentStreak — exponential heavy tail; most users average < 10 days,
    # a few mature users reach 50+.
    avg_current_streak = np.clip(
        rng.exponential(scale=8.0, size=rows), 0.0, 200.0
    ).astype(np.float32)

    # habitCount — most users track 3..7 habits, with a small power-user tail.
    habit_count = np.clip(
        rng.poisson(lam=5.0, size=rows), 1, 30
    ).astype(np.int16)

    # Per-weekday rates: anchor around lastWeekRate with weekday-specific drift.
    # Realistic priors: Sat/Sun slightly lower (-0.05), Mon slightly higher (+0.02).
    weekday_drifts = np.array(
        [+0.02, 0.00, +0.01, 0.00, -0.02, -0.05, -0.05], dtype=np.float32
    )
    rates_by_day = np.zeros((rows, 7), dtype=np.float32)
    for i in range(7):
        noise = rng.normal(0.0, 0.12, size=rows).astype(np.float32)
        rates_by_day[:, i] = np.clip(
            last_week_rate + weekday_drifts[i] + noise, 0.0, 1.0
        )

    # weekOfYear ∈ 1..52 sampled uniformly; converted to sin/cos for the model.
    week_of_year = rng.integers(1, 53, size=rows).astype(np.float32)
    week_sin = np.sin(2.0 * math.pi * week_of_year / 52.0).astype(np.float32)
    week_cos = np.cos(2.0 * math.pi * week_of_year / 52.0).astype(np.float32)

    # ── Label generation ───────────────────────────────────────────────────

    week_mean = rates_by_day.mean(axis=1)
    base = 0.65 * last_week_rate + 0.35 * week_mean

    adj = np.zeros(rows, dtype=np.float32)
    adj += np.where(avg_current_streak >= 14.0, 0.05, 0.0).astype(np.float32)
    adj += np.where(avg_current_streak >= 30.0, 0.03, 0.0).astype(np.float32)
    adj += np.where(habit_count >= 12, -0.05, 0.0).astype(np.float32)
    adj += np.where(last_week_rate < 0.20, -0.10, 0.0).astype(np.float32)
    adj += np.where(last_week_rate > 0.80, 0.04, 0.0).astype(np.float32)

    seasonality_bump = (0.02 * week_sin).astype(np.float32)
    noise = rng.normal(0.0, 0.06, size=rows).astype(np.float32)

    next_week_rate = np.clip(
        base + adj + seasonality_bump + noise, 0.0, 1.0
    ).astype(np.float32)

    df = pd.DataFrame(
        {
            "lastWeekRate": last_week_rate,
            "avgCurrentStreak": avg_current_streak,
            "habitCount": habit_count,
            "rateMon": rates_by_day[:, 0],
            "rateTue": rates_by_day[:, 1],
            "rateWed": rates_by_day[:, 2],
            "rateThu": rates_by_day[:, 3],
            "rateFri": rates_by_day[:, 4],
            "rateSat": rates_by_day[:, 5],
            "rateSun": rates_by_day[:, 6],
            "weekOfYearSin": week_sin,
            "weekOfYearCos": week_cos,
            "label": next_week_rate,
        }
    )
    return df


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate Phase 8.3 synthetic dataset.")
    parser.add_argument("--rows", type=int, default=50_000)
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()

    df = generate(rows=args.rows, seed=args.seed)
    path = output_path()
    df.to_csv(path, index=False)
    print(f"Wrote {len(df):,} rows to {path}")
    print(f"Label mean: {df['label'].mean():.4f}")
    print(f"Label std : {df['label'].std():.4f}")


if __name__ == "__main__":
    main()
