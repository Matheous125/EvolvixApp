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

    /** Achievements screen — displays earned and locked achievements with progress. */
    object Achievements : Screen("achievements")

    /** Daily-summary inbox — paginated history of generated summary cards. */
    object SummaryInbox : Screen("summary_inbox")

    /** Settings screen — theme, language, notifications, account, and support. */
    object Settings : Screen("settings")

    /**
     * History screen — shows all completion records for a single habit,
     * grouped by year and month, with edit/delete and retroactive-add support.
     * (Pattern: Sealed Class — type-safe route with Int argument)
     */
    object History : Screen("history/{habitId}/{habitName}") {
        /**
         * Builds the fully-resolved route string for navigation.
         * @param habitId   Primary key of the habit whose history to display.
         * @param habitName Display name forwarded to [HistoryScreen] TopAppBar.
         */
        fun createRoute(habitId: Int, habitName: String): String =
            "history/$habitId/${java.net.URLEncoder.encode(habitName, "UTF-8")}"
    }
}