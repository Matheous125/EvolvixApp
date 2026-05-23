package com.example.evolvix.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evolvix.R
import com.example.evolvix.data.model.AchievementEntity
import com.example.evolvix.domain.model.AchievementDefinition
import com.example.evolvix.domain.model.AchievementGroup
import com.example.evolvix.ui.viewmodel.AchievementsViewModel

/** Maps an [AchievementGroup] enum value to its string resource ID. */
@StringRes
private fun AchievementGroup.titleResId(): Int = when (this) {
    AchievementGroup.GETTING_STARTED -> R.string.achievement_group_getting_started
    AchievementGroup.STREAKS         -> R.string.achievement_group_streaks
    AchievementGroup.MILESTONES      -> R.string.achievement_group_milestones
    AchievementGroup.TIME_OF_DAY     -> R.string.achievement_group_time_of_day
    AchievementGroup.WEEKLY          -> R.string.achievement_group_weekly
    AchievementGroup.ORGANIZATION    -> R.string.achievement_group_organization
    AchievementGroup.GOD_TIER        -> R.string.achievement_group_god_tier
}

/**
 * Maps an [AchievementDefinition] to its localised title string resource ID.
 * Keeping this mapping in the UI layer preserves the domain model's Android-free purity.
 */
@StringRes
private fun AchievementDefinition.titleResId(): Int = when (key) {
    "FIRST_STEP"         -> R.string.achievement_title_first_step
    "ACTION_TAKER"       -> R.string.achievement_title_action_taker
    "DOUBLE_TROUBLE"     -> R.string.achievement_title_double_trouble
    "THREES_A_CHARM"     -> R.string.achievement_title_threes_a_charm
    "PERFECT_DAY"        -> R.string.achievement_title_perfect_day
    "THE_COMEBACK"       -> R.string.achievement_title_the_comeback
    "WARMING_UP"         -> R.string.achievement_title_warming_up
    "UNSTOPPABLE"        -> R.string.achievement_title_unstoppable
    "FORTNIGHT"          -> R.string.achievement_title_fortnight
    "HABIT_FORMING"      -> R.string.achievement_title_habit_forming
    "MONTHLY_MASTER"     -> R.string.achievement_title_monthly_master
    "SOARING_HIGH"       -> R.string.achievement_title_soaring_high
    "CENTURY_CLUB"       -> R.string.achievement_title_century_club
    "HALF_A_YEAR"        -> R.string.achievement_title_half_a_year
    "FULL_CIRCLE"        -> R.string.achievement_title_full_circle
    "JUGGLER"            -> R.string.achievement_title_juggler
    "MULTITASKER"        -> R.string.achievement_title_multitasker
    "NOVICE"             -> R.string.achievement_title_novice
    "APPRENTICE"         -> R.string.achievement_title_apprentice
    "JOURNEYMAN"         -> R.string.achievement_title_journeyman
    "EXPERT"             -> R.string.achievement_title_expert
    "MASTER"             -> R.string.achievement_title_master
    "GRANDMASTER"        -> R.string.achievement_title_grandmaster
    "LEGEND"             -> R.string.achievement_title_legend
    "MYTHIC"             -> R.string.achievement_title_mythic
    "TEN_K_CLUB"         -> R.string.achievement_title_ten_k_club
    "A_YEAR_IN_ACTIONS"  -> R.string.achievement_title_a_year_in_actions
    "EARLY_BIRD"         -> R.string.achievement_title_early_bird
    "BREAKFAST_CHAMPION" -> R.string.achievement_title_breakfast_champion
    "HIGH_NOON"          -> R.string.achievement_title_high_noon
    "AFTERNOON_HUSTLE"   -> R.string.achievement_title_afternoon_hustle
    "NIGHT_OWL"          -> R.string.achievement_title_night_owl
    "MIDNIGHT_OIL"       -> R.string.achievement_title_midnight_oil
    "BOOKENDS"           -> R.string.achievement_title_bookends
    "CLOCKWORK"          -> R.string.achievement_title_clockwork
    "MONDAY_MOTIVATION"  -> R.string.achievement_title_monday_motivation
    "HUMP_DAY_HERO"      -> R.string.achievement_title_hump_day_hero
    "TGIF"               -> R.string.achievement_title_tgif
    "WEEKEND_WARRIOR"    -> R.string.achievement_title_weekend_warrior
    "NO_DAYS_OFF"        -> R.string.achievement_title_no_days_off
    "THE_DAILY_GRIND"    -> R.string.achievement_title_the_daily_grind
    "PERFECT_WEEK"       -> R.string.achievement_title_perfect_week
    "THE_ARCHITECT"      -> R.string.achievement_title_the_architect
    "VISIONARY"          -> R.string.achievement_title_visionary
    "COLORFUL_LIFE"      -> R.string.achievement_title_colorful_life
    "SPRING_CLEANING"    -> R.string.achievement_title_spring_cleaning
    "JOURNALIST"         -> R.string.achievement_title_journalist
    "THE_MACHINE"        -> R.string.achievement_title_the_machine
    "ABSOLUTE_ZERO"      -> R.string.achievement_title_absolute_zero
    "PLATINUM_TROPHY"    -> R.string.achievement_title_platinum_trophy
    else                 -> R.string.app_name
}

