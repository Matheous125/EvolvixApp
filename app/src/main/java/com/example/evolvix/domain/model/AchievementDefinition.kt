package com.example.evolvix.domain.model

/**
 * Categorises achievements into logical display groups.
 * Used by the UI to render collapsible sections in [AchievementsScreen].
 */
enum class AchievementGroup {
    GETTING_STARTED,
    STREAKS,
    MILESTONES,
    TIME_OF_DAY,
    WEEKLY,
    ORGANIZATION,
    GOD_TIER
}

/**
 * Sealed class hierarchy describing every achievement available in the app.
 *
 * Pattern: **Strategy + Sealed Class polymorphism** — each `object` is a
 * concrete strategy uniquely identified by [key]. The evaluator in
 * [EvaluateAchievementsUseCase] dispatches on the concrete type via exhaustive
 * `when` expressions, guaranteeing compile-time coverage of all 50 achievements.
 *
 * This class is **pure data** — no Room annotations, no Android dependencies.
 *
 * @property key        Stable string identifier that matches [AchievementEntity.key].
 * @property title      Short display name shown in the UI.
 * @property description Human-readable unlock condition.
 * @property points     Score awarded when unlocked.
 * @property icon       Emoji icon representing the achievement.
 * @property group      Category used for UI grouping.
 * @property threshold  Primary numeric threshold the evaluator compares against
 *                      (e.g. streak length, total completions). Binary
 *                      achievements that require no count use the default of 1.
 */
