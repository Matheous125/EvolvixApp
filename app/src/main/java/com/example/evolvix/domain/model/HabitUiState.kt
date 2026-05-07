package com.example.evolvix.domain.model

import com.example.evolvix.data.model.HabitFrequency
import java.time.LocalDateTime

/**
 * Represents the UI state for a habit.
 * Optimized for Jetpack Compose with immutable properties.
 * Acts as a bridge between domain layer and UI layer.
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

    /** Hex color string for the habit (e.g. "#4CAF50") */
    val colorHex: String = "#4CAF50",

    /**
     * True when [currentCount] exceeds [target].
     * Computed by the ViewModel during the entity→UiState mapping.
     * The View uses this to render a glowing over-completion border on the card.
     */
    val isOverCompleted: Boolean = false
)