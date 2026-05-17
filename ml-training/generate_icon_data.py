"""
generate_icon_data.py — Synthetic dataset for Model 2 (HabitIconClassifier).

Phase 6.5.3 of PLAN.md.

Pipeline:
    1. A hand-written SEED dictionary: ~30 representative habit-name phrases per
       category, across the 17 categories enumerated in PLAN.md:
           fitness, health, learning, mindfulness, creative, social,
           productivity, finance, food, sleep, cleaning, nature, pet,
           music, reading, writing, other.
    2. Synonym + template augmentation:
           - prefix templates  ("morning {x}", "evening {x}", "daily {x}", ...)
           - suffix templates  ("{x} 10 min", "{x} for 20 minutes", ...)
       This expands the ~500 seeds to ~2,000 augmented examples — matching
       PLAN.md §6.5.3 ("Hand-write ~500 ... Augment via simple synonyms ...
       to reach ~2,000 examples").
    3. The augmented (name, label) pairs are deduplicated, shuffled, and
       persisted to ml-training/data/icon_dataset.csv with columns:
           name,label

The dataset is intentionally small, broad, and human-readable — these are the
exact phrases a thesis reviewer would expect a real user to type into the
"new habit" form.

Usage:
    python generate_icon_data.py
"""

from __future__ import annotations

import argparse
import csv
import random
from pathlib import Path


# ---------------------------------------------------------------------------
# 17 categories — order matches PLAN.md §6.5.3 verbatim. Used as the label
# vocabulary for the classifier (argmax -> label index -> name string).
# ---------------------------------------------------------------------------
LABELS: list[str] = [
    "fitness",
    "health",
    "learning",
    "mindfulness",
    "creative",
    "social",
    "productivity",
    "finance",
    "food",
    "sleep",
    "cleaning",
    "nature",
    "pet",
    "music",
    "reading",
    "writing",
    "other",
]


