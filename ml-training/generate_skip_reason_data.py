"""
generate_skip_reason_data.py — Synthetic dataset for Phase 9.5 (SkipReasonClassifier).

PLAN-ML-EXTENSION.md §9.5.2.

This is the **Skip Reason** multi-class classification task: given a habit's state and
the current context (time of day, day of week, etc.), predict the most likely skip reason
from the six-class enum:

    0  TOO_TIRED    — user is physically/mentally exhausted
    1  TOO_BUSY     — schedule conflict; no time slot available
    2  FORGOT       — no reminder reached the user; passive omission
    3  SICK         — illness / involuntary skip (should NOT penalize resilience)
    4  TRAVELING    — away from home context (also involuntary)
    5  NO_REASON    — dismissed the reason picker without selecting; catch-all

  ⚠ THESIS NOTE — OBSERVATIONAL CAVEAT:
  The classifier predicts the *most probable upcoming skip reason* from observational
  features, NOT a causal model.  A user who consistently skips on Friday evenings may
  do so for TOO_TIRED, TOO_BUSY, or social reasons that share common confounders.
  Present this in the thesis as "predicted skip reason given current context" and NOT
  as evidence that, e.g., habit age *causes* a user to skip for a particular reason.

Features per row (field order MUST exactly mirror SkipReasonFeatures.kt
→ toFloatArray() and skip_reason_scaler.json → feature_columns):

    1. habitAge                int     days since first completion (1..730)
    2. completionRateLast7Days float   0.0..1.0  (recent engagement)
    3. completionRateLast30Days float  0.0..1.0  (long-term engagement)
    4. currentStreak           int     0..200    (consecutive periods hit)
    5. dayOfWeek               int     1..7      (1 = Mon, 7 = Sun; ISO 8601)
    6. hourOfDay               int     0..23
    7. frequencyOrdinal        int     0=DAILY, 1=WEEKLY, 2=MONTHLY
    8. recentSkipRate14d       float   0.0..1.0  (skips / opportunities, last 14 d)

Label (int, 0–5):
    The index of the most likely SkipReason for the row.

Label generation strategy — multinomial logit per class:
    For each of the six classes we compute a raw score from the features.
    The final label is sampled from the softmax distribution (hard label via
    argmax of noisy scores, not soft label, so the CSV is integer-typed).

    Priors that drive each class:

    TOO_TIRED (0):
        Base logit −0.3 (moderate prior; second-most-common reason).
        +2.0 if hourOfDay ∈ [20, 23] or hourOfDay ∈ [0, 5]  (evening / night)
        +1.5 if dayOfWeek == 5 (Friday) or dayOfWeek == 7 (Sunday)
        +1.0 if completionRateLast7Days < 0.3  (struggling week)
        −1.5 if hourOfDay ∈ [8, 12]  (morning alert window)

    TOO_BUSY (1):
        Base logit −0.2 (most-common real-world reason after NO_REASON).
        +2.0 if dayOfWeek ∈ {1, 2, 3} and hourOfDay ∈ [9, 18]  (weekday work hours)
        +1.5 if currentStreak >= 10 and recentSkipRate14d > 0.2
              (even committed users get busy)
        +1.0 if frequencyOrdinal == 1 (WEEKLY — larger commitment per session)
        −1.0 if dayOfWeek ∈ {6, 7}  (weekend; more flexible)

    FORGOT (2):
        Base logit 0.0.
        +2.5 if habitAge < 14  (new habit, no reminder habit yet)
        +1.5 if recentSkipRate14d > 0.4  (already skipping a lot)
        +1.0 if currentStreak == 0  (no streak momentum to remember)
        +0.5 if hourOfDay ∈ [0, 7]  (reminder may have fired while asleep)
        −2.0 if currentStreak >= 14  (long streaks self-remind)
        −1.0 if completionRateLast7Days >= 0.7  (high-engagement users don't forget)

    SICK (3):
        Base logit −1.5 (relatively rare; ~5–10 % of skips).
        +0.5 unconditionally (mild base boost so class is learnable)
        Noise-driven: purely random with low base logit.  We cannot observe
        illness from behavioral features so this class mostly appears as noise.
        In the logit we still add a tiny age signal:
        +0.3 if habitAge > 180  (older users accumulate more sick days)

    TRAVELING (4):
        Base logit −1.8 (rarest class; ~3–8 % of skips).
        +1.0 if dayOfWeek ∈ {5, 6, 7}  (weekend / Friday trips)
        +0.5 if frequencyOrdinal >= 1  (weekly / monthly easier to skip while away)
        +0.3 if habitAge > 90  (established habit; skip is contextual, not decay)

    NO_REASON (5):
        Base logit 0.2 (catch-all; should win when all behavioral signals are weak).
        +1.5 if completionRateLast30Days is mid-range [0.35, 0.65]
              (neither committed nor disengaged — ambiguous motivation)
        +1.0 if recentSkipRate14d ∈ [0.1, 0.4]
              (mild skipping without a clear driver)
        −1.5 if hourOfDay ∈ [20, 23]  (evening has strong TOO_TIRED signal)
        −1.0 if habitAge < 14  (new habits lean FORGOT)

    After computing all six logits, Gumbel noise is added (temperature 1.2) before
    argmax to ensure each class appears in the dataset and to prevent deterministic
    mono-class runs.

Positive-rate target per class (approximate, over 50 k rows):
    TOO_TIRED   ≈ 23%
    TOO_BUSY    ≈ 25%
    FORGOT      ≈ 20%
    SICK        ≈  8%
    TRAVELING   ≈  6%
    NO_REASON   ≈ 18%

    (Mild class imbalance is intentional; class_weight in the trainer handles it.)

Usage:
    python generate_skip_reason_data.py
    python generate_skip_reason_data.py --rows 60000

Output:
    ml-training/data/skip_reason_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

SEED = 42

# Field order mirrors SkipReasonFeatures.kt → toFloatArray() and
# skip_reason_scaler.json → feature_columns.  DO NOT reorder.
FEATURE_COLUMNS: list[str] = [
    "habitAge",
    "completionRateLast7Days",
    "completionRateLast30Days",
    "currentStreak",
    "dayOfWeek",
    "hourOfDay",
    "frequencyOrdinal",
    "recentSkipRate14d",
]

# Integer label → SkipReason enum ordinal (matches Kotlin enum declaration order)
CLASS_LABELS: list[str] = [
    "TOO_TIRED",
    "TOO_BUSY",
    "FORGOT",
    "SICK",
    "TRAVELING",
    "NO_REASON",
]
N_CLASSES = len(CLASS_LABELS)


def generate(rows: int = 50_000, seed: int = SEED) -> pd.DataFrame:
    """Build a ``rows``-long DataFrame following the §9.5.2 generative model.

    The label distribution is intentionally imbalanced (SICK / TRAVELING are rare)
    to mirror the real-world skip distribution in a habit-tracker population.
    The trainer must use class_weight='balanced' to compensate.
    """
    rng = np.random.default_rng(seed)

    # ── Feature distributions ──────────────────────────────────────────────

    # habitAge: exponential tail; most habits are young
    habit_age = np.clip(
        rng.exponential(scale=70.0, size=rows) + 1, 1, 730
    ).astype(np.int16)

    # Shared latent engagement level ∈ (0, 1)
    engagement = rng.beta(a=2.5, b=1.5, size=rows).astype(np.float64)

    rate_7d = np.clip(
        engagement + rng.normal(0.0, 0.12, size=rows), 0.0, 1.0
    ).astype(np.float32)
    rate_30d = np.clip(
        engagement + rng.normal(0.0, 0.08, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # currentStreak: most users have short streaks
    current_streak = np.clip(
        rng.exponential(scale=9.0, size=rows), 0, 200
    ).astype(np.int16)

    # dayOfWeek: uniform over 1–7 (ISO 8601: 1=Mon)
    day_of_week = rng.integers(1, 8, size=rows).astype(np.int8)

    # hourOfDay: bimodal — morning peak (7–9) and evening peak (20–22)
    # Modeled as mixture of two normals mapped to [0, 23]
    is_evening = rng.random(size=rows) < 0.55  # 55 % evening skips
    hour_morning = np.clip(rng.normal(loc=8.0, scale=2.5, size=rows), 0, 23)
    hour_evening = np.clip(rng.normal(loc=21.0, scale=2.0, size=rows), 0, 23)
    hour_of_day = np.where(is_evening, hour_evening, hour_morning).astype(np.int8)

    # frequencyOrdinal: 70% DAILY, 20% WEEKLY, 10% MONTHLY
    frequency_ordinal = rng.choice(
        [0, 1, 2], size=rows, p=[0.70, 0.20, 0.10]
    ).astype(np.int8)

    # recentSkipRate14d: inversely correlated with engagement; most users rarely skip
    skip_rate_14d = np.clip(
        (1.0 - engagement) * 0.6 + rng.exponential(0.08, size=rows), 0.0, 1.0
    ).astype(np.float32)

    # ── Per-class logit computation ────────────────────────────────────────

    # Shape: (rows, 6) — one logit column per SkipReason class
    logits = np.zeros((rows, N_CLASSES), dtype=np.float64)

    # ── TOO_TIRED (class 0) ────────────────────────────────────────────────
    logits[:, 0] -= 0.3  # base prior
    logits[:, 0] += np.where(
        (hour_of_day >= 20) | (hour_of_day <= 5), 2.0, 0.0
    )
    logits[:, 0] += np.where(
        (day_of_week == 5) | (day_of_week == 7), 1.5, 0.0
    )
    logits[:, 0] += np.where(rate_7d < 0.30, 1.0, 0.0)
    logits[:, 0] += np.where(
        (hour_of_day >= 8) & (hour_of_day <= 12), -1.5, 0.0
    )

    # ── TOO_BUSY (class 1) ─────────────────────────────────────────────────
    logits[:, 1] -= 0.2
    logits[:, 1] += np.where(
        ((day_of_week >= 1) & (day_of_week <= 3)) &
        ((hour_of_day >= 9) & (hour_of_day <= 18)), 2.0, 0.0
    )
    logits[:, 1] += np.where(
        (current_streak >= 10) & (skip_rate_14d > 0.2), 1.5, 0.0
    )
    logits[:, 1] += np.where(frequency_ordinal == 1, 1.0, 0.0)
    logits[:, 1] += np.where((day_of_week == 6) | (day_of_week == 7), -1.0, 0.0)

    # ── FORGOT (class 2) ───────────────────────────────────────────────────
    logits[:, 2] += 0.0  # neutral base
    logits[:, 2] += np.where(habit_age < 14, 2.5, 0.0)
    logits[:, 2] += np.where(skip_rate_14d > 0.40, 1.5, 0.0)
    logits[:, 2] += np.where(current_streak == 0, 1.0, 0.0)
    logits[:, 2] += np.where(
        (hour_of_day >= 0) & (hour_of_day <= 7), 0.5, 0.0
    )
    logits[:, 2] += np.where(current_streak >= 14, -2.0, 0.0)
    logits[:, 2] += np.where(rate_7d >= 0.70, -1.0, 0.0)

    # ── SICK (class 3) ────────────────────────────────────────────────────
    # Illness is not predictable from behavioral features; keep a low base
    # logit so the class remains learnable but is genuinely rare.
    logits[:, 3] -= 1.5
    logits[:, 3] += 0.5  # small constant lift
    logits[:, 3] += np.where(habit_age > 180, 0.3, 0.0)

    # ── TRAVELING (class 4) ───────────────────────────────────────────────
    logits[:, 4] -= 1.8
    logits[:, 4] += np.where(
        (day_of_week >= 5) & (day_of_week <= 7), 1.0, 0.0
    )
    logits[:, 4] += np.where(frequency_ordinal >= 1, 0.5, 0.0)
    logits[:, 4] += np.where(habit_age > 90, 0.3, 0.0)

    # ── NO_REASON (class 5) ───────────────────────────────────────────────
    logits[:, 5] += 0.2
    logits[:, 5] += np.where(
        (rate_30d >= 0.35) & (rate_30d <= 0.65), 1.5, 0.0
    )
    logits[:, 5] += np.where(
        (skip_rate_14d >= 0.10) & (skip_rate_14d <= 0.40), 1.0, 0.0
    )
    logits[:, 5] += np.where(
        (hour_of_day >= 20) & (hour_of_day <= 23), -1.5, 0.0
    )
    logits[:, 5] += np.where(habit_age < 14, -1.0, 0.0)

    # ── Add Gumbel noise and take argmax (hard labels) ────────────────────
    # Temperature 1.2 broadens the distribution slightly so rare classes
    # (SICK, TRAVELING) still appear regularly in the training set.
    gumbel = rng.gumbel(loc=0.0, scale=1.2, size=(rows, N_CLASSES))
    label = np.argmax(logits + gumbel, axis=1).astype(np.int8)

    df = pd.DataFrame(
        {
            "habitAge": habit_age.astype(np.int16),
            "completionRateLast7Days": rate_7d.astype(np.float32),
            "completionRateLast30Days": rate_30d.astype(np.float32),
            "currentStreak": current_streak.astype(np.int16),
            "dayOfWeek": day_of_week.astype(np.int8),
            "hourOfDay": hour_of_day.astype(np.int8),
            "frequencyOrdinal": frequency_ordinal.astype(np.int8),
            "recentSkipRate14d": skip_rate_14d.astype(np.float32),
            "label": label,
        }
    )

    return df


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate synthetic skip-reason dataset for Phase 9.5."
    )
    parser.add_argument(
        "--rows",
        type=int,
        default=50_000,
        help="Number of synthetic rows to generate (default: 50000).",
    )
    args = parser.parse_args()

    out_dir = Path(__file__).parent / "data"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / "skip_reason_dataset.csv"

    df = generate(rows=args.rows)

    df.to_csv(out_path, index=False)
    print(f"Saved {len(df):,} rows → {out_path}")

    # Quick sanity: print per-class distribution
    counts = df["label"].value_counts().sort_index()
    print("\nClass distribution:")
    for idx, count in counts.items():
        pct = count / len(df) * 100
        print(f"  {idx} {CLASS_LABELS[int(idx)]:<12} {count:6,}  ({pct:.1f}%)")


if __name__ == "__main__":
    main()
