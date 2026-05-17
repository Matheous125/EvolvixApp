# ml-training/

External Python pipeline for the three TensorFlow Lite models consumed by the
Habit Tracker 3 Android app (engineering thesis project, Phase 6.5).

This folder is **outside the Android Gradle module** and is intentionally kept
in version control so the thesis ML chapter is fully reproducible. The Android
build never invokes anything in here — the only artifacts that cross the
boundary are the trained `*.tflite` files (plus their scaler/vocab JSON
sidecars), which are copied manually into `app/src/main/assets/`.

## Why a separate folder?

- Keeps Python tooling out of the Android Gradle classpath.
- Lets the thesis pipeline (data generation → training → export → evaluation)
  run on any machine with Python 3.10, independent of Android Studio.
- Follows the **Strategy + Dependency Inversion** pattern used in
  `domain/ai/`: the Android side depends on the `HabitPredictor` interface,
  not on how the `.tflite` files were produced.

## Folder layout

```
ml-training/
  requirements.txt
  README.md
  data/                          # generated CSVs + evaluation plots (gitignored)
  models/                        # exported .tflite + scaler/vocab JSON (committed)
  generate_success_data.py       # Model 1 synthetic dataset
  generate_icon_data.py          # Model 2 labeled dataset
  generate_reminder_data.py      # Model 3 synthetic dataset
  train_success_model.py         # Model 1 training + TFLite export
  train_icon_model.py            # Model 2 training + TFLite export
  train_reminder_model.py        # Model 3 training + TFLite export
  evaluate_models.py             # thesis metrics tables + plots
```

## One-time setup (Python 3.10)

TensorFlow 2.14 requires Python 3.10. Newer Python versions will fail to
resolve the `tensorflow==2.14.0` wheel.

### Windows (PowerShell)

```powershell
# from the repository root
cd ml-training
py -3.10 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
```

### macOS / Linux

```bash
cd ml-training
python3.10 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
pip install -r requirements.txt
```

Verify the install:

```bash
python -c "import tensorflow as tf; print(tf.__version__)"
# expected: 2.14.0
```

## Running the pipeline

Each script is standalone and writes its outputs into either `data/` (CSVs,
plots) or `models/` (`.tflite` + JSON sidecars). The intended order is:

```bash
# Model 1 — HabitSuccessClassifier (binary)
python generate_success_data.py
python train_success_model.py

# Model 2 — HabitIconClassifier (17-class text)
python generate_icon_data.py
python train_icon_model.py

# Model 3 — ReminderTemplateClassifier (15-class)
python generate_reminder_data.py
python train_reminder_model.py

# Thesis evaluation report (confusion matrices, ROC, calibration, F1 tables)
python evaluate_models.py
```

## Exporting to Android

After `train_*_model.py` finishes, copy the produced files from
`ml-training/models/` into `app/src/main/assets/`:

| Source (in `models/`)               | Android asset path                            |
| ----------------------------------- | --------------------------------------------- |
| `habit_success_classifier.tflite`   | `app/src/main/assets/habit_success_classifier.tflite` |
| `success_scaler.json`               | `app/src/main/assets/success_scaler.json`     |
| `habit_icon_classifier.tflite`      | `app/src/main/assets/habit_icon_classifier.tflite` |
| `icon_vocab.json`                   | `app/src/main/assets/icon_vocab.json`         |
| `reminder_template_classifier.tflite` | `app/src/main/assets/reminder_template_classifier.tflite` |
| `reminder_scaler.json`              | `app/src/main/assets/reminder_scaler.json`    |

The Android `TfliteHabitPredictor` (Phase 6.5.6) loads these from
`assets/` via `Interpreter` and applies the same normalization /
tokenization that the Python training scripts saved.

## Notes for the thesis

- All synthetic data generators are seeded (`numpy.random.seed`) so runs are
  reproducible.
- Acceptance thresholds for each model are documented in `PLAN.md` §6.5.2–6.5.4.
- `evaluate_models.py` outputs the metrics table that is pasted directly into
  the ML chapter.
