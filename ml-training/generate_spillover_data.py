"""
generate_spillover_data.py — Synthetic dataset for Phase 8.5 (SpilloverRegressor).

PLAN-ML-EXTENSION.md §8.5.1.

This is the **Cross-Habit Spillover** regression task: given that habit A was completed
at a specific hour today, predict the *observational lift* on habit B's same-day
completion probability.  The output (lift_delta) is in [-0.5, +0.5].

  ⚠ THESIS NOTE — CAUSAL CAVEAT:
  This model estimates a *predicted lift* (observational correlation), NOT a causal
  effect.  Completion of A and B on the same day may both be driven by an unobserved
  confounder (e.g. high-energy day, day off work).  The lift should be framed in the
  thesis as a correlation-based estimate, not a causal treatment effect.

Features per row (field order MUST exactly mirror SpilloverFeatures.kt
→ toFloatArray() and spillover_scaler.json → feature_columns):

    1. rateA              0.0..1.0  habit A's 30-day completion rate
    2. rateB              0.0..1.0  habit B's 30-day completion rate
    3. hourACompleted      0..23    hour-of-day at which A was completed (int, normalised by scaler)
    4. coOccurrenceRate   0.0..1.0  fraction of A-completed days on which B was also completed
    5. typicalGapHours    0.0..24.0 median |t_B − t_A| in hours on shared completion days;
                                    falls back to training median (≈ 3 h) when shared days < 3

Label:
    lift_delta ∈ [-0.5, +0.5]
    Positive → completing A raises the probability of completing B on the same day.
    Negative → completing A is associated with lower B completion (time-crowding).

Generative model:
    base_lift = coOccurrenceRate − rateB
      ↳ captures excess co-occurrence beyond B's unconditional rate.
        Positive when B happens more often on A-days; negative when B is crowded out.

    activity_factor = sqrt(rateA × rateB)
      ↳ down-weights pairs where one habit is nearly inactive (sparse pairs produce
        noisy co-occurrence estimates in real data, so signal should be small).

    gap_factor = 1 − typicalGapHours / 24
      ↳ spillover decays with time: habits completed close together (small gap)
        carry stronger sequential motivation.

    raw_lift = base_lift × activity_factor × gap_factor

    lift_delta = clip(raw_lift × 1.6 + N(0, 0.05), −0.5, +0.5)

    Scale 1.6: spreads the raw_lift (which naturally sits in ±0.15 for typical
    parameters) toward the full ±0.5 output range, raising the learnable signal
    above the Bayes-noise floor.  Noise std=0.05 keeps the Bayes-optimal MAE ≈ 0.04,
    giving the MLP headroom to reach the target MAE ≤ 0.08 on the test split.

Positive-lift rate target: ~45–55 % (symmetric priors → roughly balanced regression).

Usage:
    python generate_spillover_data.py
    python generate_spillover_data.py --rows 50000

Output:
    ml-training/data/spillover_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors SpilloverFeatures.kt → toFloatArray() and
# spillover_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "rateA",
    "rateB",
    "hourACompleted",
    "coOccurrenceRate",
    "typicalGapHours",
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §8.5.1 generative model.

    Each row simulates one ordered habit-pair (A, B) snapshot:
    - A was completed at some hour today.
    - The label is the observational lift on B's same-day completion probability.

    The synthetic population covers the full behaviour spectrum:
    - High-activity users with tightly-coupled habit clusters (large positive lift).
    - Independent habits with neutral spillover (lift ≈ 0).
    - Time-competing habits where A crowds out B (negative lift).
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # rateA: habit A's 30-day completion rate — skewed toward active users.
    rate_a = np.clip(
        rng.beta(a=3.0, b=1.5, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # rateB: habit B's 30-day completion rate — similar prior; independent draw.
    rate_b = np.clip(
        rng.beta(a=3.0, b=1.5, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # hourACompleted: uniform over waking hours; slight morning/evening peaks.
    # Model as a mixture: 60% peak hours (6-9, 17-21), 40% random.
    peak_mask = rng.random(rows) < 0.60
    peak_hours = rng.choice(
        list(range(6, 10)) + list(range(17, 22)), size=rows
    ).astype(np.int8)
    random_hours = rng.integers(0, 24, size=rows).astype(np.int8)
    hour_a = np.where(peak_mask, peak_hours, random_hours).astype(np.int8)

    # coOccurrenceRate: fraction of A-completed days on which B was also completed.
    # Correlated with rateB (can't exceed 1.0) and has a realistic spread.
    # Modelled as: rateB + perturbation, clipped to [0, 1].
    # Perturbation is a zero-centred normal so the population is symmetric around
    # rateB (neutral co-occurrence = no spillover).
    perturbation = rng.normal(loc=0.0, scale=0.20, size=rows)
    co_occurrence_rate = np.clip(
        rate_b.astype(np.float64) + perturbation, 0.0, 1.0
    ).astype(np.float32)

    # typicalGapHours: median |t_B − t_A| in hours on shared days.
    # Exponential with scale=3.0 captures the "both done in same session" peak,
    # with a long tail for habits done at opposite ends of the day.
    # Capped at 24 h because cross-day spill is outside the model's scope.
    typical_gap = np.clip(
        rng.exponential(scale=3.0, size=rows), 0.0, 24.0
    ).astype(np.float32)

    # ── Label (lift_delta) generation ─────────────────────────────────────

    # base_lift: excess co-occurrence beyond B's unconditional rate.
    base_lift = co_occurrence_rate.astype(np.float64) - rate_b.astype(np.float64)

    # activity_factor: down-weight pairs where one habit is sparse.
    # Using sqrt so the scaling is less aggressive than a plain product.
    activity_factor = np.sqrt(
        rate_a.astype(np.float64) * rate_b.astype(np.float64)
    )

    # gap_factor: proximity in time amplifies sequential motivation spillover.
    gap_factor = 1.0 - typical_gap.astype(np.float64) / 24.0

    raw_lift = base_lift * activity_factor * gap_factor

    # Scale + noise + clip to target range.
    noise = rng.normal(0.0, 0.05, size=rows)
    lift_delta = np.clip(raw_lift * 1.6 + noise, -0.5, 0.5).astype(np.float32)

    return pd.DataFrame(
        {
            "rateA": rate_a,
            "rateB": rate_b,
            "hourACompleted": hour_a.astype(np.int8),
            "coOccurrenceRate": co_occurrence_rate,
            "typicalGapHours": typical_gap,
            "lift_delta": lift_delta,
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/spillover_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "spillover_dataset.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rows",
        type=int,
        default=50_000,
        help="Number of synthetic habit-pair rows to generate (default: 50000).",
    )
    args = parser.parse_args()

    path = output_path()
    df = generate(rows=args.rows)

    df.to_csv(path, index=False)

    positive = (df["lift_delta"] > 0).mean()
    print(f"Generated {len(df):,} rows → {path}")
    print(f"  lift_delta range : {df['lift_delta'].min():.4f} … {df['lift_delta'].max():.4f}")
    print(f"  mean lift_delta  : {df['lift_delta'].mean():.4f}")
    print(f"  positive-lift %  : {positive:.1%}")


if __name__ == "__main__":
    main()
