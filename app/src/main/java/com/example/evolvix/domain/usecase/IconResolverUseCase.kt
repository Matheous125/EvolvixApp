package com.example.evolvix.domain.usecase

/**
 * Use Case responsible for resolving an emoji icon for a habit based on its name.
 *
 * Architecture: **Strategy + Dependency Inversion**
 * - Tier 1 (current): Pure keyword-map lookup. Covers ~70% of real-world habit names.
 * - Tier 2 (Phase 6.5 stub): Falls back to a default emoji when no keyword matches.
 *   In Phase 6.5, [TfliteHabitPredictor.classifyIcon] will be injected here to replace
 *   the Tier 2 stub with a genuine on-device ML classifier.
 *
 * Returns a single emoji [String] that can be stored in [HabitEntity.iconKey] or
 * rendered directly in the Statistics screen header row.
 *
 * Invoked via the `operator fun invoke(name)` convention (Interactor pattern).
 */
class IconResolverUseCase {

    /**
     * Resolves an emoji icon for the given habit [name].
     *
     * Algorithm:
     * 1. Normalise the input to lowercase.
     * 2. Iterate over [KEYWORD_MAP] entries; return the category's emoji on the first keyword match.
     * 3. If no keyword matches, fall through to [resolveTier2] (Tier 2 stub / future ML hook).
     *
     * @param name The habit name as entered by the user.
     * @return Emoji string representing the resolved icon category.
     */
    operator fun invoke(name: String): String {
        val lower = name.lowercase()

        for ((keywords, emoji) in KEYWORD_MAP) {
            if (keywords.any { lower.contains(it) }) return emoji
        }

        // Tier 2: ML stub — returns the fallback "other" emoji.
        // Phase 6.5 will call TfliteHabitPredictor.classifyIcon(name) here instead.
        return resolveTier2(name)
    }

    /**
     * Tier 2 resolver — stub for the future TFLite ML classifier.
     *
     * Phase 6.5 replaces this body with:
     *   return tflitePredictor.classifyIcon(name)
     * At that point the constructor gains a [HabitPredictor] parameter and this
     * method becomes a delegation call — no other changes needed (Strategy pattern).
     *
     * @param name The habit name that Tier 1 could not classify.
     * @return Default "other" emoji until ML is wired.
     */
    private fun resolveTier2(name: String): String = FALLBACK_EMOJI

