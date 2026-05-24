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
    top_k_accuracy_score,
)
from sklearn.model_selection import train_test_split  # noqa: E402

# Reuse the three generator modules so column orders / label orders cannot
# drift between training and evaluation.
import generate_abandonment_data as gen_abandonment  # noqa: E402
import generate_icon_data as gen_icon  # noqa: E402
import generate_reminder_data as gen_reminder  # noqa: E402
import generate_streak_break_data as gen_streak_break  # noqa: E402
import generate_success_data as gen_success  # noqa: E402
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
# Markdown summary (thesis-ready table).
# ---------------------------------------------------------------------------
def write_summary(results: list) -> Path:
    success, icon, reminder, abandonment = results

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
    ]
    summary_path = write_summary(results)
    print(f"\nWrote thesis summary: {summary_path}")
    print(f"Plots directory     : {PLOTS_DIR}")


if __name__ == "__main__":
    main()
