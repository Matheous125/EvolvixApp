"""
train_clustering_model.py — Train + export Phase 8.4 (Habit Behavioral Clustering).

PLAN-ML-EXTENSION.md §8.4.1.

Unlike all previous models, this is **K-Means (sklearn, unsupervised)** — NOT TensorFlow Lite.
No `.tflite` file is produced. The sole output is `habit_clusters.json`, which contains
everything the Android app needs to classify a habit's behavioral tier at runtime:

    feature_columns     — ordered list of 5 feature names (mirrors ClusterFeatures.kt)
    feature_means       — StandardScaler mean per feature (for on-device standardization)
    feature_scales      — StandardScaler scale per feature (for on-device standardization)
    centroids           — 4 × 5 float matrix in STANDARDIZED space; Android computes
                          Euclidean distance to each row and takes the argmin
    labels              — 4 semantic strings, indexed by centroid row (= K-Means cluster index):
                          "effortless_routine" | "consistent_effort" | "struggling" | "dormant"
    training_medians    — per-feature medians of the raw dataset; Android substitutes
                          training_medians[i] when feature i cannot be computed (null analytic)
    silhouette_score    — float; persisted for evaluate_models.py and thesis reporting

Pipeline:

    1. Load (or auto-generate) ml-training/data/clustering_dataset.csv.
    2. Fit StandardScaler on ALL rows.
       (Unsupervised learning — no train/test label split. Silhouette is computed
        on the full scaled set, which is standard practice for K-Means evaluation.)
    3. Fit KMeans(n_clusters=4, n_init=20, random_state=42).
    4. Validate: silhouette_score >= 0.35 (PLAN-ML-EXTENSION.md §8.4.1).
       If not met, auto-retry once with RETRY_ROWS (15 000).
    5. Assign semantic labels by ranking per-cluster mean rate30d in descending order:
           rank 0 (highest mean rate30d) → "effortless_routine"
           rank 1                        → "consistent_effort"
           rank 2                        → "struggling"
           rank 3 (lowest  mean rate30d) → "dormant"
       This mapping is deterministic regardless of K-Means centroid initialization order.
    6. Persist habit_clusters.json to ml-training/models/.
    7. Print per-cluster statistics for thesis documentation.

Outputs:
    ml-training/models/habit_clusters.json   (NO .tflite — K-Means is JSON + Kotlin math)

Usage:
    python train_clustering_model.py
"""

from __future__ import annotations

import json
import random
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler

import generate_clustering_data as gen

# ---------------------------------------------------------------------------
# Reproducibility
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance threshold (PLAN-ML-EXTENSION.md §8.4.1)
# ---------------------------------------------------------------------------
MIN_SILHOUETTE = 0.35
INITIAL_ROWS   = 10_000
RETRY_ROWS     = 15_000

# Semantic label assigned by rate30d ranking: rank 0 = best performer → effortless.
RANKED_LABELS: list[str] = [
    "effortless_routine",
    "consistent_effort",
    "struggling",
    "dormant",
]

# R4 addition: K=5 label set — "life_disrupted" sits at rank 2 (intermediate rate30d
# 0.30–0.70, distinguishable from Struggling via high involuntary_skip_rate_30d).
RANKED_LABELS_K5: list[str] = [
    "effortless_routine",
    "consistent_effort",
    "life_disrupted",
    "struggling",
    "dormant",
]


def _models_dir() -> Path:
    here = Path(__file__).resolve().parent
    out = here / "models"
    out.mkdir(parents=True, exist_ok=True)
    return out


def _load_or_generate(rows: int) -> pd.DataFrame:
    """Use an existing CSV if it has at least `rows` rows; otherwise regenerate."""
    csv_path = gen.output_path()
    if csv_path.exists():
        df = pd.read_csv(csv_path)
        if len(df) >= rows:
            return df
        print(f"Existing dataset has {len(df):,} rows; need {rows:,}. Regenerating.")
    print(f"Generating {rows:,}-row dataset...")
    df = gen.generate(rows=rows, seed=SEED)
    df.to_csv(csv_path, index=False)
    return df


