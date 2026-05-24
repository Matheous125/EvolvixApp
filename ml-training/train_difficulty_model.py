"""
train_difficulty_model.py — Train + export Phase 9.4 (PerceivedDifficultyRegressor).

PLAN-ML-EXTENSION.md §9.4.2.

This is a REGRESSION pipeline producing perceived_difficulty ∈ [1.0, 5.0].
The trained model powers DifficultyEstimateUseCase on Android, which imputes
expected difficulty for habits with fewer than MIN_RATINGS=5 user-rated completions.

Architecture differences vs Phase 9.3 (TargetAdjustmentRegressor):
    * Label range   : [1.0, 5.0]  (not [-2.0, +2.0])
    * Output layer  : Dense(1, sigmoid) → Lambda(x * 4.0 + 1.0)
                      sigmoid → [0,1]; * 4 + 1 → [1, 5].
                      Both ops are TFLite-compatible (Mul + Add by constant).
    * Loss          : MAE
    * Acceptance    : MAE ≤ 0.55 on the test set
                      (higher than 9.3 because Likert-scale self-report has
                      inherent inter-rater variability noise of σ ≈ 0.45)
    * Naive baseline: always predict 3.0 (scale midpoint).
    * Retry policy  : 50k rows first; retry at 80k if gate not met.

Pipeline:
    1. Load (or auto-generate) ml-training/data/difficulty_dataset.csv.
    2. 80/20 train/test split (no stratification — continuous label).
    3. Fit StandardScaler on training features; persist mean/scale to
       models/perceived_difficulty_scaler.json for Android TFLite inference.
    4. Build the Keras MLP:
           Dense(32, relu) → Dropout(0.2) → Dense(16, relu)
           → Dense(1, sigmoid) → Lambda(x * 4.0 + 1.0)
    5. Compile (Adam lr=1e-3, MAE loss).
    6. Train up to 120 epochs with EarlyStopping on val_mae (patience=12).
    7. Evaluate on the held-out test set.
       Also compare vs naive midpoint-prediction baseline.
    8. Convert to TFLite with Optimize.DEFAULT (dynamic-range quantisation).
    9. Write models/perceived_difficulty_regressor.tflite.

Outputs:
    ml-training/models/perceived_difficulty_regressor.tflite
    ml-training/models/perceived_difficulty_scaler.json

Usage:
    python train_difficulty_model.py
"""

from __future__ import annotations

import json
import os
import random
from pathlib import Path

import numpy as np
import pandas as pd

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import tensorflow as tf  # noqa: E402
from sklearn.metrics import mean_absolute_error, mean_squared_error  # noqa: E402
from sklearn.model_selection import train_test_split  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

import generate_difficulty_data as gen  # noqa: E402

# ---------------------------------------------------------------------------
# Reproducibility
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance thresholds (PLAN-ML-EXTENSION.md §9.4, regression)
# ---------------------------------------------------------------------------
MAX_MAE = 0.55        # Likert noise floor; tighter thresholds are unrealistic
NAIVE_PRED = 3.0      # Scale midpoint — naive baseline for MAE comparison
INITIAL_ROWS = 50_000
RETRY_ROWS = 80_000

LABEL_COL = "perceived_difficulty"


def _models_dir() -> Path:
    here = Path(__file__).resolve().parent
    out = here / "models"
    out.mkdir(parents=True, exist_ok=True)
    return out


def _load_or_generate(rows: int) -> pd.DataFrame:
    """Use an existing CSV if it has at least ``rows`` rows; otherwise regenerate."""
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


def _build_model(n_features: int) -> tf.keras.Model:
    """Small MLP with sigmoid → rescale output to enforce the [1.0, 5.0] label range.

    sigmoid output ∈ [0, 1].
    Lambda(x * 4.0 + 1.0) maps that to [1.0, 5.0] exactly.
    Both TFLite-compatible (Mul + Add by constant — no dynamic shapes).
    Architecture is identical to Phase 9.3 to keep thesis consistency;
    only the output scaling Lambda differs.
    """
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(n_features,), name="features"),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.Dense(16, activation="relu"),
            # sigmoid → [0, 1]; rescale → [1, 5].
            tf.keras.layers.Dense(1, activation="sigmoid"),
            tf.keras.layers.Lambda(
                lambda x: x * 4.0 + 1.0, name="difficulty_score"
            ),
        ],
        name="PerceivedDifficultyRegressor",
    )
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=1e-3),
        loss="mae",
        metrics=["mae"],
    )
    return model


