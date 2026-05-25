"""
evaluate_models.py — Thesis-grade evaluation report for the three TFLite models.

Phase 6.5.5 of PLAN.md. Loads the exported `.tflite` artifacts (alongside the
scaler / vocab JSON files), reconstructs the SAME stratified 80/20 test split
used during training (SEED=42, identical to the train_* scripts), and produces:

    Model 1 — HabitSuccessClassifier (binary)
        * Confusion matrix PNG
        * ROC curve PNG
        * Calibration plot PNG (predicted vs. empirical probability)
        * Test accuracy + ROC-AUC

    Model 2 — HabitIconClassifier (17-way)
        * Confusion matrix PNG (17x17)
        * Classification report (precision / recall / F1 per class)
        * Top-1 + top-3 accuracy

    Model 3 — ReminderTemplateClassifier (15-way)
        * Confusion matrix PNG (15x15)
        * Classification report (precision / recall / F1 per class)
        * Top-1 accuracy

A consolidated Markdown summary table is written to:
    ml-training/data/plots/metrics_summary.md
(also echoed to stdout so the student can copy directly into the thesis chapter).

Usage:
    python evaluate_models.py

Note: This script ONLY consumes the exported artifacts (`.tflite` + JSON). It
does NOT retrain. That keeps the evaluation honest — the numbers reflect what
ships into the Android `assets/` folder, not a freshly-trained copy.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Sequence

import numpy as np
import pandas as pd

# Suppress TF noise.
os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import matplotlib.pyplot as plt  # noqa: E402
import tensorflow as tf  # noqa: E402
from sklearn.metrics import (  # noqa: E402
    classification_report,
    confusion_matrix,
    f1_score,
    roc_auc_score,
    roc_curve,
    silhouette_score,
    top_k_accuracy_score,
)
from sklearn.decomposition import PCA  # noqa: E402
from sklearn.model_selection import train_test_split  # noqa: E402

# Reuse the three generator modules so column orders / label orders cannot
# drift between training and evaluation.
import generate_abandonment_data as gen_abandonment  # noqa: E402
import generate_clustering_data as gen_clustering  # noqa: E402
import generate_icon_data as gen_icon  # noqa: E402
import generate_reminder_data as gen_reminder  # noqa: E402
import generate_reminder_lift_data as gen_reminder_lift  # noqa: E402
import generate_spillover_data as gen_spillover  # noqa: E402
import generate_streak_break_data as gen_streak_break  # noqa: E402
import generate_success_data as gen_success  # noqa: E402
import generate_weekly_forecast_data as gen_weekly_forecast  # noqa: E402
import generate_snooze_disengagement_data as gen_snooze_disengagement  # noqa: E402
import generate_target_change_data as gen_target_change  # noqa: E402
import generate_difficulty_data as gen_difficulty  # noqa: E402
import generate_skip_reason_data as gen_skip_reason  # noqa: E402
from train_icon_model import name_to_ngram_string  # noqa: E402

# ---------------------------------------------------------------------------
# Paths + reproducibility seed (must equal the seed used in train_* scripts).
# ---------------------------------------------------------------------------
SEED = 42
HERE = Path(__file__).resolve().parent
MODELS_DIR = HERE / "models"
PLOTS_DIR = HERE / "data" / "plots"


def _ensure_plots_dir() -> Path:
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)
    return PLOTS_DIR


# ---------------------------------------------------------------------------
# Tiny helper: run a TFLite interpreter over a (n, d) FloatArray batch.
# We loop one example at a time because the interpreter's input tensor is
# allocated with shape (1, d) by the converter; resizing per-batch is more
# fragile than a simple Python loop for an offline evaluation script.
# ---------------------------------------------------------------------------
def _tflite_predict(model_path: Path, x: np.ndarray) -> np.ndarray:
    """Return raw model outputs for every row in x. Shape: (n, out_dim)."""
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    interpreter.allocate_tensors()
    in_detail = interpreter.get_input_details()[0]
    out_detail = interpreter.get_output_details()[0]

    out_dim = int(out_detail["shape"][-1])
    out = np.zeros((len(x), out_dim), dtype=np.float32)
    for i, row in enumerate(x):
        interpreter.set_tensor(
            in_detail["index"],
            row.reshape(1, -1).astype(np.float32),
        )
        interpreter.invoke()
        out[i] = interpreter.get_tensor(out_detail["index"]).ravel()
    return out


# ---------------------------------------------------------------------------
# Confusion matrix plotting helper — kept deliberately simple (matplotlib only,
# no seaborn dependency) so the thesis pipeline has minimal moving parts.
# ---------------------------------------------------------------------------
def _plot_confusion_matrix(
    cm: np.ndarray,
    class_names: Sequence[str],
    title: str,
    out_path: Path,
) -> None:
    fig, ax = plt.subplots(figsize=(max(6, len(class_names) * 0.55),
                                    max(5, len(class_names) * 0.5)))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_title(title)
    ax.set_xlabel("Predicted")
    ax.set_ylabel("Actual")
    ax.set_xticks(range(len(class_names)))
    ax.set_yticks(range(len(class_names)))
    ax.set_xticklabels(class_names, rotation=45, ha="right", fontsize=8)
    ax.set_yticklabels(class_names, fontsize=8)

    # Annotate counts. Skip annotation if the matrix is large enough that
    # text would overlap (>20 classes); the colormap alone is sufficient.
    if len(class_names) <= 20:
        threshold = cm.max() / 2.0 if cm.max() > 0 else 0.5
        for i in range(cm.shape[0]):
            for j in range(cm.shape[1]):
                ax.text(
                    j, i, int(cm[i, j]),
                    ha="center", va="center",
                    color="white" if cm[i, j] > threshold else "black",
                    fontsize=7,
                )

    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


# ---------------------------------------------------------------------------
# Model 1 — HabitSuccessClassifier
# ---------------------------------------------------------------------------
def evaluate_success_model() -> dict:
    print("\n=== Model 1 — HabitSuccessClassifier ===")
    csv_path = gen_success.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run train_success_model.py first.")

    df = pd.read_csv(csv_path)
    x = df[gen_success.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    # Same split as training (SEED=42, stratified, test_size=0.2).
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    # Apply the saved StandardScaler (mean / scale) before inference.
    scaler = json.loads((MODELS_DIR / "success_scaler.json").read_text())
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    probs = _tflite_predict(
        MODELS_DIR / "habit_success_classifier.tflite",
        x_test_scaled,
    ).ravel()
    y_pred = (probs >= 0.5).astype(np.int32)

    accuracy = float((y_pred == y_test).mean())
    auc = float(roc_auc_score(y_test, probs))

    print(f"Test accuracy: {accuracy:.4f}")
    print(f"ROC-AUC      : {auc:.4f}")

    # ----- Confusion matrix -----
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1])
    _plot_confusion_matrix(
        cm,
        class_names=["fail (0)", "success (1)"],
        title="Model 1 — HabitSuccessClassifier — Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_success.png",
    )

    # ----- ROC curve -----
    fpr, tpr, _ = roc_curve(y_test, probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"ROC (AUC = {auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Random")
    ax.set_xlabel("False Positive Rate")
    ax.set_ylabel("True Positive Rate")
    ax.set_title("Model 1 — ROC curve")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR / "roc_success.png", dpi=150)
    plt.close(fig)

    # ----- Calibration plot (reliability diagram) -----
    # Bin predicted probabilities into 10 equal-width buckets, then plot the
    # empirical positive rate of each bucket against its mean predicted prob.
    # A perfectly calibrated model lies on y=x.
    bucket_idx = np.clip(np.digitize(probs, np.linspace(0.0, 1.0, 11)) - 1, 0, 9)
    mean_pred, empirical = [], []
    for b in range(10):
        mask = bucket_idx == b
        if mask.sum() == 0:
            continue
        mean_pred.append(float(probs[mask].mean()))
        empirical.append(float(y_test[mask].mean()))

    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray",
            label="Perfect calibration")
    ax.plot(mean_pred, empirical, marker="o", label="Model")
    ax.set_xlabel("Mean predicted probability")
    ax.set_ylabel("Empirical positive rate")
    ax.set_title("Model 1 — Calibration plot")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR / "calibration_success.png", dpi=150)
    plt.close(fig)

    return {"name": "HabitSuccessClassifier",
            "task": "binary classification",
            "test_size": int(len(y_test)),
            "accuracy": accuracy,
            "roc_auc": auc}


# ---------------------------------------------------------------------------
# Model 4 — HabitAbandonmentClassifier (Phase 8.1)
# ---------------------------------------------------------------------------
def evaluate_abandonment_model() -> dict:
    print("\n=== Model 4 \u2014 HabitAbandonmentClassifier ===")
    csv_path = gen_abandonment.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing \u2014 run train_abandonment_model.py first.")

    df = pd.read_csv(csv_path)
    x = df[gen_abandonment.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    # Reproduce the same stratified 80/20 split used in train_abandonment_model.py.
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads((MODELS_DIR / "abandonment_scaler.json").read_text())
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    probs = _tflite_predict(
        MODELS_DIR / "habit_abandonment_classifier.tflite",
        x_test_scaled,
    ).ravel()
    y_pred = (probs >= 0.5).astype(np.int32)

    accuracy = float((y_pred == y_test).mean())
    auc = float(roc_auc_score(y_test, probs))
    macro_f1 = float(f1_score(y_test, y_pred, average="macro", zero_division=0))

    print(f"Test accuracy : {accuracy:.4f}")
    print(f"ROC-AUC       : {auc:.4f}")
    print(f"Macro F1      : {macro_f1:.4f}  (threshold >= 0.75 to pass)")
    print(classification_report(
        y_test, y_pred,
        target_names=["active (0)", "abandoned (1)"],
        digits=3, zero_division=0,
    ))

    # ----- Confusion matrix -----
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1])
    _plot_confusion_matrix(
        cm,
        class_names=["active (0)", "abandoned (1)"],
        title="Model 4 \u2014 HabitAbandonmentClassifier \u2014 Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_abandonment.png",
    )

    # ----- ROC curve -----
    fpr, tpr, _ = roc_curve(y_test, probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"ROC (AUC = {auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Random")
    ax.set_xlabel("False Positive Rate")
    ax.set_ylabel("True Positive Rate")
    ax.set_title("Model 4 \u2014 HabitAbandonmentClassifier \u2014 ROC curve")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR / "roc_abandonment.png", dpi=150)
    plt.close(fig)

    return {
        "name": "HabitAbandonmentClassifier",
        "task": "binary classification",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "roc_auc": auc,
        "macro_f1": macro_f1,
    }


# ---------------------------------------------------------------------------
# Model 5 — StreakBreakClassifier (Phase 8.2)
# ---------------------------------------------------------------------------
def evaluate_streak_break_model() -> dict:
    print("\n=== Model 5 \u2014 StreakBreakClassifier ===")
    csv_path = gen_streak_break.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing \u2014 run train_streak_break_model.py first.")

    df = pd.read_csv(csv_path)
    x = df[gen_streak_break.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    # Reproduce the same stratified 80/20 split used in train_streak_break_model.py.
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads((MODELS_DIR / "streak_break_scaler.json").read_text())
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    probs = _tflite_predict(
        MODELS_DIR / "streak_break_classifier.tflite",
        x_test_scaled,
    ).ravel()
    y_pred = (probs >= 0.5).astype(np.int32)

    accuracy = float((y_pred == y_test).mean())
    auc = float(roc_auc_score(y_test, probs))
    macro_f1 = float(f1_score(y_test, y_pred, average="macro", zero_division=0))

    print(f"Test accuracy : {accuracy:.4f}")
    print(f"ROC-AUC       : {auc:.4f}")
    print(f"Macro F1      : {macro_f1:.4f}  (threshold >= 0.75 to pass)")
    print(classification_report(
        y_test, y_pred,
        target_names=["survives (0)", "breaks (1)"],
        digits=3, zero_division=0,
    ))

    # ----- Confusion matrix -----
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1])
    _plot_confusion_matrix(
        cm,
        class_names=["survives (0)", "breaks (1)"],
        title="Model 5 \u2014 StreakBreakClassifier \u2014 Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_streak_break.png",
    )

    # ----- ROC curve -----
    fpr, tpr, _ = roc_curve(y_test, probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"ROC (AUC = {auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Random")
    ax.set_xlabel("False Positive Rate")
    ax.set_ylabel("True Positive Rate")
    ax.set_title("Model 5 \u2014 StreakBreakClassifier \u2014 ROC curve")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR / "roc_streak_break.png", dpi=150)
    plt.close(fig)

    # ----- Precision-Recall curve -----
    from sklearn.metrics import precision_recall_curve, average_precision_score
    precision, recall, _ = precision_recall_curve(y_test, probs)
    ap = float(average_precision_score(y_test, probs))
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(recall, precision, label=f"PR (AP = {ap:.3f})")
    ax.set_xlabel("Recall")
    ax.set_ylabel("Precision")
    ax.set_title("Model 5 \u2014 StreakBreakClassifier \u2014 PR curve")
    ax.legend(loc="upper right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR / "pr_streak_break.png", dpi=150)
    plt.close(fig)

    return {
        "name": "StreakBreakClassifier",
        "task": "binary classification",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "roc_auc": auc,
        "macro_f1": macro_f1,
    }


# ---------------------------------------------------------------------------
# Model 2 — HabitIconClassifier
# Replicates the EXACT n-gram + TF-IDF pipeline that Android will run, using
# only the contents of icon_vocab.json — no Keras TextVectorization layer.
# ---------------------------------------------------------------------------
def _tfidf_vector(name: str,
                  vocab_index: dict,
                  idf: np.ndarray,
                  ngram_sizes: tuple) -> np.ndarray:
    """Build a single TF-IDF feature vector for `name` from the saved vocab.

    Mirrors the Kotlin-side procedure documented in train_icon_model._save_vocab.
    OOV n-grams fall through to the [UNK] bucket at index 0 (Keras default).
    """
    vec = np.zeros(len(idf), dtype=np.float32)
    tokens = name_to_ngram_string(name, sizes=ngram_sizes).split()
    unk_index = vocab_index.get("[UNK]", 0)
    for token in tokens:
        i = vocab_index.get(token, unk_index)
        vec[i] += idf[i]
    return vec


def evaluate_icon_model() -> dict:
    print("\n=== Model 2 — HabitIconClassifier ===")
    csv_path = gen_icon.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run train_icon_model.py first.")

    df = pd.read_csv(csv_path)
    names = df["name"].astype(str).tolist()
    labels = df["label"].astype(str).tolist()

    # Load vocab + idf + label order from JSON (identical to Android runtime).
    vocab_payload = json.loads(
        (MODELS_DIR / "icon_vocab.json").read_text(encoding="utf-8")
    )
    vocab = vocab_payload["vocabulary"]
    idf = np.array(vocab_payload["idf_weights"], dtype=np.float32)
    label_names = vocab_payload["labels"]
    ngram_sizes = tuple(vocab_payload["ngram_sizes"])
    vocab_index = {tok: i for i, tok in enumerate(vocab)}
    label_to_id = {name: i for i, name in enumerate(label_names)}

    y = np.array([label_to_id[lbl] for lbl in labels], dtype=np.int32)

    # Reproduce the SAME stratified split as train_icon_model.py (SEED=42).
    indices = np.arange(len(names))
    _, idx_test, _, y_test = train_test_split(
        indices, y, test_size=0.2, random_state=SEED, stratify=y
    )
    names_test = [names[i] for i in idx_test]

    # Build TF-IDF features for the test set.
    x_test = np.stack([
        _tfidf_vector(n, vocab_index, idf, ngram_sizes) for n in names_test
    ])

    probs = _tflite_predict(
        MODELS_DIR / "habit_icon_classifier.tflite",
        x_test,
    )
    y_pred = probs.argmax(axis=1)

    top1 = float((y_pred == y_test).mean())
    top3 = float(top_k_accuracy_score(
        y_test, probs, k=3, labels=list(range(len(label_names))),
    ))

    print(f"Top-1 accuracy: {top1:.4f}")
    print(f"Top-3 accuracy: {top3:.4f}")

    report_text = classification_report(
        y_test, y_pred,
        labels=list(range(len(label_names))),
        target_names=label_names,
        digits=3,
        zero_division=0,
    )
    print("\nClassification report (per-class precision / recall / F1):")
    print(report_text)

    cm = confusion_matrix(y_test, y_pred, labels=list(range(len(label_names))))
    _plot_confusion_matrix(
        cm,
        class_names=label_names,
        title="Model 2 — HabitIconClassifier — Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_icon.png",
    )

    return {"name": "HabitIconClassifier",
            "task": f"{len(label_names)}-class text classification",
            "test_size": int(len(y_test)),
            "top1": top1,
            "top3": top3,
            "report": report_text}


# ---------------------------------------------------------------------------
# Model 3 — ReminderTemplateClassifier
# ---------------------------------------------------------------------------
def evaluate_reminder_model() -> dict:
    print("\n=== Model 3 — ReminderTemplateClassifier ===")
    csv_path = gen_reminder.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run train_reminder_model.py first.")

    df = pd.read_csv(csv_path)
    x = df[gen_reminder.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads((MODELS_DIR / "reminder_scaler.json").read_text())
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    label_names = scaler["label_names"]
    x_test_scaled = (x_test - mean) / scale

    probs = _tflite_predict(
        MODELS_DIR / "reminder_template_classifier.tflite",
        x_test_scaled,
    )
    y_pred = probs.argmax(axis=1)
    top1 = float((y_pred == y_test).mean())

    print(f"Top-1 accuracy: {top1:.4f}")

    report_text = classification_report(
        y_test, y_pred,
        labels=list(range(len(label_names))),
        target_names=label_names,
        digits=3,
        zero_division=0,
    )
    print("\nClassification report (per-class precision / recall / F1):")
    print(report_text)

    cm = confusion_matrix(y_test, y_pred, labels=list(range(len(label_names))))
    _plot_confusion_matrix(
        cm,
        class_names=label_names,
        title="Model 3 — ReminderTemplateClassifier — Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_reminder.png",
    )

    return {"name": "ReminderTemplateClassifier",
            "task": f"{len(label_names)}-class classification",
            "test_size": int(len(y_test)),
            "top1": top1,
            "report": report_text}


# ---------------------------------------------------------------------------
# Model 6 — WeeklyForecastRegressor (Phase 8.3)
# Regression: predicts the user’s next-week habit-completion rate (0–1).
# Acceptance criterion: Test MAE ≤ 0.12.
# ---------------------------------------------------------------------------
def evaluate_weekly_forecast_model() -> dict:
    print("\n=== Model 6 — WeeklyForecastRegressor ===")
    csv_path = gen_weekly_forecast.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run generate_weekly_forecast_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_weekly_forecast.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.float32)

    # 80/20 split — no stratification (continuous regression label).
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED
    )

    scaler = json.loads(
        (MODELS_DIR / "weekly_forecast_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw = _tflite_predict(
        MODELS_DIR / "weekly_forecast_regressor.tflite",
        x_test_scaled,
    )
    y_pred = raw.ravel()  # shape (n,)

    mae = float(np.mean(np.abs(y_pred - y_test)))
    rmse = float(np.sqrt(np.mean((y_pred - y_test) ** 2)))

    print(f"Test MAE  : {mae:.4f}  (threshold <= 0.12 to pass)")
    print(f"Test RMSE : {rmse:.4f}")
    passed = "PASS" if mae <= 0.12 else "FAIL"
    print(f"Acceptance: {passed}")

    # Scatter plot: actual vs predicted (visual residual check for thesis).
    out_path = PLOTS_DIR / "scatter_weekly_forecast.png"
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, y_pred, alpha=0.3, s=8, label="predictions")
    lo, hi = min(y_test.min(), y_pred.min()), max(y_test.max(), y_pred.max())
    ax.plot([lo, hi], [lo, hi], linestyle="--", color="gray", label="perfect fit")
    ax.set_xlabel("Actual label")
    ax.set_ylabel("Predicted label")
    ax.set_title("Model 6 \u2014 WeeklyForecastRegressor \u2014 Actual vs Predicted")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

    return {
        "name": "WeeklyForecastRegressor",
        "task": "regression",
        "test_size": int(len(y_test)),
        "mae": mae,
        "rmse": rmse,
        "passed": passed,
    }


# ---------------------------------------------------------------------------
# Phase 8.4 — K-Means Behavioral Clustering
# ---------------------------------------------------------------------------
def evaluate_clustering_model() -> dict:
    """
    Evaluate the K-Means nearest-centroid clustering (Phase 8.4).

    Uses the full clustering_dataset.csv (unsupervised, no train/test split).
    Metrics: silhouette score, per-cluster size, and a PCA 2-D scatter plot
    saved to data/plots/cluster_pca.png.
    """
    print("\n=== Phase 8.4 — K-Means Behavioral Clustering ===")

    csv_path = gen_clustering.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run train_clustering_model.py first."
        )

    json_path = MODELS_DIR / "habit_clusters.json"
    if not json_path.exists():
        raise FileNotFoundError(
            f"{json_path} missing — run train_clustering_model.py first."
        )

    artifact = json.loads(json_path.read_text())
    feature_cols: list[str] = artifact["feature_columns"]
    means = np.array(artifact["feature_means"], dtype=np.float32)
    scales = np.array(artifact["feature_scales"], dtype=np.float32)
    centroids = np.array(artifact["centroids"], dtype=np.float32)  # (4, 5)
    labels: list[str] = artifact["labels"]
    saved_silhouette: float = artifact["silhouette_score"]

    df = pd.read_csv(csv_path)
    x_raw = df[feature_cols].to_numpy(dtype=np.float32)

    # Standardize with the saved scaler (mirrors Kotlin standardScale()).
    x_scaled = (x_raw - means) / np.where(scales == 0, 1.0, scales)

    # Nearest-centroid assignment (mirrors Kotlin sqDistance loop).
    diffs = x_scaled[:, np.newaxis, :] - centroids[np.newaxis, :, :]  # (n, 4, 5)
    sq_dists = (diffs ** 2).sum(axis=2)                                  # (n, 4)
    assigned = sq_dists.argmin(axis=1)                                   # (n,)
    cluster_names = np.array([labels[i] for i in assigned])

    # Silhouette score (verify it matches the training-time value).
    sil = silhouette_score(x_scaled, assigned, sample_size=5_000, random_state=SEED)
    print(f"  Silhouette score (recomputed) : {sil:.4f}")
    print(f"  Silhouette score (from JSON)  : {saved_silhouette:.4f}")
    gate_ok = sil >= 0.35
    print(f"  Quality gate (>= 0.35)        : {'PASS' if gate_ok else 'FAIL'}")

    # Per-cluster sizes.
    print("\n  Per-cluster sizes:")
    size_map: dict[str, int] = {}
    for label in labels:
        count = int((cluster_names == label).sum())
        size_map[label] = count
        pct = count / len(assigned) * 100
        print(f"    {label:<22} {count:>6}  ({pct:.1f}%)")

    # Centroid table (raw feature space, for thesis appendix).
    print("\n  Centroid table (standardized space):")
    header = f"{'Label':<22} " + "  ".join(f"{c:>18}" for c in feature_cols)
    print("  " + header)
    for i, label in enumerate(labels):
        row_vals = "  ".join(f"{v:>18.4f}" for v in centroids[i])
        print(f"  {label:<22} {row_vals}")

    # PCA 2-D scatter plot.
    pca = PCA(n_components=2, random_state=SEED)
    x_2d = pca.fit_transform(x_scaled)
    explained = pca.explained_variance_ratio_

    color_map = {
        "effortless_routine": "#2196F3",
        "consistent_effort":  "#4CAF50",
        "struggling":         "#FF9800",
        "dormant":            "#9E9E9E",
    }

    fig, ax = plt.subplots(figsize=(8, 6))
    for label in labels:
        mask = cluster_names == label
        color = color_map.get(label, "#000000")
        ax.scatter(
            x_2d[mask, 0], x_2d[mask, 1],
            c=color, label=label, alpha=0.35, s=8, linewidths=0
        )

    # Plot centroids in PCA space.
    centroids_2d = pca.transform(centroids)
    for i, label in enumerate(labels):
        color = color_map.get(label, "#000000")
        ax.scatter(
            centroids_2d[i, 0], centroids_2d[i, 1],
            marker="*", s=220, c=color, edgecolors="black", linewidths=0.8, zorder=5
        )

    ax.set_title(
        f"K-Means Behavioral Clusters — PCA projection\n"
        f"(PC1 {explained[0]*100:.1f}%  +  PC2 {explained[1]*100:.1f}%  = "
        f"{sum(explained)*100:.1f}% variance explained)"
    )
    ax.set_xlabel(f"PC1 ({explained[0]*100:.1f}%)")
    ax.set_ylabel(f"PC2 ({explained[1]*100:.1f}%)")
    ax.legend(title="Cluster", fontsize=8)
    fig.tight_layout()
    plot_path = _ensure_plots_dir() / "cluster_pca.png"
    fig.savefig(plot_path, dpi=150)
    plt.close(fig)
    print(f"\n  PCA scatter plot saved → {plot_path}")

    return {
        "name": "KMeansBehavioralClustering",
        "task": "clustering",
        "total_rows": len(assigned),
        "silhouette": sil,
        "gate_passed": gate_ok,
        "cluster_sizes": size_map,
    }


# ---------------------------------------------------------------------------
# Phase 8.5 — SpilloverRegressor
# ---------------------------------------------------------------------------
def evaluate_spillover_model() -> dict:
    """
    Evaluate the SpilloverRegressor TFLite model (Phase 8.5).

    Reproduces the same 80/20 non-stratified split (SEED=42) used in
    train_spillover_model.py and reports MAE + R² on the held-out test set.
    A predicted-vs-actual scatter plot is saved to data/plots/.
    Acceptance gate: MAE <= 0.08.
    """
    print("\n=== Phase 8.5 — SpilloverRegressor ===")

    csv_path = gen_spillover.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run generate_spillover_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_spillover.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["lift_delta"].to_numpy(dtype=np.float32)

    # 80/20 split — no stratification (continuous regression label).
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED
    )

    scaler = json.loads(
        (MODELS_DIR / "spillover_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw = _tflite_predict(
        MODELS_DIR / "spillover_regressor.tflite",
        x_test_scaled,
    )
    y_pred = raw.ravel()

    mae = float(np.mean(np.abs(y_pred - y_test)))
    ss_res = float(np.sum((y_test - y_pred) ** 2))
    ss_tot = float(np.sum((y_test - y_test.mean()) ** 2))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0

    passed = "PASS" if mae <= 0.08 else "FAIL"
    print(f"Test MAE  : {mae:.4f}  (threshold <= 0.08 to pass)")
    print(f"Test R²   : {r2:.4f}")
    print(f"Acceptance: {passed}")

    # Scatter plot: actual vs predicted.
    out_path = PLOTS_DIR / "scatter_spillover.png"
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, y_pred, alpha=0.25, s=6, label="predictions")
    lo = min(float(y_test.min()), float(y_pred.min()))
    hi = max(float(y_test.max()), float(y_pred.max()))
    ax.plot([lo, hi], [lo, hi], linestyle="--", color="gray", label="perfect fit")
    ax.set_xlabel("Actual lift_delta")
    ax.set_ylabel("Predicted lift_delta")
    ax.set_title("Phase 8.5 \u2014 SpilloverRegressor \u2014 Actual vs Predicted")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  Scatter plot saved \u2192 {out_path}")

    return {
        "name": "SpilloverRegressor",
        "task": "regression",
        "test_size": int(len(y_test)),
        "mae": mae,
        "r2": r2,
        "passed": passed,
    }


def evaluate_reminder_lift_model() -> dict:
    """
    Evaluate the ReminderLiftClassifier TFLite model (Phase 9.1).

    Reproduces the same 80/20 stratified split (SEED=42) used in
    train_reminder_lift_model.py and reports accuracy, ROC-AUC, Macro F1,
    and lift MAE on the held-out test set.
    Acceptance gate: Macro F1 >= 0.75 and lift MAE <= 0.12.
    """
    print("\n=== Phase 9.1 \u2014 ReminderLiftClassifier ===")

    csv_path = gen_reminder_lift.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing \u2014 run generate_reminder_lift_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_reminder_lift.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["completed_within_30min"].to_numpy(dtype=np.int32)

    # Stratified 80/20 split \u2014 identical to train_reminder_lift_model.py.
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads(
        (MODELS_DIR / "reminder_lift_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw = _tflite_predict(
        MODELS_DIR / "reminder_lift_classifier.tflite",
        x_test_scaled,
    )
    y_prob = raw.ravel()
    y_pred = (y_prob >= 0.5).astype(np.int32)

    acc = float(np.mean(y_pred == y_test))
    roc_auc = float(roc_auc_score(y_test, y_prob))
    macro_f1 = float(f1_score(y_test, y_pred, average="macro"))

    # Lift MAE: compare predicted vs actual completion lift (reminder=1 minus reminder=0).
    reminder_col_idx = gen_reminder_lift.FEATURE_COLUMNS.index("reminderSent")
    mask_0 = x_test[:, reminder_col_idx] == 0
    mask_1 = x_test[:, reminder_col_idx] == 1
    pred_lift = float(y_prob[mask_1].mean()) - float(y_prob[mask_0].mean())
    act_lift = float(y_test[mask_1].mean()) - float(y_test[mask_0].mean())
    lift_mae = abs(pred_lift - act_lift)

    passed_f1 = macro_f1 >= 0.75
    passed_lift = lift_mae <= 0.12
    passed = "PASS" if (passed_f1 and passed_lift) else "FAIL"
    print(f"Test accuracy : {acc:.4f}")
    print(f"ROC-AUC       : {roc_auc:.4f}")
    print(f"Macro F1      : {macro_f1:.4f}  (threshold >= 0.75 to pass)")
    print(f"Lift MAE      : {lift_mae:.4f}  (threshold <= 0.12 to pass)")
    print(f"Acceptance    : {passed}")

    # Confusion matrix.
    cm = confusion_matrix(y_test, y_pred)
    out_path = PLOTS_DIR / "confusion_reminder_lift.png"
    fig, ax = plt.subplots(figsize=(4, 4))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks([0, 1]); ax.set_xticklabels(["No", "Yes"])
    ax.set_yticks([0, 1]); ax.set_yticklabels(["No", "Yes"])
    ax.set_xlabel("Predicted"); ax.set_ylabel("Actual")
    ax.set_title("Phase 9.1 \u2014 ReminderLiftClassifier")
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, ax=ax)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  Confusion matrix saved \u2192 {out_path}")

    report = classification_report(
        y_test, y_pred, target_names=["Not completed", "Completed"]
    )

    return {
        "name": "ReminderLiftClassifier",
        "task": "binary classification",
        "test_size": int(len(y_test)),
        "accuracy": acc,
        "roc_auc": roc_auc,
        "macro_f1": macro_f1,
        "lift_mae": lift_mae,
        "report": report,
        "passed": passed,
    }


# ---------------------------------------------------------------------------
# Phase 9.2 — SnoozeDisengagementClassifier
# ---------------------------------------------------------------------------
def evaluate_snooze_disengagement_model() -> dict:
    """
    Evaluate the SnoozeDisengagementClassifier TFLite model (Phase 9.2).

    Reproduces the same 80/20 stratified split (SEED=42) used in
    train_snooze_disengagement_model.py and reports accuracy, ROC-AUC, and
    Macro F1 on the held-out test set.
    Acceptance gate: Macro F1 >= 0.75.
    """
    print("\n=== Phase 9.2 \u2014 SnoozeDisengagementClassifier ===")

    csv_path = gen_snooze_disengagement.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing \u2014 run generate_snooze_disengagement_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_snooze_disengagement.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    # Stratified 80/20 split \u2014 identical to train_snooze_disengagement_model.py.
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads(
        (MODELS_DIR / "snooze_disengagement_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw = _tflite_predict(
        MODELS_DIR / "snooze_disengagement_classifier.tflite",
        x_test_scaled,
    )
    y_prob = raw.ravel()
    y_pred = (y_prob >= 0.5).astype(np.int32)

    acc = float(np.mean(y_pred == y_test))
    roc_auc = float(roc_auc_score(y_test, y_prob))
    macro_f1 = float(f1_score(y_test, y_pred, average="macro"))

    passed = "PASS" if macro_f1 >= 0.75 else "FAIL"
    print(f"Test accuracy : {acc:.4f}")
    print(f"ROC-AUC       : {roc_auc:.4f}")
    print(f"Macro F1      : {macro_f1:.4f}  (threshold >= 0.75 to pass)")
    print(f"Acceptance    : {passed}")

    # Confusion matrix.
    cm = confusion_matrix(y_test, y_pred)
    out_path = PLOTS_DIR / "confusion_snooze_disengagement.png"
    fig, ax = plt.subplots(figsize=(4, 4))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks([0, 1]); ax.set_xticklabels(["Engaged", "Disengaged"])
    ax.set_yticks([0, 1]); ax.set_yticklabels(["Engaged", "Disengaged"])
    ax.set_xlabel("Predicted"); ax.set_ylabel("Actual")
    ax.set_title("Phase 9.2 \u2014 SnoozeDisengagementClassifier")
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, ax=ax)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"  Confusion matrix saved \u2192 {out_path}")

    report = classification_report(
        y_test, y_pred, target_names=["Engaged (0)", "Disengaged (1)"]
    )

    return {
        "name": "SnoozeDisengagementClassifier",
        "task": "binary classification",
        "test_size": int(len(y_test)),
        "accuracy": acc,
        "roc_auc": roc_auc,
        "macro_f1": macro_f1,
        "report": report,
        "passed": passed,
    }


# ---------------------------------------------------------------------------
# Phase 9.3 — TargetChangeRegressor
# Regression: predicts the optimal target delta ∈ [-2, +2].
# Acceptance criterion: MAE ≤ 0.50 on the rounded-integer delta.
# ---------------------------------------------------------------------------
def evaluate_target_change_model() -> dict:
    """
    Evaluate the TargetChangeRegressor TFLite model (Phase 9.3).

    Reproduces the same 80/20 random split (SEED=42, no stratification because
    the label is continuous) used in train_target_change_model.py and reports:
      - MAE and RMSE on the raw continuous prediction
      - MAE on the rounded-to-int delta prediction
      - A confusion matrix of rounded_pred vs rounded_true (5-class: -2…+2)
      - An actual-vs-predicted scatter plot saved to data/plots/

    Acceptance gate: rounded-delta MAE ≤ 0.50.
    """
    print("\n=== Phase 9.3 — TargetChangeRegressor ===")

    csv_path = gen_target_change.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run generate_target_change_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_target_change.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["ideal_delta"].to_numpy(dtype=np.float32)

    # 80/20 split — no stratification (continuous regression target).
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED
    )

    scaler = json.loads(
        (MODELS_DIR / "target_change_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw_preds = _tflite_predict(
        MODELS_DIR / "target_change_regressor.tflite",
        x_test_scaled,
    ).ravel()  # continuous ∈ [-2, 2]

    # Round to nearest integer delta, clamp to [-2, 2].
    y_pred_rounded = np.clip(np.round(raw_preds).astype(np.int32), -2, 2)
    y_true_rounded = np.clip(np.round(y_test).astype(np.int32), -2, 2)

    mae_raw = float(np.mean(np.abs(raw_preds - y_test)))
    rmse_raw = float(np.sqrt(np.mean((raw_preds - y_test) ** 2)))
    mae_rounded = float(np.mean(np.abs(y_pred_rounded - y_true_rounded)))

    passed = "PASS" if mae_rounded <= 0.50 else "FAIL"
    print(f"MAE  (raw continuous) : {mae_raw:.4f}")
    print(f"RMSE (raw continuous) : {rmse_raw:.4f}")
    print(f"MAE  (rounded delta)  : {mae_rounded:.4f}  (threshold <= 0.50 to pass)")
    print(f"Acceptance            : {passed}")

    # ----- Confusion matrix of rounded delta (-2 … +2) -----
    class_names = ["-2", "-1", "0", "+1", "+2"]
    labels = [-2, -1, 0, 1, 2]
    cm = confusion_matrix(y_true_rounded, y_pred_rounded, labels=labels)
    _plot_confusion_matrix(
        cm,
        class_names=class_names,
        title="Phase 9.3 — TargetChangeRegressor — Rounded-Delta Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_target_change.png",
    )
    print(f"  Confusion matrix saved → {PLOTS_DIR / 'confusion_target_change.png'}")

    # ----- Actual vs predicted scatter -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, raw_preds, alpha=0.3, s=8, label="Test samples")
    lims = [-2.2, 2.2]
    ax.plot(lims, lims, linestyle="--", color="gray", label="Perfect prediction")
    ax.set_xlim(lims); ax.set_ylim(lims)
    ax.set_xlabel("Actual delta")
    ax.set_ylabel("Predicted delta (raw)")
    ax.set_title("Phase 9.3 — TargetChangeRegressor — Actual vs Predicted")
    ax.legend(loc="upper left")
    fig.tight_layout()
    scatter_path = PLOTS_DIR / "scatter_target_change.png"
    fig.savefig(scatter_path, dpi=150)
    plt.close(fig)
    print(f"  Scatter plot saved → {scatter_path}")

    return {
        "name": "TargetChangeRegressor",
        "task": "regression (delta ∈ [-2,+2])",
        "test_size": int(len(y_test)),
        "mae_raw": mae_raw,
        "rmse_raw": rmse_raw,
        "mae_rounded": mae_rounded,
        "passed": passed,
    }


# ---------------------------------------------------------------------------
# Phase 9.4 — PerceivedDifficultyRegressor
# Regression: predicts perceived difficulty ∈ [1, 5] for a completion session.
# Acceptance criterion: MAE ≤ 0.55 on the held-out test split.
# ---------------------------------------------------------------------------
def evaluate_difficulty_model() -> dict:
    """
    Evaluate the PerceivedDifficultyRegressor TFLite model (Phase 9.4).

    Reproduces the same 80/20 random split (SEED=42, no stratification because
    the label is continuous) used in train_difficulty_model.py and reports:
      - MAE and RMSE on the raw continuous prediction
      - Per-bucket distribution of predicted vs actual difficulty (1–5)
      - An actual-vs-predicted scatter plot saved to data/plots/

    Acceptance gate: MAE ≤ 0.55.
    """
    print("\n=== Phase 9.4 — PerceivedDifficultyRegressor ===")

    csv_path = gen_difficulty.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run generate_difficulty_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_difficulty.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["perceived_difficulty"].to_numpy(dtype=np.float32)

    # 80/20 split — no stratification (continuous regression label).
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED
    )

    scaler = json.loads(
        (MODELS_DIR / "perceived_difficulty_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw_preds = _tflite_predict(
        MODELS_DIR / "perceived_difficulty_regressor.tflite",
        x_test_scaled,
    ).ravel()  # continuous ∈ [1, 5]

    mae = float(np.mean(np.abs(raw_preds - y_test)))
    rmse = float(np.sqrt(np.mean((raw_preds - y_test) ** 2)))

    # Naive baseline: always predict midpoint 3.0.
    naive_mae = float(np.mean(np.abs(np.full_like(y_test, 3.0) - y_test)))

    passed = "PASS" if mae <= 0.55 else "FAIL"
    print(f"Test MAE      : {mae:.4f}  (threshold <= 0.55 to pass)")
    print(f"Test RMSE     : {rmse:.4f}")
    print(f"Naive MAE     : {naive_mae:.4f}  (always predict 3.0)")
    print(f"MAE lift      : {naive_mae - mae:.4f}")
    print(f"Acceptance    : {passed}")

    # Per-bucket accuracy (round predictions to nearest integer 1–5).
    y_pred_rounded = np.clip(np.round(raw_preds).astype(np.int32), 1, 5)
    y_true_rounded = np.clip(np.round(y_test).astype(np.int32), 1, 5)
    exact_match = float((y_pred_rounded == y_true_rounded).mean())
    within_one = float((np.abs(y_pred_rounded - y_true_rounded) <= 1).mean())
    print(f"Exact bucket  : {exact_match:.1%}")
    print(f"Within ±1     : {within_one:.1%}")

    # ----- Confusion matrix of rounded buckets (1 … 5) -----
    bucket_labels = [1, 2, 3, 4, 5]
    bucket_names = ["1", "2", "3", "4", "5"]
    cm = confusion_matrix(y_true_rounded, y_pred_rounded, labels=bucket_labels)
    _plot_confusion_matrix(
        cm,
        class_names=bucket_names,
        title="Phase 9.4 — PerceivedDifficultyRegressor — Rounded-Bucket Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_difficulty.png",
    )
    print(f"  Confusion matrix saved → {PLOTS_DIR / 'confusion_difficulty.png'}")

    # ----- Actual vs predicted scatter -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, raw_preds, alpha=0.25, s=6, label="Test samples")
    ax.plot([1, 5], [1, 5], linestyle="--", color="gray", label="Perfect prediction")
    ax.set_xlim(0.8, 5.2)
    ax.set_ylim(0.8, 5.2)
    ax.set_xlabel("Actual perceived_difficulty")
    ax.set_ylabel("Predicted perceived_difficulty (raw)")
    ax.set_title("Phase 9.4 — PerceivedDifficultyRegressor — Actual vs Predicted")
    ax.legend(loc="upper left")
    fig.tight_layout()
    scatter_path = PLOTS_DIR / "scatter_difficulty.png"
    fig.savefig(scatter_path, dpi=150)
    plt.close(fig)
    print(f"  Scatter plot saved → {scatter_path}")

    return {
        "name": "PerceivedDifficultyRegressor",
        "task": "regression (difficulty ∈ [1,5])",
        "test_size": int(len(y_test)),
        "mae": mae,
        "rmse": rmse,
        "naive_mae": naive_mae,
        "exact_match": exact_match,
        "within_one": within_one,
        "passed": passed,
    }


# ---------------------------------------------------------------------------
# Phase 9.5 — SkipReasonClassifier
# 6-class softmax classifier predicting the most likely skip reason given
# 8 behavioral context features. Noise classes (SICK, TRAVELING) are rare by
# design — Macro F1 ≥ 0.35 is the acceptance threshold.
# ---------------------------------------------------------------------------
def evaluate_skip_reason_model() -> dict:
    """
    Evaluate the SkipReasonClassifier TFLite model (Phase 9.5).

    Reproduces the same stratified 80/20 split (SEED=42) used in
    train_skip_reason_model.py and reports:
      - Test accuracy
      - Macro F1 (acceptance gate: ≥ 0.35)
      - Per-class precision, recall, F1 via sklearn classification_report
      - 6×6 confusion matrix saved to data/plots/

    SICK and TRAVELING are intentionally rare (noise classes); Macro F1 is
    expected to be lower than binary/few-class models — this is documented
    in the thesis as an observational caveats section.
    """
    print("\n=== Phase 9.5 — SkipReasonClassifier ===")

    csv_path = HERE / "data" / "skip_reason_dataset.csv"
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} missing — run generate_skip_reason_data.py first."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_skip_reason.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    # Same split as training (SEED=42, stratified).
    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads(
        (MODELS_DIR / "skip_reason_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    # Raw softmax output shape: (n_test, 6).
    probs = _tflite_predict(
        MODELS_DIR / "skip_reason_classifier.tflite",
        x_test_scaled,
    )
    y_pred = probs.argmax(axis=1).astype(np.int32)

    accuracy = float((y_pred == y_test).mean())
    macro_f1 = float(f1_score(y_test, y_pred, average="macro", zero_division=0))
    passed = "PASS" if macro_f1 >= 0.35 else "FAIL"

    print(f"Test accuracy : {accuracy:.4f}")
    print(f"Macro F1      : {macro_f1:.4f}  (threshold >= 0.35, {passed})")

    class_names = gen_skip_reason.CLASS_LABELS
    report = classification_report(
        y_test,
        y_pred,
        target_names=class_names,
        zero_division=0,
    )
    print("\nPer-class classification report:")
    print(report)

    # ----- Confusion matrix -----
    cm = confusion_matrix(y_test, y_pred, labels=list(range(len(class_names))))
    _plot_confusion_matrix(
        cm,
        class_names=class_names,
        title="Phase 9.5 — SkipReasonClassifier — Confusion Matrix",
        out_path=PLOTS_DIR / "confusion_skip_reason.png",
    )
    print(f"  Confusion matrix saved → {PLOTS_DIR / 'confusion_skip_reason.png'}")

    return {
        "name": "SkipReasonClassifier",
        "task": "6-class classification",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "passed": passed,
        "report": report,
    }


# ---------------------------------------------------------------------------
# Markdown summary (thesis-ready table).
# ---------------------------------------------------------------------------
def write_summary(results: list) -> Path:
    success, icon, reminder, abandonment, streak_break, weekly_forecast, spillover, reminder_lift, snooze_disengagement, target_change, difficulty, skip_reason = results

    lines = [
        "# Thesis ML Evaluation Summary",
        "",
        "_Generated by `ml-training/evaluate_models.py` (Phase 6.5.5)._",
        "",
        "All numbers are computed on the held-out 20% test split "
        "(stratified, SEED=42) using the EXPORTED `.tflite` artifacts plus "
        "the saved scaler / vocab JSON files — i.e. exactly what ships into "
        "`app/src/main/assets/`.",
        "",
        "## Headline metrics",
        "",
        "| Model | Task | Test rows | Primary metric | Value | Secondary metric | Value |",
        "|---|---|---:|---|---:|---|---:|",
        f"| {success['name']} | {success['task']} | {success['test_size']} | "
        f"Accuracy | {success['accuracy']:.4f} | ROC-AUC | {success['roc_auc']:.4f} |",
        f"| {icon['name']} | {icon['task']} | {icon['test_size']} | "
        f"Top-1 accuracy | {icon['top1']:.4f} | Top-3 accuracy | {icon['top3']:.4f} |",
        f"| {reminder['name']} | {reminder['task']} | {reminder['test_size']} | "
        f"Top-1 accuracy | {reminder['top1']:.4f} | — | — |",
        f"| {weekly_forecast['name']} | {weekly_forecast['task']} | {weekly_forecast['test_size']} | "
        f"MAE | {weekly_forecast['mae']:.4f} | RMSE | {weekly_forecast['rmse']:.4f} |",
        f"| {spillover['name']} | {spillover['task']} | {spillover['test_size']} | "
        f"MAE | {spillover['mae']:.4f} | R\u00b2 | {spillover['r2']:.4f} |",
        f"| {reminder_lift['name']} | {reminder_lift['task']} | {reminder_lift['test_size']} | "
        f"Macro F1 | {reminder_lift['macro_f1']:.4f} | ROC-AUC | {reminder_lift['roc_auc']:.4f} |",
        f"| {snooze_disengagement['name']} | {snooze_disengagement['task']} | {snooze_disengagement['test_size']} | "
        f"Macro F1 | {snooze_disengagement['macro_f1']:.4f} | ROC-AUC | {snooze_disengagement['roc_auc']:.4f} |",
        f"| {target_change['name']} | {target_change['task']} | {target_change['test_size']} | "
        f"MAE (rounded) | {target_change['mae_rounded']:.4f} | RMSE (raw) | {target_change['rmse_raw']:.4f} |",
        f"| {difficulty['name']} | {difficulty['task']} | {difficulty['test_size']} | "
        f"MAE | {difficulty['mae']:.4f} | RMSE | {difficulty['rmse']:.4f} |",
        f"| {skip_reason['name']} | {skip_reason['task']} | {skip_reason['test_size']} | "
        f"Accuracy | {skip_reason['accuracy']:.4f} | Macro F1 | {skip_reason['macro_f1']:.4f} |",
        "",
        "## Generated plots",
        "",
        "All PNGs are stored under `ml-training/data/plots/`:",
        "",
        "- `confusion_success.png` — Model 1 confusion matrix",
        "- `roc_success.png` — Model 1 ROC curve",
        "- `calibration_success.png` — Model 1 reliability diagram",
        "- `confusion_icon.png` — Model 2 confusion matrix (17 classes)",
        "- `confusion_reminder.png` — Model 3 confusion matrix (15 classes)",
        "- `scatter_weekly_forecast.png` — Model 6 actual vs predicted scatter",
        "- `scatter_spillover.png` — Phase 8.5 SpilloverRegressor actual vs predicted scatter",
        "- `confusion_reminder_lift.png` — Phase 9.1 ReminderLiftClassifier confusion matrix",
        "- `confusion_snooze_disengagement.png` — Phase 9.2 SnoozeDisengagementClassifier confusion matrix",
        "- `confusion_target_change.png` — Phase 9.3 TargetChangeRegressor rounded-delta confusion matrix",
        "- `scatter_target_change.png` — Phase 9.3 TargetChangeRegressor actual vs predicted scatter",
        "- `confusion_difficulty.png` — Phase 9.4 PerceivedDifficultyRegressor rounded-bucket confusion matrix",
        "- `scatter_difficulty.png` — Phase 9.4 PerceivedDifficultyRegressor actual vs predicted scatter",
        "- `confusion_skip_reason.png` — Phase 9.5 SkipReasonClassifier 6-class confusion matrix",
        "",
        "## Model 2 — per-class classification report",
        "",
        "```",
        icon["report"].rstrip(),
        "```",
        "",
        "## Model 3 — per-class classification report",
        "",
        "```",
        reminder["report"].rstrip(),
        "```",
        "",
        "## Phase 9.1 \u2014 ReminderLiftClassifier classification report",
        "",
        "```",
        reminder_lift["report"].rstrip(),
        "```",
        "",
        "## Phase 9.2 \u2014 SnoozeDisengagementClassifier classification report",
        "",
        "```",
        snooze_disengagement["report"].rstrip(),
        "```",
        "",
        "## Phase 9.5 \u2014 SkipReasonClassifier per-class classification report",
        "",
        f"Acceptance gate: Macro F1 \u2265 0.35 — **{skip_reason['passed']}** "
        f"({skip_reason['macro_f1']:.4f}). SICK and TRAVELING are intentionally "
        "rare noise classes; their low recall is expected and documented.",
        "",
        "```",
        skip_reason["report"].rstrip(),
        "```",
        "",
    ]

    out = PLOTS_DIR / "metrics_summary.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    return out


def main() -> None:
    _ensure_plots_dir()
    results = [
        evaluate_success_model(),
        evaluate_icon_model(),
        evaluate_reminder_model(),
        evaluate_abandonment_model(),
        evaluate_streak_break_model(),
        evaluate_weekly_forecast_model(),
        evaluate_spillover_model(),
        evaluate_reminder_lift_model(),
        evaluate_snooze_disengagement_model(),
        evaluate_target_change_model(),
        evaluate_difficulty_model(),
        evaluate_skip_reason_model(),
    ]
    evaluate_clustering_model()
    summary_path = write_summary(results)
    print(f"\nWrote thesis summary: {summary_path}")
    print(f"Plots directory     : {PLOTS_DIR}")


if __name__ == "__main__":
    main()
