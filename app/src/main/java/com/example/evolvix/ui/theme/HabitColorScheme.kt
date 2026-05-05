package com.example.evolvix.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Enum defining color schemes available for habits.
 * Each scheme provides three color variants:
 * - Background color (with dark/light variants)
 * - Progress color (with dark/light variants)
 * - Text color (with dark/light variants)
 */
enum class HabitColorScheme {
    GREEN,
    BLUE,
    PURPLE,
    RED,
    ORANGE,
    PINK,
    CYAN,
    TEAL,
    INDIGO,
    LIME;

    /**
     * Gets the background color for the habit item
     * @param isDark Whether dark mode is enabled
     * @return Color with appropriate alpha for background
     */
    @Composable
    fun getBackgroundColor(isDark: Boolean): Color = when (this) {
        GREEN -> if (isDark) Color(0xFF1B5E20).copy(alpha = 0.3f) else Color(0xFFE8F5E9)
        BLUE -> if (isDark) Color(0xFF0D47A1).copy(alpha = 0.3f) else Color(0xFFE3F2FD)
        PURPLE -> if (isDark) Color(0xFF4A148C).copy(alpha = 0.3f) else Color(0xFFEDE7F6)
        RED -> if (isDark) Color(0xFFB71C1C).copy(alpha = 0.3f) else Color(0xFFFFEBEE)
        ORANGE -> if (isDark) Color(0xFFE65100).copy(alpha = 0.3f) else Color(0xFFFFF8E1)
        PINK -> if (isDark) Color(0xFF880E4F).copy(alpha = 0.3f) else Color(0xFFFCE4EC)
        CYAN -> if (isDark) Color(0xFF006064).copy(alpha = 0.3f) else Color(0xFFE0F7FA)
        TEAL -> if (isDark) Color(0xFF004D40).copy(alpha = 0.3f) else Color(0xFFE0F2F1)
        INDIGO -> if (isDark) Color(0xFF1A237E).copy(alpha = 0.3f) else Color(0xFFE8EAF6)
        LIME -> if (isDark) Color(0xFF827717).copy(alpha = 0.3f) else Color(0xFFF9FBE7)
    }

     /**
     * Gets the progress bar color for the habit item
     * @param isDark Whether dark mode is enabled
     * @return Color for progress indication
     */
    @Composable
    fun getProgressColor(isDark: Boolean): Color = when (this) {
        GREEN -> if (isDark) Color(0xFF81C784) else Color(0xFF66BB6A)
        BLUE -> if (isDark) Color(0xFF64B5F6) else Color(0xFF42A5F5)
        PURPLE -> if (isDark) Color(0xFF9575CD) else Color(0xFF7E57C2)
        RED -> if (isDark) Color(0xFFE57373) else Color(0xFFEF5350)
        ORANGE -> if (isDark) Color(0xFFFFB74D) else Color(0xFFFFA726)
        PINK -> if (isDark) Color(0xFFF06292) else Color(0xFFEC407A)
        CYAN -> if (isDark) Color(0xFF4DD0E1) else Color(0xFF26C6DA)
        TEAL -> if (isDark) Color(0xFF4DB6AC) else Color(0xFF26A69A)
        INDIGO -> if (isDark) Color(0xFF7986CB) else Color(0xFF5C6BC0)
        LIME -> if (isDark) Color(0xFFDCE775) else Color(0xFFD4E157)
    }

     /**
     * Gets the text color for the habit item
     * @param isDark Whether dark mode is enabled
     * @return Color for text elements
     */
    @Composable
    fun getTextColor(isDark: Boolean): Color = when (this) {
        GREEN -> if (isDark) Color(0xFFE8F5E9) else Color(0xFF1B5E20)
        BLUE -> if (isDark) Color(0xFFE3F2FD) else Color(0xFF0D47A1)
        PURPLE -> if (isDark) Color(0xFFEDE7F6) else Color(0xFF4A148C)
        RED -> if (isDark) Color(0xFFFFEBEE) else Color(0xFFB71C1C)
        ORANGE -> if (isDark) Color(0xFFFFF8E1) else Color(0xFFE65100)
        PINK -> if (isDark) Color(0xFFFCE4EC) else Color(0xFF880E4F)
        CYAN -> if (isDark) Color(0xFFE0F7FA) else Color(0xFF006064)
        TEAL -> if (isDark) Color(0xFFE0F2F1) else Color(0xFF004D40)
        INDIGO -> if (isDark) Color(0xFFE8EAF6) else Color(0xFF1A237E)
        LIME -> if (isDark) Color(0xFF212121) else Color(0xFF827717)
    }
}