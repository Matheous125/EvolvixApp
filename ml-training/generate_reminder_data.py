"""
generate_reminder_data.py — Synthetic dataset for Model 3
(`ReminderTemplateClassifier`).

Phase 6.5.4 of PLAN.md. Multi-class classification over 15 reminder template
categories used by the on-device notification engine (Phase 7) and the
in-app `MotivationMessageUseCase`.

Features per row (PLAN.md §6.5.4 + R3 retrain 2026-05-26):
    currentStreak              0..200
    completionRateLast7Days    0.0..1.0
    daysSinceLastCompletion    0..30
    dayOfWeek                  1..7   (1 = Monday, 7 = Sunday)
    hourOfDay                  0..23
    abandonmentProbability     0.0..1.0  (R3: replaces binary isAtRisk with continuous score)
    targetReachedToday         0/1

Label space (15 templates, in this fixed index order — Android side maps
argmax → key identically):
    0  cheer_streak_milestone
    1  gentle_nudge_at_risk
    2  celebrate_consistency
    3  recovery_encouragement
    4  morning_optimistic
    5  evening_reflection
    6  comeback_after_break
    7  weekend_warrior
    8  first_week_support
    9  cold_start
    10 streak_save
    11 target_smashed
    12 category_balance
    13 pace_yourself
    14 quiet_encouragement   (default / fallback)

Label generation strategy:
    Apply a *priority cascade* of behavioral rules. Each row falls into the
    first rule it satisfies (this gives the model a deterministic teacher
    signal across 15 classes that would otherwise be statistically too
    sparse). After the deterministic label is chosen, **10% noise** is
    injected by reassigning the label to a uniformly random class — this
    matches the PLAN.md §6.5.4 requirement and prevents the network from
    memorising the rules verbatim, forcing it to learn the underlying
    distribution.

Usage:
    python generate_reminder_data.py
    python generate_reminder_data.py --rows 20000      # capacity-retry path

Output:
    ml-training/data/reminder_dataset.csv
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

# Reproducibility — thesis runs must be deterministic.
SEED = 42

# Column order is part of the model contract: training, scaler JSON, and the
# Android `ReminderContext.toFloatArray()` MUST iterate in this exact order.
FEATURE_COLUMNS: list[str] = [
    "currentStreak",
    "completionRateLast7Days",
    "daysSinceLastCompletion",
    "dayOfWeek",
    "hourOfDay",
    "abandonmentProbability",  # R3: continuous [0,1] from AbandonmentRiskUseCase; replaces binary isAtRisk
    "targetReachedToday",
    "snoozeCountToday",   # R1: number of snoozes today before completion/skip
]

# Fixed label order — Android reads `argmax` and maps to this list. Editing
# this order silently breaks inference; append-only changes are safe.
LABEL_NAMES: list[str] = [
    "cheer_streak_milestone",   # 0
    "gentle_nudge_at_risk",     # 1
    "celebrate_consistency",    # 2
    "recovery_encouragement",   # 3
    "morning_optimistic",       # 4
    "evening_reflection",       # 5
    "comeback_after_break",     # 6
    "weekend_warrior",          # 7
    "first_week_support",       # 8
    "cold_start",               # 9
    "streak_save",              # 10
    "target_smashed",           # 11
    "category_balance",         # 12
    "pace_yourself",            # 13
    "quiet_encouragement",      # 14 (fallback)
]
N_CLASSES = len(LABEL_NAMES)

# Noise rate per PLAN.md §6.5.4 ("rule-based label assignment + 10% noise").
NOISE_RATE = 0.10


def _assign_labels(
    current_streak: np.ndarray,
    completion_rate: np.ndarray,
    days_since_last: np.ndarray,
    day_of_week: np.ndarray,
    hour_of_day: np.ndarray,
    abandonment_prob: np.ndarray,  # R3: continuous [0,1] replaces binary is_at_risk
    target_reached: np.ndarray,
    snooze_count_today: np.ndarray,  # R1: new 8th feature
) -> np.ndarray:
    """Vectorised priority-cascade rule engine.

    Each row receives the index of the first rule it matches. Rules are
    ordered from "most specific / highest user value" downward so that, for
    example, a streak-milestone day on a weekend is celebrated as the
    milestone rather than as a generic weekend nudge.
    """
    rows = current_streak.shape[0]
    # Pre-fill every row with the fallback label so any row that fails
    # every rule still gets a valid teacher signal.
    labels = np.full(rows, LABEL_NAMES.index("quiet_encouragement"), dtype=np.int8)
    # Track which rows still need a label. We never overwrite once assigned.
    unassigned = np.ones(rows, dtype=bool)

    def assign(mask: np.ndarray, label_name: str) -> None:
        """Apply `label_name` to all currently-unassigned rows matching `mask`."""
        effective = mask & unassigned
        labels[effective] = LABEL_NAMES.index(label_name)
        unassigned[effective] = False

    # Rule 0 — Heavy snoozer: user has already snoozed this reminder twice or
    # more today. Gentleness wins over celebration/urgency — mirrors the Kotlin
    # MathHabitPredictor R1 math fallback rule exactly.
    assign(snooze_count_today >= 2, "gentle_nudge_at_risk")

    # Rule 1 — Target smashed: user already crossed the day's goal AND has
    # been consistent. Highest-value positive moment to celebrate.
    assign(
        (target_reached == 1) & (completion_rate >= 0.70),
        "target_smashed",
    )

    # Rule 2 — Streak save: streak is non-trivial and abandonment risk is high.
    # R3: smooth threshold replaces binary is_at_risk check.
    assign(
        (abandonment_prob >= 0.6) & (current_streak >= 5),
        "streak_save",
    )

    # Rule 3 — Streak milestone day: classic engagement moment at 7 / 14 /
    # 21 / 30 / 50 / 100 days. Checked AFTER streak_save so an at-risk
    # milestone day prompts a save rather than a celebration.
    milestone_mask = np.isin(current_streak, [7, 14, 21, 30, 50, 100])
    assign(milestone_mask, "cheer_streak_milestone")

    # Rule 4 — Comeback after a long break: streak collapsed to 0 but the
    # user is opening the app again. Encouragement, not pressure.
    assign(
        (current_streak == 0) & (days_since_last >= 3),
        "comeback_after_break",
    )

    # Rule 5 — Recovery encouragement: still has a streak but missed
    # recent days. Softer than streak_save.
    assign(
        (current_streak > 0) & (days_since_last >= 3),
        "recovery_encouragement",
    )

    # Rule 6 — Gentle nudge: moderate abandonment risk and streak is small.
    # R3: threshold 0.4 (lower than streak_save) gives smooth gradient for model stacking.
    assign(
        (abandonment_prob >= 0.4) & (current_streak < 5),
        "gentle_nudge_at_risk",
    )

    # Rule 7 — Pace yourself: long streak + very high completion rate.
    # Prevents burnout — a documented retention risk in habit literature.
    assign(
        (current_streak >= 30) & (completion_rate >= 0.90),
        "pace_yourself",
    )

    # Rule 8 — Celebrate consistency: solid streak + high recent rate, but
    # not extreme enough to warrant the pace-yourself warning.
    assign(
        (current_streak >= 3) & (completion_rate >= 0.85),
        "celebrate_consistency",
    )

    # Rule 9 — First-week support: user is building a brand-new streak.
    assign(
        (current_streak >= 1) & (current_streak <= 7) & (completion_rate >= 0.50),
        "first_week_support",
    )

    # Rule 10 — Cold start: low streak AND low completion rate. The user is
    # still struggling to establish the habit.
    assign(
        (current_streak <= 1) & (completion_rate < 0.30),
        "cold_start",
    )

    # Rule 11 — Weekend warrior: it's Saturday/Sunday and the user is
    # engaging — context-aware framing without overriding more specific
    # rules above.
    assign(
        np.isin(day_of_week, [6, 7]),
        "weekend_warrior",
    )

    # Rule 12 — Morning optimistic: time-of-day rule (5–10 AM).
    assign(
        (hour_of_day >= 5) & (hour_of_day <= 10),
        "morning_optimistic",
    )

    # Rule 13 — Evening reflection: late-day reminders skew reflective.
    assign(
        (hour_of_day >= 19) & (hour_of_day <= 23),
        "evening_reflection",
    )

    # Rule 14 — Category balance: middling completion rate with a small
    # streak — encourages spreading effort across habits.
    assign(
        (completion_rate >= 0.40) & (completion_rate < 0.70) & (current_streak < 5),
        "category_balance",
    )

    # Any row still unassigned keeps the pre-filled "quiet_encouragement".
    return labels


def generate(rows: int = 10_000, seed: int = SEED) -> pd.DataFrame:
    """Build a `rows`-long DataFrame following the §6.5.4 generative model."""
    rng = np.random.default_rng(seed)

    # Skewed distributions so the rule cascade splits roughly evenly across
    # classes — uniform draws would leave the rare classes (milestones,
    # cold_start) starved.
    current_streak = np.clip(
        rng.exponential(scale=10.0, size=rows), 0, 200
    ).astype(np.int16)
    completion_rate = rng.uniform(0.0, 1.0, size=rows).astype(np.float32)
    days_since_last = np.clip(
        rng.exponential(scale=2.0, size=rows), 0, 30
    ).astype(np.int16)
    day_of_week = rng.integers(1, 8, size=rows).astype(np.int16)
    hour_of_day = rng.integers(0, 24, size=rows).astype(np.int16)

    # R3 (A): `abandonmentProbability` is a continuous [0,1] score produced by
    # AbandonmentRiskUseCase on-device (Model 8.1 output). Here we synthesise a
    # realistic distribution with FOUR weighted components so the feature is not
    # a pure linear function of the other features already in the feature set:
    #   - gap signal     (days_since_last) — shared with explicit feature
    #   - rate signal    (completion_rate) — shared with explicit feature
    #   - age signal     (simulated habit_age) — NOT in Model 3's feature set,
    #                    breaks collinearity: younger habits abandon faster
    #   - streak signal  (current_streak) — shared, but weighted low
    # The independent age component prevents the network from treating
    # abandonmentProbability as a redundant re-encoding of existing features.
    simulated_habit_age_days = np.clip(
        rng.exponential(scale=60.0, size=rows), 1, 730
    ).astype(np.float32)
    abandonment_prob = np.clip(
        0.25 * (days_since_last / 7.0).astype(np.float32)
        + 0.30 * (1.0 - completion_rate).astype(np.float32)
        + 0.20 * (1.0 - np.clip(simulated_habit_age_days / 200.0, 0.0, 1.0))
        + 0.10 * (1.0 - np.clip(current_streak.astype(np.float32) / 30.0, 0.0, 1.0))
        + rng.normal(0.0, 0.08, size=rows).astype(np.float32),
        0.0,
        1.0,
    ).astype(np.float32)

    # `targetReachedToday` — modest base rate (~25%) so the
    # `target_smashed` rule still has signal but doesn't dominate.
    target_reached = (rng.uniform(0.0, 1.0, size=rows) < 0.25).astype(np.int8)

    # R1 — `snoozeCountToday`: skewed exponential so most rows = 0, ~10 % ≥ 2.
    # scale=0.6 gives mean≈0.6 which matches real-world snooze behaviour where
    # the median user never snoozes on a given day.
    snooze_count_today = np.clip(
        rng.exponential(scale=0.6, size=rows), 0, 8
    ).astype(np.int16)

    # Boost milestone-day frequency so the model sees enough of class 0.
    # Otherwise milestones (exact integer matches in {7,14,21,30,50,100})
    # appear in <1% of rows and the network ignores them.
    milestone_boost_mask = rng.uniform(0.0, 1.0, size=rows) < 0.05
    milestone_values = rng.choice([7, 14, 21, 30, 50, 100], size=rows)
    current_streak = np.where(
        milestone_boost_mask, milestone_values, current_streak
    ).astype(np.int16)

    labels = _assign_labels(
        current_streak=current_streak,
        completion_rate=completion_rate,
        days_since_last=days_since_last,
        day_of_week=day_of_week,
        hour_of_day=hour_of_day,
        abandonment_prob=abandonment_prob,  # R3: continuous score
        target_reached=target_reached,
        snooze_count_today=snooze_count_today,
    )

    # Inject 10% noise (PLAN.md §6.5.4): pick rows uniformly and reassign
    # them to a uniformly random class. This puts an upper bound (~90%) on
    # achievable accuracy and forces the network to generalise instead of
    # memorising the deterministic rule table.
    noise_mask = rng.uniform(0.0, 1.0, size=rows) < NOISE_RATE
    noisy_labels = rng.integers(0, N_CLASSES, size=rows).astype(np.int8)
    labels = np.where(noise_mask, noisy_labels, labels)

    return pd.DataFrame(
        {
            "currentStreak": current_streak,
            "completionRateLast7Days": completion_rate,
            "daysSinceLastCompletion": days_since_last,
            "dayOfWeek": day_of_week,
            "hourOfDay": hour_of_day,
            "abandonmentProbability": abandonment_prob,  # R3: continuous [0,1] replaces isAtRisk
            "targetReachedToday": target_reached,
            "snoozeCountToday": snooze_count_today,  # R1
            "label": labels.astype(np.int8),
        }
    )


def output_path() -> Path:
    """Resolve `ml-training/data/reminder_dataset.csv` relative to this file."""
    here = Path(__file__).resolve().parent
    out = here / "data" / "reminder_dataset.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rows",
        type=int,
        default=10_000,
        help="Number of synthetic rows to generate (default: 10000).",
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

    print(f"Wrote {len(df):,} rows to {out}")
    print("Class distribution:")
    counts = df["label"].value_counts().sort_index()
    for idx, count in counts.items():
        name = LABEL_NAMES[int(idx)]
        print(f"  {int(idx):>2}  {name:<24} {count:>6}  ({count / len(df):.3f})")


if __name__ == "__main__":
    main()

