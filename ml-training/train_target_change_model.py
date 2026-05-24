"""
train_target_change_model.py — Train + export Phase 9.3 (TargetAdjustmentRegressor).

PLAN-ML-EXTENSION.md §9.3.2.

This is a REGRESSION pipeline producing ideal_delta ∈ [-2.0, +2.0].
The trained model replaces the hard-coded ±1 rule in AdaptiveDifficultyUseCase.

Architecture differences vs Phase 8.5 (Spillover):
    * Output range      : [-2.0, +2.0]  (not [-0.5, +0.5])
    * Output layer      : Dense(1, tanh) → Lambda(x * 2.0)
                          Both ops are TFLite-compatible (Mul by constant).
    * Loss              : MAE
    * Acceptance gate   : MAE <= 0.35 on the test set
                          (signal-to-noise ratio is lower than Spillover because
                          the label range is 4× wider and synthetic noise is larger).
    * Retry policy      : 50k rows first; retry at 80k if gate not met.

Pipeline:
    1. Load (or auto-generate) ml-training/data/target_change_dataset.csv.
    2. 80/20 train/test split (no stratification — continuous label).
    3. Fit StandardScaler on training features; persist mean/scale to
       models/target_change_scaler.json so Android can apply IDENTICAL
       normalisation at inference time inside TfliteHabitPredictor.
    4. Build the Keras MLP:
           Dense(32, relu) → Dropout(0.2) → Dense(16, relu)
           → Dense(1, tanh) → Lambda(x * 2.0)
    5. Compile (Adam lr=1e-3, MAE loss).
    6. Train up to 100 epochs with EarlyStopping on val_mae (patience=12).
    7. Evaluate on the held-out test set.
       Also report per-rounded-delta breakdown (treat rounded output as classes,
       compare accuracy vs naive always-predict-0 baseline).
    8. Convert to TFLite with Optimize.DEFAULT (dynamic-range quantisation).
    9. Write models/target_change_regressor.tflite.

Outputs:
    ml-training/models/target_change_regressor.tflite
    ml-training/models/target_change_scaler.json

Usage:
    python train_target_change_model.py
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

import generate_target_change_data as gen  # noqa: E402

# ---------------------------------------------------------------------------
# Reproducibility
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance thresholds (PLAN-ML-EXTENSION.md §9.3, regression)
# ---------------------------------------------------------------------------
MAX_MAE = 0.35
INITIAL_ROWS = 50_000
RETRY_ROWS = 80_000

LABEL_COL = "ideal_delta"


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


def _build_model(n_features: int) -> tf.keras.Model:
    """Small MLP with tanh * 2.0 output to enforce the [-2.0, +2.0] output range.

    Architecture mirrors Phase 8.5 (SpilloverRegressor) for thesis consistency.
    Only the Lambda scaling factor changes (0.5 → 2.0) to match the wider label range.
    Dense(1, tanh) → [-1, 1]; Lambda(x * 2.0) → [-2, +2].
    """
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(n_features,), name="features"),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.Dense(16, activation="relu"),
            # tanh → [-1, 1]; scale to [-2.0, +2.0].
            tf.keras.layers.Dense(1, activation="tanh"),
            tf.keras.layers.Lambda(lambda x: x * 2.0, name="target_delta"),
        ],
        name="TargetAdjustmentRegressor",
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

    print(f"Label range  : {float(y_train.min()):.4f} … {float(y_train.max()):.4f}")
    print(f"Label mean   : {float(y_train.mean()):.4f}  std: {float(y_train.std()):.4f}")
    print(f"Training rows: {len(x_train):,}   Test rows: {len(x_test):,}")

    model.fit(
        x_train_scaled,
        y_train,
        epochs=100,
        batch_size=128,
        validation_split=0.1,
        callbacks=[early_stop],
        verbose=2,
    )

    y_pred = model.predict(x_test_scaled, verbose=0).ravel()
    test_mae = float(mean_absolute_error(y_test, y_pred))
    test_rmse = float(np.sqrt(mean_squared_error(y_test, y_pred)))

    # Naive baseline: always predict 0 (no change).
    naive_mae = float(mean_absolute_error(y_test, np.zeros_like(y_test)))
    print(f"\nTest MAE     : {test_mae:.4f}  (threshold: {MAX_MAE})")
    print(f"Test RMSE    : {test_rmse:.4f}")
    print(f"Naive MAE    : {naive_mae:.4f}  (always predict delta=0)")

    # Per-rounded-delta accuracy (treat as classification for reporting).
    y_test_rounded = np.round(y_test).astype(int)
    y_pred_rounded = np.round(y_pred).clip(-2, 2).astype(int)
    exact_match = (y_test_rounded == y_pred_rounded).mean()
    within_one = (np.abs(y_test_rounded - y_pred_rounded) <= 1).mean()
    print(f"Exact delta accuracy (rounded) : {exact_match:.1%}")
    print(f"Within ±1 delta accuracy       : {within_one:.1%}")

    return model, scaler, test_mae, test_rmse


def _save_scaler(scaler: StandardScaler) -> Path:
    """Persist StandardScaler params as JSON for Android TFLite inference.

    Field order must exactly mirror TargetChangeFeatures.kt → toFloatArray()
    and gen.FEATURE_COLUMNS.
    """
    payload = {
        "feature_columns": gen.FEATURE_COLUMNS,
        "mean": scaler.mean_.astype(float).tolist(),
        "scale": scaler.scale_.astype(float).tolist(),
    }
    out = _models_dir() / "target_change_scaler.json"
    out.write_text(json.dumps(payload, indent=2))
    return out


def _export_tflite(model: tf.keras.Model) -> Path:
    """Dynamic-range quantisation (Optimize.DEFAULT) for on-device efficiency."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    out = _models_dir() / "target_change_regressor.tflite"
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
            "Review label-generation rules in generate_target_change_data.py "
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
