package com.example.evolvix.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evolvix.R
import com.example.evolvix.domain.model.AchievementDefinition
import com.example.evolvix.ui.viewmodel.AchievementsViewModel
import com.example.evolvix.ui.viewmodel.SettingsViewModel
import com.example.evolvix.ui.viewmodel.ThemeMode

// ── Rank helpers ──────────────────────────────────────────────────────────────

/**
 * Maps a total achievement-points score to a string resource ID for the rank label.
 * Thresholds are spaced relative to [AchievementDefinition.maxPoints] so that
 * a user who completes the full achievement tree reaches "Legend".
 */
@StringRes
private fun rankResId(points: Int): Int = when {
    points < 100  -> R.string.rank_beginner
    points < 300  -> R.string.rank_apprentice
    points < 600  -> R.string.rank_practitioner
    points < 1000 -> R.string.rank_expert
    points < 2000 -> R.string.rank_master
    else          -> R.string.rank_legend
}

/**
 * Derives the initials shown in the profile avatar circle from the display name.
 * "John Doe" → "JD", "Evolvix User" → "EU", single word → first two chars uppercased.
 */
private fun initials(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotEmpty() }
    return when {
        parts.size >= 2 -> "${parts.first().first()}${parts.last().first()}".uppercase()
        parts.size == 1 -> parts.first().take(2).uppercase()
        else            -> "?"
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Settings screen for the Evolvix app.
 *
 * Sections:
 *  1. **Profile header** — avatar initials, display name (editable via inline dialog),
 *     achievement rank derived from total earned points.
 *  2. **Appearance** — theme mode selector (Light / Dark / System) using [SingleChoiceSegmentedButtonRow];
 *     language selector via a simple radio dialog.
 *  3. **Notifications** — daily summary enable/disable [Switch].
 *  4. **Account** — Change Password and Login/Logout placeholders (Phase 9/10).
 *  5. **Support** — Help dialog and Feedback email intent.
 *
 * Pattern: **MVVM + Observer** — all state is read from [SettingsViewModel] (StateFlow)
 * and [AchievementsViewModel] (StateFlow for points). No business logic in the Composable.
 *
 * @param settingsViewModel  Manages theme, language, name, and daily-summary preference.
 * @param achievementsViewModel  Activity-scoped VM; provides total earned points for rank.
 * @param onNavigateBack     Callback to pop the back stack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    achievementsViewModel: AchievementsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // Collect settings state
    val themeMode       by settingsViewModel.themeMode.collectAsState()
    val languageCode    by settingsViewModel.languageCode.collectAsState()
    val summaryEnabled  by settingsViewModel.dailySummaryEnabled.collectAsState()
    val displayName     by settingsViewModel.displayName.collectAsState()

    // Compute rank from achievements points
    val achievements by achievementsViewModel.achievements.collectAsState()
    val totalPoints = remember(achievements) {
        achievements
            .filter { it.unlockedAt != null }
            .sumOf { entity -> AchievementDefinition.fromKey(entity.key)?.points ?: 0 }
    }
    val rank = stringResource(remember(totalPoints) { rankResId(totalPoints) })

    // Dialog visibility flags
    var showNameDialog     by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showHelpDialog     by remember { mutableStateOf(false) }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showNameDialog) {
        EditNameDialog(
            currentName = displayName,
            onConfirm   = { newName ->
                settingsViewModel.setDisplayName(newName)
                showNameDialog = false
            },
            onDismiss   = { showNameDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguagePickerDialog(
            currentCode = languageCode,
            onSelect    = { code ->
                settingsViewModel.setLanguageCode(code)
                showLanguageDialog = false
            },
            onDismiss   = { showLanguageDialog = false }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            icon             = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null) },
            title            = { Text(stringResource(R.string.dialog_help_title)) },
            text             = { Text(stringResource(R.string.dialog_help_body)) },
            confirmButton    = {
                TextButton(onClick = { showHelpDialog = false }) { Text(stringResource(R.string.btn_got_it)) }
            }
        )
    }

    // ── Main layout ───────────────────────────────────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text(stringResource(R.string.screen_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                start  = 16.dp,
                end    = 16.dp,
                top    = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            // ── 1. Profile card ───────────────────────────────────────────────
            item(key = "profile_card") {
                ProfileCard(
                    displayName = displayName,
                    rank        = rank,
                    totalPoints = totalPoints,
                    onEditName  = { showNameDialog = true }
                )
                Spacer(Modifier.height(16.dp))
            }

            // ── 2. Appearance ─────────────────────────────────────────────────
            item(key = "section_appearance") {
                SettingsSectionHeader(title = stringResource(R.string.section_appearance))
            }
            item(key = "theme_selector") {
                ThemeSelectorRow(
                    selected  = themeMode,
                    onSelect  = settingsViewModel::setThemeMode
                )
            }
            item(key = "language_selector") {
                SettingsListItem(
                    icon        = Icons.Filled.Language,
                    title       = stringResource(R.string.dialog_language_title),
                    subtitle    = if (languageCode == "pl") stringResource(R.string.language_polish) else stringResource(R.string.language_english),
                    onClick     = { showLanguageDialog = true }
                )
            }

            // ── 3. Notifications ──────────────────────────────────────────────
            item(key = "section_notifications") {
                Spacer(Modifier.height(8.dp))
                SettingsSectionHeader(title = stringResource(R.string.section_notifications))
            }
            item(key = "daily_summary_switch") {
                SettingsSwitchRow(
                    icon     = Icons.Filled.Notifications,
                    title    = stringResource(R.string.label_daily_summary),
                    subtitle = stringResource(R.string.subtitle_daily_summary),
                    checked  = summaryEnabled,
                    onCheckedChange = settingsViewModel::setDailySummaryEnabled
                )
            }

            // ── 4. Account ────────────────────────────────────────────────────
            item(key = "section_account") {
                Spacer(Modifier.height(8.dp))
                SettingsSectionHeader(title = stringResource(R.string.section_account))
            }
            item(key = "change_password") {
                SettingsListItem(
                    icon     = Icons.Filled.Lock,
                    title    = stringResource(R.string.menu_change_password),
                    subtitle = stringResource(R.string.subtitle_change_password),
                    onClick  = { /* Phase 9 placeholder */ }
                )
            }
            item(key = "login_logout") {
                SettingsListItem(
                    icon     = Icons.Filled.AccountCircle,
                    title    = stringResource(R.string.menu_login_logout),
                    subtitle = stringResource(R.string.subtitle_login_logout),
                    onClick  = { /* Phase 10 placeholder */ }
                )
            }

            // ── 5. Support ────────────────────────────────────────────────────
            item(key = "section_support") {
                Spacer(Modifier.height(8.dp))
                SettingsSectionHeader(title = stringResource(R.string.section_support))
            }
            item(key = "help") {
                SettingsListItem(
                    icon    = Icons.AutoMirrored.Filled.Help,
                    title   = stringResource(R.string.menu_help),
                    onClick = { showHelpDialog = true }
                )
            }
            item(key = "feedback") {
                SettingsListItem(
                    icon    = Icons.AutoMirrored.Filled.Send,
                    title   = stringResource(R.string.menu_feedback),
                    subtitle = stringResource(R.string.subtitle_feedback),
                    onClick = {
                        // Opens the user's default mail client with a pre-filled recipient.
                        // ACTION_SENDTO with a mailto: URI is the recommended pattern for
                        // composing emails without requiring the INTERNET permission.
                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                            data    = Uri.parse("mailto:")
                            putExtra(Intent.EXTRA_EMAIL,   arrayOf("feedback@evolvix.app"))
                            putExtra(Intent.EXTRA_SUBJECT, "Evolvix Feedback")
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

/**
 * Profile card displayed at the top of the Settings screen.
 * Shows an avatar circle with initials, the display name, rank label, and total points.
 * Tapping the edit icon opens the name-edit dialog.
 */
@Composable
private fun ProfileCard(
    displayName: String,
    rank: String,
    totalPoints: Int,
    onEditName: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier  = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle — shows two initials derived from the display name.
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text  = initials(displayName),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text  = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                // Rank badge — derived from total achievement points.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint   = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text  = stringResource(R.string.label_rank_points, rank, totalPoints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Edit name button
            IconButton(onClick = onEditName) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.cd_edit_name))
            }
        }
    }
}