# ---------------------------------------------------------------------------
# SEED phrases per category — hand-curated representative habit names.
# Target: ~30 per category × 17 ≈ ~500 seeds (PLAN.md §6.5.3).
# Phrases stay short and realistic; they emulate what real users type.
# ---------------------------------------------------------------------------
SEEDS: dict[str, list[str]] = {
    "fitness": [
        "run", "morning run", "jog", "go for a jog", "sprint",
        "workout", "gym", "exercise", "push ups", "pull ups",
        "squats", "plank", "cycling", "ride bike", "swim",
        "swimming", "lift weights", "deadlift", "bench press",
        "hiit session", "cardio", "burpees", "lunges", "strength training",
        "crossfit", "fitness routine", "soccer practice", "basketball",
        "tennis", "boxing",
    ],
    "health": [
        "drink water", "hydrate", "take vitamins", "take supplement",
        "take medicine", "doctor appointment", "dental checkup", "floss teeth",
        "brush teeth", "shower", "hygiene routine", "stretch",
        "improve posture", "track steps", "weigh in",
        "blood pressure check", "skincare", "wash face",
        "take iron supplement", "take vitamin d", "drink 2 liters water",
        "morning hydration", "evening stretch", "take probiotic",
        "monthly checkup", "physiotherapy", "eye exercises",
        "wear sunscreen", "limit caffeine",
    ],
    "learning": [
        "study", "study math", "learn spanish", "duolingo lesson",
        "online course", "watch lecture", "attend class", "do homework",
        "certification prep", "practice skill", "learn vocab",
        "flashcards", "anki review", "quiz myself", "revise notes",
        "learn algorithm", "leetcode problem", "code practice",
        "programming exercise", "study chemistry", "study history",
        "learn french", "language exchange", "tutorial video",
        "khan academy lesson", "udemy course", "research paper read",
        "memorize formulas", "practice typing", "study for exam",
    ],
    "mindfulness": [
        "meditate", "morning meditation", "mindfulness practice",
        "breathing exercise", "deep breaths", "gratitude journal",
        "reflect on day", "say prayer", "evening prayer", "mantra recital",
        "visualization", "positive affirmation", "relaxation exercise",
        "body scan", "headspace session", "calm app session",
        "5 minute meditation", "10 minute mindfulness", "guided meditation",
        "breath work", "box breathing", "loving kindness meditation",
        "silent sitting", "mindful walk", "mindful eating",
        "intention setting", "evening reflection", "vipassana",
        "yoga nidra", "zen practice",
    ],
    "creative": [
        "draw", "sketch", "paint", "watercolor", "digital painting",
        "design", "graphic design", "do crafts", "art practice",
        "diy project", "sew", "knit", "crochet", "sculpt",
        "illustration", "photography", "edit photos", "video editing",
        "make collage", "calligraphy", "doodle", "pottery",
        "origami", "scrapbooking", "design poster", "ui design",
        "creative writing prompt", "draw portrait", "draw landscape",
        "logo design",
    ],
    "social": [
        "call mom", "call dad", "call friend", "text friend",
        "family dinner", "social meetup", "connect with friend",
        "send message", "date night", "volunteer", "networking event",
        "meetup", "improve relationship", "talk to colleague",
        "have conversation", "video call grandparents", "write letter to friend",
        "send birthday wish", "thank someone", "compliment a friend",
        "join community", "attend party", "host dinner",
        "coffee with friend", "lunch with coworker", "reconnect with old friend",
        "group chat reply", "social walk", "club meeting",
        "mentor someone",
    ],
    "productivity": [
        "plan day", "daily planning", "task review", "todo list",
        "review priorities", "focus session", "pomodoro",
        "organize desk", "inbox zero", "process email", "gtd review",
        "set weekly goals", "schedule tasks", "time blocking",
        "morning planning", "evening review", "calendar review",
        "deep work block", "shallow work batch", "weekly review",
        "monthly planning", "clear notifications", "process inbox",
        "task triage", "stand up notes", "sprint planning",
        "project milestone check", "personal okrs", "kanban update",
        "shutdown ritual",
    ],
    "finance": [
        "budget review", "track expenses", "save money",
        "invest in index fund", "pay bills", "review spending",
        "money diary", "financial planning", "buy stock",
        "check portfolio", "crypto check", "rebalance investments",
        "log debt payoff", "loan payment", "review bank statement",
        "side hustle accounting", "tax preparation", "envelope budgeting",
        "ynab review", "mint check", "set savings goal",
        "emergency fund deposit", "401k contribution", "ira deposit",
        "log subscription", "review subscriptions", "negotiate bill",
        "check credit score", "buy etf", "review insurance",
    ],
    "food": [
        "cook dinner", "prepare lunch", "meal prep", "eat healthy",
        "diet plan", "track nutrition", "eat vegetables", "eat fruit",
        "try new recipe", "weekly meal plan", "prepare breakfast",
        "cook lunch", "pack lunch", "snack healthy", "track calories intake",
        "eat protein", "vegan meal", "intermittent fasting",
        "eat salad", "no sugar today", "low carb meal",
        "drink smoothie", "eat oatmeal", "prepare bento",
        "batch cook sunday", "try new cuisine", "bake bread",
        "ferment vegetables", "cook from scratch", "no junk food",
    ],
    "sleep": [
        "sleep early", "sleep 8 hours", "bedtime routine",
        "go to bed by 10", "no screens before bed", "wake up early",
        "wake at 6", "morning routine", "night routine",
        "take short nap", "afternoon nap", "consistent sleep schedule",
        "lights out by 11", "sleep tracking", "track sleep",
        "evening wind down", "no caffeine after 2pm", "read before sleep",
        "blue light off", "magnesium before bed", "cold bedroom",
        "no late dinners", "stretch before bed", "early to bed",
        "fixed wake time", "sleep mask on", "white noise machine",
        "weekend sleep in", "alarm clock setup", "deep sleep tracking",
    ],
    "cleaning": [
        "clean kitchen", "wash dishes", "do laundry", "fold laundry",
        "vacuum living room", "tidy bedroom", "declutter closet",
        "organize drawer", "mop floor", "dust shelves",
        "take out trash", "sweep porch", "clean bathroom",
        "wipe counters", "change bedsheets", "clean fridge",
        "empty dishwasher", "wash windows", "polish furniture",
        "clean car interior", "scrub shower", "organize garage",
        "clean desk", "shred old papers", "wash car",
        "minimalism check", "donate clothes", "clean oven",
        "sort recycling", "clean microwave",
    ],
    "nature": [
        "water plants", "garden", "weed garden", "go outside",
        "hike", "morning hike", "go outdoors", "walk in park",
        "nature walk", "forest bath", "plant tree", "tend flowers",
        "compost food scraps", "watch sunrise", "watch sunset",
        "stargazing", "birdwatching", "tend vegetable garden",
        "outdoor picnic", "kayak on lake", "camp overnight",
        "fishing trip", "beach visit", "river walk", "mountain trail",
        "feed birds", "build compost bin", "biking outdoors",
        "barefoot grounding", "sunlight exposure",
    ],
    "pet": [
        "walk dog", "feed cat", "feed dog", "groom dog",
        "groom cat", "clean litter box", "play with dog",
        "play with cat", "vet appointment", "train puppy",
        "brush dog", "brush cat", "feed fish", "clean fish tank",
        "feed bird", "clean bird cage", "feed hamster", "clean hamster cage",
        "feed rabbit", "exercise rabbit", "dog park visit",
        "puppy training session", "buy pet food", "trim pet nails",
        "pet medication", "deworm pet", "flea treatment", "pet bath",
        "play fetch with dog", "cuddle pet",
    ],
    "music": [
        "guitar practice", "play piano", "practice drums", "sing scales",
        "vocal practice", "learn new song", "music theory study",
        "chord practice", "scale practice", "compose melody",
        "bass practice", "violin practice", "ukulele practice",
        "saxophone practice", "instrument warm up", "ear training",
        "songwriting", "practice arpeggios", "improv session",
        "record music demo", "music lesson", "metronome practice",
        "rhythm exercise", "music transcription", "sight reading",
        "harmonica practice", "drum rudiments", "fingerstyle guitar",
        "vocal warm up", "jam session",
    ],
    "reading": [
        "read book", "read chapter", "read article", "read newspaper",
        "read ebook", "read kindle", "listen audiobook",
        "read literature", "read novel", "read poetry",
        "read non fiction", "read 20 pages", "read 30 minutes",
        "morning reading", "evening reading", "read before bed",
        "read tech article", "read philosophy", "read biography",
        "read history book", "book club reading", "read short story",
        "read essay collection", "read magazine", "read scripture",
        "read science book", "read research paper", "read newsletter",
        "read manga", "read comic",
    ],
    "writing": [
        "write journal", "daily journal", "morning pages",
        "write diary", "blog post", "write essay", "write story",
        "write poem", "writing practice", "write log",
        "take notes", "journal reflection", "gratitude log",
        "free writing", "write letter", "write 500 words",
        "write novel chapter", "writing sprint", "write blog draft",
        "write thesis section", "write report", "write proposal",
        "write song lyrics", "write screenplay", "write haiku",
        "creative writing exercise", "blog publish",
        "write newsletter", "edit my writing", "write reflection",
    ],
    "other": [
        "miscellaneous task", "personal project", "random habit",
        "do something new", "experiment", "weekly challenge",
        "self improvement task", "side quest", "monthly review",
        "lifestyle change", "personal admin", "errand run",
        "appointment day", "renew documents", "passport renewal",
        "fix something", "repair task", "household admin",
        "general task", "weekend errand", "buy groceries",
        "grocery shopping", "pickup package", "post office visit",
        "library visit", "haircut appointment", "car maintenance",
        "phone backup", "update software", "reset router",
    ],
}


