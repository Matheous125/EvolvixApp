package com.example.evolvix.domain.model

import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitTemplate
import java.time.LocalDateTime

/**
 * Represents the UI state for a habit.
 * Serves a dual role — State Holder pattern (Unidirectional Data Flow):
 *   1. Per-habit display state in [allHabits] list.
 *   2. Form state for the Add/Edit screens via [addHabitFormState].
 * Display-only fields have no meaning in the form context (defaulted);
 * form-only fields have no meaning in the list context (defaulted).
 */
data class HabitUiState(
    /** Unique identifier for the habit */
    val id: Int = 0,

    /** User-defined name of the habit */
    val name: String,

    /** Current progress towards the target */
    val currentCount: Int,

    /** Frequency of habit tracking */
    val frequency: HabitFrequency = HabitFrequency.Daily,

    /** Target count to reach for completion */
    val target: Int,

    /** Total number of progress increments made */
    val totalProgressUpdates: Int = 0,

    /** Number of times target was reached */
    val totalTargetReaches: Int = 0,

    /** Timestamp of last progress reset */
    val lastResetDate: LocalDateTime = LocalDateTime.now(),

    /** Hex color string for the saved habit (e.g. "#4CAF50") */
    val colorHex: String = "#4CAF50",

    /**
     * True when [currentCount] exceeds [target].
     * Computed by the ViewModel during the entity→UiState mapping.
     * The View uses this to render a glowing over-completion border on the card.
     */
    val isOverCompleted: Boolean = false,

    // ── Form-level state fields (used by Add/Edit screens via addHabitFormState) ──

    /**
     * Pre-built habit suggestions shown in the Templates row.
     * Populated from the in-memory [defaultHabitTemplates] list; empty for list items.
     */
    val templates: List<HabitTemplate> = emptyList(),

    /**
     * Categories currently selected via FilterChip in the form.
     * Persisted to [HabitEntity.categories] on save.
     */
    val selectedCategories: Set<String> = emptySet(),

    /**
     * Hex color currently chosen by the color picker in the form.
     * Distinct from [colorHex] (the saved value) so the picker can preview
     * changes before the user confirms.
     */
    val selectedColor: String = "#4CAF50",

    /**
     * The "N" part of the frequency builder (e.g. 3 in "3 times per week").
     * Maps to [targetCount] on save when the frequency unit is chosen.
     */
    val frequencyN: Int = 1,

    /**
     * The time-unit part of the frequency builder (Daily / Weekly / etc.).
     * Maps to [HabitEntity.frequency] on save.
     */
    val frequencyUnit: HabitFrequency = HabitFrequency.Daily,

    /**
     * Target completions per frequency period, as entered in the Target field.
     * Maps to [HabitEntity.target] on save.
     */
    val targetCount: Int = 1
)