/** Bold section header label separating settings groups. */
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
    )
}

/**
 * Three-option segmented button for theme selection.
 * Uses [SingleChoiceSegmentedButtonRow] (M3) — the standard component for mutually
 * exclusive compact options in a single row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelectorRow(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.LIGHT  to stringResource(R.string.theme_light),
        ThemeMode.DARK   to stringResource(R.string.theme_dark),
        ThemeMode.SYSTEM to stringResource(R.string.theme_system)
    )
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        ) {
            Icon(
                imageVector      = Icons.Filled.Palette,
                contentDescription = null,
                tint             = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier         = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.label_theme), style = MaterialTheme.typography.bodyLarge)
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    shape    = SegmentedButtonDefaults.itemShape(
                        index = index, count = options.size
                    ),
                    onClick  = { onSelect(mode) },
                    selected = selected == mode,
                    label    = { Text(label) }
                )
            }
        }
    }
}

/**
 * A [ListItem]-style settings row with a leading icon, title, optional subtitle,
 * and a trailing chevron. Clicking the whole row triggers [onClick].
 */
@Composable
private fun SettingsListItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color   = MaterialTheme.colorScheme.surface,
        shape   = MaterialTheme.shapes.small
    ) {
        ListItem(
            leadingContent  = {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            trailingContent = {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

/**
 * A settings row with a leading icon, title, optional subtitle, and a trailing [Switch].
 * The entire row is tappable to toggle the switch (standard Android UX pattern).
 */
@Composable
private fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        color   = MaterialTheme.colorScheme.surface,
        shape   = MaterialTheme.shapes.small
    ) {
        ListItem(
            leadingContent  = {
                Icon(icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            headlineContent = { Text(title) },
            supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
            trailingContent = {
                Switch(
                    checked         = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        )
    }
}

/**
 * Dialog for editing the user's display name.
 * Uses a single text field with a character limit of 30.
 */
@Composable
private fun EditNameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Filled.Edit, contentDescription = null) },
        title = { Text(stringResource(R.string.dialog_edit_name_title)) },
        text  = {
            OutlinedTextField(
                value         = value,
                onValueChange = { if (it.length <= 30) value = it },
                label         = { Text(stringResource(R.string.hint_display_name)) },
                singleLine    = true,
                supportingText = { Text(stringResource(R.string.label_name_char_count, value.length)) }
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.btn_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

/**
 * Dialog for picking the app language.
 * Currently offers English and Polish; full locale switching is implemented in Phase 8.
 */
@Composable
private fun LanguagePickerDialog(
    currentCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf("en" to "English", "pl" to "Polski")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Filled.Language, contentDescription = null) },
        title = { Text(stringResource(R.string.dialog_language_title)) },
        text  = {
            Column {
                languages.forEach { (code, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = currentCode == code,
                            onClick  = { onSelect(code) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}
