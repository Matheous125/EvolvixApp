"""
train_streak_break_model.py — Train + export Phase 8.2 (StreakBreakClassifier).

PLAN-ML-EXTENSION.md §8.2.1. Pipeline:

    1. Load (or auto-generate) ml-training/data/streak_break_dataset.csv.
    2. 80/20 train/test split (stratified on label) via sklearn.
    3. Fit StandardScaler on training features; persist mean & scale to
       models/streak_break_scaler.json so Android can apply IDENTICAL
       normalization at inference time inside TfliteHabitPredictor.
    4. Build the Keras MLP (same architecture as Model 1 / Phase 8.1):
           Dense(32, relu) → Dropout(0.2) → Dense(16, relu) → Dense(1, sigmoid)
    5. Compile (Adam, binary_crossentropy, [accuracy, AUC]).
    6. Train up to 50 epochs with EarlyStopping on val_auc (patience=8).
    7. Evaluate on the held-out test set. Acceptance threshold:
           F1 >= 0.75   (PLAN-ML-EXTENSION.md Global Standards mandate)
       If not met on the initial 50k dataset, auto-retry once at 80k rows.
    8. Convert to TFLite with Optimize.DEFAULT (dynamic-range quantization).
    9. Write models/streak_break_classifier.tflite.

Outputs:
    ml-training/models/streak_break_classifier.tflite
    ml-training/models/streak_break_scaler.json

Usage:
    python train_streak_break_model.py
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
from sklearn.metrics import (  # noqa: E402
    classification_report,
    f1_score,
    roc_auc_score,
)
from sklearn.model_selection import train_test_split  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

import generate_streak_break_data as gen  # noqa: E402

# ---------------------------------------------------------------------------
# Reproducibility
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance thresholds (PLAN-ML-EXTENSION.md Global Standards)
# ---------------------------------------------------------------------------
MIN_F1 = 0.75
INITIAL_ROWS = 50_000
RETRY_ROWS = 80_000


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
    """Small MLP per PLAN-ML-EXTENSION.md Global Standards:
    2 hidden layers with ReLU, sigmoid output for binary classification.
    Architecture is identical to Model 1 (HabitSuccessClassifier) and
    Phase 8.1 (HabitAbandonmentClassifier) to keep the pattern consistent
    and easy to explain in the thesis.
    """
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(n_features,), name="features"),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dropout(0.2),
            tf.keras.layers.Dense(16, activation="relu"),
            tf.keras.layers.Dense(
                1, activation="sigmoid", name="streak_break_probability"
            ),
        ],
        name="StreakBreakClassifier",
    )
    model.compile(
        optimizer="adam",
        loss="binary_crossentropy",
        metrics=["accuracy", tf.keras.metrics.AUC(name="auc")],
    )
    return model


def _train_once(rows: int) -> tuple[tf.keras.Model, StandardScaler, float, float, float]:
    """Full generate → split → scale → train → evaluate cycle.

    Returns (model, scaler, accuracy, auc, f1).
    """
    df = _load_or_generate(rows)

    x = df[gen.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.float32)

    x_train, x_test, y_train, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    # Scaler fitted ONLY on training split — never on test data.
    scaler = StandardScaler().fit(x_train)
    x_train_scaled = scaler.transform(x_train).astype(np.float32)
    x_test_scaled = scaler.transform(x_test).astype(np.float32)

    model = _build_model(n_features=x_train_scaled.shape[1])

    early_stop = tf.keras.callbacks.EarlyStopping(
        monitor="val_auc",
        patience=8,
        mode="max",
        restore_best_weights=True,
    )

    pos_rate = float(y_train.mean())
    neg_rate = 1.0 - pos_rate
    print(f"Training set positive rate: {pos_rate:.3f}")

    # class_weight compensates for the ~2:1 surviving/breaking imbalance so the
    # model does not learn to always predict "streak survives."
    # Weight ratio = neg_rate / pos_rate, so each break miss is penalised
    # proportionally more than a false alarm — boosting recall on the positive class.
    class_weight = {0: 1.0, 1: neg_rate / pos_rate}

    model.fit(
        x_train_scaled,
        y_train,
        epochs=50,
        batch_size=128,
        validation_split=0.1,
        callbacks=[early_stop],
        class_weight=class_weight,
        verbose=2,
    )

    # Evaluate on the held-out test set.
    eval_results = model.evaluate(x_test_scaled, y_test, verbose=0, return_dict=True)
    accuracy = float(eval_results["accuracy"])

    y_pred_prob = model.predict(x_test_scaled, verbose=0).ravel()
    auc = float(roc_auc_score(y_test, y_pred_prob))

    y_pred_label = (y_pred_prob >= 0.5).astype(np.int8)
    # Macro-averaged F1 gives equal weight to both classes (survives / breaks),
    # consistent with the PLAN-ML-EXTENSION.md Global Standards mandate and the
    # same metric reported by evaluate_abandonment_model() in evaluate_models.py.
    f1 = float(f1_score(y_test, y_pred_label, average="macro"))

    print(f"Test accuracy : {accuracy:.4f}")
    print(f"ROC-AUC       : {auc:.4f}")
    print(f"Macro F1      : {f1:.4f}")
    print()
    print(classification_report(y_test, y_pred_label, target_names=["survives", "breaks"]))

    return model, scaler, accuracy, auc, f1


def _save_scaler(scaler: StandardScaler) -> Path:
    """Persist StandardScaler params as JSON for Android TFLite inference.

    TfliteHabitPredictor reads this file on init and applies
    `(x - mean) / scale` per feature before running the model.
    Field order must exactly mirror StreakBreakFeatures.kt → toFloatArray().
    """
    payload = {
        "feature_columns": gen.FEATURE_COLUMNS,
        "mean": scaler.mean_.astype(float).tolist(),
        "scale": scaler.scale_.astype(float).tolist(),
    }
    out = _models_dir() / "streak_break_scaler.json"
    out.write_text(json.dumps(payload, indent=2))
    return out


def _export_tflite(model: tf.keras.Model) -> Path:
    """Dynamic-range quantization via Optimize.DEFAULT (PLAN-ML-EXTENSION.md Global Standards)."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    out = _models_dir() / "streak_break_classifier.tflite"
    out.write_bytes(tflite_bytes)
    return out


