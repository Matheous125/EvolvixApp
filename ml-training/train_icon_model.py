"""
train_icon_model.py — Train + export Model 2 (HabitIconClassifier).

Phase 6.5.3 of PLAN.md. Pipeline:

    1. Load (or auto-generate) ml-training/data/icon_dataset.csv.
    2. 80/20 stratified train/test split via sklearn.
    3. Convert each habit name into a space-separated string of CHARACTER
       n-grams (sizes 2 and 3). Word boundaries are marked with the sentinel
       char "_" so the model can learn prefix / suffix signals.
       *This preprocessing is intentionally pure Python so the Android side
       (TfliteHabitPredictor.classifyIcon) can replicate the EXACT tokens.*
    4. Fit a Keras `TextVectorization` (whitespace split, output_mode='tf_idf',
       max_tokens=2000) on the n-gram strings to learn:
           - vocabulary    (the 2,000 most-frequent n-grams)
           - IDF weights   (one per vocab entry)
       Both are serialized to models/icon_vocab.json so Android can rebuild
       the same TF-IDF vector at inference time.
    5. Train the classifier head (PLAN.md §6.5.3):
           Dense(32, relu) -> Dense(17, softmax)
       with sparse_categorical_crossentropy for 30 epochs.
    6. Evaluate top-1 and top-3 accuracy on the held-out test set.
       Acceptance thresholds (PLAN.md §6.5.3):
           top-1 >= 0.75   AND   top-3 >= 0.92
    7. Export the Dense-head model to habit_icon_classifier.tflite.
       (TextVectorization stays OUT of the TFLite graph — Android replicates
       it from icon_vocab.json. Keeps the TFLite graph small and predictable.)

Outputs:
    ml-training/models/habit_icon_classifier.tflite
    ml-training/models/icon_vocab.json

Usage:
    python train_icon_model.py
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
from sklearn.metrics import top_k_accuracy_score  # noqa: E402
from sklearn.model_selection import train_test_split  # noqa: E402

import generate_icon_data as gen  # noqa: E402


# ---------------------------------------------------------------------------
# Reproducibility (seeds Python / NumPy / TF — required for thesis runs).
# ---------------------------------------------------------------------------
SEED = 42
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

# ---------------------------------------------------------------------------
# Acceptance thresholds — sourced verbatim from PLAN.md §6.5.3.
# ---------------------------------------------------------------------------
MIN_TOP1 = 0.75
MIN_TOP3 = 0.92

# ---------------------------------------------------------------------------
# Hyperparameters from PLAN.md §6.5.3.
# ---------------------------------------------------------------------------
MAX_TOKENS = 2000   # max vocabulary size for TextVectorization
NGRAM_SIZES = (2, 3)  # char n-gram sizes — PLAN.md specifies char n-grams
EPOCHS = 30
BATCH_SIZE = 64


def _models_dir() -> Path:
    here = Path(__file__).resolve().parent
    out = here / "models"
    out.mkdir(parents=True, exist_ok=True)
    return out


def _load_or_generate() -> pd.DataFrame:
    """Use existing CSV if present; otherwise regenerate from seeds."""
    csv_path = gen.output_path()
    if not csv_path.exists():
        print("Dataset missing — regenerating via generate_icon_data.generate().")
        pairs = gen.generate(seed=SEED)
        csv_path.parent.mkdir(parents=True, exist_ok=True)
        pd.DataFrame(pairs, columns=["name", "label"]).to_csv(csv_path, index=False)
    return pd.read_csv(csv_path)


def name_to_ngram_string(name: str, sizes: tuple[int, ...] = NGRAM_SIZES) -> str:
    """Convert a habit name to a space-separated string of character n-grams.

    Steps (this exact procedure must be mirrored in Kotlin for the Android
    inference path — see TfliteHabitPredictor.classifyIcon):
        1. Lowercase.
        2. Keep only [a-z0-9] and whitespace; drop everything else.
        3. Collapse multiple whitespaces; split into words.
        4. Surround each word with the sentinel "_" so n-grams that span the
           word boundary become explicit prefix / suffix features.
        5. Emit every n-gram of each requested size as a separate token.
        6. Join with single spaces.

    Returning a flat string lets the Keras TextVectorization layer treat each
    n-gram as a "word" and learn TF-IDF weights directly.
    """
    # Step 1+2: lowercase + alphanumeric/whitespace whitelist.
    cleaned_chars = []
    for ch in name.lower():
        if ch.isalnum() or ch.isspace():
            cleaned_chars.append(ch)
    cleaned = "".join(cleaned_chars)

    # Step 3: tokenize into words.
    words = cleaned.split()

    # Steps 4+5: surround with "_" and slide an n-gram window.
    ngrams: list[str] = []
    for word in words:
        padded = f"_{word}_"
        for n in sizes:
            if len(padded) < n:
                continue
            for i in range(len(padded) - n + 1):
                ngrams.append(padded[i : i + n])

    return " ".join(ngrams)


def _build_vectorizer(corpus: list[str]) -> tf.keras.layers.TextVectorization:
    """Adapt a TextVectorization layer to the n-gram corpus.

    `output_mode='tf_idf'` produces a (max_tokens,) float vector per example,
    where vector[i] = count(vocab[i] in text) * idf[i]. This matches how the
    Android side will reconstruct the input feature vector from the JSON
    vocab + idf table — no further math discrepancies.
    """
    layer = tf.keras.layers.TextVectorization(
        max_tokens=MAX_TOKENS,
        standardize=None,       # we already pre-cleaned in name_to_ngram_string
        split="whitespace",
        output_mode="tf_idf",
    )
    layer.adapt(tf.constant(corpus))
    return layer


def _build_classifier(input_dim: int, num_classes: int) -> tf.keras.Model:
    """Dense head per PLAN.md §6.5.3."""
    model = tf.keras.Sequential(
        [
            tf.keras.layers.Input(shape=(input_dim,), name="tfidf_vector"),
            tf.keras.layers.Dense(32, activation="relu"),
            tf.keras.layers.Dense(num_classes, activation="softmax", name="probs"),
        ],
        name="HabitIconClassifier",
    )
    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    return model


def _save_vocab(vectorizer: tf.keras.layers.TextVectorization,
                labels: list[str]) -> Path:
    """Persist vocab + IDF + labels + n-gram sizes for Android to replicate.

    Android-side procedure to recompute a TF-IDF vector:
        vocab_index = { vocab[i] : i for i in range(len(vocab)) }
        vector = FloatArray(len(vocab))      // zero-initialized
        for token in name_to_ngrams(name, ngram_sizes):
            i = vocab_index[token] ?: continue
            vector[i] += idf_weights[i]
    """
    vocab = vectorizer.get_vocabulary()
    # In tf_idf mode, get_weights() returns [idf_weights_array] — a numpy
    # array of shape (vocab_size,). The public `idf_weights` attribute was
    # renamed across TF versions, so we use the stable get_weights() API.
    weights = vectorizer.get_weights()
    idf = weights[0].astype(float).tolist() if weights else [1.0] * len(vocab)

    payload = {
        "vocabulary": list(vocab),
        "idf_weights": idf,
        "labels": labels,
        "ngram_sizes": list(NGRAM_SIZES),
        "max_tokens": MAX_TOKENS,
    }
    out = _models_dir() / "icon_vocab.json"
    out.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return out


def _export_tflite(model: tf.keras.Model) -> Path:
    """Quantize + export TFLite (Optimize.DEFAULT, matches Model 1's pipeline)."""
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_bytes = converter.convert()
    out = _models_dir() / "habit_icon_classifier.tflite"
    out.write_bytes(tflite_bytes)
    return out


def main() -> None:
    # --- Load / regenerate dataset ----------------------------------------
    df = _load_or_generate()
    print(f"Loaded {len(df):,} (name,label) rows.")

    # --- Encode labels (string -> int index) ------------------------------
    labels: list[str] = gen.LABELS
    label_to_idx = {lbl: i for i, lbl in enumerate(labels)}
    y = df["label"].map(label_to_idx).to_numpy(dtype=np.int32)

    # --- Pre-tokenize names into n-gram strings ---------------------------
    ngram_corpus = [name_to_ngram_string(name) for name in df["name"].astype(str)]

    # --- 80/20 stratified split -------------------------------------------
    x_train, x_test, y_train, y_test = train_test_split(
        ngram_corpus, y, test_size=0.2, random_state=SEED, stratify=y
    )

    # --- Fit TextVectorization on training corpus only --------------------
    # Fitting only on training data prevents leakage of test-set vocabulary
    # statistics into the IDF table — the same hygiene rule we apply for the
    # StandardScaler in Model 1.
    vectorizer = _build_vectorizer(x_train)
    vocab_size = len(vectorizer.get_vocabulary())
    print(f"Learned vocabulary size: {vocab_size:,} (cap = {MAX_TOKENS:,})")

    # Materialize the dense TF-IDF feature matrices.
    x_train_vec = vectorizer(tf.constant(x_train)).numpy().astype(np.float32)
    x_test_vec = vectorizer(tf.constant(x_test)).numpy().astype(np.float32)

    # --- Train classifier head --------------------------------------------
    model = _build_classifier(input_dim=x_train_vec.shape[1], num_classes=len(labels))
    early_stop = tf.keras.callbacks.EarlyStopping(
        monitor="val_accuracy", patience=6, mode="max", restore_best_weights=True
    )
    model.fit(
        x_train_vec, y_train,
        epochs=EPOCHS,
        batch_size=BATCH_SIZE,
        validation_split=0.1,
        callbacks=[early_stop],
        verbose=2,
    )

    # --- Evaluate top-1 and top-3 accuracy on the test set ----------------
    probs = model.predict(x_test_vec, verbose=0)
    top1 = float((probs.argmax(axis=1) == y_test).mean())
    top3 = float(top_k_accuracy_score(y_test, probs, k=3, labels=list(range(len(labels)))))
    print(f"Test top-1 accuracy: {top1:.4f}")
    print(f"Test top-3 accuracy: {top3:.4f}")

    if top1 < MIN_TOP1 or top3 < MIN_TOP3:
        raise SystemExit(
            f"FAILED acceptance threshold (need top1>={MIN_TOP1}, top3>={MIN_TOP3}). "
            f"Got top1={top1:.4f}, top3={top3:.4f}. "
            "Add more seed phrases per category in generate_icon_data.SEEDS "
            "or widen augmentation templates before retrying."
        )

    # --- Persist artifacts ------------------------------------------------
    vocab_path = _save_vocab(vectorizer, labels)
    tflite_path = _export_tflite(model)

    print()
    print("=" * 60)
    print("SUCCESS — Model 2 (HabitIconClassifier) ready.")
    print(f"  test top-1     : {top1:.4f}")
    print(f"  test top-3     : {top3:.4f}")
    print(f"  vocab JSON     : {vocab_path}")
    print(f"  TFLite model   : {tflite_path}")
    print("Next: copy both files into app/src/main/assets/ (see README).")
    print("=" * 60)


if __name__ == "__main__":
    main()
