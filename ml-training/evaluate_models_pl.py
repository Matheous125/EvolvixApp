"""
evaluate_models_pl.py — Raport ewaluacji modeli uczenia maszynowego (wersja polska).

Polska wersja skryptu evaluate_models.py, przygotowana na potrzeby pracy inżynierskiej.
Wszystkie komunikaty konsolowe, wykresy oraz raporty generowane są w języku polskim.
Wykresy zapisywane są do oddzielnego katalogu ml-training/data/plots_pl/, co zapobiega
nadpisaniu wersji anglojęzycznej.

Skrypt obejmuje pełną ewaluację wszystkich 14 modeli uczenia maszynowego:
  Model 1  — HabitSuccessClassifier          (klasyfikacja binarna)
  Model 2  — HabitIconClassifier             (klasyfikacja wieloklasowa, 17 klas)
  Model 3  — ReminderTemplateClassifier      (klasyfikacja wieloklasowa, 15 klas)
  Model 4  — HabitAbandonmentClassifier      (klasyfikacja binarna)
  Model 5  — StreakBreakClassifier           (klasyfikacja binarna)
  Model 6  — WeeklyForecastRegressor         (regresja)
  Faza 8.4 — KMeansBehavioralClustering      (grupowanie)
  Faza 8.5 — SpilloverRegressor              (regresja)
  Faza 9.1 — ReminderLiftClassifier          (klasyfikacja binarna)
  Faza 9.2 — SnoozeDisengagementClassifier   (klasyfikacja binarna)
  Faza 9.3 — TargetChangeRegressor           (regresja)
  Faza 9.4 — PerceivedDifficultyRegressor    (regresja)
  Faza 9.5 — SkipReasonClassifier            (klasyfikacja wieloklasowa, 6 klas)
  Faza 9.6 — EngagementWindowRegressor       (regresja)

Dla każdego modelu klasyfikacyjnego generowany jest pełny raport klasyfikacji
(precyzja / czułość / miara F1 / wsparcie) w układzie tabelarycznym.
Dla każdego modelu regresyjnego generowany jest szczegółowy raport regresji
(MAE / RMSE / R² / statystyki rozkładu błędu).

Wyniki zbiorcze zapisywane są do:
    ml-training/data/plots_pl/podsumowanie_metryk.md

Użycie:
    python evaluate_models_pl.py
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path
from typing import Sequence

# Wymuszenie kodowania UTF-8 na konsoli Windows (strony kodowe cp850 / cp1252
# nie obsługują polskich znaków diakrytycznych wymaganych przez raporty).
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import numpy as np
import pandas as pd

os.environ.setdefault("TF_CPP_MIN_LOG_LEVEL", "2")

import matplotlib
import matplotlib.pyplot as plt  # noqa: E402

# DejaVu Sans obsługuje pełen zestaw znaków Latin Extended (ą, ę, ó, ś, ź, ż, ć, ł, ń).
matplotlib.rcParams["font.family"] = "DejaVu Sans"

import tensorflow as tf  # noqa: E402
from sklearn.metrics import (  # noqa: E402
    classification_report,
    confusion_matrix,
    f1_score,
    roc_auc_score,
    roc_curve,
    silhouette_score,
    top_k_accuracy_score,
    precision_recall_curve,
    average_precision_score,
)
from sklearn.decomposition import PCA  # noqa: E402
from sklearn.model_selection import train_test_split  # noqa: E402

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
import generate_engagement_window_data as gen_engagement_window  # noqa: E402
from train_icon_model import name_to_ngram_string  # noqa: E402

# ---------------------------------------------------------------------------
# Ścieżki i parametr reprodukowalności (identyczny z train_* scripts).
# ---------------------------------------------------------------------------
SEED = 42
HERE = Path(__file__).resolve().parent
MODELS_DIR = HERE / "models"
PLOTS_DIR_PL = HERE / "data" / "plots_pl"

# Polskie nazwy wyświetlane dla klastrów K-Means.
NAZWY_KLASTROW_PL: dict[str, str] = {
    "effortless_routine": "efektywna rutyna",
    "consistent_effort":  "konsekwentny wysiłek",
    "struggling":         "trudności",
    "dormant":            "uśpiony",
}


def _ensure_plots_dir() -> Path:
    """Tworzy katalog wyjściowy dla wykresów polskich, jeśli nie istnieje."""
    PLOTS_DIR_PL.mkdir(parents=True, exist_ok=True)
    return PLOTS_DIR_PL


# ---------------------------------------------------------------------------
# Pomocnicza funkcja uruchamiająca interpreter TFLite na zbiorze danych.
# ---------------------------------------------------------------------------
def _tflite_predict(model_path: Path, x: np.ndarray) -> np.ndarray:
    """Zwraca surowe wyjście modelu dla każdego wiersza macierzy x. Kształt: (n, wymiar_wyjść)."""
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
# Pomocnicza funkcja rysowania macierzy pomyłek.
# ---------------------------------------------------------------------------
def _plot_macierz_pomylek(
    cm: np.ndarray,
    nazwy_klas: Sequence[str],
    tytul: str,
    sciezka: Path,
) -> None:
    """
    Rysuje i zapisuje macierz pomyłek z opisami w języku polskim.

    Adnotacje liczbowe pomijane są przy macierzach powyżej 20 klas,
    aby uniknąć nakładania się tekstu — mapa kolorów jest wówczas wystarczająca.
    """
    fig, ax = plt.subplots(
        figsize=(max(6, len(nazwy_klas) * 0.55), max(5, len(nazwy_klas) * 0.5))
    )
    im = ax.imshow(cm, cmap="Blues")
    ax.set_title(tytul)
    ax.set_xlabel("Klasa predykowana")
    ax.set_ylabel("Klasa rzeczywista")
    ax.set_xticks(range(len(nazwy_klas)))
    ax.set_yticks(range(len(nazwy_klas)))
    ax.set_xticklabels(nazwy_klas, rotation=45, ha="right", fontsize=8)
    ax.set_yticklabels(nazwy_klas, fontsize=8)

    if len(nazwy_klas) <= 20:
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
    fig.savefig(sciezka, dpi=150)
    plt.close(fig)


# ---------------------------------------------------------------------------
# Pomocnicza funkcja generująca raport klasyfikacji w języku polskim.
# ---------------------------------------------------------------------------
def _raport_klasyfikacji_pl(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    nazwy_klas: list[str] | None = None,
    digits: int = 3,
    zero_division: int = 0,
) -> str:
    """
    Generuje tabelaryczny raport klasyfikacji z nagłówkami w języku polskim.

    Odpowiada funkcji sklearn.metrics.classification_report, lecz stosuje
    polskie nazwy kolumn: precyzja, czułość, miara F1, wsparcie.
    """
    rep = classification_report(
        y_true, y_pred,
        target_names=nazwy_klas,
        output_dict=True,
        digits=digits,
        zero_division=zero_division,
    )

    klasy = nazwy_klas if nazwy_klas else [
        k for k in rep if k not in ("accuracy", "macro avg", "weighted avg")
    ]
    wszystkie_etykiety = list(klasy) + ["dokładność", "makro śr.", "ważona śr."]
    szer = max(len(e) for e in wszystkie_etykiety) + 2

    naglowek = (
        f"{'':>{szer}}  {'precyzja':>10}  {'czułość':>10}"
        f"  {'miara F1':>10}  {'wsparcie':>10}"
    )
    separator = "  " + "-" * (len(naglowek) - 2)
    wiersze = [naglowek, separator]

    for cls in klasy:
        if cls in rep:
            m = rep[cls]
            wiersze.append(
                f"{cls:>{szer}}  {m['precision']:>10.{digits}f}"
                f"  {m['recall']:>10.{digits}f}"
                f"  {m['f1-score']:>10.{digits}f}"
                f"  {int(m['support']):>10}"
            )

    wiersze.append("")

    if "accuracy" in rep:
        n_total = int(rep["macro avg"]["support"])
        wiersze.append(
            f"{'dokładność':>{szer}}  {'':>10}  {'':>10}"
            f"  {rep['accuracy']:>10.{digits}f}  {n_total:>10}"
        )
    if "macro avg" in rep:
        m = rep["macro avg"]
        wiersze.append(
            f"{'makro śr.':>{szer}}  {m['precision']:>10.{digits}f}"
            f"  {m['recall']:>10.{digits}f}"
            f"  {m['f1-score']:>10.{digits}f}"
            f"  {int(m['support']):>10}"
        )
    if "weighted avg" in rep:
        m = rep["weighted avg"]
        wiersze.append(
            f"{'ważona śr.':>{szer}}  {m['precision']:>10.{digits}f}"
            f"  {m['recall']:>10.{digits}f}"
            f"  {m['f1-score']:>10.{digits}f}"
            f"  {int(m['support']):>10}"
        )

    return "\n".join(wiersze)


# ---------------------------------------------------------------------------
# Pomocnicza funkcja generująca raport regresji w języku polskim.
# ---------------------------------------------------------------------------
def _raport_regresji_pl(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    nazwa_zmiennej: str = "wartość docelowa",
    digits: int = 4,
) -> str:
    """
    Generuje tabelaryczny raport ewaluacji modelu regresji w języku polskim.

    Zawiera: MAE, RMSE, współczynnik determinacji R², obciążenie (średni błąd
    ze znakiem), odchylenie standardowe błędu oraz kwantyle bezwzględnego błędu.
    """
    errors = y_pred - y_true
    abs_err = np.abs(errors)

    mae = float(np.mean(abs_err))
    rmse = float(np.sqrt(np.mean(errors ** 2)))
    ss_res = float(np.sum(errors ** 2))
    ss_tot = float(np.sum((y_true - float(y_true.mean())) ** 2))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    bias = float(np.mean(errors))
    std_err = float(np.std(errors))
    p25 = float(np.percentile(abs_err, 25))
    p50 = float(np.percentile(abs_err, 50))
    p75 = float(np.percentile(abs_err, 75))
    p90 = float(np.percentile(abs_err, 90))
    max_err = float(abs_err.max())

    w = 42  # szerokość pierwszej kolumny
    sep = "  " + "-" * 57
    wiersze = [
        f"  Raport ewaluacji regresji — {nazwa_zmiennej}",
        sep,
        f"  {'Miara':<{w}} {'Wartość':>12}",
        sep,
        f"  {'Średni bezwzględny błąd (MAE)':<{w}} {mae:>12.{digits}f}",
        f"  {'Pierwiastek błędu kwadr. (RMSE)':<{w}} {rmse:>12.{digits}f}",
        f"  {'Współczynnik determinacji R²':<{w}} {r2:>12.{digits}f}",
        f"  {'Obciążenie (średni błąd ze znakiem)':<{w}} {bias:>12.{digits}f}",
        f"  {'Odchylenie standardowe błędu':<{w}} {std_err:>12.{digits}f}",
        sep,
        f"  {'Kwantyle bezwzględnego błędu predykcji':<{w}}",
        f"  {'  Kwartyl dolny Q25':<{w}} {p25:>12.{digits}f}",
        f"  {'  Mediana Q50':<{w}} {p50:>12.{digits}f}",
        f"  {'  Kwartyl górny Q75':<{w}} {p75:>12.{digits}f}",
        f"  {'  Percentyl 90.':<{w}} {p90:>12.{digits}f}",
        f"  {'  Maksymalny błąd bezwzględny':<{w}} {max_err:>12.{digits}f}",
        sep,
    ]
    return "\n".join(wiersze)


# ---------------------------------------------------------------------------
# Pomocnicza funkcja wektora TF-IDF (wymagana przez Model 2).
# ---------------------------------------------------------------------------
def _tfidf_vector(
    name: str,
    vocab_index: dict,
    idf: np.ndarray,
    ngram_sizes: tuple,
) -> np.ndarray:
    """Buduje wektor cech TF-IDF dla podanej nazwy z zapisanego słownika."""
    vec = np.zeros(len(idf), dtype=np.float32)
    tokens = name_to_ngram_string(name, sizes=ngram_sizes).split()
    unk_index = vocab_index.get("[UNK]", 0)
    for token in tokens:
        i = vocab_index.get(token, unk_index)
        vec[i] += idf[i]
    return vec


# ---------------------------------------------------------------------------
# Model 1 — HabitSuccessClassifier
# ---------------------------------------------------------------------------
def evaluate_success_model() -> dict:
    """
    Ewaluacja modelu HabitSuccessClassifier (klasyfikacja binarna).

    Przewiduje, czy użytkownik wykona zaplanowany nawyk w danym dniu.
    Metryki główne: dokładność, pole pod krzywą ROC (AUC).
    Wykresy: macierz pomyłek, krzywa ROC, diagram kalibracji.
    """
    print("\n=== Model 1 — HabitSuccessClassifier ===")
    csv_path = gen_success.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw train_success_model.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_success.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

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
    macro_f1 = float(f1_score(y_test, y_pred, average="macro", zero_division=0))

    print(f"Dokładność na zbiorze testowym : {accuracy:.4f}")
    print(f"Pole pod krzywą ROC (AUC)      : {auc:.4f}")
    print(f"Makro miara F1                 : {macro_f1:.4f}")

    nazwy_klas_pl = ["porażka (0)", "sukces (1)"]
    raport_pl = _raport_klasyfikacji_pl(y_test, y_pred, nazwy_klas=nazwy_klas_pl)
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    # ----- Macierz pomyłek -----
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1])
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=nazwy_klas_pl,
        tytul="Model 1 — HabitSuccessClassifier — Macierz pomyłek",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_sukces.png",
    )

    # ----- Krzywa ROC -----
    fpr, tpr, _ = roc_curve(y_test, probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"Krzywa ROC (AUC = {auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Model losowy")
    ax.set_xlabel("Wskaźnik fałszywych alarmów (FPR)")
    ax.set_ylabel("Czułość — wskaźnik prawdziwych pozytywów (TPR)")
    ax.set_title("Model 1 — Krzywa ROC (HabitSuccessClassifier)")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "krzywa_roc_sukces.png", dpi=150)
    plt.close(fig)

    # ----- Diagram kalibracji (diagram niezawodności) -----
    # Przedziały szerokości 0,1 na osi prawdopodobieństwa predykowanego.
    # Idealna kalibracja odpowiada prostej y = x.
    bucket_idx = np.clip(np.digitize(probs, np.linspace(0.0, 1.0, 11)) - 1, 0, 9)
    mean_pred, empirical = [], []
    for b in range(10):
        mask = bucket_idx == b
        if mask.sum() == 0:
            continue
        mean_pred.append(float(probs[mask].mean()))
        empirical.append(float(y_test[mask].mean()))

    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Idealna kalibracja")
    ax.plot(mean_pred, empirical, marker="o", label="Model")
    ax.set_xlabel("Średnie prawdopodobieństwo predykowane")
    ax.set_ylabel("Empiryczny odsetek pozytywnych przypadków")
    ax.set_title("Model 1 — Diagram kalibracji (HabitSuccessClassifier)")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "kalibracja_sukces.png", dpi=150)
    plt.close(fig)

    return {
        "name": "HabitSuccessClassifier",
        "zadanie": "klasyfikacja binarna",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "roc_auc": auc,
        "macro_f1": macro_f1,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Model 2 — HabitIconClassifier
# ---------------------------------------------------------------------------
def evaluate_icon_model() -> dict:
    """
    Ewaluacja modelu HabitIconClassifier (klasyfikacja wieloklasowa, 17 klas).

    Replika potoku n-gram + TF-IDF z train_icon_model.py przy użyciu
    zapisanego słownika icon_vocab.json — bez ponownego uczenia.
    Metryki: dokładność Top-1, dokładność Top-3.
    """
    print("\n=== Model 2 — HabitIconClassifier ===")
    csv_path = gen_icon.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw train_icon_model.py."
        )

    df = pd.read_csv(csv_path)
    names = df["name"].astype(str).tolist()
    labels = df["label"].astype(str).tolist()

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

    indices = np.arange(len(names))
    _, idx_test, _, y_test = train_test_split(
        indices, y, test_size=0.2, random_state=SEED, stratify=y
    )
    names_test = [names[i] for i in idx_test]

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

    print(f"Dokładność Top-1 : {top1:.4f}")
    print(f"Dokładność Top-3 : {top3:.4f}")

    raport_pl = _raport_klasyfikacji_pl(
        y_test, y_pred,
        nazwy_klas=label_names,
        digits=3,
        zero_division=0,
    )
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    cm = confusion_matrix(y_test, y_pred, labels=list(range(len(label_names))))
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=label_names,
        tytul="Model 2 — HabitIconClassifier — Macierz pomyłek",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_ikona.png",
    )

    return {
        "name": "HabitIconClassifier",
        "zadanie": f"klasyfikacja wieloklasowa ({len(label_names)} klas)",
        "test_size": int(len(y_test)),
        "top1": top1,
        "top3": top3,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Model 3 — ReminderTemplateClassifier
# ---------------------------------------------------------------------------
def evaluate_reminder_model() -> dict:
    """
    Ewaluacja modelu ReminderTemplateClassifier (klasyfikacja wieloklasowa, 15 klas).

    Przewiduje optymalny szablon powiadomienia przypominającego o nawyku
    na podstawie cech behawioralnych użytkownika.
    """
    print("\n=== Model 3 — ReminderTemplateClassifier ===")
    csv_path = gen_reminder.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw train_reminder_model.py."
        )

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

    print(f"Dokładność Top-1 : {top1:.4f}")

    raport_pl = _raport_klasyfikacji_pl(
        y_test, y_pred,
        nazwy_klas=label_names,
        digits=3,
        zero_division=0,
    )
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    cm = confusion_matrix(y_test, y_pred, labels=list(range(len(label_names))))
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=label_names,
        tytul="Model 3 — ReminderTemplateClassifier — Macierz pomyłek",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_przypomnienie.png",
    )

    return {
        "name": "ReminderTemplateClassifier",
        "zadanie": f"klasyfikacja wieloklasowa ({len(label_names)} klas)",
        "test_size": int(len(y_test)),
        "top1": top1,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Model 4 — HabitAbandonmentClassifier
# ---------------------------------------------------------------------------
def evaluate_abandonment_model() -> dict:
    """
    Ewaluacja modelu HabitAbandonmentClassifier (klasyfikacja binarna).

    Przewiduje ryzyko porzucenia nawyku przez użytkownika w oparciu
    o jego historię aktywności. Kryterium akceptacji: makro miara F1 >= 0,75.
    """
    print("\n=== Model 4 — HabitAbandonmentClassifier ===")
    csv_path = gen_abandonment.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw train_abandonment_model.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_abandonment.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

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
    passed = "ZALICZONO" if macro_f1 >= 0.75 else "NIEZALICZONO"

    print(f"Dokładność na zbiorze testowym : {accuracy:.4f}")
    print(f"Pole pod krzywą ROC (AUC)      : {auc:.4f}")
    print(f"Makro miara F1                 : {macro_f1:.4f}  (próg >= 0,75 — {passed})")

    nazwy_klas_pl = ["aktywne (0)", "porzucone (1)"]
    raport_pl = _raport_klasyfikacji_pl(y_test, y_pred, nazwy_klas=nazwy_klas_pl)
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    # ----- Macierz pomyłek -----
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1])
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=nazwy_klas_pl,
        tytul="Model 4 — HabitAbandonmentClassifier — Macierz pomyłek",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_porzucenie.png",
    )

    # ----- Krzywa ROC -----
    fpr, tpr, _ = roc_curve(y_test, probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"Krzywa ROC (AUC = {auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Model losowy")
    ax.set_xlabel("Wskaźnik fałszywych alarmów (FPR)")
    ax.set_ylabel("Czułość — wskaźnik prawdziwych pozytywów (TPR)")
    ax.set_title("Model 4 — Krzywa ROC (HabitAbandonmentClassifier)")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "krzywa_roc_porzucenie.png", dpi=150)
    plt.close(fig)

    return {
        "name": "HabitAbandonmentClassifier",
        "zadanie": "klasyfikacja binarna",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "roc_auc": auc,
        "macro_f1": macro_f1,
        "passed": passed,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Model 5 — StreakBreakClassifier
# ---------------------------------------------------------------------------
def evaluate_streak_break_model() -> dict:
    """
    Ewaluacja modelu StreakBreakClassifier (klasyfikacja binarna).

    Przewiduje, czy seria (streak) nawyku zostanie przerwana w nadchodzącym tygodniu.
    Kryterium akceptacji: makro miara F1 >= 0,75.
    Wykresy dodatkowe: krzywa precyzja-czułość (PK).
    """
    print("\n=== Model 5 — StreakBreakClassifier ===")
    csv_path = gen_streak_break.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw train_streak_break_model.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_streak_break.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

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
    passed = "ZALICZONO" if macro_f1 >= 0.75 else "NIEZALICZONO"

    print(f"Dokładność na zbiorze testowym : {accuracy:.4f}")
    print(f"Pole pod krzywą ROC (AUC)      : {auc:.4f}")
    print(f"Makro miara F1                 : {macro_f1:.4f}  (próg >= 0,75 — {passed})")

    nazwy_klas_pl = ["kontynuacja (0)", "przerwanie (1)"]
    raport_pl = _raport_klasyfikacji_pl(y_test, y_pred, nazwy_klas=nazwy_klas_pl)
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    # ----- Macierz pomyłek -----
    cm = confusion_matrix(y_test, y_pred, labels=[0, 1])
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=nazwy_klas_pl,
        tytul="Model 5 — StreakBreakClassifier — Macierz pomyłek",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_seria.png",
    )

    # ----- Krzywa ROC -----
    fpr, tpr, _ = roc_curve(y_test, probs)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"Krzywa ROC (AUC = {auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Model losowy")
    ax.set_xlabel("Wskaźnik fałszywych alarmów (FPR)")
    ax.set_ylabel("Czułość — wskaźnik prawdziwych pozytywów (TPR)")
    ax.set_title("Model 5 — Krzywa ROC (StreakBreakClassifier)")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "krzywa_roc_seria.png", dpi=150)
    plt.close(fig)

    # ----- Krzywa precyzja-czułość (PK) -----
    precision_vals, recall_vals, _ = precision_recall_curve(y_test, probs)
    ap = float(average_precision_score(y_test, probs))
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(recall_vals, precision_vals, label=f"Krzywa PK (AP = {ap:.3f})")
    ax.set_xlabel("Czułość")
    ax.set_ylabel("Precyzja")
    ax.set_title("Model 5 — Krzywa precyzja-czułość (StreakBreakClassifier)")
    ax.legend(loc="upper right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "krzywa_pk_seria.png", dpi=150)
    plt.close(fig)

    return {
        "name": "StreakBreakClassifier",
        "zadanie": "klasyfikacja binarna",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "roc_auc": auc,
        "macro_f1": macro_f1,
        "passed": passed,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Model 6 — WeeklyForecastRegressor
# ---------------------------------------------------------------------------
def evaluate_weekly_forecast_model() -> dict:
    """
    Ewaluacja modelu WeeklyForecastRegressor (regresja).

    Przewiduje wskaźnik ukończenia nawyku w nadchodzącym tygodniu (wartość 0–1).
    Kryterium akceptacji: MAE <= 0,12.
    """
    print("\n=== Model 6 — WeeklyForecastRegressor ===")
    csv_path = gen_weekly_forecast.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_weekly_forecast_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_weekly_forecast.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.float32)

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
    y_pred = raw.ravel()

    mae = float(np.mean(np.abs(y_pred - y_test)))
    rmse = float(np.sqrt(np.mean((y_pred - y_test) ** 2)))
    passed = "ZALICZONO" if mae <= 0.12 else "NIEZALICZONO"

    print(f"Średni bezwzględny błąd (MAE) : {mae:.4f}  (próg <= 0,12 — {passed})")
    print(f"Pierwiastek błędu kwadr. (RMSE): {rmse:.4f}")

    raport_reg_pl = _raport_regresji_pl(
        y_test, y_pred, nazwa_zmiennej="wskaźnik ukończenia tygodniowego"
    )
    print("\nRaport ewaluacji regresji:")
    print(raport_reg_pl)

    # ----- Wykres rozrzutu: wartości rzeczywiste vs. predykowane -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, y_pred, alpha=0.3, s=8, label="Próbki testowe")
    lo = min(float(y_test.min()), float(y_pred.min()))
    hi = max(float(y_test.max()), float(y_pred.max()))
    ax.plot([lo, hi], [lo, hi], linestyle="--", color="gray", label="Idealne dopasowanie")
    ax.set_xlabel("Rzeczywisty wskaźnik ukończenia")
    ax.set_ylabel("Predykowany wskaźnik ukończenia")
    ax.set_title("Model 6 — WeeklyForecastRegressor — Wartości rzeczywiste vs. predykowane")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "rozrzut_prognoza_tygodniowa.png", dpi=150)
    plt.close(fig)

    # ----- Histogram rozkładu błędów predykcji -----
    errors = y_pred - y_test
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(errors, bins=40, color="steelblue", edgecolor="white", alpha=0.85)
    ax.axvline(0, color="red", linestyle="--", linewidth=1.5, label="Zero (idealna predykcja)")
    ax.axvline(float(errors.mean()), color="orange", linestyle="--", linewidth=1.5,
               label=f"Obciążenie ({errors.mean():.4f})")
    ax.set_xlabel("Błąd predykcji (predykcja − wartość rzeczywista)")
    ax.set_ylabel("Liczba próbek")
    ax.set_title("Model 6 — WeeklyForecastRegressor — Rozkład błędów predykcji")
    ax.legend()
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "histogram_bledow_prognoza_tygodniowa.png", dpi=150)
    plt.close(fig)

    return {
        "name": "WeeklyForecastRegressor",
        "zadanie": "regresja",
        "test_size": int(len(y_test)),
        "mae": mae,
        "rmse": rmse,
        "passed": passed,
        "raport_pl": raport_reg_pl,
    }


# ---------------------------------------------------------------------------
# Faza 8.4 — Grupowanie behawioralne K-Means
# ---------------------------------------------------------------------------
def evaluate_clustering_model() -> dict:
    """
    Ewaluacja modelu grupowania behawioralnego K-Means (Faza 8.4).

    Grupowanie niestrzeżone — brak podziału na zbiory uczący/testowy.
    Metryki: współczynnik sylwetkowy, rozmiary klastrów, tabela centroidów.
    Kryterium jakości: współczynnik sylwetkowy >= 0,35.
    """
    print("\n=== Faza 8.4 — Grupowanie behawioralne K-Means ===")

    csv_path = gen_clustering.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw train_clustering_model.py."
        )

    json_path = MODELS_DIR / "habit_clusters.json"
    if not json_path.exists():
        raise FileNotFoundError(
            f"{json_path} nie istnieje — uruchom najpierw train_clustering_model.py."
        )

    artifact = json.loads(json_path.read_text())
    feature_cols: list[str] = artifact["feature_columns"]
    means = np.array(artifact["feature_means"], dtype=np.float32)
    scales = np.array(artifact["feature_scales"], dtype=np.float32)
    centroids = np.array(artifact["centroids"], dtype=np.float32)
    labels: list[str] = artifact["labels"]
    saved_silhouette: float = artifact["silhouette_score"]

    df = pd.read_csv(csv_path)
    x_raw = df[feature_cols].to_numpy(dtype=np.float32)
    x_scaled = (x_raw - means) / np.where(scales == 0, 1.0, scales)

    diffs = x_scaled[:, np.newaxis, :] - centroids[np.newaxis, :, :]
    sq_dists = (diffs ** 2).sum(axis=2)
    assigned = sq_dists.argmin(axis=1)
    cluster_names = np.array([labels[i] for i in assigned])

    sil = silhouette_score(x_scaled, assigned, sample_size=5_000, random_state=SEED)
    gate_ok = sil >= 0.35
    print(f"  Współczynnik sylwetkowy (obliczony ponownie) : {sil:.4f}")
    print(f"  Współczynnik sylwetkowy (z pliku JSON)       : {saved_silhouette:.4f}")
    print(f"  Kryterium jakości (>= 0,35)                  : {'ZALICZONO' if gate_ok else 'NIEZALICZONO'}")

    print("\n  Rozmiary klastrów:")
    size_map: dict[str, int] = {}
    for label in labels:
        count = int((cluster_names == label).sum())
        size_map[label] = count
        pct = count / len(assigned) * 100
        pl_name = NAZWY_KLASTROW_PL.get(label, label)
        print(f"    {pl_name:<28} {count:>6}  ({pct:.1f}%)")

    print("\n  Tabela centroidów (przestrzeń zestandaryzowana):")
    nagl = f"  {'Klaster':<28} " + "  ".join(f"{c:>18}" for c in feature_cols)
    print(nagl)
    for i, label in enumerate(labels):
        pl_name = NAZWY_KLASTROW_PL.get(label, label)
        row_vals = "  ".join(f"{v:>18.4f}" for v in centroids[i])
        print(f"  {pl_name:<28} {row_vals}")

    # ----- Wykres rozrzutu PCA (projekcja 2D) -----
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
        pl_name = NAZWY_KLASTROW_PL.get(label, label)
        ax.scatter(
            x_2d[mask, 0], x_2d[mask, 1],
            c=color, label=pl_name, alpha=0.35, s=8, linewidths=0,
        )

    centroids_2d = pca.transform(centroids)
    for i, label in enumerate(labels):
        color = color_map.get(label, "#000000")
        ax.scatter(
            centroids_2d[i, 0], centroids_2d[i, 1],
            marker="*", s=220, c=color, edgecolors="black", linewidths=0.8, zorder=5,
        )

    ax.set_title(
        f"Grupowanie behawioralne K-Means — projekcja PCA\n"
        f"(PC1 {explained[0]*100:.1f}%  +  PC2 {explained[1]*100:.1f}%  = "
        f"{sum(explained)*100:.1f}% wyjaśnionej wariancji)"
    )
    ax.set_xlabel(f"PC1 ({explained[0]*100:.1f}%)")
    ax.set_ylabel(f"PC2 ({explained[1]*100:.1f}%)")
    ax.legend(title="Klaster", fontsize=8)
    fig.tight_layout()
    plot_path = PLOTS_DIR_PL / "klastry_pca.png"
    fig.savefig(plot_path, dpi=150)
    plt.close(fig)
    print(f"\n  Wykres PCA zapisany → {plot_path}")

    # Budujemy tekstowy raport grupowania do podsumowania Markdown.
    raport_linie = [
        f"Współczynnik sylwetkowy: **{sil:.4f}** "
        f"(próg >= 0,35 — {'ZALICZONO' if gate_ok else 'NIEZALICZONO'})",
        "",
        "| Klaster | Liczba próbek | Udział [%] |",
        "|---|---:|---:|",
    ]
    total = sum(size_map.values())
    for label in labels:
        count = size_map[label]
        pl_name = NAZWY_KLASTROW_PL.get(label, label)
        raport_linie.append(f"| {pl_name} | {count} | {count / total * 100:.1f}% |")
    raport_linie += [
        "",
        "**Tabela centroidów (przestrzeń zestandaryzowana):**",
        "",
        "| Klaster | " + " | ".join(feature_cols) + " |",
        "|---|" + "|".join(["---:" for _ in feature_cols]) + "|",
    ]
    for i, label in enumerate(labels):
        pl_name = NAZWY_KLASTROW_PL.get(label, label)
        row_vals = " | ".join(f"{v:.4f}" for v in centroids[i])
        raport_linie.append(f"| {pl_name} | {row_vals} |")

    return {
        "name": "KMeansBehavioralClustering",
        "zadanie": "grupowanie (niestrzeżone)",
        "total_rows": len(assigned),
        "silhouette": sil,
        "gate_passed": gate_ok,
        "cluster_sizes": size_map,
        "raport_pl": "\n".join(raport_linie),
    }


# ---------------------------------------------------------------------------
# Faza 8.5 — SpilloverRegressor
# ---------------------------------------------------------------------------
def evaluate_spillover_model() -> dict:
    """
    Ewaluacja modelu SpilloverRegressor (regresja, Faza 8.5).

    Przewiduje efekt przenoszenia (spillover) między nawykami — zmianę wskaźnika
    ukończenia powiązanego nawyku. Kryterium akceptacji: MAE <= 0,08.
    """
    print("\n=== Faza 8.5 — SpilloverRegressor ===")

    csv_path = gen_spillover.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_spillover_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_spillover.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["lift_delta"].to_numpy(dtype=np.float32)

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
    passed = "ZALICZONO" if mae <= 0.08 else "NIEZALICZONO"

    print(f"Średni bezwzględny błąd (MAE) : {mae:.4f}  (próg <= 0,08 — {passed})")
    print(f"Współczynnik determinacji R²  : {r2:.4f}")

    raport_reg_pl = _raport_regresji_pl(
        y_test, y_pred, nazwa_zmiennej="lift_delta (efekt przenoszenia)"
    )
    print("\nRaport ewaluacji regresji:")
    print(raport_reg_pl)

    # ----- Wykres rozrzutu -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, y_pred, alpha=0.25, s=6, label="Próbki testowe")
    lo = min(float(y_test.min()), float(y_pred.min()))
    hi = max(float(y_test.max()), float(y_pred.max()))
    ax.plot([lo, hi], [lo, hi], linestyle="--", color="gray", label="Idealne dopasowanie")
    ax.set_xlabel("Rzeczywisty lift_delta")
    ax.set_ylabel("Predykowany lift_delta")
    ax.set_title("Faza 8.5 — SpilloverRegressor — Wartości rzeczywiste vs. predykowane")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "rozrzut_spillover.png", dpi=150)
    plt.close(fig)

    # ----- Histogram błędów -----
    errors = y_pred - y_test
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(errors, bins=40, color="steelblue", edgecolor="white", alpha=0.85)
    ax.axvline(0, color="red", linestyle="--", linewidth=1.5, label="Zero (idealna predykcja)")
    ax.axvline(float(errors.mean()), color="orange", linestyle="--", linewidth=1.5,
               label=f"Obciążenie ({errors.mean():.4f})")
    ax.set_xlabel("Błąd predykcji (predykcja − wartość rzeczywista)")
    ax.set_ylabel("Liczba próbek")
    ax.set_title("Faza 8.5 — SpilloverRegressor — Rozkład błędów predykcji")
    ax.legend()
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "histogram_bledow_spillover.png", dpi=150)
    plt.close(fig)

    return {
        "name": "SpilloverRegressor",
        "zadanie": "regresja",
        "test_size": int(len(y_test)),
        "mae": mae,
        "r2": r2,
        "passed": passed,
        "raport_pl": raport_reg_pl,
    }


# ---------------------------------------------------------------------------
# Faza 9.1 — ReminderLiftClassifier
# ---------------------------------------------------------------------------
def evaluate_reminder_lift_model() -> dict:
    """
    Ewaluacja modelu ReminderLiftClassifier (klasyfikacja binarna, Faza 9.1).

    Przewiduje, czy wysłanie powiadomienia przypominającego zwiększy prawdopodobieństwo
    ukończenia nawyku w ciągu 30 minut. Kryterium akceptacji: makro miara F1 >= 0,75
    i MAE efektu przypomnienia <= 0,12.
    """
    print("\n=== Faza 9.1 — ReminderLiftClassifier ===")

    csv_path = gen_reminder_lift.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_reminder_lift_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_reminder_lift.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["completed_within_30min"].to_numpy(dtype=np.int32)

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

    reminder_col_idx = gen_reminder_lift.FEATURE_COLUMNS.index("reminderSent")
    mask_0 = x_test[:, reminder_col_idx] == 0
    mask_1 = x_test[:, reminder_col_idx] == 1
    pred_lift = float(y_prob[mask_1].mean()) - float(y_prob[mask_0].mean())
    act_lift = float(y_test[mask_1].mean()) - float(y_test[mask_0].mean())
    lift_mae = abs(pred_lift - act_lift)

    passed_f1 = macro_f1 >= 0.75
    passed_lift = lift_mae <= 0.12
    passed = "ZALICZONO" if (passed_f1 and passed_lift) else "NIEZALICZONO"

    print(f"Dokładność na zbiorze testowym : {acc:.4f}")
    print(f"Pole pod krzywą ROC (AUC)      : {roc_auc:.4f}")
    print(f"Makro miara F1                 : {macro_f1:.4f}  (próg >= 0,75 — {'ZALICZONO' if passed_f1 else 'NIEZALICZONO'})")
    print(f"MAE efektu przypomnienia       : {lift_mae:.4f}  (próg <= 0,12 — {'ZALICZONO' if passed_lift else 'NIEZALICZONO'})")
    print(f"Kryterium akceptacji           : {passed}")

    nazwy_klas_pl = ["Nieukończone (0)", "Ukończone (1)"]
    raport_pl = _raport_klasyfikacji_pl(y_test, y_pred, nazwy_klas=nazwy_klas_pl)
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    # ----- Macierz pomyłek -----
    cm = confusion_matrix(y_test, y_pred)
    out_path = PLOTS_DIR_PL / "macierz_pomylek_lift_przypomnienia.png"
    fig, ax = plt.subplots(figsize=(4, 4))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks([0, 1])
    ax.set_xticklabels(["Nie", "Tak"])
    ax.set_yticks([0, 1])
    ax.set_yticklabels(["Nie", "Tak"])
    ax.set_xlabel("Klasa predykowana")
    ax.set_ylabel("Klasa rzeczywista")
    ax.set_title("Faza 9.1 — ReminderLiftClassifier — Macierz pomyłek")
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, ax=ax)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

    # ----- Krzywa ROC -----
    fpr, tpr, _ = roc_curve(y_test, y_prob)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"Krzywa ROC (AUC = {roc_auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Model losowy")
    ax.set_xlabel("Wskaźnik fałszywych alarmów (FPR)")
    ax.set_ylabel("Czułość — wskaźnik prawdziwych pozytywów (TPR)")
    ax.set_title("Faza 9.1 — Krzywa ROC (ReminderLiftClassifier)")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "krzywa_roc_lift_przypomnienia.png", dpi=150)
    plt.close(fig)

    return {
        "name": "ReminderLiftClassifier",
        "zadanie": "klasyfikacja binarna",
        "test_size": int(len(y_test)),
        "accuracy": acc,
        "roc_auc": roc_auc,
        "macro_f1": macro_f1,
        "lift_mae": lift_mae,
        "passed": passed,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Faza 9.2 — SnoozeDisengagementClassifier
# ---------------------------------------------------------------------------
def evaluate_snooze_disengagement_model() -> dict:
    """
    Ewaluacja modelu SnoozeDisengagementClassifier (klasyfikacja binarna, Faza 9.2).

    Przewiduje, czy wielokrotne odkładanie powiadomień świadczy o wycofaniu się
    użytkownika z nawyku (niezaangażowanie). Kryterium akceptacji: makro miara F1 >= 0,75.
    """
    print("\n=== Faza 9.2 — SnoozeDisengagementClassifier ===")

    csv_path = gen_snooze_disengagement.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_snooze_disengagement_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_snooze_disengagement.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

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
    passed = "ZALICZONO" if macro_f1 >= 0.75 else "NIEZALICZONO"

    print(f"Dokładność na zbiorze testowym : {acc:.4f}")
    print(f"Pole pod krzywą ROC (AUC)      : {roc_auc:.4f}")
    print(f"Makro miara F1                 : {macro_f1:.4f}  (próg >= 0,75 — {passed})")

    nazwy_klas_pl = ["Zaangażowany (0)", "Niezaangażowany (1)"]
    raport_pl = _raport_klasyfikacji_pl(y_test, y_pred, nazwy_klas=nazwy_klas_pl)
    print("\nRaport klasyfikacji (precyzja / czułość / miara F1 na klasę):")
    print(raport_pl)

    # ----- Macierz pomyłek -----
    cm = confusion_matrix(y_test, y_pred)
    out_path = PLOTS_DIR_PL / "macierz_pomylek_snooze.png"
    fig, ax = plt.subplots(figsize=(4, 4))
    im = ax.imshow(cm, cmap="Blues")
    ax.set_xticks([0, 1])
    ax.set_xticklabels(["Zaangażowany", "Niezaangażowany"])
    ax.set_yticks([0, 1])
    ax.set_yticklabels(["Zaangażowany", "Niezaangażowany"])
    ax.set_xlabel("Klasa predykowana")
    ax.set_ylabel("Klasa rzeczywista")
    ax.set_title("Faza 9.2 — SnoozeDisengagementClassifier — Macierz pomyłek")
    for i in range(2):
        for j in range(2):
            ax.text(j, i, str(cm[i, j]), ha="center", va="center",
                    color="white" if cm[i, j] > cm.max() / 2 else "black")
    fig.colorbar(im, ax=ax)
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)

    # ----- Krzywa ROC -----
    fpr, tpr, _ = roc_curve(y_test, y_prob)
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.plot(fpr, tpr, label=f"Krzywa ROC (AUC = {roc_auc:.3f})")
    ax.plot([0, 1], [0, 1], linestyle="--", color="gray", label="Model losowy")
    ax.set_xlabel("Wskaźnik fałszywych alarmów (FPR)")
    ax.set_ylabel("Czułość — wskaźnik prawdziwych pozytywów (TPR)")
    ax.set_title("Faza 9.2 — Krzywa ROC (SnoozeDisengagementClassifier)")
    ax.legend(loc="lower right")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "krzywa_roc_snooze.png", dpi=150)
    plt.close(fig)

    return {
        "name": "SnoozeDisengagementClassifier",
        "zadanie": "klasyfikacja binarna",
        "test_size": int(len(y_test)),
        "accuracy": acc,
        "roc_auc": roc_auc,
        "macro_f1": macro_f1,
        "passed": passed,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Faza 9.3 — TargetChangeRegressor
# ---------------------------------------------------------------------------
def evaluate_target_change_model() -> dict:
    """
    Ewaluacja modelu TargetChangeRegressor (regresja, Faza 9.3).

    Przewiduje optymalną zmianę celu dziennego nawyku z zakresu [-2, +2].
    Kryterium akceptacji: MAE na zaokrąglonej delcie <= 0,50.
    Dodatkowa ewaluacja: macierz pomyłek dla zaokrąglonych klas delta (-2…+2).
    """
    print("\n=== Faza 9.3 — TargetChangeRegressor ===")

    csv_path = gen_target_change.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_target_change_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_target_change.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["ideal_delta"].to_numpy(dtype=np.float32)

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
    ).ravel()

    y_pred_rounded = np.clip(np.round(raw_preds).astype(np.int32), -2, 2)
    y_true_rounded = np.clip(np.round(y_test).astype(np.int32), -2, 2)

    mae_raw = float(np.mean(np.abs(raw_preds - y_test)))
    rmse_raw = float(np.sqrt(np.mean((raw_preds - y_test) ** 2)))
    mae_rounded = float(np.mean(np.abs(y_pred_rounded - y_true_rounded)))
    passed = "ZALICZONO" if mae_rounded <= 0.50 else "NIEZALICZONO"

    print(f"MAE  (ciągła predykcja)       : {mae_raw:.4f}")
    print(f"RMSE (ciągła predykcja)       : {rmse_raw:.4f}")
    print(f"MAE  (zaokrąglona delta)       : {mae_rounded:.4f}  (próg <= 0,50 — {passed})")

    raport_reg_pl = _raport_regresji_pl(
        y_test, raw_preds, nazwa_zmiennej="delta docelowa (zakres [-2, +2])"
    )
    print("\nRaport ewaluacji regresji:")
    print(raport_reg_pl)

    # Raport klasyfikacji dla zaokrąglonych klas delta (-2 … +2).
    class_names = ["-2", "-1", "0", "+1", "+2"]
    raport_klas_pl = _raport_klasyfikacji_pl(
        y_true_rounded, y_pred_rounded,
        nazwy_klas=class_names,
        digits=3,
        zero_division=0,
    )
    print("\nRaport klasyfikacji zaokrąglonych klas delta (-2 … +2):")
    print(raport_klas_pl)

    # ----- Macierz pomyłek (zaokrąglone klasy delta) -----
    labels_int = [-2, -1, 0, 1, 2]
    cm = confusion_matrix(y_true_rounded, y_pred_rounded, labels=labels_int)
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=class_names,
        tytul="Faza 9.3 — TargetChangeRegressor — Macierz pomyłek (zaokrąglona delta)",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_zmiana_celu.png",
    )

    # ----- Wykres rozrzutu -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, raw_preds, alpha=0.3, s=8, label="Próbki testowe")
    lims = [-2.2, 2.2]
    ax.plot(lims, lims, linestyle="--", color="gray", label="Idealna predykcja")
    ax.set_xlim(lims)
    ax.set_ylim(lims)
    ax.set_xlabel("Rzeczywista delta docelowa")
    ax.set_ylabel("Predykowana delta (ciągła)")
    ax.set_title("Faza 9.3 — TargetChangeRegressor — Wartości rzeczywiste vs. predykowane")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "rozrzut_zmiana_celu.png", dpi=150)
    plt.close(fig)

    # ----- Histogram błędów -----
    errors = raw_preds - y_test
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(errors, bins=40, color="steelblue", edgecolor="white", alpha=0.85)
    ax.axvline(0, color="red", linestyle="--", linewidth=1.5, label="Zero (idealna predykcja)")
    ax.axvline(float(errors.mean()), color="orange", linestyle="--", linewidth=1.5,
               label=f"Obciążenie ({errors.mean():.4f})")
    ax.set_xlabel("Błąd predykcji (predykcja − wartość rzeczywista)")
    ax.set_ylabel("Liczba próbek")
    ax.set_title("Faza 9.3 — TargetChangeRegressor — Rozkład błędów predykcji")
    ax.legend()
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "histogram_bledow_zmiana_celu.png", dpi=150)
    plt.close(fig)

    return {
        "name": "TargetChangeRegressor",
        "zadanie": "regresja (delta ∈ [-2,+2])",
        "test_size": int(len(y_test)),
        "mae_raw": mae_raw,
        "rmse_raw": rmse_raw,
        "mae_rounded": mae_rounded,
        "passed": passed,
        "raport_pl": raport_reg_pl,
        "raport_klas_pl": raport_klas_pl,
    }


# ---------------------------------------------------------------------------
# Faza 9.4 — PerceivedDifficultyRegressor
# ---------------------------------------------------------------------------
def evaluate_difficulty_model() -> dict:
    """
    Ewaluacja modelu PerceivedDifficultyRegressor (regresja, Faza 9.4).

    Przewiduje postrzeganą trudność sesji wykonania nawyku w skali 1–5.
    Kryterium akceptacji: MAE <= 0,55.
    Benchmarking: porównanie z prognozą bazową (zawsze predykuj wartość 3,0).
    """
    print("\n=== Faza 9.4 — PerceivedDifficultyRegressor ===")

    csv_path = gen_difficulty.output_path()
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_difficulty_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_difficulty.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["perceived_difficulty"].to_numpy(dtype=np.float32)

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
    ).ravel()

    mae = float(np.mean(np.abs(raw_preds - y_test)))
    rmse = float(np.sqrt(np.mean((raw_preds - y_test) ** 2)))
    naive_mae = float(np.mean(np.abs(np.full_like(y_test, 3.0) - y_test)))
    passed = "ZALICZONO" if mae <= 0.55 else "NIEZALICZONO"

    y_pred_rounded = np.clip(np.round(raw_preds).astype(np.int32), 1, 5)
    y_true_rounded = np.clip(np.round(y_test).astype(np.int32), 1, 5)
    exact_match = float((y_pred_rounded == y_true_rounded).mean())
    within_one = float((np.abs(y_pred_rounded - y_true_rounded) <= 1).mean())

    print(f"Średni bezwzględny błąd (MAE)    : {mae:.4f}  (próg <= 0,55 — {passed})")
    print(f"Pierwiastek błędu kwadr. (RMSE)   : {rmse:.4f}")
    print(f"MAE prognozy bazowej (zawsze 3,0) : {naive_mae:.4f}")
    print(f"Poprawa MAE względem prognozy baz.: {naive_mae - mae:.4f}")
    print(f"Dokładność co do klasy (zaokr.)   : {exact_match:.1%}")
    print(f"Dokładność ± 1 klasa              : {within_one:.1%}")

    raport_reg_pl = _raport_regresji_pl(
        y_test, raw_preds, nazwa_zmiennej="postrzegana trudność (skala 1–5)"
    )
    print("\nRaport ewaluacji regresji:")
    print(raport_reg_pl)

    # Raport klasyfikacji zaokrąglonych kubełków trudności (1…5).
    bucket_labels = [1, 2, 3, 4, 5]
    bucket_names = ["1", "2", "3", "4", "5"]
    raport_klas_pl = _raport_klasyfikacji_pl(
        y_true_rounded, y_pred_rounded,
        nazwy_klas=bucket_names,
        digits=3,
        zero_division=0,
    )
    print("\nRaport klasyfikacji zaokrąglonych kubełków trudności (1…5):")
    print(raport_klas_pl)

    # ----- Macierz pomyłek (zaokrąglone kubełki) -----
    cm = confusion_matrix(y_true_rounded, y_pred_rounded, labels=bucket_labels)
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=bucket_names,
        tytul="Faza 9.4 — PerceivedDifficultyRegressor — Macierz pomyłek (kubełki)",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_trudnosc.png",
    )

    # ----- Wykres rozrzutu -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, raw_preds, alpha=0.25, s=6, label="Próbki testowe")
    ax.plot([1, 5], [1, 5], linestyle="--", color="gray", label="Idealna predykcja")
    ax.set_xlim(0.8, 5.2)
    ax.set_ylim(0.8, 5.2)
    ax.set_xlabel("Rzeczywista postrzegana trudność")
    ax.set_ylabel("Predykowana postrzegana trudność (ciągła)")
    ax.set_title("Faza 9.4 — PerceivedDifficultyRegressor — Wartości rzeczywiste vs. predykowane")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "rozrzut_trudnosc.png", dpi=150)
    plt.close(fig)

    # ----- Histogram błędów -----
    errors = raw_preds - y_test
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(errors, bins=40, color="steelblue", edgecolor="white", alpha=0.85)
    ax.axvline(0, color="red", linestyle="--", linewidth=1.5, label="Zero (idealna predykcja)")
    ax.axvline(float(errors.mean()), color="orange", linestyle="--", linewidth=1.5,
               label=f"Obciążenie ({errors.mean():.4f})")
    ax.set_xlabel("Błąd predykcji (predykcja − wartość rzeczywista)")
    ax.set_ylabel("Liczba próbek")
    ax.set_title("Faza 9.4 — PerceivedDifficultyRegressor — Rozkład błędów predykcji")
    ax.legend()
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "histogram_bledow_trudnosc.png", dpi=150)
    plt.close(fig)

    return {
        "name": "PerceivedDifficultyRegressor",
        "zadanie": "regresja (trudność ∈ [1,5])",
        "test_size": int(len(y_test)),
        "mae": mae,
        "rmse": rmse,
        "naive_mae": naive_mae,
        "exact_match": exact_match,
        "within_one": within_one,
        "passed": passed,
        "raport_pl": raport_reg_pl,
        "raport_klas_pl": raport_klas_pl,
    }


# ---------------------------------------------------------------------------
# Faza 9.5 — SkipReasonClassifier
# ---------------------------------------------------------------------------
def evaluate_skip_reason_model() -> dict:
    """
    Ewaluacja modelu SkipReasonClassifier (klasyfikacja 6-klasowa, Faza 9.5).

    Przewiduje najbardziej prawdopodobną przyczynę pominięcia nawyku na podstawie
    8 cech behawioralnych kontekstu. Kryterium akceptacji: makro miara F1 >= 0,35.

    Uwaga: Klasy SICK i TRAVELING są celowo rzadkie (klasy szumowe); ich niski
    wskaźnik czułości jest oczekiwany i udokumentowany w sekcji ograniczeń pracy.
    """
    print("\n=== Faza 9.5 — SkipReasonClassifier ===")

    csv_path = HERE / "data" / "skip_reason_dataset.csv"
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_skip_reason_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_skip_reason.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df["label"].to_numpy(dtype=np.int32)

    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED, stratify=y
    )

    scaler = json.loads(
        (MODELS_DIR / "skip_reason_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    probs = _tflite_predict(
        MODELS_DIR / "skip_reason_classifier.tflite",
        x_test_scaled,
    )
    y_pred = probs.argmax(axis=1).astype(np.int32)

    accuracy = float((y_pred == y_test).mean())
    macro_f1 = float(f1_score(y_test, y_pred, average="macro", zero_division=0))
    passed = "ZALICZONO" if macro_f1 >= 0.35 else "NIEZALICZONO"

    print(f"Dokładność na zbiorze testowym : {accuracy:.4f}")
    print(f"Makro miara F1                 : {macro_f1:.4f}  (próg >= 0,35 — {passed})")

    class_names = gen_skip_reason.CLASS_LABELS
    raport_pl = _raport_klasyfikacji_pl(
        y_test, y_pred,
        nazwy_klas=class_names,
        digits=3,
        zero_division=0,
    )
    print("\nRaport klasyfikacji na klasę:")
    print(raport_pl)

    # ----- Macierz pomyłek -----
    cm = confusion_matrix(y_test, y_pred, labels=list(range(len(class_names))))
    _plot_macierz_pomylek(
        cm,
        nazwy_klas=class_names,
        tytul="Faza 9.5 — SkipReasonClassifier — Macierz pomyłek (6 klas)",
        sciezka=PLOTS_DIR_PL / "macierz_pomylek_powod_pominiecia.png",
    )

    return {
        "name": "SkipReasonClassifier",
        "zadanie": "klasyfikacja wieloklasowa (6 klas)",
        "test_size": int(len(y_test)),
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "passed": passed,
        "raport_pl": raport_pl,
    }


# ---------------------------------------------------------------------------
# Faza 9.6 — EngagementWindowRegressor
# ---------------------------------------------------------------------------
def evaluate_engagement_window_model() -> dict:
    """
    Ewaluacja modelu EngagementWindowRegressor (regresja, Faza 9.6).

    Przewiduje godzinę doby (0–24), w której użytkownik z największym
    prawdopodobieństwem otworzy aplikację podczas następnej sesji.
    Kryterium akceptacji: MAE <= 1,5 h.
    """
    print("\n=== Faza 9.6 — EngagementWindowRegressor ===")

    csv_path = HERE / "data" / "engagement_window_dataset.csv"
    if not csv_path.exists():
        raise FileNotFoundError(
            f"{csv_path} nie istnieje — uruchom najpierw generate_engagement_window_data.py."
        )

    df = pd.read_csv(csv_path)
    x = df[gen_engagement_window.FEATURE_COLUMNS].to_numpy(dtype=np.float32)
    y = df[gen_engagement_window.LABEL_COLUMN].to_numpy(dtype=np.float32)

    _, x_test, _, y_test = train_test_split(
        x, y, test_size=0.2, random_state=SEED
    )

    scaler = json.loads(
        (MODELS_DIR / "engagement_window_scaler.json").read_text(encoding="utf-8")
    )
    mean = np.array(scaler["mean"], dtype=np.float32)
    scale = np.array(scaler["scale"], dtype=np.float32)
    x_test_scaled = (x_test - mean) / scale

    raw_preds = _tflite_predict(
        MODELS_DIR / "engagement_window_regressor.tflite",
        x_test_scaled,
    ).ravel()

    mae = float(np.mean(np.abs(raw_preds - y_test)))
    rmse = float(np.sqrt(np.mean((raw_preds - y_test) ** 2)))
    naive_mae = float(np.mean(np.abs(np.full_like(y_test, y_test.mean()) - y_test)))
    passed = "ZALICZONO" if mae <= 1.5 else "NIEZALICZONO"

    print(f"Średni bezwzględny błąd MAE [h]   : {mae:.4f}  (próg <= 1,5 h — {passed})")
    print(f"Pierwiastek błędu kwadr. RMSE [h] : {rmse:.4f}")
    print(f"MAE prognozy bazowej (śr. = {y_test.mean():.1f} h): {naive_mae:.4f}")
    print(f"Poprawa MAE względem prognozy baz. : {naive_mae - mae:.4f} h")

    raport_reg_pl = _raport_regresji_pl(
        y_test, raw_preds,
        nazwa_zmiennej="godzina następnej sesji [h]"
    )
    print("\nRaport ewaluacji regresji:")
    print(raport_reg_pl)

    # ----- Wykres rozrzutu -----
    fig, ax = plt.subplots(figsize=(6, 5))
    ax.scatter(y_test, raw_preds, alpha=0.15, s=5, label="Próbki testowe")
    ax.plot([0, 24], [0, 24], linestyle="--", color="gray", label="Idealna predykcja")
    ax.set_xlim(-0.5, 24.5)
    ax.set_ylim(-0.5, 24.5)
    ax.set_xlabel("Rzeczywista godzina kolejnej sesji [h]")
    ax.set_ylabel("Predykowana godzina kolejnej sesji [h]")
    ax.set_title("Faza 9.6 — EngagementWindowRegressor — Wartości rzeczywiste vs. predykowane")
    ax.legend(loc="upper left")
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "rozrzut_okno_zaangazowania.png", dpi=150)
    plt.close(fig)

    # ----- Histogram błędów -----
    errors = raw_preds - y_test
    fig, ax = plt.subplots(figsize=(6, 4))
    ax.hist(errors, bins=40, color="steelblue", edgecolor="white", alpha=0.85)
    ax.axvline(0, color="red", linestyle="--", linewidth=1.5, label="Zero (idealna predykcja)")
    ax.axvline(float(errors.mean()), color="orange", linestyle="--", linewidth=1.5,
               label=f"Obciążenie ({errors.mean():.4f} h)")
    ax.set_xlabel("Błąd predykcji [h] (predykcja − wartość rzeczywista)")
    ax.set_ylabel("Liczba próbek")
    ax.set_title("Faza 9.6 — EngagementWindowRegressor — Rozkład błędów predykcji")
    ax.legend()
    fig.tight_layout()
    fig.savefig(PLOTS_DIR_PL / "histogram_bledow_okno_zaangazowania.png", dpi=150)
    plt.close(fig)

    return {
        "name": "EngagementWindowRegressor",
        "zadanie": "regresja (godzina ∈ [0, 24))",
        "test_size": int(len(y_test)),
        "mae": mae,
        "rmse": rmse,
        "naive_mae": naive_mae,
        "passed": passed,
        "raport_pl": raport_reg_pl,
    }


# ---------------------------------------------------------------------------
# Zapis zbiorczego podsumowania w formacie Markdown (gotowe do pracy inż.).
# ---------------------------------------------------------------------------
def write_summary(results: list) -> Path:
    """
    Generuje zbiorcze podsumowanie wyników ewaluacji w języku polskim.

    Zapisuje plik Markdown do PLOTS_DIR_PL/podsumowanie_metryk.md.
    Plik zawiera tabelę zbiorczą oraz szczegółowe raporty dla wszystkich modeli.
    """
    (
        success, icon, reminder, abandonment, streak_break,
        weekly_forecast, clustering, spillover, reminder_lift,
        snooze_disengagement, target_change, difficulty,
        skip_reason, engagement_window,
    ) = results

    def _pass(r: dict) -> str:
        return r.get("passed", "—")

    linie = [
        "# Podsumowanie ewaluacji modeli uczenia maszynowego",
        "",
        "_Wygenerowano przez `ml-training/evaluate_models_pl.py`._",
        "",
        "Wszystkie wartości metryczne obliczone zostały na wydzielonym zbiorze testowym "
        "(20% danych, podział warstwowy, ziarno losowości SEED=42) z wykorzystaniem "
        "wyeksportowanych artefaktów `.tflite` i zapisanych plików skalera / słownika JSON — "
        "tzn. dokładnie tych komponentów, które są wdrażane do katalogu `app/src/main/assets/`.",
        "",
        "---",
        "",
        "## 1. Zbiorcze metryki wydajności",
        "",
        "| Model | Zadanie | Próbki testowe | Metryka główna | Wartość | Metryka pomocnicza | Wartość |",
        "|---|---|---:|---|---:|---|---:|",
        f"| {success['name']} | {success['zadanie']} | {success['test_size']} |"
        f" Dokładność | {success['accuracy']:.4f} | AUC | {success['roc_auc']:.4f} |",
        f"| {icon['name']} | {icon['zadanie']} | {icon['test_size']} |"
        f" Top-1 | {icon['top1']:.4f} | Top-3 | {icon['top3']:.4f} |",
        f"| {reminder['name']} | {reminder['zadanie']} | {reminder['test_size']} |"
        f" Top-1 | {reminder['top1']:.4f} | — | — |",
        f"| {abandonment['name']} | {abandonment['zadanie']} | {abandonment['test_size']} |"
        f" Makro F1 | {abandonment['macro_f1']:.4f} | AUC | {abandonment['roc_auc']:.4f} |",
        f"| {streak_break['name']} | {streak_break['zadanie']} | {streak_break['test_size']} |"
        f" Makro F1 | {streak_break['macro_f1']:.4f} | AUC | {streak_break['roc_auc']:.4f} |",
        f"| {weekly_forecast['name']} | {weekly_forecast['zadanie']} | {weekly_forecast['test_size']} |"
        f" MAE | {weekly_forecast['mae']:.4f} | RMSE | {weekly_forecast['rmse']:.4f} |",
        f"| {clustering['name']} | {clustering['zadanie']} | {clustering['total_rows']} (wszystkie) |"
        f" Silhouette | {clustering['silhouette']:.4f} | — | — |",
        f"| {spillover['name']} | {spillover['zadanie']} | {spillover['test_size']} |"
        f" MAE | {spillover['mae']:.4f} | R² | {spillover['r2']:.4f} |",
        f"| {reminder_lift['name']} | {reminder_lift['zadanie']} | {reminder_lift['test_size']} |"
        f" Makro F1 | {reminder_lift['macro_f1']:.4f} | AUC | {reminder_lift['roc_auc']:.4f} |",
        f"| {snooze_disengagement['name']} | {snooze_disengagement['zadanie']} | {snooze_disengagement['test_size']} |"
        f" Makro F1 | {snooze_disengagement['macro_f1']:.4f} | AUC | {snooze_disengagement['roc_auc']:.4f} |",
        f"| {target_change['name']} | {target_change['zadanie']} | {target_change['test_size']} |"
        f" MAE (zaokr.) | {target_change['mae_rounded']:.4f} | RMSE | {target_change['rmse_raw']:.4f} |",
        f"| {difficulty['name']} | {difficulty['zadanie']} | {difficulty['test_size']} |"
        f" MAE | {difficulty['mae']:.4f} | RMSE | {difficulty['rmse']:.4f} |",
        f"| {skip_reason['name']} | {skip_reason['zadanie']} | {skip_reason['test_size']} |"
        f" Dokładność | {skip_reason['accuracy']:.4f} | Makro F1 | {skip_reason['macro_f1']:.4f} |",
        f"| {engagement_window['name']} | {engagement_window['zadanie']} | {engagement_window['test_size']} |"
        f" MAE [h] | {engagement_window['mae']:.4f} | RMSE [h] | {engagement_window['rmse']:.4f} |",
        "",
        "---",
        "",
        "## 2. Wygenerowane wykresy",
        "",
        "Wszystkie pliki PNG zapisane są w katalogu `ml-training/data/plots_pl/`:",
        "",
        "**Macierze pomyłek:**",
        "- `macierz_pomylek_sukces.png` — Model 1",
        "- `macierz_pomylek_ikona.png` — Model 2 (17 klas)",
        "- `macierz_pomylek_przypomnienie.png` — Model 3 (15 klas)",
        "- `macierz_pomylek_porzucenie.png` — Model 4",
        "- `macierz_pomylek_seria.png` — Model 5",
        "- `macierz_pomylek_lift_przypomnienia.png` — Faza 9.1",
        "- `macierz_pomylek_snooze.png` — Faza 9.2",
        "- `macierz_pomylek_zmiana_celu.png` — Faza 9.3 (zaokrąglona delta)",
        "- `macierz_pomylek_trudnosc.png` — Faza 9.4 (kubełki trudności)",
        "- `macierz_pomylek_powod_pominiecia.png` — Faza 9.5 (6 klas)",
        "",
        "**Krzywe ROC:**",
        "- `krzywa_roc_sukces.png` — Model 1",
        "- `krzywa_roc_porzucenie.png` — Model 4",
        "- `krzywa_roc_seria.png` — Model 5",
        "- `krzywa_roc_lift_przypomnienia.png` — Faza 9.1",
        "- `krzywa_roc_snooze.png` — Faza 9.2",
        "",
        "**Pozostałe wykresy:**",
        "- `kalibracja_sukces.png` — Model 1 (diagram kalibracji)",
        "- `krzywa_pk_seria.png` — Model 5 (krzywa precyzja-czułość)",
        "- `klastry_pca.png` — Faza 8.4 (projekcja PCA klastrów)",
        "- `rozrzut_prognoza_tygodniowa.png` — Model 6",
        "- `rozrzut_spillover.png` — Faza 8.5",
        "- `rozrzut_zmiana_celu.png` — Faza 9.3",
        "- `rozrzut_trudnosc.png` — Faza 9.4",
        "- `rozrzut_okno_zaangazowania.png` — Faza 9.6",
        "- `histogram_bledow_*.png` — rozkłady błędów predykcji (modele regresji)",
        "",
        "---",
        "",
        "## 3. Szczegółowe raporty ewaluacji",
        "",
        "### Model 1 — HabitSuccessClassifier",
        "",
        f"Kryterium akceptacji: brak formalnego progu — dokładność {success['accuracy']:.4f},"
        f" AUC {success['roc_auc']:.4f}, makro miara F1 {success['macro_f1']:.4f}.",
        "",
        "```",
        success["raport_pl"].rstrip(),
        "```",
        "",
        "### Model 2 — HabitIconClassifier",
        "",
        f"Dokładność Top-1: {icon['top1']:.4f} | Dokładność Top-3: {icon['top3']:.4f}.",
        "",
        "```",
        icon["raport_pl"].rstrip(),
        "```",
        "",
        "### Model 3 — ReminderTemplateClassifier",
        "",
        f"Dokładność Top-1: {reminder['top1']:.4f}.",
        "",
        "```",
        reminder["raport_pl"].rstrip(),
        "```",
        "",
        "### Model 4 — HabitAbandonmentClassifier",
        "",
        f"Kryterium akceptacji: makro miara F1 >= 0,75 — **{_pass(abandonment)}**"
        f" ({abandonment['macro_f1']:.4f}).",
        "",
        "```",
        abandonment["raport_pl"].rstrip(),
        "```",
        "",
        "### Model 5 — StreakBreakClassifier",
        "",
        f"Kryterium akceptacji: makro miara F1 >= 0,75 — **{_pass(streak_break)}**"
        f" ({streak_break['macro_f1']:.4f}).",
        "",
        "```",
        streak_break["raport_pl"].rstrip(),
        "```",
        "",
        "### Model 6 — WeeklyForecastRegressor",
        "",
        f"Kryterium akceptacji: MAE <= 0,12 — **{_pass(weekly_forecast)}**"
        f" (MAE = {weekly_forecast['mae']:.4f}, RMSE = {weekly_forecast['rmse']:.4f}).",
        "",
        "```",
        weekly_forecast["raport_pl"].rstrip(),
        "```",
        "",
        "### Faza 8.4 — Grupowanie behawioralne K-Means",
        "",
        clustering["raport_pl"],
        "",
        "### Faza 8.5 — SpilloverRegressor",
        "",
        f"Kryterium akceptacji: MAE <= 0,08 — **{_pass(spillover)}**"
        f" (MAE = {spillover['mae']:.4f}, R² = {spillover['r2']:.4f}).",
        "",
        "```",
        spillover["raport_pl"].rstrip(),
        "```",
        "",
        "### Faza 9.1 — ReminderLiftClassifier",
        "",
        f"Kryterium akceptacji: makro miara F1 >= 0,75 i MAE efektu <= 0,12 — "
        f"**{_pass(reminder_lift)}** "
        f"(F1 = {reminder_lift['macro_f1']:.4f}, lift MAE = {reminder_lift['lift_mae']:.4f}).",
        "",
        "```",
        reminder_lift["raport_pl"].rstrip(),
        "```",
        "",
        "### Faza 9.2 — SnoozeDisengagementClassifier",
        "",
        f"Kryterium akceptacji: makro miara F1 >= 0,75 — **{_pass(snooze_disengagement)}**"
        f" ({snooze_disengagement['macro_f1']:.4f}).",
        "",
        "```",
        snooze_disengagement["raport_pl"].rstrip(),
        "```",
        "",
        "### Faza 9.3 — TargetChangeRegressor",
        "",
        f"Kryterium akceptacji: MAE zaokrąglonej delty <= 0,50 — "
        f"**{_pass(target_change)}** (MAE = {target_change['mae_rounded']:.4f}).",
        "",
        "**Raport regresji (ciągła predykcja):**",
        "",
        "```",
        target_change["raport_pl"].rstrip(),
        "```",
        "",
        "**Raport klasyfikacji zaokrąglonych klas delta (-2 … +2):**",
        "",
        "```",
        target_change["raport_klas_pl"].rstrip(),
        "```",
        "",
        "### Faza 9.4 — PerceivedDifficultyRegressor",
        "",
        f"Kryterium akceptacji: MAE <= 0,55 — **{_pass(difficulty)}**"
        f" (MAE = {difficulty['mae']:.4f}, dokładność co do klasy {difficulty['exact_match']:.1%},"
        f" dokładność ±1 {difficulty['within_one']:.1%}).",
        "",
        "**Raport regresji (ciągła predykcja):**",
        "",
        "```",
        difficulty["raport_pl"].rstrip(),
        "```",
        "",
        "**Raport klasyfikacji zaokrąglonych kubełków trudności (1…5):**",
        "",
        "```",
        difficulty["raport_klas_pl"].rstrip(),
        "```",
        "",
        "### Faza 9.5 — SkipReasonClassifier",
        "",
        f"Kryterium akceptacji: makro miara F1 >= 0,35 — **{_pass(skip_reason)}**"
        f" ({skip_reason['macro_f1']:.4f}). Klasy SICK i TRAVELING są celowo rzadkie"
        f" (klasy szumowe); ich niska czułość jest oczekiwana i udokumentowana.",
        "",
        "```",
        skip_reason["raport_pl"].rstrip(),
        "```",
        "",
        "### Faza 9.6 — EngagementWindowRegressor",
        "",
        f"Kryterium akceptacji: MAE <= 1,5 h — **{_pass(engagement_window)}**"
        f" (MAE = {engagement_window['mae']:.4f} h, RMSE = {engagement_window['rmse']:.4f} h,"
        f" prognoza bazowa MAE = {engagement_window['naive_mae']:.4f} h).",
        "",
        "```",
        engagement_window["raport_pl"].rstrip(),
        "```",
        "",
    ]

    out = PLOTS_DIR_PL / "podsumowanie_metryk.md"
    out.write_text("\n".join(linie), encoding="utf-8")
    return out


# ---------------------------------------------------------------------------
# Punkt wejścia skryptu.
# ---------------------------------------------------------------------------
def main() -> None:
    _ensure_plots_dir()
    print("=" * 60)
    print("  Ewaluacja modeli uczenia maszynowego — wersja polska")
    print("  Katalog wyjściowy:", PLOTS_DIR_PL)
    print("=" * 60)

    results = [
        evaluate_success_model(),
        evaluate_icon_model(),
        evaluate_reminder_model(),
        evaluate_abandonment_model(),
        evaluate_streak_break_model(),
        evaluate_weekly_forecast_model(),
        evaluate_clustering_model(),
        evaluate_spillover_model(),
        evaluate_reminder_lift_model(),
        evaluate_snooze_disengagement_model(),
        evaluate_target_change_model(),
        evaluate_difficulty_model(),
        evaluate_skip_reason_model(),
        evaluate_engagement_window_model(),
    ]

    summary_path = write_summary(results)
    print(f"\nPodsumowanie Markdown zapisano : {summary_path}")
    print(f"Katalog wykresów               : {PLOTS_DIR_PL}")


if __name__ == "__main__":
    main()
