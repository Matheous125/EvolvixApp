"""
train_success_model.py — Train + export Model 1 (HabitSuccessClassifier).

Phase 6.5.2 of PLAN.md. Pipeline:

    1. Load (or auto-generate) ml-training/data/success_dataset.csv.
    2. 80/20 train/test split (stratified on label) via sklearn.
    3. Fit StandardScaler on training features; persist mean & scale to
       models/success_scaler.json so Android can apply IDENTICAL normalization
       at inference time inside TfliteHabitPredictor.
    4. Build the Keras MLP:
           Dense(32, relu) → Dropout(0.2) → Dense(16, relu) → Dense(1, sigmoid)
    5. Compile (Adam, binary_crossentropy, [accuracy, AUC]).
    6. Train 50 epochs with validation_split=0.1.
    7. Evaluate on the held-out test set. Acceptance threshold:
           test accuracy >= 0.82  AND  ROC-AUC >= 0.88.
       If not met on 30k rows, automatically regenerate at 50k and retry once
       (PLAN.md mandate).
    8. Convert to TFLite with Optimize.DEFAULT (dynamic-range quantization).
    9. Write models/habit_success_classifier.tflite.

Outputs:
    ml-training/models/habit_success_classifier.tflite
    ml-training/models/success_scaler.json

Usage:
    python train_success_model.py
"""

from __future__ import annotations

import json
import os
import random
from pathlib import Path

import numpy as np
import pandas as pd

# Keep TF noise out of the thesis console output.
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import tensorflow as tf  # noqa: E402  (env var must be set first)
from sklearn.metrics import roc_auc_score  # noqa: E402
from sklearn.model_selection import train_test_split  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

import generate_success_data as gen  # noqa: E402

# ---------------------------------------------------------------------------
# Reproducibility (seeds Python / NumPy / TF — required for thesis runs).
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance thresholds — sourced verbatim from PLAN.md §6.5.2.
# ---------------------------------------------------------------------------
MIN_ACCURACY = 0.82
MIN_AUC = 0.88
INITIAL_ROWS = 30_000
RETRY_ROWS = 50_000


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
    """Architecture exactly as specified in PLAN.md §6.5.2."""
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(n_features,), name="features"),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.Dense(16, activation="relu"),
            tf.keras.layers.Dense(1, activation="sigmoid", name="success_probability"),
        ],
        name="HabitSuccessClassifier",
    )
    model.compile(
        optimizer="adam",
        loss="binary_crossentropy",
        metrics=["accuracy", tf.keras.metrics.AUC(name="auc")],
    )
    return model


def _train_once(rows: int) -> tuple[tf.keras.Model, StandardScaler, float, float]:
    """Run a complete generate→split→scale→train→evaluate cycle."""
    df = _load_or_generate(rows)

    x = df[gen.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.float32)

    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    # Fit the scaler ONLY on the training set — never on the test set,
    # otherwise we leak test-set statistics into training (classic mistake).
    scaler = StandardScaler().fit(x_train)
    x_train_scaled = scaler.transform(x_train).astype(np.float32)
    x_test_scaled = scaler.transform(x_test).astype(np.float32)

    model = _build_model(n_features=x_train_scaled.shape[1])
    model.fit(
        x_train_scaled,
        y_train,
        epochs=50,
        batch_size=128,
        validation_split=0.1,
        verbose=2,
    )

    # Test-set metrics (held-out, never touched during training).
    eval_results = model.evaluate(x_test_scaled, y_test, verbose=0, return_dict=True)
    accuracy = float(eval_results["accuracy"])
    # Compute AUC from raw predictions — Keras' built-in AUC metric is a stateful
    # streaming estimate, so recomputing with sklearn gives a sharper number for
    # the thesis table.
    y_pred = model.predict(x_test_scaled, verbose=0).ravel()
    auc = float(roc_auc_score(y_test, y_pred))

    print(f"Test accuracy: {accuracy:.4f}   ROC-AUC: {auc:.4f}")
    return model, scaler, accuracy, auc


def _save_scaler(scaler: StandardScaler) -> Path:
    """Persist scaler params as JSON so Android can normalize identically.

    StandardScaler applies `(x - mean) / scale` per feature. We store the
    feature column order alongside, so the Android side can build an
    identically-ordered FloatArray.
    """
    payload = {
        "feature_columns": gen.FEATURE_COLUMNS,
        "mean": scaler.mean_.astype(float).tolist(),
        "scale": scaler.scale_.astype(float).tolist(),
    }
    out = _models_dir() / "success_scaler.json"
    out.write_text(json.dumps(payload, indent=2))
    return out


def _export_tflite(model: tf.keras.Model) -> Path:
    """Quantize + export TFLite per PLAN.md (Optimize.DEFAULT)."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    out = _models_dir() / "habit_success_classifier.tflite"
    out.write_bytes(tflite_bytes)
    return out


def main() -> None:
    print(f"--- Training attempt 1 (rows={INITIAL_ROWS:,}) ---")
    model, scaler, accuracy, auc = _train_once(INITIAL_ROWS)

    if accuracy < MIN_ACCURACY or auc < MIN_AUC:
        print(
            f"Acceptance threshold missed "
            f"(need accuracy>={MIN_ACCURACY}, AUC>={MIN_AUC}). "
            f"Retrying with {RETRY_ROWS:,} rows per PLAN.md §6.5.2."
        )
        print(f"--- Training attempt 2 (rows={RETRY_ROWS:,}) ---")
        model, scaler, accuracy, auc = _train_once(RETRY_ROWS)

    if accuracy < MIN_ACCURACY or auc < MIN_AUC:
        raise SystemExit(
            f"FAILED to meet acceptance threshold after retry "
            f"(accuracy={accuracy:.4f}, AUC={auc:.4f}). "
            "Review label-generation rules before increasing capacity."
        )

    scaler_path = _save_scaler(scaler)
    tflite_path = _export_tflite(model)

    print()
    print("=" * 60)
    print("SUCCESS — Model 1 (HabitSuccessClassifier) ready.")
    print(f"  test accuracy : {accuracy:.4f}")
    print(f"  ROC-AUC       : {auc:.4f}")
    print(f"  scaler JSON   : {scaler_path}")
    print(f"  TFLite model  : {tflite_path}")
    print("Next: copy both files into app/src/main/assets/ (see README).")
    print("=" * 60)


if __name__ == "__main__":
    main()
