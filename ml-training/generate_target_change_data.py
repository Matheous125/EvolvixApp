"""
generate_target_change_data.py — Synthetic dataset for Phase 9.3 (TargetAdjustmentRegressor).

PLAN-ML-EXTENSION.md §9.3.2.

This is the **Target Adjustment Recommender** regression task: given a habit's current
state, predict the ideal integer target delta ∈ {−2, −1, 0, +1, +2}.  The trained
regressor replaces the hard-coded ±1 rule in `AdaptiveDifficultyUseCase`.

  ⚠ THESIS NOTE — CAUSAL CAVEAT:
  This model is trained on *synthetic priors* that encode behavioral hypotheses about
  when a target change is beneficial.  It is an observational recommender, not a
  counterfactual treatment-effect estimator.  Frame accordingly in the thesis: the model
  predicts "what target tends to correlate with sustained high performance given the
  current habit state," not "what would happen if we forced the target to change."

  ⚠ R9 RETRAIN (PLAN-MODEL-RETRAINING.md):
  Phase 9.4 added `perceivedDifficulty` to HabitCompletionEntity.  R9 fulfils the
  promise made there: `recentAvgDifficulty` (rolling 14-completion average of user-
  reported difficulty, default 3.0 when no ratings exist) is now the 9th input feature.
  The R9 prior pushes the recommended delta to -1 when the user is succeeding through
  grinding (recentAvgDifficulty ≥ 4.0 AND rate30d ≥ 0.80), even though the raw
  completion rate would otherwise trigger a +1 recommendation.

Features per row (field order MUST exactly mirror TargetChangeFeatures.kt
→ toFloatArray() and target_change_scaler.json → feature_columns):

    1. currentTarget           ≥ 1      current daily/weekly repetition target
    2. rate30d                 0..1     30-day reached-period rate
    3. rate7d                  0..1     7-day reached-period rate
    4. avgProgressRatio30d     0..~3    mean(completions_in_period / target) over 30 days
                                        values > 1.0 indicate over-completion
    5. currentStreak           0..∞     current streak length in periods
    6. habitAgeDays            0..∞     days since habit creation
    7. previousDelta           −2..+2   last target delta applied (0 if no prior change)
    8. periodsSinceLastChange  0..999   periods elapsed since the last target change;
                                        999 sentinel when the target has never changed
    9. recentAvgDifficulty     1..5     rolling average of user-reported perceivedDifficulty
                                        over the last 14 rated completions; 3.0 default
                                        when fewer than 3 ratings are available (neutral)

Label:
    ideal_delta ∈ [−2.0, +2.0]  (continuous; caller rounds to integer after inference)

Generative model (baked-in behavioral priors):

    R9 grinding suppressor (checked before all other rules):
        recentAvgDifficulty ≥ 4.0  AND  rate30d ≥ 0.80
        → user is succeeding through sheer grinding; ease target down by −1 to
          promote sustainable habit formation even though rate looks acceptable.

    Strong increase (+2) signal:
        rate30d ≥ 0.95  AND  avgProgressRatio30d ≥ 1.30  AND  habitAgeDays ≥ 30
        → user is crushing the current target; double-step up.

    Moderate increase (+1) signal:
        rate30d ≥ 0.88  AND  avgProgressRatio30d ≥ 1.05
        → consistently meeting or beating target; time to raise the bar.

    Strong decrease (−2) signal:
        rate30d ≤ 0.20  AND  avgProgressRatio30d ≤ 0.40
        → habit is near-abandoned; aggressive reduction to rebuild momentum.

    Moderate decrease (−1) signal:
        rate30d ≤ 0.38  AND  avgProgressRatio30d ≤ 0.70
        → struggling; ease off by one unit.

    Neutral (0):
        everything else — target is well-calibrated.

    Damping factors applied to every raw signal:
        • Too-soon damping: signal × exp(−k / max(periodsSinceLastChange, 1))
          where k = 6.  Smoothly suppresses recommendations made within 6 periods
          of the previous change (avoids oscillation).
        • Habituation penalty: if |previousDelta| == 2 and periodsSinceLastChange < 10,
          attenuate by 0.5 to avoid cascading large jumps.

    Noise: N(0, 0.20) added before clipping.  Keeps the Bayes-optimal MAE ≈ 0.15,
    giving the MLP room to reach target MAE ≤ 0.35 on the test split.

Usage:
    python generate_target_change_data.py
    python generate_target_change_data.py --rows 60000

Output:
    ml-training/data/target_change_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors TargetChangeFeatures.kt → toFloatArray() and
# target_change_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "currentTarget",
    "rate30d",
    "rate7d",
    "avgProgressRatio30d",
    "currentStreak",
    "habitAgeDays",
    "previousDelta",
    "periodsSinceLastChange",
    "recentAvgDifficulty",  # R9: rolling avg of user-reported perceivedDifficulty (1–5)
]


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §9.3.2 generative model.

    Each row simulates one habit-state snapshot: features at the moment a target
    adjustment decision is considered, and the ideal delta the user's future
    performance would justify.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # currentTarget: most habits have a small integer target (1–5); heavy tail to ~10.
    current_target = rng.integers(1, 11, size=rows).astype(np.int32)

    # rate30d: 30-day completion rate — bimodal: many users near-perfect or struggling.
    # Mixture: 35% from Beta(6,2) (high performers), 30% from Beta(2,6) (low performers),
    # 35% from Beta(4,4) (middle ground).
    mix = rng.choice([0, 1, 2], size=rows, p=[0.35, 0.30, 0.35])
    rate30d_high = rng.beta(6.0, 2.0, size=rows).astype(np.float32)
    rate30d_low = rng.beta(2.0, 6.0, size=rows).astype(np.float32)
    rate30d_mid = rng.beta(4.0, 4.0, size=rows).astype(np.float32)
    rate30d = np.where(mix == 0, rate30d_high,
              np.where(mix == 1, rate30d_low, rate30d_mid)).astype(np.float32)

    # rate7d: correlated with rate30d but noisier (short window).
    # Modelled as: rate30d + local fluctuation, clipped to [0, 1].
    rate7d_noise = rng.normal(0.0, 0.12, size=rows)
    rate7d = np.clip(rate30d.astype(np.float64) + rate7d_noise, 0.0, 1.0).astype(np.float32)

    # avgProgressRatio30d: mean(progress / target) per period.
    # Values > 1.0 indicate over-completion.  High-performing habits (rate30d ≥ 0.75)
    # regularly exceed the target; low-performing habits rarely reach it.
    # Modelled with separate distributions per performance tier so over-completion
    # is realistic for the classes that should receive a +delta recommendation.
    high_perf_mask = rate30d >= 0.75
    apr_high = rng.normal(loc=1.15, scale=0.18, size=rows)   # often above 1.0
    apr_low = rate30d.astype(np.float64) * rng.uniform(0.55, 0.90, size=rows)
    avg_progress_ratio = np.clip(
        np.where(high_perf_mask, apr_high, apr_low), 0.0, 3.0
    ).astype(np.float32)

    # currentStreak: Poisson-distributed; correlated with high rate30d.
    streak_base = (rate30d.astype(np.float64) * 25.0).astype(int)
    current_streak = np.clip(
        rng.poisson(lam=np.maximum(streak_base, 1)), 0, 120
    ).astype(np.int32)

    # habitAgeDays: roughly log-normal; new habits (< 14 d) through established ones.
    habit_age = np.clip(
        rng.lognormal(mean=4.0, sigma=1.2, size=rows).astype(int), 1, 1800
    ).astype(np.int32)

    # previousDelta: last delta applied (−2..+2); 0 most common (no prior change).
    previous_delta = rng.choice(
        [-2, -1, 0, 0, 0, 1, 2], size=rows
    ).astype(np.int8)

    # periodsSinceLastChange: periods elapsed since last change.
    # 999 sentinel if previousDelta == 0 (no change ever made).
    never_changed_mask = (previous_delta == 0)
    periods_since = np.where(
        never_changed_mask,
        999,
        rng.integers(1, 200, size=rows)
    ).astype(np.int32)

    # recentAvgDifficulty (R9): rolling average of user-reported perceivedDifficulty (1–5)
    # over the last 14 rated completions.  Modelled as inversely correlated with rate30d
    # so the R9 grinding-suppressor rule has realistic training signal:
    #   • Low performers (rate30d < 0.45) tend to find habits hard   → mean ≈ 4.0
    #   • Mid performers                                              → mean ≈ 3.0
    #   • High performers (rate30d ≥ 0.75): most find it easy, but
    #     ~22% are grinding at high difficulty (the R9 target group)  → mean ≈ 2.0
    # Normal noise (σ = 0.5) applied; result clipped to [1.0, 5.0].
    difficulty_base = np.where(
        rate30d < 0.45,
        rng.normal(loc=4.0, scale=0.5, size=rows),
        np.where(
            rate30d < 0.75,
            rng.normal(loc=3.0, scale=0.5, size=rows),
            rng.normal(loc=2.0, scale=0.6, size=rows),
        ),
    )
    # Inject grinding subset: ~22% of high-rate rows get difficulty ≥ 4.0.
    grinding_mask = (rate30d >= 0.80) & (rng.random(size=rows) < 0.22)
    difficulty_base = np.where(
        grinding_mask,
        rng.uniform(4.0, 5.0, size=rows),
        difficulty_base,
    )
    recent_avg_difficulty = np.clip(difficulty_base, 1.0, 5.0).astype(np.float32)

    # ── Label (ideal_delta) generation ────────────────────────────────────

    r30 = rate30d.astype(np.float64)
    r7 = rate7d.astype(np.float64)
    apr = avg_progress_ratio.astype(np.float64)
    age = habit_age.astype(np.float64)
    prev = previous_delta.astype(np.float64)
    psc = periods_since.astype(np.float64)

    # Compute raw delta signal from behavioral priors.
    raw_delta = np.zeros(rows, dtype=np.float64)

    # Strong increase: user is over-completing consistently.
    strong_up = (r30 >= 0.90) & (apr >= 1.20) & (age >= 21)
    raw_delta = np.where(strong_up, 2.0, raw_delta)

    # Moderate increase: consistently meeting or beating target.
    mod_up = (~strong_up) & (r30 >= 0.78) & (apr >= 1.02)
    raw_delta = np.where(mod_up, 1.0, raw_delta)

    # Strong decrease: near-abandoned habit.
    strong_down = (r30 <= 0.22) & (apr <= 0.45)
    raw_delta = np.where(strong_down, -2.0, raw_delta)

    # Moderate decrease: struggling.
    mod_down = (~strong_down) & (r30 <= 0.40) & (apr <= 0.72)
    raw_delta = np.where(mod_down, -1.0, raw_delta)

    # Damping 1: too-soon — suppress recommendations close to previous change.
    # Smoothed exponential decay: factor approaches 1.0 as periodsSinceLastChange grows.
    K = 6.0
    recency_factor = 1.0 - np.exp(-psc / K)
    # For the 999-sentinel (never changed), recency_factor → 1.0 naturally.
    recency_factor = np.where(psc >= 999, 1.0, recency_factor)
    raw_delta = raw_delta * recency_factor

    # Damping 2: habituation — soften after a large recent delta.
    habituation_mask = (np.abs(prev) == 2) & (psc < 10)
    raw_delta = np.where(habituation_mask, raw_delta * 0.5, raw_delta)

    # R9 grinding suppressor: override increase signals when the user is succeeding
    # through grinding (high difficulty despite good completion rate).  Applied BEFORE
    # noise so the model learns a clean decision boundary for this regime.
    grinding_prior = (recent_avg_difficulty.astype(np.float64) >= 4.0) & (r30 >= 0.80)
    raw_delta = np.where(grinding_prior, -1.0, raw_delta)

    # Noise and clip.
    noise = rng.normal(0.0, 0.20, size=rows)
    ideal_delta = np.clip(raw_delta + noise, -2.0, 2.0).astype(np.float32)

    return pd.DataFrame(
        {
            "currentTarget": current_target,
            "rate30d": rate30d,
            "rate7d": rate7d,
            "avgProgressRatio30d": avg_progress_ratio,
            "currentStreak": current_streak,
            "habitAgeDays": habit_age,
            "previousDelta": previous_delta.astype(np.int8),
            "periodsSinceLastChange": periods_since,
            "recentAvgDifficulty": recent_avg_difficulty,  # R9
            "ideal_delta": ideal_delta,
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/target_change_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "target_change_dataset.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rows",
        type=int,
        default=50_000,
        help="Number of synthetic habit-state rows to generate (default: 50000).",
    )
    args = parser.parse_args()

    path = output_path()
    df = generate(rows=args.rows)

    df.to_csv(path, index=False)

    delta_counts = df["ideal_delta"].round().astype(int).value_counts().sort_index()
    print(f"Generated {len(df):,} rows → {path}")
    print(f"  ideal_delta range : {df['ideal_delta'].min():.4f} … {df['ideal_delta'].max():.4f}")
    print(f"  mean ideal_delta  : {df['ideal_delta'].mean():.4f}")
    print("  rounded delta distribution:")
    for delta_val, count in delta_counts.items():
        print(f"    delta={delta_val:+d} : {count:>6,} ({count / len(df):.1%})")


if __name__ == "__main__":
    main()