def main() -> None:
    print(f"--- Training attempt 1 (rows={INITIAL_ROWS:,}) ---")
    model, scaler, accuracy, auc, f1 = _train_once(INITIAL_ROWS)

    if f1 < MIN_F1:
        print(
            f"Macro F1={f1:.4f} below threshold (need >= {MIN_F1}). "
            f"Retrying with {RETRY_ROWS:,} rows per PLAN-ML-EXTENSION.md Global Standards."
        )
        print(f"--- Training attempt 2 (rows={RETRY_ROWS:,}) ---")
        model, scaler, accuracy, auc, f1 = _train_once(RETRY_ROWS)

    if f1 < MIN_F1:
        raise SystemExit(
            f"FAILED to meet macro-F1 threshold after retry (F1={f1:.4f}). "
            "Review label-generation rules in generate_streak_break_data.py "
            "or increase model capacity."
        )

    scaler_path = _save_scaler(scaler)
    tflite_path = _export_tflite(model)

    print()
    print("=" * 60)
    print("SUCCESS — Phase 8.2 (StreakBreakClassifier) ready.")
    print(f"  test accuracy : {accuracy:.4f}")
    print(f"  ROC-AUC       : {auc:.4f}")
    print(f"  Macro F1      : {f1:.4f}")
    print(f"  scaler JSON   : {scaler_path}")
    print(f"  TFLite model  : {tflite_path}")
    print()
    print("Next: copy both files into app/src/main/assets/")
    print("      then implement TfliteHabitPredictor.predictStreakBreak.")
    print("=" * 60)


if __name__ == "__main__":
    main()
