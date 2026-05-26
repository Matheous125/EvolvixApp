"""
generate_abandonment_data.py — Synthetic dataset for Phase 8.1 (HabitAbandonmentClassifier).

PLAN-ML-EXTENSION.md §8.1.2.

Features per row (field order must exactly mirror AbandonmentFeatures.kt
and abandonment_scaler.json → feature_columns):
    habitAge                  1..730  (days since habit was created)
    daysSinceLastCompletion   0..30   (capped at 30 for training stability)
    completionRateLast7Days   0.0..1.0
    completionRateLast30Days  0.0..1.0
    currentStreak             0..200
    totalCompletions          0..730
    frequencyOrdinal          0=DAILY, 1=WEEKLY, 2=MONTHLY
    involuntarySkipDays7d     0..7  (distinct SICK/TRAVELING skip dates in last 7d)
    involuntarySkipDays30d    0..30 (distinct SICK/TRAVELING skip dates in last 30d)

R2 note: The two new fields allow the model to discount raw gap signals when the user
was genuinely sick or traveling — matching the adjusted_gap logic in MathHabitPredictor.

Label:
    1  if the habit receives **zero completions in (T, T+14 days]** (abandoned)
    0  otherwise

Label generation strategy (logit-based, matching generate_success_data.py convention):
    Start from a neutral logit=0 baseline and apply signed behavioral nudges:
        HIGH ABANDONMENT signals (positive logit → label → 1):
        +3.0  daysSinceLastCompletion >= 14
        +2.0  daysSinceLastCompletion >= 7  AND  completionRateLast7Days < 0.2
        +1.5  completionRateLast30Days < 0.1
        +1.0  completionRateLast7Days < 0.2  (mild signal alone)
        +0.5  young habit (habitAge < 14) with low total completions (< 3)

        LOW ABANDONMENT signals (negative logit → label → 0):
        -3.0  currentStreak >= 14
        -2.0  completionRateLast7Days >= 0.8
        -1.5  currentStreak >= 7
        -1.0  completionRateLast30Days >= 0.7
        -0.5  totalCompletions >= 30  (established habit)

    Logit is then scaled by 1.8 before sigmoid (same rationale as
    generate_success_data.py: avoids clustering samples near p=0.5
    so the MLP has a learnable signal rather than Bayes-noise).

    Note on frequencyOrdinal: WEEKLY/MONTHLY habits naturally have longer
    inter-completion gaps, so daysSinceLastCompletion alone would over-predict
    abandonment. The model can learn to discount gap signals for
    WEEKLY/MONTHLY habits from the joint distribution — the ordinal is
    therefore an important confound-correction feature.

Positive rate target: ~35–45 % (habitual users are a majority; realistic
for a habit-tracker app where most active users maintain their habits).

Usage:
    python generate_abandonment_data.py
    python generate_abandonment_data.py --rows 50000

Output:
    ml-training/data/abandonment_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors AbandonmentFeatures.kt → toFloatArray() and
# abandonment_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "habitAge",
    "daysSinceLastCompletion",
    "completionRateLast7Days",
    "completionRateLast30Days",
    "currentStreak",
    "totalCompletions",
    "frequencyOrdinal",
    # R2 — positions 8 & 9: involuntary-skip counts so the model can discount gap signals
    # when the user was legitimately absent (SICK / TRAVELING).
    "involuntarySkipDays7d",
    "involuntarySkipDays30d",
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §8.1.2 generative model.

    All distributions are chosen to reflect a realistic habit-tracker
    population (skewed toward users who mostly complete their habits, with
    a realistic tail of struggling / dormant habits).
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # habitAge: exponential with long tail; most habits are < 90 days old
    habit_age = np.clip(
        rng.exponential(scale=60.0, size=rows) + 1, 1, 730
    ).astype(np.int16)

    # frequencyOrdinal: 70% DAILY, 20% WEEKLY, 10% MONTHLY (realistic split)
    frequency_ordinal = rng.choice(
        [0, 1, 2], size=rows, p=[0.70, 0.20, 0.10]
    ).astype(np.int8)

    # daysSinceLastCompletion: varies by frequency.
    # DAILY habits: short gap (scale=3), WEEKLY: scale=7, MONTHLY: scale=14.
    # This ensures WEEKLY/MONTHLY habits are not automatically flagged as
    # abandoned based on gap alone — the model must learn to use the ordinal.
    gap_scale = np.where(
        frequency_ordinal == 0, 3.0,
        np.where(frequency_ordinal == 1, 7.0, 14.0)
    )
    days_since_last = np.clip(
        rng.exponential(scale=gap_scale, size=rows), 0, 30
    ).astype(np.int8)

    # completionRateLast7Days and completionRateLast30Days:
    # Both are correlated with each other (a struggling user is struggling
    # on both windows), but not identical.  Simulate via a shared latent
    # "engagement" variable with per-window noise.
    engagement = rng.beta(a=2.5, b=1.5, size=rows).astype(np.float64)  # skewed high
    rate_7d = np.clip(
        engagement + rng.normal(0.0, 0.12, size=rows), 0.0, 1.0
    ).astype(np.float32)
    rate_30d = np.clip(
        engagement + rng.normal(0.0, 0.08, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # currentStreak: exponential; most users have short streaks
    current_streak = np.clip(
        rng.exponential(scale=8.0, size=rows), 0, 200
    ).astype(np.int16)

    # involuntarySkipDays7d: distinct days with SICK/TRAVELING skips in the last 7d.
    # Most users have 0 such days; ~10-15% have ≥1 on any given week.
    # Poisson(0.4) gives P(0)≈67%, P(1)≈27%, P(≥2)≈6%.
    involuntary_7d = np.clip(
        rng.poisson(lam=0.4, size=rows), 0, 7
    ).astype(np.int8)

    # involuntarySkipDays30d: includes the 7d count plus additional days in the
    # broader 30-day window.  Extra Poisson(1.0) keeps the marginal distribution
    # realistic (≈1-2 involuntary days per month on average).
    involuntary_30d = np.clip(
        involuntary_7d + rng.poisson(lam=1.0, size=rows), 0, 30
    ).astype(np.int8)

    # totalCompletions: bounded by habitAge; for DAILY: at most habitAge completions
    max_total = np.where(
        frequency_ordinal == 0, habit_age,
        np.where(frequency_ordinal == 1, habit_age // 7 + 1, habit_age // 30 + 1)
    )
    total_completions = np.clip(
        (engagement * max_total).astype(np.int16), 0, 730
    ).astype(np.int16)

    # ── Logit-based label generation ───────────────────────────────────────

    score = np.zeros(rows, dtype=np.float64)

    # R2: adjust the raw gap by subtracting involuntary-skip days in the 7d window
    # so SICK/TRAVELING absences do not inflate the abandonment signal.
    # This mirrors the MathHabitPredictor.predictAbandonment fallback rule.
    adjusted_gap = np.maximum(0, days_since_last - involuntary_7d.astype(np.int8))

    # HIGH ABANDONMENT signals
    # Strong: 2+ weeks of effective silence (after removing involuntary days)
    score += np.where(adjusted_gap >= 14, 3.0, 0.0)

    # Strong combined: 1-week effective silence AND very low weekly rate
    score += np.where(
        (adjusted_gap >= 7) & (rate_7d < 0.20), 2.0, 0.0
    )

    # Moderate: very low 30-day rate regardless of gap
    score += np.where(rate_30d < 0.10, 1.5, 0.0)

    # Mild: low 7-day rate alone (without the gap criterion)
    score += np.where(rate_7d < 0.20, 1.0, 0.0)

    # Mild: very young habit with almost no completions logged (fragile)
    score += np.where(
        (habit_age < 14) & (total_completions < 3), 0.5, 0.0
    )

    # LOW ABANDONMENT signals
    # Very strong: long active streak — this user is NOT going to quit
    score += np.where(current_streak >= 14, -3.0, 0.0)

    # Strong: excellent recent completion rate
    score += np.where(rate_7d >= 0.80, -2.0, 0.0)

    # Moderate: healthy streak
    score += np.where(
        (current_streak >= 7) & (current_streak < 14), -1.5, 0.0
    )

    # Moderate: solid 30-day rate
    score += np.where(rate_30d >= 0.70, -1.0, 0.0)

    # Mild: established habit with many completions (social proof of self)
    score += np.where(total_completions >= 30, -0.5, 0.0)

    # ── Scale and sigmoid ──────────────────────────────────────────────────

    # Scale 1.8: biases the sigmoid output away from the 0.4–0.6 dead zone,
    # raising the Bayes accuracy ceiling and making the ≥ 0.75 F1 target
    # achievable.  Same rationale as generate_success_data.py (scale=2.0).
    # Slightly softer here (1.8) so the 35–45% positive rate target is hit.
    score *= 1.8

    probability = 1.0 / (1.0 + np.exp(-score))
    probability = np.clip(probability, 0.05, 0.95)

    label = (rng.uniform(0.0, 1.0, size=rows) < probability).astype(np.int8)

    return pd.DataFrame(
        {
            "habitAge": habit_age.astype(np.int16),
            "daysSinceLastCompletion": days_since_last.astype(np.int8),
            "completionRateLast7Days": rate_7d.astype(np.float32),
            "completionRateLast30Days": rate_30d.astype(np.float32),
            "currentStreak": current_streak.astype(np.int16),
            "totalCompletions": total_completions.astype(np.int16),
            "frequencyOrdinal": frequency_ordinal.astype(np.int8),
            # R2 — columns 8 & 9 (positions match FEATURE_COLUMNS)
            "involuntarySkipDays7d": involuntary_7d.astype(np.int8),
            "involuntarySkipDays30d": involuntary_30d.astype(np.int8),
            "label": label,
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/abandonment_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "abandonment_dataset.csv"
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

    positives = int(df["label"].sum())
    print(f"Wrote {len(df):,} rows to {out}")
    print(
        f"Class balance: positives={positives:,} "
        f"negatives={len(df) - positives:,} "
        f"(positive rate = {positives / len(df):.3f})"
    )

    # Sanity checks: verify key priors are honoured
    high_gap_low_rate = df[(df["daysSinceLastCompletion"] >= 7) & (df["completionRateLast7Days"] < 0.20)]
    if len(high_gap_low_rate) > 0:
        prior_rate = high_gap_low_rate["label"].mean()
        print(f"Prior check — gap≥7 AND rate7d<0.2 → abandonment rate = {prior_rate:.3f}  (expect >0.75)")

    long_streak = df[df["currentStreak"] >= 14]
    if len(long_streak) > 0:
        safe_rate = long_streak["label"].mean()
        print(f"Prior check — streak≥14 → abandonment rate = {safe_rate:.3f}  (expect <0.10)")


if __name__ == "__main__":
    main()
