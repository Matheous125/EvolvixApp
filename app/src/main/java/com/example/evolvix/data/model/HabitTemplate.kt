package com.example.evolvix.data.model

/**
 * Represents a pre-built habit suggestion shown to the user on the Add Habit screen.
 * This is a plain in-memory data class — it is NOT a Room entity.
 * Acts as a simple Value Object seeded from HABIT-TEMPLATES.MD.
 *
 * @param name     Display name of the habit.
 * @param frequency The recurrence period (Daily / Weekly / etc.).
 * @param target   How many times the habit should be completed per period.
 * @param category Broad category label (e.g. "Health", "Fitness").
 * @param colorHex Hex color string used as the habit's default color (e.g. "#00BCD4").
 */
data class HabitTemplate(
    val name: String,
    val frequency: HabitFrequency,
    val target: Int,
    val category: String,
    val colorHex: String
)

/**
 * Singleton list of default habit templates.
 * Loaded once at runtime — no DB reads needed.
 * Source: HABIT-TEMPLATES.MD
 */
val defaultHabitTemplates: List<HabitTemplate> = listOf(
    HabitTemplate(
        name = "Drink Water",
        frequency = HabitFrequency.Daily,
        target = 8,
        category = "Health",
        colorHex = "#00BCD4"   // Cyan
    ),
    HabitTemplate(
        name = "Exercise",
        frequency = HabitFrequency.Weekly,
        target = 3,
        category = "Fitness",
        colorHex = "#4CAF50"   // Green
    ),
    HabitTemplate(
        name = "Read",
        frequency = HabitFrequency.Daily,
        target = 1,
        category = "Mindfulness",
        colorHex = "#3F51B5"   // Indigo
    ),
    HabitTemplate(
        name = "Meditate",
        frequency = HabitFrequency.Daily,
        target = 1,
        category = "Mindfulness",
        colorHex = "#9C27B0"   // Purple
    ),
    HabitTemplate(
        name = "Walk",
        frequency = HabitFrequency.Daily,
        target = 1,
        category = "Fitness",
        colorHex = "#8BC34A"   // Lime
    ),
    HabitTemplate(
        name = "Journal",
        frequency = HabitFrequency.Daily,
        target = 1,
        category = "Mindfulness",
        colorHex = "#FF9800"   // Orange
    ),
    HabitTemplate(
        name = "Stretch",
        frequency = HabitFrequency.Daily,
        target = 1,
        category = "Fitness",
        colorHex = "#009688"   // Teal
    ),
    HabitTemplate(
        name = "Plan Your Day",
        frequency = HabitFrequency.Daily,
        target = 1,
        category = "Productivity",
        colorHex = "#2196F3"   // Blue
    )
)
