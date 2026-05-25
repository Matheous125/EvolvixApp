"""
train_engagement_window_model.py — Train + export Phase 9.6 (EngagementWindowRegressor).

PLAN-ML-EXTENSION.md §9.6.2.

This is a REGRESSION pipeline producing next_session_hour ∈ [0.0, 23.99].

Output range and architecture:
    * Output range  : [0.0, 24.0) — naturally bounded to one 24-hour day.
    * Output layer  : Dense(1, sigmoid) → Lambda(x * 24.0)
                      sigmoid bounds to (0, 1); the Lambda scales to [0, 24).
                      Both ops are TFLite-compatible (Mul by constant).
    * Loss          : MAE (mean absolute error on clock hours)
    * Acceptance gate: MAE ≤ 1.5 hours on the test set.
    * Retry policy  : 50k rows first; retry at 80k if gate not met.

  ⚠ THESIS NOTE — OBSERVATIONAL CAVEAT:
  This model predicts when the user *typically* opens the app based on recent
  session statistics.  It does NOT estimate optimal push-notification timing
  in a causal sense.  See generate_engagement_window_data.py for a fuller
  discussion of the prior behavioural assumptions baked into the synthetic data.

Pipeline:

    1. Load (or auto-generate) ml-training/data/engagement_window_dataset.csv.
    2. 80/20 train/test split (no stratification — continuous label).
    3. Fit StandardScaler on training features; persist mean/scale to
       models/engagement_window_scaler.json so Android applies IDENTICAL
       normalisation at inference time inside TfliteHabitPredictor.
    4. Build the Keras MLP:
           Dense(32, relu) → Dropout(0.2) → Dense(16, relu)
           → Dense(1, sigmoid) → Lambda(x * 24.0)
    5. Compile (Adam, MAE loss).
    6. Train up to 100 epochs with EarlyStopping on val_mae (patience=10).
    7. Evaluate on the held-out test set.
    8. Convert to TFLite with Optimize.DEFAULT (dynamic-range quantisation).
    9. Write models/engagement_window_regressor.tflite.

Outputs:
    ml-training/models/engagement_window_regressor.tflite
    ml-training/models/engagement_window_scaler.json

Usage:
    python train_engagement_window_model.py
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

import generate_engagement_window_data as gen  # noqa: E402

# ---------------------------------------------------------------------------
# Reproducibility
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance thresholds (PLAN-ML-EXTENSION.md §9.6.2)
# ---------------------------------------------------------------------------
MAX_MAE = 1.5          # hours — a prediction within ±1.5 h is practically useful
INITIAL_ROWS = 50_000
RETRY_ROWS = 80_000

LABEL_COL = gen.LABEL_COLUMN   # "next_session_hour"


def _models_dir() -> Path:
    out = Path(__file__).resolve().parent / "models"
    out.mkdir(parents=True, exist_ok=True)
    return out


def _load_or_generate(rows: int) -> pd.DataFrame:
    """Use an existing CSV if it has at least ``rows`` rows; otherwise regenerate."""
    csv_path = gen.OUT_FILE
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
    """Small MLP with sigmoid × 24 output, bounding predictions to [0, 24) hours.

    Architecture:
        Dense(32, relu) → Dropout(0.2) → Dense(16, relu)
        → Dense(1, sigmoid) → Lambda(x * 24.0)

    The sigmoid-then-scale trick avoids the model predicting negative hours or
    hours > 24, while keeping both operations fully TFLite-compatible (sigmoid is
    a built-in op; multiply-by-constant becomes a Mul node in the flatbuffer).
    """
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(n_features,), name="features"),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.Dense(16, activation="relu"),
            # sigmoid → (0, 1); then scale to (0, 24) hours.
            tf.keras.layers.Dense(1, activation="sigmoid"),
            tf.keras.layers.Lambda(lambda x: x * 24.0, name="hour_of_day"),
        ],
        name="EngagementWindowRegressor",
    )
    model.compile(
        optimizer="adam",
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
        patience=10,
        mode="min",
        restore_best_weights=True,
    )

    print(f"Label range: {float(y_train.min()):.2f}h … {float(y_train.max()):.2f}h")
    print(f"Label mean : {float(y_train.mean()):.2f}h  std: {float(y_train.std()):.2f}h")
    print("Naive baseline (predict mean hour): MAE = "
          f"{float(mean_absolute_error(y_test, np.full_like(y_test, y_train.mean()))):.4f}h")

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

    print(f"Test MAE   : {test_mae:.4f}h")
    print(f"Test RMSE  : {test_rmse:.4f}h")

    return model, scaler, test_mae, test_rmse


def _save_scaler(scaler: StandardScaler) -> Path:
    """Persist StandardScaler params as JSON for Android TFLite inference.

    Field order must exactly mirror EngagementWindowFeatures.kt → toFloatArray()
    and gen.FEATURE_COLUMNS.  Any reordering breaks normalisation silently.
    """
    payload = {
        "feature_columns": gen.FEATURE_COLUMNS,
        "mean": scaler.mean_.astype(float).tolist(),
        "scale": scaler.scale_.astype(float).tolist(),
    }
    out = _models_dir() / "engagement_window_scaler.json"
    out.write_text(json.dumps(payload, indent=2))
    return out


def _export_tflite(model: tf.keras.Model) -> Path:
    """Dynamic-range quantisation (Optimize.DEFAULT) for on-device efficiency."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    out = _models_dir() / "engagement_window_regressor.tflite"
    out.write_bytes(tflite_bytes)
    return out


def main() -> None:
    print(f"--- Training attempt 1 (rows={INITIAL_ROWS:,}) ---")
    model, scaler, test_mae, test_rmse = _train_once(INITIAL_ROWS)

    if test_mae > MAX_MAE:
        print(
            f"Test MAE={test_mae:.4f}h above threshold (need ≤ {MAX_MAE}h). "
            f"Retrying with {RETRY_ROWS:,} rows per PLAN-ML-EXTENSION.md Global Standards."
        )
        print(f"--- Training attempt 2 (rows={RETRY_ROWS:,}) ---")
        model, scaler, test_mae, test_rmse = _train_once(RETRY_ROWS)

    if test_mae > MAX_MAE:
        raise SystemExit(
            f"FAILED to meet MAE threshold after retry (MAE={test_mae:.4f}h). "
            "Review label-generation rules in generate_engagement_window_data.py "
            "or increase RETRY_ROWS."
        )

    scaler_path = _save_scaler(scaler)
    tflite_path = _export_tflite(model)

    print("\n=== Export complete ===")
    print(f"  TFLite model : {tflite_path}")
    print(f"  Scaler JSON  : {scaler_path}")
    print(f"  Final MAE    : {test_mae:.4f}h  (threshold: ≤ {MAX_MAE}h)")
    print(f"  Final RMSE   : {test_rmse:.4f}h")


if __name__ == "__main__":
    main()
