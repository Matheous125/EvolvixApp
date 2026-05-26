"""
generate_clustering_data.py — Synthetic dataset for Phase 8.4 (Habit Behavioral Clustering).

PLAN-ML-EXTENSION.md §8.4.1.

This is an **unsupervised** task: the dataset contains NO label column.
The train script (`train_clustering_model.py`) fits KMeans(n_clusters=4) on the
feature matrix and derives cluster label assignments post-hoc by ranking centroids
on `rate30d` (highest = "effortless_routine", lowest = "dormant").

Features per row (field order MUST exactly mirror ClusterFeatures.kt → toFloatArray()
and `habit_clusters.json` → feature_columns):

    1. rate30d                    0.0..1.0   30-day completion rate (reached / due days).
    2. routine_precision_stddev   0..300     Std-dev of daily completion hour in MINUTES.
                                             Lower = more consistent routine timing.
                                             Android substitutes training_medians[1] when
                                             the habit has < 5 completions (insufficient
                                             data for a meaningful stddev).
    3. procrastination_skew      -3.0..3.0   Skewness of completion hour-of-day distribution.
                                             Positive = completions cluster at end of day
                                             (procrastinating); negative = early completer.
                                             Android substitutes 0.0 (neutral) when < 10
                                             completions — stored as training_medians[2] in
                                             habit_clusters.json for consistency.
    4. habit_age                  1..365     Days since first recorded completion (conservative
                                             proxy for habit creation date), capped at 365.
    5. resilience_avg_gap         0..15      Average days to resume after a missed period.
                                             0 = habit has never been missed.
                                             Android substitutes training_medians[4] when no
                                             recovery events are observable (new habits).

No label column — K-Means is unsupervised; cluster labels are derived post-fit by
train_clustering_model.py.

Behavioral archetype priors
────────────────────────────
These reflect literature-informed hypotheses on habit-tracker users (not measured
ground truth — appropriate to state as such in the thesis):

    ARCHETYPE           WEIGHT   rate30d      precision_std  proc_skew    habit_age    resilience_gap  vol_skip   invol_skip
    ─────────────────── ──────   ──────────   ─────────────  ─────────    ─────────    ──────────────  ─────────  ──────────
    Effortless Routine  18 %     0.85–1.00    10–40 min      -1.5–0.2     90–365 d     0.5–2.5 d       0–0.05     0–0.05
    Consistent Effort   32 %     0.55–0.85    30–95 min       0.0–1.0     45–270 d     1.5–5.0 d       0–0.15     0–0.10
    Struggling          27 %     0.15–0.55    85–210 min      0.5–2.5     14–120 d     4.0–10.0 d      0.10–0.60  0–0.15
    Dormant             13 %     0.00–0.20    130–300 min    -1.5–3.0      7–365 d     8.0–15.0 d      0.05–0.40  0–0.10
    Life-Disrupted      10 %     0.30–0.70    20–70 min      -0.5–0.5     30–180 d     1.0–4.0 d       0–0.08     0.15–0.50

    (Weights shown for include_life_disrupted=True / K=5. When include_life_disrupted=False
     the original 20/35/30/15 four-archetype weights are used and no life_disrupted rows
     are generated.)

Weight rationale (20 / 35 / 30 / 15): most habit-tracker users are "making effort" or
"struggling"; high-performers are a minority; abandoned habits are the smallest tail.
This produces realistic non-uniform cluster sizes, which is important for K-Means to
learn meaningful separation (uniform clusters defeat the purpose of clustering).

Separation notes:
  • rate30d is the primary discriminating feature across all four archetypes with
    non-overlapping ranges (except a small Consistent/Struggling overlap at 0.55).
  • routine_precision_stddev and resilience_avg_gap provide secondary orthogonal signal
    that helps separate Effortless from Consistent (which share moderate rate30d tails).
  • habit_age is a nuisance feature for Dormant (bimodal: new give-ups OR old abandoned)
    — this is intentional; the model should learn that Dormant habits span all ages.
  • procrastination_skew is weakest separator but still contributes to intra-cluster
    coherence (Effortless users tend to complete early; Struggling users tend to delay).

Usage:
    python generate_clustering_data.py
    python generate_clustering_data.py --rows 10000

Output:
    ml-training/data/clustering_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors ClusterFeatures.kt → toFloatArray() and
# habit_clusters.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "rate30d",
    "routine_precision_stddev",
    "procrastination_skew",
    "habit_age",
    "resilience_avg_gap",
    "voluntary_skip_rate_30d",    # R4 addition — fraction of last-30d days with a voluntary skip
    "involuntary_skip_rate_30d",  # R4 addition — fraction of last-30d days with an involuntary skip
]


def output_path() -> Path:
    here = Path(__file__).resolve().parent
    out = here / "data"
    out.mkdir(parents=True, exist_ok=True)
    return out / "clustering_dataset.csv"


def _sample_effortless(rng: np.random.Generator, n: int) -> pd.DataFrame:
    """
    Archetype: Effortless Routine (20 % of population).

    High performers who have built automatic habits. Characterized by:
    - Very high 30-day completion rate (≥ 0.85).
    - Narrow timing window (low routine_precision_stddev): same time, same place.
    - Slight tendency to complete early in the day (mildly negative skew).
    - Established habits (older habit_age: 3 months to 1 year).
    - Fast recovery from rare misses (low resilience_avg_gap).
    """
    # Beta(a >> b) concentrates mass near 1.0; clip removes lower-tail artefacts.
    rate30d = rng.beta(a=9.0, b=1.5, size=n).clip(0.85, 1.0).astype(np.float32)

    # Normal distribution centered at 22 min stddev; modest spread.
    precision_std = np.clip(
        rng.normal(loc=22.0, scale=7.0, size=n), 10.0, 40.0
    ).astype(np.float32)

    proc_skew = rng.uniform(-1.5, 0.2, size=n).astype(np.float32)

    habit_age = rng.integers(90, 366, size=n).astype(np.int16)

    resilience_gap = rng.uniform(0.5, 2.5, size=n).astype(np.float32)

    # Skip rates (R4): effortless users seldom skip — near-zero on both axes.
    voluntary_skip_rate = rng.beta(a=1.0, b=50.0, size=n).clip(0.0, 0.05).astype(np.float32)
    involuntary_skip_rate = rng.beta(a=1.0, b=50.0, size=n).clip(0.0, 0.05).astype(np.float32)

    return pd.DataFrame({
        "rate30d": rate30d,
        "routine_precision_stddev": precision_std,
        "procrastination_skew": proc_skew,
        "habit_age": habit_age,
        "resilience_avg_gap": resilience_gap,
        "voluntary_skip_rate_30d": voluntary_skip_rate,
        "involuntary_skip_rate_30d": involuntary_skip_rate,
    })


def _sample_consistent(rng: np.random.Generator, n: int) -> pd.DataFrame:
    """
    Archetype: Consistent Effort (35 % of population).

    Users who actively maintain their habits but with some variability.
    Characterized by:
    - Good but imperfect completion rate (0.55–0.85).
    - Moderate timing consistency (30–95 min stddev): routine exists but flexible.
    - Mild procrastination tendency (slightly positive skew).
    - Mid-age habits (6 weeks to 9 months).
    - Moderate recovery from misses (2–5 days).
    """
    rate30d = rng.beta(a=5.0, b=3.0, size=n).clip(0.55, 0.85).astype(np.float32)

    precision_std = np.clip(
        rng.normal(loc=60.0, scale=18.0, size=n), 30.0, 95.0
    ).astype(np.float32)

    proc_skew = rng.uniform(0.0, 1.0, size=n).astype(np.float32)

    habit_age = rng.integers(45, 271, size=n).astype(np.int16)

    resilience_gap = rng.uniform(1.5, 5.0, size=n).astype(np.float32)

    # Skip rates (R4): consistent users skip occasionally but mostly voluntarily.
    voluntary_skip_rate = rng.beta(a=1.5, b=20.0, size=n).clip(0.0, 0.15).astype(np.float32)
    involuntary_skip_rate = rng.beta(a=1.0, b=30.0, size=n).clip(0.0, 0.10).astype(np.float32)

    return pd.DataFrame({
        "rate30d": rate30d,
        "routine_precision_stddev": precision_std,
        "procrastination_skew": proc_skew,
        "habit_age": habit_age,
        "resilience_avg_gap": resilience_gap,
        "voluntary_skip_rate_30d": voluntary_skip_rate,
        "involuntary_skip_rate_30d": involuntary_skip_rate,
    })


def _sample_struggling(rng: np.random.Generator, n: int) -> pd.DataFrame:
    """
    Archetype: Struggling (30 % of population).

    Users who intend to maintain habits but frequently fail. Characterized by:
    - Low-to-moderate completion rate (0.15–0.55).
    - High timing variability (85–210 min stddev): no consistent routine window.
    - Clear procrastination pattern (positive skew: completions bunched at end of day).
    - Younger habits (2 weeks to 4 months): recent attempts that haven't yet stabilized.
    - Slow recovery from misses (4–10 days).
    """
    rate30d = rng.beta(a=2.5, b=5.0, size=n).clip(0.15, 0.55).astype(np.float32)

    precision_std = np.clip(
        rng.normal(loc=145.0, scale=35.0, size=n), 85.0, 210.0
    ).astype(np.float32)

    proc_skew = rng.uniform(0.5, 2.5, size=n).astype(np.float32)

    habit_age = rng.integers(14, 121, size=n).astype(np.int16)

    resilience_gap = rng.uniform(4.0, 10.0, size=n).astype(np.float32)

    # Skip rates (R4): struggling users have elevated voluntary skip rates —
    # choosing to skip is part of the disengagement pattern.
    voluntary_skip_rate = rng.beta(a=3.0, b=5.0, size=n).clip(0.10, 0.60).astype(np.float32)
    involuntary_skip_rate = rng.beta(a=1.5, b=20.0, size=n).clip(0.0, 0.15).astype(np.float32)

    return pd.DataFrame({
        "rate30d": rate30d,
        "routine_precision_stddev": precision_std,
        "procrastination_skew": proc_skew,
        "habit_age": habit_age,
        "resilience_avg_gap": resilience_gap,
        "voluntary_skip_rate_30d": voluntary_skip_rate,
        "involuntary_skip_rate_30d": involuntary_skip_rate,
    })


def _sample_dormant(rng: np.random.Generator, n: int) -> pd.DataFrame:
    """
    Archetype: Dormant (15 % of population).

    Habits that have effectively been abandoned. Characterized by:
    - Very low completion rate (0–0.20).
    - Chaotic or absent timing (130–300 min stddev): no structure remains.
    - Variable procrastination skew (habit is barely touched; irrelevant).
    - Bimodal habit_age: either brand-new give-ups (7–44 d) OR long-abandoned
      habits (180–365 d) that are still sitting in the app. This bimodality
      prevents K-Means from cleanly separating Dormant from Struggling on age alone,
      which is realistic: Dormant habits are not defined by age but by rate30d.
    - Very slow recovery (8–15 days avg gap).
    """
    rate30d = rng.beta(a=1.0, b=8.0, size=n).clip(0.0, 0.20).astype(np.float32)

    precision_std = np.clip(
        rng.normal(loc=210.0, scale=45.0, size=n), 130.0, 300.0
    ).astype(np.float32)

    # procrastination_skew: uniform across [-1.5, 3.0] — dormant habits have no
    # reliable timing pattern, so skew carries no behavioral signal.
    proc_skew = rng.uniform(-1.5, 3.0, size=n).astype(np.float32)

    # Bimodal habit_age: 50 % "new give-up" (7–44 days), 50 % "long abandoned" (180–365 days).
    young = rng.integers(7, 45, size=n).astype(np.int16)
    old   = rng.integers(180, 366, size=n).astype(np.int16)
    pick_young = rng.random(size=n) < 0.5
    habit_age  = np.where(pick_young, young, old).astype(np.int16)

    resilience_gap = rng.uniform(8.0, 15.0, size=n).astype(np.float32)

    # Skip rates (R4): dormant habits are barely opened — moderate voluntary skip rate
    # (user still occasionally remembers but chooses not to act).
    voluntary_skip_rate = rng.beta(a=2.0, b=6.0, size=n).clip(0.05, 0.40).astype(np.float32)
    involuntary_skip_rate = rng.beta(a=1.0, b=30.0, size=n).clip(0.0, 0.10).astype(np.float32)

    return pd.DataFrame({
        "rate30d": rate30d,
        "routine_precision_stddev": precision_std,
        "procrastination_skew": proc_skew,
        "habit_age": habit_age,
        "resilience_avg_gap": resilience_gap,
        "voluntary_skip_rate_30d": voluntary_skip_rate,
        "involuntary_skip_rate_30d": involuntary_skip_rate,
    })


def _sample_life_disrupted(rng: np.random.Generator, n: int) -> pd.DataFrame:
    """
    Archetype: Life-Disrupted (10 % of population when K=5 is attempted, R4).

    Users who are behaviourally engaged but whose completion rate is suppressed
    by external factors — illness, travel, life events — rather than by
    disengagement or procrastination. Distinguished from Struggling by:

    - LOW voluntary_skip_rate_30d  (< 0.08): user is NOT choosing to skip.
    - HIGH involuntary_skip_rate_30d (0.15–0.50): external disruption is the cause.
    - Intermediate rate30d (0.30–0.70): completing on "good" days.
    - Tight timing precision (20–70 min stddev): consistent routine when able.
    - Minimal procrastination (−0.5–0.5): completes early when circumstances allow.
    - Fast resilience_avg_gap (1–4 d): wants to resume, does so quickly.
    """
    # Intermediate rate — reflecting days available to complete (not disengagement).
    rate30d = rng.beta(a=3.0, b=4.0, size=n).clip(0.30, 0.70).astype(np.float32)

    # Tight timing when able — mirrors Effortless/Consistent routine discipline.
    precision_std = np.clip(
        rng.normal(loc=42.0, scale=12.0, size=n), 20.0, 70.0
    ).astype(np.float32)

    # Minimal delay — completes at habitual time when life permits.
    proc_skew = rng.uniform(-0.5, 0.5, size=n).astype(np.float32)

    # Moderate age — habit is established; disruptions are episodic, not chronic.
    habit_age = rng.integers(30, 181, size=n).astype(np.int16)

    # Fast recovery — distinguishes from Dormant (slow) and Struggling (moderate-slow).
    resilience_gap = rng.uniform(1.0, 4.0, size=n).astype(np.float32)

    # Defining markers (R4): near-zero voluntary, elevated involuntary.
    voluntary_skip_rate = rng.beta(a=1.5, b=30.0, size=n).clip(0.0, 0.08).astype(np.float32)
    involuntary_skip_rate = rng.beta(a=4.0, b=6.0, size=n).clip(0.15, 0.50).astype(np.float32)

    return pd.DataFrame({
        "rate30d": rate30d,
        "routine_precision_stddev": precision_std,
        "procrastination_skew": proc_skew,
        "habit_age": habit_age,
        "resilience_avg_gap": resilience_gap,
        "voluntary_skip_rate_30d": voluntary_skip_rate,
        "involuntary_skip_rate_30d": involuntary_skip_rate,
    })


def generate(rows: int = 10_000, seed: int = SEED, include_life_disrupted: bool = True) -> pd.DataFrame:
    """Build a `rows`-long unsupervised dataset.

    Rows are drawn from four or five behavioral archetypes in proportions that
    reflect realistic habit-tracker usage (see module docstring for prior justification).
    No label column is included — K-Means is unsupervised.

    Args:
        rows:                   Total number of synthetic habit-state snapshots.
        seed:                   NumPy RNG seed for reproducibility.
        include_life_disrupted: When True (default), adds the Life-Disrupted archetype
                                at 10 % weight (K=5 trial in train_clustering_model.py).
                                When False, uses the original 4-archetype distribution.

    Returns:
        DataFrame with columns matching FEATURE_COLUMNS, shuffled.
    """
    rng = np.random.default_rng(seed)

    if include_life_disrupted:
        # K=5 priors: effortless 18%, consistent 32%, struggling 27%, dormant 13%, life_disrupted 10%.
        fractions = [0.18, 0.32, 0.27, 0.13, 0.10]
        samplers  = [
            _sample_effortless, _sample_consistent, _sample_struggling,
            _sample_dormant, _sample_life_disrupted,
        ]
    else:
        # K=4 priors: effortless 20%, consistent 35%, struggling 30%, dormant 15%.
        fractions = [0.20, 0.35, 0.30, 0.15]
        samplers  = [_sample_effortless, _sample_consistent, _sample_struggling, _sample_dormant]

    counts     = [int(rows * f) for f in fractions]
    # Any integer-rounding remainder goes to the largest archetype (consistent).
    counts[1] += rows - sum(counts)

    parts = [fn(rng, n) for fn, n in zip(samplers, counts)]
    df = pd.concat(parts, ignore_index=True)

    # Shuffle rows so archetype groups are interleaved — good practice even though
    # K-Means is order-independent (makes EDA plots more representative).
    df = df.sample(frac=1.0, random_state=seed).reset_index(drop=True)

    # Final bounds clip (guards against Normal tail artefacts above the clamp inside
    # each sampler — belt-and-suspenders so the trainer never sees out-of-range values).
    df["rate30d"]                   = df["rate30d"].clip(0.0, 1.0)
    df["routine_precision_stddev"]  = df["routine_precision_stddev"].clip(0.0, 300.0)
    df["procrastination_skew"]      = df["procrastination_skew"].clip(-3.0, 3.0)
    df["habit_age"]                 = df["habit_age"].clip(1, 365)
    df["resilience_avg_gap"]        = df["resilience_avg_gap"].clip(0.0, 15.0)
    df["voluntary_skip_rate_30d"]   = df["voluntary_skip_rate_30d"].clip(0.0, 1.0)    # R4
    df["involuntary_skip_rate_30d"] = df["involuntary_skip_rate_30d"].clip(0.0, 1.0)  # R4

    return df[FEATURE_COLUMNS]


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate clustering_dataset.csv for Phase 8.4 K-Means training."
    )
    parser.add_argument(
        "--rows", type=int, default=10_000,
        help="Number of synthetic habit-state rows to generate (default: 10 000)."
    )
    parser.add_argument(
        "--no-life-disrupted", dest="include_life_disrupted", action="store_false",
        default=True,
        help="Exclude Life-Disrupted archetype (use 4-archetype K=4 distribution)."
    )
    args = parser.parse_args()

    df = generate(rows=args.rows, include_life_disrupted=args.include_life_disrupted)
    path = output_path()
    df.to_csv(path, index=False)
    print(f"Saved {len(df):,} rows → {path}")

    # Print per-feature medians for quick sanity-check during development.
    # The authoritative training_medians used by Android are computed and
    # stored into habit_clusters.json by train_clustering_model.py.
    print("\nPer-feature medians (preview — authoritative values in habit_clusters.json):")
    for col in FEATURE_COLUMNS:
        print(f"  {col}: {df[col].median():.4f}")

    print("\nPer-feature ranges:")
    for col in FEATURE_COLUMNS:
        print(f"  {col}: [{df[col].min():.4f}, {df[col].max():.4f}]")

    print("\nRow count per archetype (approx):")
    bins = {
        "effortless_routine": (df["rate30d"] >= 0.85).sum(),
        "consistent_effort":  ((df["rate30d"] >= 0.55) & (df["rate30d"] < 0.85)).sum(),
        "struggling":         ((df["rate30d"] >= 0.15) & (df["rate30d"] < 0.55)).sum(),
        "dormant":            (df["rate30d"] < 0.15).sum(),
    }
    for name, count in bins.items():
        print(f"  {name}: ~{count:,}")


if __name__ == "__main__":
    main()