/**
 * Maps an [AchievementDefinition] to its localised description string resource ID.
 */
@StringRes
private fun AchievementDefinition.descResId(): Int = when (key) {
    "FIRST_STEP"         -> R.string.achievement_desc_first_step
    "ACTION_TAKER"       -> R.string.achievement_desc_action_taker
    "DOUBLE_TROUBLE"     -> R.string.achievement_desc_double_trouble
    "THREES_A_CHARM"     -> R.string.achievement_desc_threes_a_charm
    "PERFECT_DAY"        -> R.string.achievement_desc_perfect_day
    "THE_COMEBACK"       -> R.string.achievement_desc_the_comeback
    "WARMING_UP"         -> R.string.achievement_desc_warming_up
    "UNSTOPPABLE"        -> R.string.achievement_desc_unstoppable
    "FORTNIGHT"          -> R.string.achievement_desc_fortnight
    "HABIT_FORMING"      -> R.string.achievement_desc_habit_forming
    "MONTHLY_MASTER"     -> R.string.achievement_desc_monthly_master
    "SOARING_HIGH"       -> R.string.achievement_desc_soaring_high
    "CENTURY_CLUB"       -> R.string.achievement_desc_century_club
    "HALF_A_YEAR"        -> R.string.achievement_desc_half_a_year
    "FULL_CIRCLE"        -> R.string.achievement_desc_full_circle
    "JUGGLER"            -> R.string.achievement_desc_juggler
    "MULTITASKER"        -> R.string.achievement_desc_multitasker
    "NOVICE"             -> R.string.achievement_desc_novice
    "APPRENTICE"         -> R.string.achievement_desc_apprentice
    "JOURNEYMAN"         -> R.string.achievement_desc_journeyman
    "EXPERT"             -> R.string.achievement_desc_expert
    "MASTER"             -> R.string.achievement_desc_master
    "GRANDMASTER"        -> R.string.achievement_desc_grandmaster
    "LEGEND"             -> R.string.achievement_desc_legend
    "MYTHIC"             -> R.string.achievement_desc_mythic
    "TEN_K_CLUB"         -> R.string.achievement_desc_ten_k_club
    "A_YEAR_IN_ACTIONS"  -> R.string.achievement_desc_a_year_in_actions
    "EARLY_BIRD"         -> R.string.achievement_desc_early_bird
    "BREAKFAST_CHAMPION" -> R.string.achievement_desc_breakfast_champion
    "HIGH_NOON"          -> R.string.achievement_desc_high_noon
    "AFTERNOON_HUSTLE"   -> R.string.achievement_desc_afternoon_hustle
    "NIGHT_OWL"          -> R.string.achievement_desc_night_owl
    "MIDNIGHT_OIL"       -> R.string.achievement_desc_midnight_oil
    "BOOKENDS"           -> R.string.achievement_desc_bookends
    "CLOCKWORK"          -> R.string.achievement_desc_clockwork
    "MONDAY_MOTIVATION"  -> R.string.achievement_desc_monday_motivation
    "HUMP_DAY_HERO"      -> R.string.achievement_desc_hump_day_hero
    "TGIF"               -> R.string.achievement_desc_tgif
    "WEEKEND_WARRIOR"    -> R.string.achievement_desc_weekend_warrior
    "NO_DAYS_OFF"        -> R.string.achievement_desc_no_days_off
    "THE_DAILY_GRIND"    -> R.string.achievement_desc_the_daily_grind
    "PERFECT_WEEK"       -> R.string.achievement_desc_perfect_week
    "THE_ARCHITECT"      -> R.string.achievement_desc_the_architect
    "VISIONARY"          -> R.string.achievement_desc_visionary
    "COLORFUL_LIFE"      -> R.string.achievement_desc_colorful_life
    "SPRING_CLEANING"    -> R.string.achievement_desc_spring_cleaning
    "JOURNALIST"         -> R.string.achievement_desc_journalist
    "THE_MACHINE"        -> R.string.achievement_desc_the_machine
    "ABSOLUTE_ZERO"      -> R.string.achievement_desc_absolute_zero
    "PLATINUM_TROPHY"    -> R.string.achievement_desc_platinum_trophy
    else                 -> R.string.app_name
}