# ---------------------------------------------------------------------------
# Augmentation templates. Each seed phrase is combined with prefixes/suffixes
# to multiply variation roughly 4x → ~500 seeds → ~2,000 examples.
# ---------------------------------------------------------------------------
PREFIXES: list[str] = [
    "", "morning ", "evening ", "daily ", "weekly ", "quick ",
    "do ", "complete ", "finish ", "start ", "10 min ", "30 min ",
]

SUFFIXES: list[str] = [
    "", " 10 min", " 20 min", " 30 minutes", " today", " session",
    " routine", " for 15 minutes",
]


def _augment_seed(seed: str, rng: random.Random) -> list[str]:
    """Expand one seed into ~4 augmented variants.

    Strategy: sample a small number of (prefix, suffix) combinations from the
    Cartesian product. Always include the bare seed so the model sees the
    canonical form. Variants are lowercase and stripped.
    """
    variants: set[str] = {seed.strip().lower()}

    # Pick 3 random prefix/suffix combos for breadth without bloating dataset.
    for _ in range(3):
        prefix = rng.choice(PREFIXES)
        suffix = rng.choice(SUFFIXES)
        variant = f"{prefix}{seed}{suffix}".strip().lower()
        # Collapse internal whitespace.
        variant = " ".join(variant.split())
        variants.add(variant)

    return list(variants)


def generate(seed: int = 42) -> list[tuple[str, str]]:
    """Build the full augmented (name, label) list.

    Returns a deduplicated, shuffled list. Label = category string.
    Duplicates across categories (e.g. an augmented phrase that could plausibly
    belong to two labels) are resolved by first-write-wins on the dict — this
    keeps the dataset consistent and prevents label noise from leaking in.
    """
    rng = random.Random(seed)
    rows: dict[str, str] = {}  # name → label

    for label, phrases in SEEDS.items():
        for phrase in phrases:
            for variant in _augment_seed(phrase, rng):
                if variant not in rows:
                    rows[variant] = label

    pairs = list(rows.items())
    rng.shuffle(pairs)
    return pairs


def output_path() -> Path:
    here = Path(__file__).resolve().parent
    return here / "data" / "icon_dataset.csv"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for augmentation shuffling (default: 42).",
    )
    args = parser.parse_args()

    pairs = generate(seed=args.seed)
    out = output_path()
    out.parent.mkdir(parents=True, exist_ok=True)

    with out.open("w", newline="", encoding="utf-8") as fh:
        writer = csv.writer(fh)
        writer.writerow(["name", "label"])
        writer.writerows(pairs)

    # Per-class summary — useful for spotting class imbalance before training.
    counts: dict[str, int] = {label: 0 for label in LABELS}
    for _, label in pairs:
        counts[label] = counts.get(label, 0) + 1

    print(f"Wrote {len(pairs):,} rows to {out}")
    print("Per-class counts:")
    for label in LABELS:
        print(f"  {label:<14} {counts[label]:>4}")


if __name__ == "__main__":
    main()