def _fit(df: pd.DataFrame, n_clusters: int = 4) -> tuple[KMeans, StandardScaler, float, np.ndarray]:
    """Standardize → K-Means fit → silhouette evaluation.

    Args:
        df:         Raw feature DataFrame with columns matching FEATURE_COLUMNS.
        n_clusters: Number of K-Means clusters to fit (4 = original; 5 = R4 trial).

    Returns:
        (kmeans, scaler, silhouette, cluster_assignment_per_row)
    """
    X_raw = df[gen.FEATURE_COLUMNS].to_numpy(dtype=np.float32)

    # Fit scaler on the full dataset — no train/test split for unsupervised learning.
    # StandardScaler is Euclidean-sensitive: without it, features in large units
    # (routine_precision_stddev in minutes, habit_age in days) would dominate the
    # distance metric and mask smaller-scaled features like rate30d.
    scaler = StandardScaler().fit(X_raw)
    X_scaled = scaler.transform(X_raw).astype(np.float32)

    # n_init=20 restarts reduce sensitivity to centroid initialization (Lloyd's
    # algorithm is not globally optimal). random_state=SEED ensures reproducibility.
    kmeans = KMeans(n_clusters=n_clusters, n_init=20, random_state=SEED)
    labels_per_row = kmeans.fit_predict(X_scaled)

    # Silhouette score ∈ [-1, 1]: measures how much better a point fits its own
    # cluster vs. the nearest neighbour cluster. Score ≥ 0.35 indicates reasonable
    # separation for 4 clusters on 5 features (thesis acceptance gate).
    sil = float(silhouette_score(X_scaled, labels_per_row, random_state=SEED))

    return kmeans, scaler, sil, labels_per_row


def _assign_labels(
    kmeans: KMeans,
    labels_per_row: np.ndarray,
    X_raw: np.ndarray,
) -> list[str]:
    """Map K-Means cluster indices to semantic label strings.

    K-Means cluster index ordering is arbitrary (centroid initialization-dependent).
    We make it deterministic by ranking clusters on their mean rate30d (FEATURE_COLUMNS[0]):
        - Cluster with HIGHEST mean rate30d → "effortless_routine"
        - ...
        - Cluster with LOWEST  mean rate30d → "dormant"

    Args:
        kmeans:        Fitted KMeans estimator with 4 cluster_centers_.
        labels_per_row: Per-sample cluster assignment from fit_predict.
        X_raw:         Original (unscaled) feature matrix; rate30d is column 0.

    Returns:
        labels: list of length 4 where labels[i] = semantic name of cluster i.
    """
    rate30d = X_raw[:, 0]  # FEATURE_COLUMNS[0] = "rate30d"

    # Mean rate30d for each cluster in the ORIGINAL (unscaled) space.
    cluster_mean_rate = np.array([
        rate30d[labels_per_row == i].mean()
        for i in range(kmeans.n_clusters)
    ])

    # rank_order[0] = cluster index with the highest mean rate30d, etc.
    rank_order = np.argsort(-cluster_mean_rate)

    # Pick the right label list for K=4 or K=5 (R4 addition).
    ranked = RANKED_LABELS_K5 if kmeans.n_clusters == 5 else RANKED_LABELS
    semantic_labels: list[str] = [""] * kmeans.n_clusters
    for rank, cluster_idx in enumerate(rank_order):
        semantic_labels[cluster_idx] = ranked[rank]

    return semantic_labels


def _save_json(
    kmeans: KMeans,
    scaler: StandardScaler,
    labels: list[str],
    X_raw: np.ndarray,
    silhouette: float,
) -> Path:
    """Persist habit_clusters.json.

    This file is the complete on-device inference artifact.
    TfliteHabitPredictor loads it once on init and uses:
        1. feature_means / feature_scales  → standardize the incoming ClusterFeatures vector.
        2. centroids                        → nearest-centroid lookup in standardized space.
        3. labels                           → resolve cluster index to semantic string.
        4. training_medians                 → substitute when a feature is null (insufficient data).

    No .tflite file is produced — K-Means inference is O(n_clusters × n_features) arithmetic,
    implemented directly in Kotlin inside TfliteHabitPredictor.classifyBehavioralCluster.
    """
    # Medians computed from the raw (unscaled) training data.
    # Android substitutes training_medians[i] when feature i cannot be derived from
    # Room data (e.g. routinePrecisionStddev requires ≥ 5 completions; new habits
    # will have null from computeRoutinePrecision and need a plausible imputation).
    training_medians: list[float] = np.median(X_raw, axis=0).astype(float).tolist()

    payload = {
        "feature_columns":  gen.FEATURE_COLUMNS,
        "feature_means":    scaler.mean_.astype(float).tolist(),
        "feature_scales":   scaler.scale_.astype(float).tolist(),
        # centroids[i] = centroid of cluster i in STANDARDIZED space (4 × 5 matrix).
        "centroids":        kmeans.cluster_centers_.astype(float).tolist(),
        # labels[i] = semantic name of cluster i (indexed by K-Means cluster index).
        "labels":           labels,
        "training_medians": training_medians,
        "silhouette_score": round(silhouette, 6),
    }

    out = _models_dir() / "habit_clusters.json"
    out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"Saved → {out}")
    return out