/**
 * Root composable for the Achievements screen.
 *
 * Displays:
 * 1. A total-points header [ElevatedCard] (earned pts / max pts + progress bar).
 * 2. A collapsible "Latest" section — the 3 most recently unlocked achievements.
 * 3. One collapsible section per [AchievementGroup], preserving enum declaration order.
 *    - Unlocked rows are rendered with a filled [primaryContainer] card.
 *    - Locked rows are dimmed and show a [LinearProgressIndicator] with numeric progress.
 *
 * Pattern: **MVVM + Observer** — all state is collected from
 * [AchievementsViewModel.achievements] (a `StateFlow<List<AchievementEntity>>`).
 * The Composable is a pure function of that state; no business logic lives here.
 *
 * @param viewModel Activity-scoped [AchievementsViewModel] passed from [AppContent] so that
 *   both [AchievementsScreen] and [AchievementBanner] share the same instance and the same
 *   [AchievementsViewModel.newlyUnlocked] SharedFlow.
 * @param modifier Optional [Modifier] forwarded to the root [Scaffold].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    viewModel: AchievementsViewModel,
    modifier: Modifier = Modifier
) {
    // Collect the StateFlow — recomposition fires whenever the DB emits a new list.
    val entities by viewModel.achievements.collectAsState()

    // O(1) lookup: definition key → AchievementEntity (may be null for never-seen achievements).
    val entityByKey: Map<String, AchievementEntity> = remember(entities) {
        entities.associateBy { it.key }
    }

    // --- Derived data (recomputed only when `entities` reference changes) ----

    /** Sum of points for every unlocked achievement. */
    val totalEarned: Int = remember(entities) {
        entities
            .filter { it.unlockedAt != null }
            .sumOf { entity -> AchievementDefinition.fromKey(entity.key)?.points ?: 0 }
    }

    /**
     * Up to 3 most recently unlocked achievements, sorted newest-first.
     * Pairs are (definition, entity) so the row can read both display data and
     * the unlock timestamp.
     */
    val latestItems: List<Pair<AchievementDefinition, AchievementEntity>> = remember(entities) {
        entities
            .filter { it.unlockedAt != null }
            .sortedByDescending { it.unlockedAt }
            .take(3)
            .mapNotNull { entity ->
                AchievementDefinition.fromKey(entity.key)?.let { def -> def to entity }
            }
    }

    /**
     * All 50 definitions grouped by their [AchievementGroup], preserving the
     * enum's declaration order so the UI sections always appear in a predictable sequence.
     */
    val groupedDefs: Map<AchievementGroup, List<AchievementDefinition>> = remember {
        AchievementDefinition.all
            .groupBy { it.group }
            .toSortedMap(compareBy { it.ordinal })
    }

    // --- Expand/collapse state -----------------------------------------------

    /**
     * [SnapshotStateMap] tracking whether each [AchievementGroup] section is expanded.
     * SnapshotStateMap is Compose-observable: writing to it triggers recomposition of
     * any Composable that read the changed key, including the LazyColumn content lambda.
     * (Pattern: Observer — Compose snapshot system as the notification mechanism)
     */
    val groupExpanded: SnapshotStateMap<AchievementGroup, Boolean> = remember {
        mutableStateMapOf(*AchievementGroup.values().map { it to true }.toTypedArray())
    }
    var latestExpanded by remember { mutableStateOf(true) }

    // --- UI ------------------------------------------------------------------

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_achievements_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {

            // ── Total-points header ──────────────────────────────────────────
            item(key = "header_card") {
                TotalPointsCard(
                    earned = totalEarned,
                    max = AchievementDefinition.maxPoints
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── "Latest" collapsible section ─────────────────────────────────
            if (latestItems.isNotEmpty()) {
                item(key = "latest_header") {
                    SectionHeader(
                        title = stringResource(R.string.section_latest),
                        expanded = latestExpanded,
                        onToggle = { latestExpanded = !latestExpanded }
                    )
                }
                if (latestExpanded) {
                    items(
                        items = latestItems,
                        key = { (def, _) -> "latest_${def.key}" }
                    ) { (def, entity) ->
                        AchievementRow(definition = def, entity = entity, compact = true)
                    }
                    item(key = "latest_spacer") { Spacer(Modifier.height(8.dp)) }
                }
            }

            // ── Category group sections ──────────────────────────────────────
            for ((group, defs) in groupedDefs) {
                val expanded = groupExpanded[group] ?: true
                // Unlocked achievements are sorted to the top of each group so the
                // user immediately sees what they've earned before the locked items.
                val sortedDefs = defs.sortedByDescending { entityByKey[it.key]?.unlockedAt != null }

                item(key = "group_header_${group.name}") {
                    SectionHeader(
                        title = stringResource(group.titleResId()),
                        expanded = expanded,
                        onToggle = { groupExpanded[group] = !expanded }
                    )
                }

                if (expanded) {
                    items(
                        items = sortedDefs,
                        key = { "group_${it.key}" }
                    ) { def ->
                        AchievementRow(
                            definition = def,
                            entity = entityByKey[def.key]
                        )
                    }
                    item(key = "group_spacer_${group.name}") {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

// ── Private helper composables ────────────────────────────────────────────────

/**
 * Full-width [ElevatedCard] summarising the user's total achievement score.
 *
 * Shows earned points, maximum possible points, and a [LinearProgressIndicator]
 * so the user gets an at-a-glance feel for overall completion progress.
 *
 * @param earned Points accumulated from all unlocked achievements.
 * @param max    Total points available across all 50 definitions.
 */
@Composable
private fun TotalPointsCard(earned: Int, max: Int) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.EmojiEvents,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.achievement_pts_earned, earned),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.achievement_pts_of_max, max),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (max > 0) earned.toFloat() / max else 0f },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Clickable row used as a collapsible section header.
 *
 * Displays the section [title] on the left and an expand/collapse chevron icon
 * on the right. A [HorizontalDivider] below the row visually separates it from
 * the content below.
 *
 * @param title    Text displayed as the section label.
 * @param expanded Whether the section is currently expanded.
 * @param onToggle Callback invoked when the user taps the header row.
 */
@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.cd_collapse_section, title) else stringResource(R.string.cd_expand_section, title),
            tint = MaterialTheme.colorScheme.primary
        )
    }
    HorizontalDivider()
}

