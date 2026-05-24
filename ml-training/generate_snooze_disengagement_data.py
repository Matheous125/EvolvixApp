"""
generate_snooze_disengagement_data.py — Synthetic dataset for Phase 9.2 (SnoozeDisengagementClassifier).

PLAN-ML-EXTENSION.md §9.2.2.

This is the **Snooze Disengagement** binary classification task: given a habit's state
and its recent snooze pattern, predict whether the user will abandon the habit within
the next 7 days (zero completions in that window).

The 7-day horizon is intentionally shorter than Phase 8.1 (14-day abandonment) so that
the model fires as an early-warning signal before the long-horizon abandonment model
triggers. Snooze frequency/count data is the distinguishing feature set.

  ⚠ THESIS NOTE — OBSERVATIONAL CAVEAT:
  This is a *predicted disengagement risk estimator*, NOT a causal treatment-effect
  model. Snooze behaviour and eventual abandonment may share common confounders
  (e.g. a sudden busy week causes both more snoozing AND eventual dropout). The model
  should be framed in the thesis as "predicted risk given snooze pattern" and should
  NOT be presented as evidence that snoozing *causes* abandonment.

Features per row (field order MUST exactly mirror SnoozeDisengagementFeatures.kt
→ toFloatArray() and snooze_disengagement_scaler.json → feature_columns):

    1. habitAge                    int     days since first completion (1..730)
    2. completionRateLast7Days     float   0.0..1.0
    3. completionRateLast30Days    float   0.0..1.0
    4. currentStreak               int     0..200
    5. avgSnoozeCountLast14Days    float   mean snoozeCount across reminder-driven
                                           completions in the past 14 days (0.0..10.0)
    6. snoozeFrequencyLast14Days   float   fraction of reminder-driven completions that
                                           had snoozeCount >= 1 in the past 14 days
                                           (0.0..1.0)
    7. frequencyOrdinal            int     0=DAILY, 1=WEEKLY, 2=MONTHLY

Label:
    1  if the habit receives **zero completions in the next 7 days** (disengaging)
    0  otherwise

Label generation strategy (logit-based, matching generate_abandonment_data.py convention):
    Start from a neutral logit=0 baseline and apply signed behavioral nudges:

        HIGH DISENGAGEMENT signals (positive logit → label → 1):
        +3.0  avgSnoozeCountLast14Days >= 3 AND completionRateLast7Days < 0.3
              (heavy snoozer with low recent engagement — strongest combined signal)
        +2.0  snoozeFrequencyLast14Days >= 0.8
              (user almost always snoozes rather than completing immediately)
        +1.5  avgSnoozeCountLast14Days >= 2.0
              (frequent multiple-snooze sessions)
        +1.0  completionRateLast7Days < 0.2
              (low recent engagement independent of snooze behaviour)
        +0.5  snoozeFrequencyLast14Days >= 0.5
              (mild snooze pattern — moderate signal)

        LOW DISENGAGEMENT signals (negative logit → label → 0):
        -3.0  currentStreak >= 14
              (long active streak — strong momentum, extremely unlikely to drop out)
        -2.0  completionRateLast7Days >= 0.8
              (highly active user — snooze is cosmetic, not disengagement)
        -1.5  avgSnoozeCountLast14Days < 0.3 AND completionRateLast7Days >= 0.6
              (rarely snoozes AND good completion rate — healthy pattern)
        -1.0  completionRateLast30Days >= 0.7
              (solid long-term engagement rate)
        -0.5  currentStreak >= 7
              (decent active streak — mild protective signal)

    Logit is scaled by 1.8 before sigmoid (same rationale as
    generate_abandonment_data.py: avoids clustering samples near p=0.5 so
    the MLP gets a learnable signal rather than Bayes-noise).

Positive rate target: ~30–40 % (more selective than 8.1 because snooze-driven
disengagement is a narrower, more actionable signal).

Usage:
    python generate_snooze_disengagement_data.py
    python generate_snooze_disengagement_data.py --rows 50000

Output:
    ml-training/data/snooze_disengagement_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors SnoozeDisengagementFeatures.kt → toFloatArray() and
# snooze_disengagement_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "habitAge",
    "completionRateLast7Days",
    "completionRateLast30Days",
    "currentStreak",
    "avgSnoozeCountLast14Days",
    "snoozeFrequencyLast14Days",
    "frequencyOrdinal",
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a ``rows``-long DataFrame following the §9.2.2 generative model.

    All distributions are chosen to reflect a realistic habit-tracker
    population with a tail of users who increasingly rely on snoozing as a
    coping mechanism before eventually abandoning the habit.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # habitAge: exponential with long tail; most habits are relatively young
    habit_age = np.clip(
        rng.exponential(scale=60.0, size=rows) + 1, 1, 730
    ).astype(np.int16)

    # frequencyOrdinal: 70% DAILY, 20% WEEKLY, 10% MONTHLY (realistic split)
    frequency_ordinal = rng.choice(
        [0, 1, 2], size=rows, p=[0.70, 0.20, 0.10]
    ).astype(np.int8)

    # Shared latent "engagement" variable: high = committed user, low = drifting.
    # Skewed toward the upper half (beta(2.5, 1.5)) because most active users
    # of a habit tracker are still moderately engaged.
    engagement = rng.beta(a=2.5, b=1.5, size=rows).astype(np.float64)

    # completionRateLast7Days and completionRateLast30Days: correlated via engagement
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

    # avgSnoozeCountLast14Days: snooze count is inversely correlated with engagement.
    # Low-engagement users snooze more, high-engagement users snooze rarely.
    # Mean snooze count ~ Poisson(lambda), where lambda is driven by (1 - engagement).
    # We clamp to [0, 10] to avoid pathological outliers.
    snooze_lambda = np.clip(
        (1.0 - engagement) * 3.0 + rng.exponential(0.3, size=rows), 0.0, 10.0
    )
    avg_snooze_count = np.clip(
        rng.poisson(lam=snooze_lambda.clip(0.01), size=rows).astype(np.float32),
        0.0, 10.0
    ).astype(np.float32)

    # snoozeFrequencyLast14Days: fraction of reminder sessions that resulted in ≥1 snooze.
    # Correlated with avgSnoozeCount: high-snooze users almost always snooze each session.
    # Moderate noise so it isn't a perfect deterministic function of avgSnoozeCount.
    snooze_frequency = np.clip(
        avg_snooze_count / 4.0 + rng.normal(0.0, 0.10, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # ── Logit-based label generation ───────────────────────────────────────

    score = np.zeros(rows, dtype=np.float64)

    # HIGH DISENGAGEMENT signals
    # Strongest: heavy snooze pattern combined with low recent engagement
    score += np.where(
        (avg_snooze_count >= 3.0) & (rate_7d < 0.30), 3.0, 0.0
    )

    # Strong: almost always snoozes rather than completing immediately
    score += np.where(snooze_frequency >= 0.80, 2.0, 0.0)

    # Moderate: frequent multiple-snooze sessions (even without low rate)
    score += np.where(avg_snooze_count >= 2.0, 1.5, 0.0)

    # Mild: low recent completion rate alone (same signal as in 8.1)
    score += np.where(rate_7d < 0.20, 1.0, 0.0)

    # Mild: moderate snooze frequency is an early-warning flag
    score += np.where(snooze_frequency >= 0.50, 0.5, 0.0)

    # LOW DISENGAGEMENT signals
    # Very strong: long active streak — this user is going nowhere
    score += np.where(current_streak >= 14, -3.0, 0.0)

    # Strong: high recent completion rate — snooze is cosmetic, not disengagement
    score += np.where(rate_7d >= 0.80, -2.0, 0.0)

    # Moderate: rarely snoozes AND good completion rate — healthy pattern
    score += np.where(
        (avg_snooze_count < 0.30) & (rate_7d >= 0.60), -1.5, 0.0
    )

    # Moderate: solid long-term engagement
    score += np.where(rate_30d >= 0.70, -1.0, 0.0)

    # Mild: decent active streak as a protective signal
    score += np.where(
        (current_streak >= 7) & (current_streak < 14), -0.5, 0.0
    )

    # ── Scale and sigmoid ──────────────────────────────────────────────────

    # Scale 1.8: biases the sigmoid output away from the 0.4–0.6 dead zone,
    # raising the Bayes accuracy ceiling and making the F1 ≥ 0.75 target
    # achievable.  Same rationale and magnitude as generate_abandonment_data.py.
    score *= 1.8

    probability = 1.0 / (1.0 + np.exp(-score))
    probability = np.clip(probability, 0.05, 0.95)

    label = (rng.uniform(0.0, 1.0, size=rows) < probability).astype(np.int8)

    return pd.DataFrame(
        {
            "habitAge": habit_age.astype(np.int16),
            "completionRateLast7Days": rate_7d.astype(np.float32),
            "completionRateLast30Days": rate_30d.astype(np.float32),
            "currentStreak": current_streak.astype(np.int16),
            "avgSnoozeCountLast14Days": avg_snooze_count.astype(np.float32),
            "snoozeFrequencyLast14Days": snooze_frequency.astype(np.float32),
            "frequencyOrdinal": frequency_ordinal.astype(np.int8),
            "label": label,
        }
    )


def output_path() -> Path:
    """Resolve ``ml-training/data/snooze_disengagement_dataset.csv`` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "snooze_disengagement_dataset.csv"
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

    # ── Sanity checks: verify key priors are honoured ──────────────────────

    heavy_snooze_low_rate = df[
        (df["avgSnoozeCountLast14Days"] >= 3.0) & (df["completionRateLast7Days"] < 0.30)
    ]
    if len(heavy_snooze_low_rate) > 0:
        prior = heavy_snooze_low_rate["label"].mean()
        print(
            f"Prior check — avgSnooze≥3 AND rate7d<0.3 → disengagement rate = {prior:.3f}"
            f"  (expect >0.75)"
        )

    high_freq_snooze = df[df["snoozeFrequencyLast14Days"] >= 0.80]
    if len(high_freq_snooze) > 0:
        prior = high_freq_snooze["label"].mean()
        print(
            f"Prior check — snoozeFreq≥0.8 → disengagement rate = {prior:.3f}"
            f"  (expect >0.65)"
        )

    long_streak = df[df["currentStreak"] >= 14]
    if len(long_streak) > 0:
        prior = long_streak["label"].mean()
        print(
            f"Prior check — streak≥14 → disengagement rate = {prior:.3f}"
            f"  (expect <0.10)"
        )

    no_snooze_high_rate = df[
        (df["avgSnoozeCountLast14Days"] < 0.30) & (df["completionRateLast7Days"] >= 0.60)
    ]
    if len(no_snooze_high_rate) > 0:
        prior = no_snooze_high_rate["label"].mean()
        print(
            f"Prior check — avgSnooze<0.3 AND rate7d≥0.6 → disengagement rate = {prior:.3f}"
            f"  (expect <0.15)"
        )


if __name__ == "__main__":
    main()