sealed class AchievementDefinition(
    val key: String,
    val title: String,
    val description: String,
    val points: Int,
    val icon: String,
    val group: AchievementGroup,
    val threshold: Int = 1
) {

    // ── Group 1: Getting Started (6) ─────────────────────────────────────────

    /** Create your very first habit. */
    object FirstStep : AchievementDefinition(
        key = "FIRST_STEP", title = "First Step",
        description = "Create your very first habit.",
        points = 10, icon = "🌱", group = AchievementGroup.GETTING_STARTED
    )

    /** Complete your first habit. */
    object ActionTaker : AchievementDefinition(
        key = "ACTION_TAKER", title = "Action Taker",
        description = "Complete your first habit.",
        points = 10, icon = "🏁", group = AchievementGroup.GETTING_STARTED
    )

    /** Complete 2 habits in a single day. */
    object DoubleTrouble : AchievementDefinition(
        key = "DOUBLE_TROUBLE", title = "Double Trouble",
        description = "Complete 2 habits in a single day.",
        points = 20, icon = "✌️", group = AchievementGroup.GETTING_STARTED, threshold = 2
    )

    /** Complete 3 habits in a single day. */
    object ThreesACharm : AchievementDefinition(
        key = "THREES_A_CHARM", title = "Three's a Charm",
        description = "Complete 3 habits in a single day.",
        points = 20, icon = "🍀", group = AchievementGroup.GETTING_STARTED, threshold = 3
    )

    /** Complete 100% of your scheduled habits for the day. */
    object PerfectDay : AchievementDefinition(
        key = "PERFECT_DAY", title = "Perfect Day",
        description = "Complete 100% of your scheduled habits for the day.",
        points = 50, icon = "🎯", group = AchievementGroup.GETTING_STARTED
    )

    /** Complete a habit the day after missing it. */
    object TheComeback : AchievementDefinition(
        key = "THE_COMEBACK", title = "The Comeback",
        description = "Complete a habit the day after missing it.",
        points = 30, icon = "🔄", group = AchievementGroup.GETTING_STARTED
    )

    // ── Group 2: Streaks & Consistency (11) ──────────────────────────────────

    /** Reach a 3-day streak on any habit. */
    object WarmingUp : AchievementDefinition(
        key = "WARMING_UP", title = "Warming Up",
        description = "Reach a 3-day streak on any habit.",
        points = 20, icon = "🏕️", group = AchievementGroup.STREAKS, threshold = 3
    )

    /** Reach a 7-day streak on any habit. */
    object Unstoppable : AchievementDefinition(
        key = "UNSTOPPABLE", title = "Unstoppable",
        description = "Reach a 7-day streak on any habit.",
        points = 50, icon = "🚀", group = AchievementGroup.STREAKS, threshold = 7
    )

    /** Reach a 14-day streak on any habit. */
    object Fortnight : AchievementDefinition(
        key = "FORTNIGHT", title = "Fortnight",
        description = "Reach a 14-day streak on any habit.",
        points = 100, icon = "🛡️", group = AchievementGroup.STREAKS, threshold = 14
    )

    /** Reach a 21-day streak on any habit. */
    object HabitForming : AchievementDefinition(
        key = "HABIT_FORMING", title = "Habit Forming",
        description = "Reach a 21-day streak. (Science says it's a habit now!)",
        points = 150, icon = "🧠", group = AchievementGroup.STREAKS, threshold = 21
    )

    /** Reach a 30-day streak on any habit. */
    object MonthlyMaster : AchievementDefinition(
        key = "MONTHLY_MASTER", title = "Monthly Master",
        description = "Reach a 30-day streak on any habit.",
        points = 200, icon = "🗓️", group = AchievementGroup.STREAKS, threshold = 30
    )

    /** Reach a 60-day streak on any habit. */
    object SoaringHigh : AchievementDefinition(
        key = "SOARING_HIGH", title = "Soaring High",
        description = "Reach a 60-day streak on any habit.",
        points = 250, icon = "🦅", group = AchievementGroup.STREAKS, threshold = 60
    )

    /** Reach a 100-day streak on any habit. */
    object CenturyClub : AchievementDefinition(
        key = "CENTURY_CLUB", title = "Century Club",
        description = "Reach a 100-day streak on any habit.",
        points = 500, icon = "💯", group = AchievementGroup.STREAKS, threshold = 100
    )

    /** Reach a 180-day streak on any habit. */
    object HalfAYear : AchievementDefinition(
        key = "HALF_A_YEAR", title = "Half a Year",
        description = "Reach a 180-day streak on any habit.",
        points = 750, icon = "🌗", group = AchievementGroup.STREAKS, threshold = 180
    )

    /** Reach a 365-day streak on any habit. */
    object FullCircle : AchievementDefinition(
        key = "FULL_CIRCLE", title = "Full Circle",
        description = "Reach a 365-day streak on any habit.",
        points = 1000, icon = "🌍", group = AchievementGroup.STREAKS, threshold = 365
    )

    /** Maintain a 7-day streak on 3 different habits simultaneously. */
    object Juggler : AchievementDefinition(
        key = "JUGGLER", title = "Juggler",
        description = "Maintain a 7-day streak on 3 different habits at once.",
        points = 150, icon = "🤹", group = AchievementGroup.STREAKS, threshold = 7
    )

    /** Maintain a 30-day streak on 3 different habits simultaneously. */
    object Multitasker : AchievementDefinition(
        key = "MULTITASKER", title = "Multitasker",
        description = "Maintain a 30-day streak on 3 different habits at once.",
        points = 400, icon = "🐙", group = AchievementGroup.STREAKS, threshold = 30
    )

    // ── Group 3: Lifetime Milestones (10) ────────────────────────────────────

    /** Complete 10 total habits. */
    object Novice : AchievementDefinition(
        key = "NOVICE", title = "Novice",
        description = "Complete 10 total habits.",
        points = 20, icon = "🥉", group = AchievementGroup.MILESTONES, threshold = 10
    )

    /** Complete 50 total habits. */
    object Apprentice : AchievementDefinition(
        key = "APPRENTICE", title = "Apprentice",
        description = "Complete 50 total habits.",
        points = 50, icon = "🥈", group = AchievementGroup.MILESTONES, threshold = 50
    )

    /** Complete 100 total habits. */
    object Journeyman : AchievementDefinition(
        key = "JOURNEYMAN", title = "Journeyman",
        description = "Complete 100 total habits.",
        points = 100, icon = "🥇", group = AchievementGroup.MILESTONES, threshold = 100
    )

    /** Complete 250 total habits. */
    object Expert : AchievementDefinition(
        key = "EXPERT", title = "Expert",
        description = "Complete 250 total habits.",
        points = 200, icon = "🏅", group = AchievementGroup.MILESTONES, threshold = 250
    )

    /** Complete 500 total habits. */
    object Master : AchievementDefinition(
        key = "MASTER", title = "Master",
        description = "Complete 500 total habits.",
        points = 350, icon = "🎖️", group = AchievementGroup.MILESTONES, threshold = 500
    )

    /** Complete 1,000 total habits. */
    object Grandmaster : AchievementDefinition(
        key = "GRANDMASTER", title = "Grandmaster",
        description = "Complete 1,000 total habits.",
        points = 500, icon = "👑", group = AchievementGroup.MILESTONES, threshold = 1000
    )

    /** Complete 2,500 total habits. */
    object Legend : AchievementDefinition(
        key = "LEGEND", title = "Legend",
        description = "Complete 2,500 total habits.",
        points = 750, icon = "🐉", group = AchievementGroup.MILESTONES, threshold = 2500
    )

    /** Complete 5,000 total habits. */
    object Mythic : AchievementDefinition(
        key = "MYTHIC", title = "Mythic",
        description = "Complete 5,000 total habits.",
        points = 1000, icon = "🌌", group = AchievementGroup.MILESTONES, threshold = 5000
    )

    /** Complete 10,000 total habits. */
    object TenKClub : AchievementDefinition(
        key = "TEN_K_CLUB", title = "10k Club",
        description = "Complete 10,000 total habits.",
        points = 2000, icon = "💎", group = AchievementGroup.MILESTONES, threshold = 10000
    )

    /** Complete 365 habits total. */
    object AYearInActions : AchievementDefinition(
        key = "A_YEAR_IN_ACTIONS", title = "A Year in Actions",
        description = "Complete 365 habits total.",
        points = 250, icon = "📆", group = AchievementGroup.MILESTONES, threshold = 365
    )

    // ── Group 4: Time of Day (8) ──────────────────────────────────────────────

    /** Complete a habit before 7:00 AM. */
    object EarlyBird : AchievementDefinition(
        key = "EARLY_BIRD", title = "Early Bird",
        description = "Complete a habit before 7:00 AM.",
        points = 30, icon = "🌅", group = AchievementGroup.TIME_OF_DAY
    )

    /** Complete 3 habits before 9:00 AM. */
    object BreakfastChampion : AchievementDefinition(
        key = "BREAKFAST_CHAMPION", title = "Breakfast Champion",
        description = "Complete 3 habits before 9:00 AM.",
        points = 50, icon = "🥞", group = AchievementGroup.TIME_OF_DAY, threshold = 3
    )

    /** Complete a habit between 12:00 PM and 1:00 PM. */
    object HighNoon : AchievementDefinition(
        key = "HIGH_NOON", title = "High Noon",
        description = "Complete a habit exactly between 12:00 PM and 1:00 PM.",
        points = 30, icon = "☀️", group = AchievementGroup.TIME_OF_DAY
    )

    /** Complete 3 habits between 1:00 PM and 5:00 PM. */
    object AfternoonHustle : AchievementDefinition(
        key = "AFTERNOON_HUSTLE", title = "Afternoon Hustle",
        description = "Complete 3 habits between 1:00 PM and 5:00 PM.",
        points = 50, icon = "☕", group = AchievementGroup.TIME_OF_DAY, threshold = 3
    )

    /** Complete a habit after 10:00 PM. */
    object NightOwl : AchievementDefinition(
        key = "NIGHT_OWL", title = "Night Owl",
        description = "Complete a habit after 10:00 PM.",
        points = 30, icon = "🦉", group = AchievementGroup.TIME_OF_DAY
    )

    /** Complete a habit after midnight. */
    object MidnightOil : AchievementDefinition(
        key = "MIDNIGHT_OIL", title = "Midnight Oil",
        description = "Complete a habit after midnight.",
        points = 40, icon = "🦇", group = AchievementGroup.TIME_OF_DAY
    )

    /** Complete one habit before 8 AM and another after 8 PM on the same day. */
    object Bookends : AchievementDefinition(
        key = "BOOKENDS", title = "Bookends",
        description = "Complete one habit before 8 AM and another after 8 PM.",
        points = 60, icon = "🌉", group = AchievementGroup.TIME_OF_DAY
    )

    /** Complete the same habit at the same hour 3 days in a row. */
    object Clockwork : AchievementDefinition(
        key = "CLOCKWORK", title = "Clockwork",
        description = "Complete the same habit at the exact same hour 3 days in a row.",
        points = 100, icon = "⚙️", group = AchievementGroup.TIME_OF_DAY, threshold = 3
    )

    // ── Group 5: Weekly Warriors (7) ─────────────────────────────────────────

    /** Complete all scheduled habits on a Monday. */
    object MondayMotivation : AchievementDefinition(
        key = "MONDAY_MOTIVATION", title = "Monday Motivation",
        description = "Complete all scheduled habits on a Monday.",
        points = 30, icon = "☕", group = AchievementGroup.WEEKLY
    )

    /** Complete all scheduled habits on a Wednesday. */
    object HumpDayHero : AchievementDefinition(
        key = "HUMP_DAY_HERO", title = "Hump Day Hero",
        description = "Complete all scheduled habits on a Wednesday.",
        points = 30, icon = "🐫", group = AchievementGroup.WEEKLY
    )

    /** Complete all scheduled habits on a Friday. */
    object TGIF : AchievementDefinition(
        key = "TGIF", title = "TGIF",
        description = "Complete all scheduled habits on a Friday.",
        points = 30, icon = "🍻", group = AchievementGroup.WEEKLY
    )

    /** Complete at least one habit on both Saturday and Sunday. */
    object WeekendWarrior : AchievementDefinition(
        key = "WEEKEND_WARRIOR", title = "Weekend Warrior",
        description = "Complete at least one habit on both Saturday and Sunday.",
        points = 40, icon = "🏕️", group = AchievementGroup.WEEKLY
    )

    /** Complete all habits every weekend for a month. */
    object NoDaysOff : AchievementDefinition(
        key = "NO_DAYS_OFF", title = "No Days Off",
        description = "Complete all habits every weekend for a month.",
        points = 150, icon = "🏖️", group = AchievementGroup.WEEKLY
    )

    /** Complete all scheduled habits Monday through Friday. */
    object TheDailyGrind : AchievementDefinition(
        key = "THE_DAILY_GRIND", title = "The Daily Grind",
        description = "Complete all scheduled habits Monday through Friday.",
        points = 100, icon = "💼", group = AchievementGroup.WEEKLY
    )

    /** Complete 100% of habits for 7 straight days. */
    object PerfectWeek : AchievementDefinition(
        key = "PERFECT_WEEK", title = "Perfect Week",
        description = "Complete 100% of your habits for 7 straight days.",
        points = 250, icon = "🏆", group = AchievementGroup.WEEKLY, threshold = 7
    )

    // ── Group 6: Organization & Variety (5) ──────────────────────────────────

    /** Create 5 active habits. */
    object TheArchitect : AchievementDefinition(
        key = "THE_ARCHITECT", title = "The Architect",
        description = "Create 5 active habits.",
        points = 20, icon = "🏗️", group = AchievementGroup.ORGANIZATION, threshold = 5
    )

    /** Create 10 active habits. */
    object Visionary : AchievementDefinition(
        key = "VISIONARY", title = "Visionary",
        description = "Create 10 active habits.",
        points = 50, icon = "👁️", group = AchievementGroup.ORGANIZATION, threshold = 10
    )

    /** Assign 5 different colors to your habits. */
    object ColorfulLife : AchievementDefinition(
        key = "COLORFUL_LIFE", title = "Colorful Life",
        description = "Assign 5 different colors to your habits.",
        points = 20, icon = "🎨", group = AchievementGroup.ORGANIZATION, threshold = 5
    )

    /** Archive or delete an old habit. */
    object SpringCleaning : AchievementDefinition(
        key = "SPRING_CLEANING", title = "Spring Cleaning",
        description = "Archive or delete an old habit.",
        points = 10, icon = "🧹", group = AchievementGroup.ORGANIZATION
    )

    /** Add a note/journal entry to a habit completion. */
    object Journalist : AchievementDefinition(
        key = "JOURNALIST", title = "Journalist",
        description = "Add a note/journal entry to a habit completion.",
        points = 20, icon = "📝", group = AchievementGroup.ORGANIZATION
    )

    // ── Group 7: God Tier (3) ─────────────────────────────────────────────────

    /** Complete 50 habits in a single week. */
    object TheMachine : AchievementDefinition(
        key = "THE_MACHINE", title = "The Machine",
        description = "Complete 50 habits in a single week.",
        points = 500, icon = "🤖", group = AchievementGroup.GOD_TIER, threshold = 50
    )

    /** Achieve a 100% completion rate for all habits in a 30-day month. */
    object AbsoluteZero : AchievementDefinition(
        key = "ABSOLUTE_ZERO", title = "Absolute Zero",
        description = "Achieve a 100% completion rate for all habits in a 30-day month.",
        points = 1000, icon = "🧊", group = AchievementGroup.GOD_TIER
    )

    /** Unlock all other 49 achievements. */
    object PlatinumTrophy : AchievementDefinition(
        key = "PLATINUM_TROPHY", title = "Platinum Trophy",
        description = "Unlock all other 49 achievements.",
        points = 2500, icon = "🏆", group = AchievementGroup.GOD_TIER, threshold = 49
    )

    companion object {
        /**
         * Canonical list of all 50 achievement definitions.
         * Used by [EvaluateAchievementsUseCase] for iteration and by the UI
         * to display locked achievements with their progress bars.
         */
        val all: List<AchievementDefinition> = listOf(
            // Getting Started
            FirstStep, ActionTaker, DoubleTrouble, ThreesACharm, PerfectDay, TheComeback,
            // Streaks & Consistency
            WarmingUp, Unstoppable, Fortnight, HabitForming, MonthlyMaster, SoaringHigh,
            CenturyClub, HalfAYear, FullCircle, Juggler, Multitasker,
            // Lifetime Milestones
            Novice, Apprentice, Journeyman, Expert, Master, Grandmaster,
            Legend, Mythic, TenKClub, AYearInActions,
            // Time of Day
            EarlyBird, BreakfastChampion, HighNoon, AfternoonHustle,
            NightOwl, MidnightOil, Bookends, Clockwork,
            // Weekly Warriors
            MondayMotivation, HumpDayHero, TGIF, WeekendWarrior,
            NoDaysOff, TheDailyGrind, PerfectWeek,
            // Organization & Variety
            TheArchitect, Visionary, ColorfulLife, SpringCleaning, Journalist,
            // God Tier
            TheMachine, AbsoluteZero, PlatinumTrophy
        )

        /** Total points a user can earn by unlocking every achievement. */
        val maxPoints: Int = all.sumOf { it.points }

        /** Look up a definition by its stable [key]. Returns null if not found. */
        fun fromKey(key: String): AchievementDefinition? = all.firstOrNull { it.key == key }
    }
}
