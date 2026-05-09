package com.example.evolvix.domain.model

/**
 * Defines the available sort orders for the habit list.
 * Used by [HabitDao.getHabitsSorted] and exposed via [HabitViewModel] as a StateFlow
 * so the UI can reactively reorder the list without touching the database schema.
 * (Pattern: Enum as Strategy — each value selects a different query at runtime)
 */
enum class SortMode {
    /** User-defined drag-and-drop order, stored as [HabitEntity.sortOrder]. */
    MANUAL,
    /** Alphabetical order by habit name (A → Z). */
    NAME,
    /** Grouped by [HabitEntity.categoryGroup], then by manual sort order within each group. */
    CATEGORY
}
