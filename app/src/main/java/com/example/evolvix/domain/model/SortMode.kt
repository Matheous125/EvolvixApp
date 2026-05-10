package com.example.evolvix.domain.model

/**
 * Defines the available sort orders for the habit list.
 * Used by [HabitDao.getHabitsSorted] and exposed via [HabitViewModel] as a StateFlow
 * so the UI can reactively reorder the list without touching the database schema.
 * Declaration order matches the UI menu order (Pattern: Enum as Strategy).
 */
enum class SortMode {
    /** Creation order: habits are sorted by [HabitEntity.id] ascending (oldest first). */
    DEFAULT,
    /** Alphabetical order by habit name (A → Z). */
    NAME,
    /** Reverse-alphabetical order by habit name (Z → A). */
    NAME_DESC,
    /** Cadence ascending: Daily → Weekly → Monthly → Yearly, then by [HabitEntity.frequencyN] ASC,
     *  ties broken alphabetically by name. */
    FREQ_ASC,
    /** Cadence descending: Yearly → Monthly → Weekly → Daily, then by [HabitEntity.frequencyN] DESC,
     *  ties broken alphabetically by name. */
    FREQ_DESC,
    /** Grouped by [HabitEntity.categoryGroup] alphabetically; items within a group sorted by name. */
    CATEGORY,
    /** User-defined drag-and-drop order, stored as [HabitEntity.sortOrder]. */
    CUSTOM
}