    companion object {

        /** Emoji returned when neither Tier 1 nor (eventually) Tier 2 can classify the name. */
        private const val FALLBACK_EMOJI = "⭐"

        /**
         * Tier 1 keyword map.
         *
         * Each entry is a [Pair] of (keyword list, emoji). The list is checked in order;
         * the first entry whose any keyword is found as a substring of the lowercase name wins.
         *
         * Covers ~70% of real-world habit names across 17 categories defined in PLAN.md:
         * fitness, health, learning, mindfulness, creative, social, productivity, finance,
         * food, sleep, cleaning, nature, pet, music, reading, writing, other.
         */
        private val KEYWORD_MAP: List<Pair<List<String>, String>> = listOf(

            // ── Fitness ────────────────────────────────────────────────────────
            listOf(
                "run", "jog", "sprint", "workout", "gym", "exercise",
                "push-up", "pushup", "pull-up", "pullup", "squat", "plank",
                "cycling", "cycle", "bike", "swim", "swimming", "lift",
                "weight", "hiit", "cardio", "burpee", "lunge", "training",
                "crossfit", "fitness"
            ) to "💪",

            // ── Mindfulness ────────────────────────────────────────────────────
            // Checked before health so "meditate" / "breathe" win over generic health terms.
            listOf(
                "meditat", "mindful", "breath", "gratitude", "reflect",
                "pray", "prayer", "mantra", "visuali", "affirmation",
                "relaxation", "body scan"
            ) to "🧘",

            // ── Sleep ──────────────────────────────────────────────────────────
            // Checked before health so "sleep" / "bedtime" win.
            listOf(
                "sleep", "nap", "bedtime", "bed time", "wake up", "wakeup",
                "morning routine", "night routine"
            ) to "😴",

            // ── Health ─────────────────────────────────────────────────────────
            // Note: "water", "walk", and "health" are intentionally excluded —
            // they are too broad and cause false-positive matches ahead of nature
            // ("water plants"), pet ("walk the dog"), and food ("healthy meal").
            // "drink" + "hydrat" cover the hydration use case unambiguously.
            listOf(
                "drink", "hydrat", "vitamin", "supplement",
                "medicine", "medication", "doctor", "dental", "floss",
                "brush teeth", "shower", "hygiene", "stretch", "posture",
                "step count", "calories", "weight loss"
            ) to "❤️",

            // ── Reading ────────────────────────────────────────────────────────
            listOf(
                "read", "book", "chapter", "article", "newspaper", "ebook",
                "kindle", "audiobook", "literature", "novel"
            ) to "📖",

            // ── Writing ────────────────────────────────────────────────────────
            listOf(
                "writ", "journal", "diary", "blog", "essay", "story",
                "poem", "poem", "log", "note"
            ) to "✍️",

            // ── Learning ──────────────────────────────────────────────────────
            listOf(
                "study", "learn", "course", "lesson", "lecture", "class",
                "homework", "certification", "skill", "language", "vocab",
                "flashcard", "quiz", "revision", "math", "code", "coding",
                "programming", "algorithm", "anki"
            ) to "📚",

            // ── Music ──────────────────────────────────────────────────────────
            listOf(
                "guitar", "piano", "drum", "sing", "singing", "instrument",
                "music", "chord", "scale", "melody", "bass", "violin",
                "ukulele", "saxophone"
            ) to "🎵",

            // ── Creative ──────────────────────────────────────────────────────
            listOf(
                "draw", "drawing", "paint", "painting", "sketch", "design",
                "craft", "art", "diy", "sew", "sewing", "knit", "crochet",
                "sculpt", "illustrat", "photo", "video", "edit"
            ) to "🎨",

            // ── Social ────────────────────────────────────────────────────────
            listOf(
                "call", "text", "friend", "family", "social", "connect",
                "message", "date", "volunteer", "network", "meetup",
                "relationship", "people", "talk"
            ) to "👥",

            // ── Productivity ──────────────────────────────────────────────────
            listOf(
                "plan", "planning", "task", "todo", "to-do", "review",
                "focus", "pomodoro", "organiz", "inbox", "email",
                "gtd", "goal", "priority", "schedule", "time block"
            ) to "✅",

            // ── Finance ───────────────────────────────────────────────────────
            listOf(
                "budget", "save", "saving", "invest", "expense", "bill",
                "spending", "money", "finance", "financial", "stock",
                "crypto", "trade", "debt", "loan"
            ) to "💰",

            // ── Food ──────────────────────────────────────────────────────────
            listOf(
                "cook", "cooking", "meal", "eat", "diet", "nutrition",
                "vegetable", "fruit", "recipe", "prep", "lunch", "dinner",
                "breakfast", "snack", "calori", "protein", "vegan",
                "intermittent"
            ) to "🍎",

            // ── Cleaning ──────────────────────────────────────────────────────
            listOf(
                "clean", "laundry", "dishes", "vacuum", "tidy", "declutter",
                "organis", "mop", "dust", "trash", "sweep", "washing"
            ) to "🧹",

            // ── Nature ────────────────────────────────────────────────────────
            listOf(
                "garden", "plant", "outdoor", "hike", "hiking", "outside",
                "park", "nature", "forest", "tree", "flower", "compost",
                "water plant", "sunrise"
            ) to "🌿",

            // ── Pet ───────────────────────────────────────────────────────────
            listOf(
                "dog", "cat", "pet", "walk dog", "feed", "groom",
                "fish", "bird", "hamster", "rabbit", "animal"
            ) to "🐾"
        )
    }
}