def _train_once(rows: int) -> tuple[tf.keras.Model, StandardScaler, float, float]:
    """Full generate → split → scale → train → evaluate cycle.

    Returns (model, scaler, test_mae, test_rmse).
    """
    df = _load_or_generate(rows)

    x = df[gen.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df[LABEL_COL].to_numpy(dtype=np.float32)

    # No stratification — label is continuous.
    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED
    )

    # Scaler fitted ONLY on training split to prevent data leakage.
    scaler = StandardScaler().fit(x_train)
    x_train_scaled = scaler.transform(x_train).astype(np.float32)
    x_test_scaled = scaler.transform(x_test).astype(np.float32)

    model = _build_model(n_features=x_train_scaled.shape[1])

    early_stop = tf.keras.callbacks.EarlyStopping(
        monitor="val_mae",
        patience=12,
        mode="min",
        restore_best_weights=True,
    )

    print(f"Label range  : {float(y_train.min()):.2f} … {float(y_train.max()):.2f}")
    print(f"Label mean   : {float(y_train.mean()):.4f}  std: {float(y_train.std()):.4f}")
    print(f"Training rows: {len(x_train):,}   Test rows: {len(x_test):,}")

    model.fit(
        x_train_scaled,
        y_train,
        epochs=120,
        batch_size=128,
        validation_split=0.1,
        callbacks=[early_stop],
        verbose=2,
    )

    y_pred = model.predict(x_test_scaled, verbose=0).ravel()
    test_mae = float(mean_absolute_error(y_test, y_pred))
    test_rmse = float(np.sqrt(mean_squared_error(y_test, y_pred)))

    # Naive baseline: always predict scale midpoint 3.0.
    naive_mae = float(mean_absolute_error(y_test, np.full_like(y_test, NAIVE_PRED)))
    print(f"\nTest MAE     : {test_mae:.4f}  (threshold: {MAX_MAE})")
    print(f"Test RMSE    : {test_rmse:.4f}")
    print(f"Naive MAE    : {naive_mae:.4f}  (always predict {NAIVE_PRED})")
    print(f"MAE lift vs naive: {naive_mae - test_mae:.4f}")

    # Per-rounded-bucket accuracy (treat rounded output as Likert classes for reporting).
    y_test_rounded = np.round(y_test).clip(1, 5).astype(int)
    y_pred_rounded = np.round(y_pred).clip(1, 5).astype(int)
    exact_match = (y_test_rounded == y_pred_rounded).mean()
    within_one = (np.abs(y_test_rounded - y_pred_rounded) <= 1).mean()
    print(f"Exact bucket accuracy (rounded): {exact_match:.1%}")
    print(f"Within ±1 bucket accuracy      : {within_one:.1%}")

    return model, scaler, test_mae, test_rmse


def _save_scaler(scaler: StandardScaler) -> Path:
    """Persist StandardScaler params as JSON for Android TFLite inference.

    Field order must exactly mirror DifficultyFeatures.kt → toFloatArray()
    and gen.FEATURE_COLUMNS.
    """
    payload = {
        "feature_columns": gen.FEATURE_COLUMNS,
        "mean": scaler.mean_.astype(float).tolist(),
        "scale": scaler.scale_.astype(float).tolist(),
    }
    out = _models_dir() / "perceived_difficulty_scaler.json"
    out.write_text(json.dumps(payload, indent=2))
    return out


def _export_tflite(model: tf.keras.Model) -> Path:
    """Dynamic-range quantisation (Optimize.DEFAULT) for on-device efficiency."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    out = _models_dir() / "perceived_difficulty_regressor.tflite"
    out.write_bytes(tflite_bytes)
    return out


def main() -> None:
    print(f"--- Training attempt 1 (rows={INITIAL_ROWS:,}) ---")
    model, scaler, test_mae, test_rmse = _train_once(INITIAL_ROWS)

    if test_mae > MAX_MAE:
        print(
            f"Test MAE={test_mae:.4f} above threshold (need <= {MAX_MAE}). "
            f"Retrying with {RETRY_ROWS:,} rows per PLAN-ML-EXTENSION.md Global Standards."
        )
        print(f"--- Training attempt 2 (rows={RETRY_ROWS:,}) ---")
        model, scaler, test_mae, test_rmse = _train_once(RETRY_ROWS)

    if test_mae > MAX_MAE:
        raise SystemExit(
            f"FAILED to meet MAE threshold after retry (MAE={test_mae:.4f}). "
            "Review label-generation rules in generate_difficulty_data.py "
            "or increase RETRY_ROWS."
        )

    scaler_path = _save_scaler(scaler)
    tflite_path = _export_tflite(model)

    print("\n=== Export complete ===")
    print(f"  TFLite model : {tflite_path}")
    print(f"  Scaler JSON  : {scaler_path}")
    print(f"  Final MAE    : {test_mae:.4f}  (threshold: {MAX_MAE})")
    print(f"  Final RMSE   : {test_rmse:.4f}")


if __name__ == "__main__":
    main()
