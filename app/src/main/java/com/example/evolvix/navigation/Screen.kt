package com.example.evolvix.navigation

sealed class Screen(val route: String) {
    /**
     * Sealed class representing all possible navigation destinations in the app.
     * Provides type-safe route handling and navigation parameter management.
     */
    
    /** Main screen - displays list of habits */
    object Habits : Screen("habits")
    
    /** Statistics screen - displays habit tracking data */
    object Statistics : Screen("statistics")
    
    /** Add habit screen - form for creating new habits */
    object AddNewHabit : Screen("add_new_habit")
    
    /** Edit habit screen - form for modifying existing habits */
    object EditHabit : Screen("edit_habit/{habitId}") {
        /**
         * Creates a type-safe route with the provided habit ID
         * @param habitId The ID of the habit to edit
         * @return Formatted route string
         */
        fun createRoute(habitId: Int): String {
            val route = "edit_habit/$habitId"
            return route
        }
    }
}