/**
 * Single achievement row rendered inside a collapsible section.
 *
 * Unlocked achievements ([entity.unlockedAt] != null) are shown with a filled
 * [primaryContainer] card. Locked achievements are dimmed (50% alpha) and include
 * a [LinearProgressIndicator] showing [AchievementEntity.progress] /
 * [AchievementDefinition.threshold] when the threshold is greater than 1.
 *
 * @param definition The static definition holding display data (title, icon, points, description).
 * @param entity     The DB row for this achievement; null means no record exists yet (progress = 0).
 * @param compact    When true, the description text is omitted. Used in the "Latest" section
 *                   to keep recently unlocked rows visually compact.
 */
@Composable
private fun AchievementRow(
    definition: AchievementDefinition,
    entity: AchievementEntity?,
    compact: Boolean = false
) {
    val isUnlocked = entity?.unlockedAt != null
    val progress = entity?.progress ?: 0

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            // Unlocked rows use a tinted container; locked rows stay on surfaceVariant.
            containerColor = if (isUnlocked)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji icon — dimmed when the achievement is still locked.
            Text(
                text = definition.icon,
                style = MaterialTheme.typography.headlineSmall,
                modifier = if (!isUnlocked) Modifier.alpha(0.45f) else Modifier
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(definition.titleResId()),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (!compact) {
                    Text(
                        text = stringResource(definition.descResId()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Progress bar — shown only for locked achievements with a numeric threshold.
                if (!isUnlocked && definition.threshold > 1) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / definition.threshold },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "$progress / ${definition.threshold}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Points badge — filled when unlocked, outlined when locked.
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isUnlocked)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = if (isUnlocked) 0.dp else 2.dp
            ) {
                Text(
                    text = stringResource(R.string.achievement_pts_badge, definition.points),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isUnlocked)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
