"""
generate_engagement_window_data.py — Synthetic dataset for Phase 9.6 (EngagementWindowPredictor).

PLAN-ML-EXTENSION.md §9.6.2.

This is the **Engagement Window** regression task: given a user's recent app-session
statistics, predict the hour-of-day (0.0 … 23.99) at which they are most likely to
open the app next.  The output is a continuous regression target, not a classification
label, so the task is solved with an MLP + MAE loss (same template as
generate_weekly_forecast_data.py and generate_spillover_data.py).

  ⚠ THESIS NOTE — OBSERVATIONAL CAVEAT:
  This model predicts *when the user normally opens the app*, NOT when they would
  respond optimally to a push notification.  The two are correlated but not identical
  — a user who opens the app at 07:00 for a quick check may not engage deeply with a
  reminder at that time.  The model is therefore framed in the thesis as a
  "predicted engagement-window estimator" and NOT as a causal recommendation engine.
  The ScheduleReminderUseCase integration is gated behind a confidence threshold
  (≥ 0.6) and a data-sufficiency guard (≥ 14 sessions) precisely to prevent
  low-confidence predictions from overriding the user-set reminder time.

Three behavioral archetypes are baked into the generative model:
  A. MORNING  (~40 % of synthetic users) — primary open window 05:00–09:00.
  B. EVENING  (~40 % of synthetic users) — primary open window 18:00–23:00.
  C. BIMODAL  (~20 % of synthetic users) — two windows: 07:00–09:00 and 20:00–22:00;
              the label is the *next* open, which alternates between the two peaks
              with mild random noise.

Features per row (field order MUST exactly mirror EngagementWindowFeatures.kt
→ toFloatArray() and engagement_window_scaler.json → feature_columns):

    1. dayOfWeek                  int     0=Mon … 6=Sun
    2. isWeekend                  int     0 (Mon-Fri) or 1 (Sat-Sun)
    3. recentAvgStartHour14d      float   mean session-start hour over last 14 days
                                          (0.0 … 23.99)
    4. stddevStartHour14d         float   stddev of session-start hours (0.0 … 12.0)
    5. sessionCountLast7d         int     how many sessions were recorded (0 … 30)
    6. avgSessionLengthMin        float   average session duration in minutes (0.5 … 60.0)
    7. daysSinceFirstSession      int     days since the user's first recorded session
                                          (1 … 365)
    8. prevSessionStartHour       float   start hour of the most recent session
                                          (0.0 … 23.99); 12.0 when no previous session

Label:
    next_session_hour  float   0.0 … 23.99 (the start-hour of the user's NEXT session)

Label generation strategy:
  For each archetype, a "typical open hour" is drawn once per user-context:
    MORNING : mu ~ Normal(7.0, 1.0), std ~ Uniform(0.5, 1.5) → next hour ~ N(mu, std)
    EVENING : mu ~ Normal(20.5, 1.5), std ~ Uniform(0.5, 2.0) → next hour ~ N(mu, std)
    BIMODAL : alternate between mu_morning ~ N(8.0, 1.0) and mu_evening ~ N(21.0, 1.0),
              selecting randomly (p_morning=0.5) per row; std ~ Uniform(0.5, 1.0)
  Weekend shifts: all archetypes shift their mu by +1.0 h on Sat/Sun (lie-in effect).
  All labels are clipped to [0.0, 23.99].

Positive rate target: N/A (regression). Expected MAE ≤ 1.5 hours on test split.

Usage:
    python generate_engagement_window_data.py
    python generate_engagement_window_data.py --rows 50000

Output:
    ml-training/data/engagement_window_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors EngagementWindowFeatures.kt → toFloatArray() and
# engagement_window_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "dayOfWeek",
    "isWeekend",
    "recentAvgStartHour14d",
    "stddevStartHour14d",
    "sessionCountLast7d",
    "avgSessionLengthMin",
    "daysSinceFirstSession",
    "prevSessionStartHour",
]

LABEL_COLUMN = "next_session_hour"

OUT_DIR = Path(__file__).parent / "data"
OUT_FILE = OUT_DIR / "engagement_window_dataset.csv"


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a ``rows``-long DataFrame following the §9.6.2 generative model.

    Three behavioral archetypes (MORNING / EVENING / BIMODAL) produce
    distinct chronotype profiles so the model learns meaningful structure
    rather than memorizing noise.
    """
    rng = np.random.default_rng(seed)

    # ── Archetype assignment ───────────────────────────────────────────────
    # MORNING=0 (40 %), EVENING=1 (40 %), BIMODAL=2 (20 %)
    archetype = rng.choice([0, 1, 2], size=rows, p=[0.40, 0.40, 0.20]).astype(np.int8)

    # ── Day-level context features ─────────────────────────────────────────
    day_of_week = rng.integers(0, 7, size=rows).astype(np.int8)   # 0=Mon … 6=Sun
    is_weekend = (day_of_week >= 5).astype(np.int8)
    weekend_shift = is_weekend.astype(np.float32) * 1.0           # lie-in effect

    # ── Per-archetype "typical open hour" for the next session ────────────
    # MORNING: centered around 07:00 ± 1.0 h personal variation
    mu_morning = np.clip(
        rng.normal(7.0, 1.0, size=rows) + weekend_shift, 4.0, 12.0
    ).astype(np.float32)

    # EVENING: centered around 20:30 ± 1.5 h
    mu_evening = np.clip(
        rng.normal(20.5, 1.5, size=rows) + weekend_shift, 16.0, 23.5
    ).astype(np.float32)

    # BIMODAL: randomly picks one of two peaks per row
    bimodal_pick = rng.random(size=rows) < 0.5   # True → morning peak
    mu_bimodal = np.where(
        bimodal_pick,
        np.clip(rng.normal(8.0, 1.0, size=rows) + weekend_shift, 5.0, 11.0),
        np.clip(rng.normal(21.0, 1.0, size=rows) + weekend_shift, 18.0, 23.5),
    ).astype(np.float32)

    # Effective mu and within-session stddev per archetype
    mu = np.select(
        [archetype == 0, archetype == 1, archetype == 2],
        [mu_morning, mu_evening, mu_bimodal],
    ).astype(np.float32)

    within_std = np.select(
        [archetype == 0, archetype == 1, archetype == 2],
        [
            rng.uniform(0.5, 1.5, size=rows),   # MORNING — tight window
            rng.uniform(0.5, 2.0, size=rows),   # EVENING — wider spread
            rng.uniform(0.5, 1.0, size=rows),   # BIMODAL — tight peaks
        ],
    ).astype(np.float32)

    # Label: next session start hour with individual noise
    next_session_hour = np.clip(
        mu + rng.normal(0.0, within_std, size=rows), 0.0, 23.99
    ).astype(np.float32)

    # ── Feature: recentAvgStartHour14d ────────────────────────────────────
    # Approximation: the 14-day average is close to mu but with extra noise
    # (multiple days, not all exactly the same).
    recent_avg_14d = np.clip(
        mu + rng.normal(0.0, 0.8, size=rows), 0.0, 23.99
    ).astype(np.float32)

    # ── Feature: stddevStartHour14d ───────────────────────────────────────
    # Derived from within_std with additive noise; wider for EVENING / BIMODAL.
    stddev_14d = np.clip(
        within_std + rng.exponential(0.3, size=rows), 0.0, 12.0
    ).astype(np.float32)

    # ── Feature: sessionCountLast7d ───────────────────────────────────────
    # Most users open the app at least once per day; exponential-ish tail.
    session_count_7d = np.clip(
        rng.poisson(lam=5.0, size=rows), 0, 30
    ).astype(np.int16)

    # ── Feature: avgSessionLengthMin ──────────────────────────────────────
    # Log-normal: most sessions are 2–10 min; occasional 30-min deep dives.
    avg_session_len = np.clip(
        np.exp(rng.normal(1.8, 0.6, size=rows)), 0.5, 60.0
    ).astype(np.float32)

    # ── Feature: daysSinceFirstSession ────────────────────────────────────
    days_since_first = np.clip(
        rng.exponential(scale=60.0, size=rows) + 1, 1, 365
    ).astype(np.int16)

    # ── Feature: prevSessionStartHour ─────────────────────────────────────
    # Very close to mu (the user's own chronotype persists day-to-day).
    # 5 % of rows get a "no previous session" default of 12.0.
    prev_session_hour = np.clip(
        mu + rng.normal(0.0, 1.0, size=rows), 0.0, 23.99
    ).astype(np.float32)
    no_prev = rng.random(size=rows) < 0.05
    prev_session_hour = np.where(no_prev, 12.0, prev_session_hour).astype(np.float32)

    # ── Assemble DataFrame ────────────────────────────────────────────────
    df = pd.DataFrame({
        "dayOfWeek":            day_of_week,
        "isWeekend":            is_weekend,
        "recentAvgStartHour14d": recent_avg_14d,
        "stddevStartHour14d":   stddev_14d,
        "sessionCountLast7d":   session_count_7d,
        "avgSessionLengthMin":  avg_session_len,
        "daysSinceFirstSession": days_since_first,
        "prevSessionStartHour": prev_session_hour,
        LABEL_COLUMN:           next_session_hour,
    })

    # Sanity-check column order
    assert list(df.columns) == FEATURE_COLUMNS + [LABEL_COLUMN], (
        "Column order mismatch — update FEATURE_COLUMNS or the DataFrame constructor"
    )
    return df


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate engagement-window dataset")
    parser.add_argument("--rows", type=int, default=50_000)
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    df = generate(rows=args.rows)
    df.to_csv(OUT_FILE, index=False)

    label = df[LABEL_COLUMN]
    print(f"Generated {len(df):,} rows → {OUT_FILE}")
    print(f"Label  mean={label.mean():.2f}h  std={label.std():.2f}h  "
          f"min={label.min():.2f}h  max={label.max():.2f}h")


if __name__ == "__main__":
    main()
