package com.example.evolvix.domain.usecase

import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.data.model.HabitEntity
import com.example.evolvix.domain.model.LifeBalanceEntry
import java.time.LocalDate

/**
 * Use Case / Interactor that computes per-category completion rates over a rolling date window.
 *
 * Each returned [LifeBalanceEntry] represents one life-balance category (e.g. "Health", "Fitness").
 * Habits with no assigned categories are grouped under the synthetic [UNCATEGORIZED_LABEL] bucket.
 * The result drives the "Life Balance" card in StatisticsScreen (Pattern: Use Case per query).
 *
 * Note: a habit can belong to multiple categories; each (habit, category) pair contributes
 * independently to that category's rate, which correctly reflects multi-area effort.
 *
 * This is a pure-function interactor — no side effects, no Room or ViewModel dependencies.
 */
class LifeBalanceUseCase {

    companion object {
        /** Synthetic category label used for habits that have no assigned categories. */
        const val UNCATEGORIZED_LABEL = "Other"
    }

    /**
     * Computes a [List<LifeBalanceEntry>] sorted alphabetically by category name.
     *
     * Algorithm:
     * 1. Filter completions to the [windowDays]-day window where [isTargetReached] is true,
     *    producing a `Set<Pair<habitId, date>>` for O(1) per-pair lookup.
     * 2. Expand each habit into its categories (or "Other" if none).
     * 3. For each category, count completed (habit, day) pairs and divide by expected pairs
     *    (habitCount × windowDays), clamped to [0.0, 1.0].
     *
     * @param habits All active [HabitEntity] records to analyse.
     * @param completions All [HabitCompletionEntity] records for these habits.
     * @param windowDays Number of calendar days to look back, inclusive (default 30).
     * @param today Window end date (injectable for testing; defaults to [LocalDate.now]).
     * @return Alphabetically sorted list of [LifeBalanceEntry] — one per distinct category.
     */
    operator fun invoke(
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>,
        windowDays: Int = 30,
        today: LocalDate = LocalDate.now()
    ): List<LifeBalanceEntry> {
        val windowStart = today.minusDays(windowDays.toLong() - 1)

        // Build a set of (habitId, date) pairs where the target was reached within the window.
        val reachedInWindow: Set<Pair<Int, LocalDate>> = completions
            .filter { it.isTargetReached }
            .mapNotNull { record ->
                val date = record.progressUpdate.toLocalDate()
                if (!date.isBefore(windowStart) && !date.isAfter(today)) {
                    record.habitId to date
                } else null
            }
            .toSet()

        // Group habits by category. A habit with multiple categories contributes to each one.
        val categoryToHabits = mutableMapOf<String, MutableList<HabitEntity>>()
        for (habit in habits) {
            val cats = if (habit.categories.isEmpty()) listOf(UNCATEGORIZED_LABEL) else habit.categories
            for (cat in cats) {
                categoryToHabits.getOrPut(cat) { mutableListOf() }.add(habit)
            }
        }

        return categoryToHabits.map { (category, habitsInCategory) ->
            val habitIds = habitsInCategory.map { it.id }.toSet()
            // Count distinct (habitId, date) pairs in the window that belong to this category.
            val completedPairs = reachedInWindow.count { (habitId, _) -> habitId in habitIds }
            val expectedPairs = habitsInCategory.size * windowDays
            val rate = if (expectedPairs == 0) 0f
                       else (completedPairs.toFloat() / expectedPairs).coerceIn(0f, 1f)
            LifeBalanceEntry(category = category, completionRate = rate, habitCount = habitsInCategory.size)
        }.sortedBy { it.category }
    }
}