def _print_cluster_stats(
    kmeans: KMeans,
    labels_per_row: np.ndarray,
    labels: list[str],
    X_raw: np.ndarray,
) -> None:
    """Print per-cluster summary for thesis documentation."""
    col_names = gen.FEATURE_COLUMNS
    print("\n── Per-cluster statistics ───────────────────────────────────────────")
    print(f"  {'Label':<22}  {'N':>6}  " + "  ".join(f"{c[:12]:>12}" for c in col_names))
    print("  " + "-" * (22 + 8 + 17 * len(col_names)))
    for i in range(kmeans.n_clusters):
        mask = labels_per_row == i
        n = int(mask.sum())
        means = X_raw[mask].mean(axis=0)
        row = f"  {labels[i]:<22}  {n:>6}  " + "  ".join(f"{v:>12.4f}" for v in means)
        print(row)
    print()


def main() -> None:
    print("=== Phase 8.4 R4 — Habit Behavioral Clustering (K-Means, sklearn) ===\n")
    print(f"Acceptance gate: silhouette >= {MIN_SILHOUETTE}")
    print(f"K=5 tolerance  : sil_K5 >= sil_K4 - 0.02 (PLAN-MODEL-RETRAINING.md §R4)\n")

    # Load (or generate) the full dataset — already regenerated at 15 000 rows in Step 2.
    df = _load_or_generate(RETRY_ROWS)
    X_raw = df[gen.FEATURE_COLUMNS].to_numpy(dtype=np.float32)

    # ---- K=4 trial ----
    print(f"--- K=4 fit (rows={len(df):,}) ---")
    kmeans_4, scaler_4, sil_4, labels_4 = _fit(df, n_clusters=4)
    print(f"Silhouette K=4: {sil_4:.4f}")

    # ---- K=5 trial ----
    print(f"\n--- K=5 fit (rows={len(df):,}) ---")
    kmeans_5, scaler_5, sil_5, labels_5 = _fit(df, n_clusters=5)
    print(f"Silhouette K=5: {sil_5:.4f}")

    # ---- Decision gate (PLAN-MODEL-RETRAINING.md §R4 silhouette gate) ----
    TOLERANCE = 0.02
    if sil_5 >= sil_4 - TOLERANCE:
        chosen_k = 5
        kmeans, scaler, sil, labels_per_row = kmeans_5, scaler_5, sil_5, labels_5
        print(
            f"\n→ Keeping K=5: sil_K5 ({sil_5:.4f}) >= sil_K4 ({sil_4:.4f}) - {TOLERANCE}."
        )
    else:
        chosen_k = 4
        kmeans, scaler, sil, labels_per_row = kmeans_4, scaler_4, sil_4, labels_4
        print(
            f"\n→ Reverting to K=4: sil_K5 ({sil_5:.4f}) < sil_K4 ({sil_4:.4f}) - {TOLERANCE}."
            " Per PLAN-MODEL-RETRAINING.md §R4 silhouette gate."
        )

    if sil < MIN_SILHOUETTE:
        print(
            f"\nWARNING: chosen silhouette {sil:.4f} still below {MIN_SILHOUETTE}. "
            "Proceeding — check archetype priors for overlapping feature ranges."
        )

    labels = _assign_labels(kmeans, labels_per_row, X_raw)
    _print_cluster_stats(kmeans, labels_per_row, labels, X_raw)
    out_path = _save_json(kmeans, scaler, labels, X_raw, sil)

    print("=" * 60)
    print(f"SUCCESS — Phase 8.4 R4 (Habit Behavioral Clustering, K={chosen_k}) ready.")
    print(f"  Silhouette K=4   : {sil_4:.4f}")
    print(f"  Silhouette K=5   : {sil_5:.4f}")
    print(f"  Chosen K         : {chosen_k}")
    print(f"  Cluster labels   : {labels}")
    print(f"  Output JSON      : {out_path}")
    print()
    print("Next steps (from plan):")
    print("  Step 5 : Copy models/habit_clusters.json → app/src/main/assets/")
    print("  Step 6 : Extend ClusterFeatures.kt (7 fields)")
    print(f"  Step 7 : {'Add LifeDisrupted to BehavioralCluster.kt' if chosen_k == 5 else 'Skip (K=4 kept)'}")
    print("=" * 60)


if __name__ == "__main__":
    main()
