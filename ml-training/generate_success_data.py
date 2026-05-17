"""
generate_success_data.py — Synthetic dataset for Model 1 (HabitSuccessClassifier).

Phase 6.5.2 of PLAN.md.

Features per row:
    dayOfWeek                 1..7   (1 = Monday, 7 = Sunday)
    hourOfDay                 0..23
    currentStreak             0..200
    completionRateLast7Days   0.0..1.0
    habitAge                  1..730 (days since habit was created)
    hoursSinceLastCompletion  0..336 (up to 14 days)
    targetCount               1..20

Label generation strategy (PLAN.md §6.5.2):
    Start from a 0.5 baseline probability and apply behavioral nudges:
        +0.25  morning slot (6 <= hour <= 10)
        +0.20  currentStreak > 7
        -0.30  completionRateLast7Days < 0.3
        +0.10  habitAge > 30
        -0.15  weekend evening (Sat/Sun, hour >= 18)
    Then clip into [0.05, 0.95] (avoid degenerate labels) and sample
    label ~ Bernoulli(p). This injects realistic noise so the network
    cannot memorize a deterministic rule — it must approximate the
    underlying distribution, which is exactly what we evaluate in
    `evaluate_models.py`.

Usage:
    python generate_success_data.py
    python generate_success_data.py --rows 50000      # threshold-retry path

Output:
    ml-training/data/success_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

# Reproducibility — thesis runs must be deterministic.
SEED = 42

# Order matters: training script + Android inference must read columns identically.
FEATURE_COLUMNS: list[str] = [
    "dayOfWeek",
    "hourOfDay",
    "currentStreak",
    "completionRateLast7Days",
    "habitAge",
    "hoursSinceLastCompletion",
    "targetCount",
]


def generate(rows: int = 30_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §6.5.2 generative model.

    Exposed as a function so `train_success_model.py` can call it directly
    when the acceptance threshold fails at 30k and the plan mandates a
    50k-row retry.
    """
    rng = np.random.default_rng(seed)

    day_of_week = rng.integers(1, 8, size=rows)               # [1, 7]
    hour_of_day = rng.integers(0, 24, size=rows)              # [0, 23]
    current_streak = rng.integers(0, 201, size=rows)          # [0, 200]
    completion_rate = rng.uniform(0.0, 1.0, size=rows)
    habit_age = rng.integers(1, 731, size=rows)               # [1, 730]
    hours_since_last = rng.integers(0, 337, size=rows)        # [0, 336]
    target_count = rng.integers(1, 21, size=rows)             # [1, 20]

    # Base probability + behavioral nudges (vectorized for speed).
    probability = np.full(rows, 0.5, dtype=np.float64)
    probability += np.where((hour_of_day >= 6) & (hour_of_day <= 10), 0.25, 0.0)
    probability += np.where(current_streak > 7, 0.20, 0.0)
    probability += np.where(completion_rate < 0.3, -0.30, 0.0)
    probability += np.where(habit_age > 30, 0.10, 0.0)
    weekend_evening = (day_of_week >= 6) & (hour_of_day >= 18)
    probability += np.where(weekend_evening, -0.15, 0.0)
    probability = np.clip(probability, 0.05, 0.95)

    # Sample label from Bernoulli(p). uniform < p is the standard trick.
    label = (rng.uniform(0.0, 1.0, size=rows) < probability).astype(np.int8)

    return pd.DataFrame(
        {
            "dayOfWeek": day_of_week.astype(np.int16),
            "hourOfDay": hour_of_day.astype(np.int16),
            "currentStreak": current_streak.astype(np.int16),
            "completionRateLast7Days": completion_rate.astype(np.float32),
            "habitAge": habit_age.astype(np.int16),
            "hoursSinceLastCompletion": hours_since_last.astype(np.int16),
            "targetCount": target_count.astype(np.int16),
            "label": label,
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/success_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "success_dataset.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rows",
        type=int,
        default=30_000,
        help="Number of synthetic rows to generate (default: 30000).",
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
    positives = int(df["label"].sum())
    print(f"Wrote {len(df):,} rows to {out}")
    print(
        f"Class balance: positives={positives:,} "
        f"negatives={len(df) - positives:,} "
        f"(positive rate = {positives / len(df):.3f})"
    )


if __name__ == "__main__":
    main()
