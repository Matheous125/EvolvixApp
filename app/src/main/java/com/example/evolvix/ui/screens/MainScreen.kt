package com.example.evolvix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.evolvix.R
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.domain.model.SortMode
import com.example.evolvix.ui.components.HabitContextMenu
import com.example.evolvix.ui.components.ProgressItem
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.ui.theme.LocalIsDarkTheme
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.SummaryInboxViewModel
import com.example.evolvix.ui.viewmodel.SummaryInboxViewModelFactory
import com.example.evolvix.BuildConfig
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Represents one visual "row" in the MANUAL mode list.
 * A named group contains one or more habits; a null groupName means a single ungrouped habit.
 * Built by scanning the sort-ordered flat list and collecting consecutive same-group habits.
 */
private data class ManualSection(
    val groupName: String?,
    val habits: List<HabitUiState>
)

/**
 * Converts a flat habit list into an ordered list of [ManualSection]s.
 * Consecutive habits sharing the same non-null [HabitUiState.manualGroup] form one section;
 * ungrouped habits each become a single-habit section.
 *
 * **Deduplication guarantee**: if the same group name appears non-contiguously in [habits]
 * (e.g. due to desynchronised sort-orders in the DB), all its habits are coalesced into the
 * section at the FIRST occurrence. This prevents LazyColumn from receiving duplicate keys
 * (`"manual_header_$group"`) which would cause an [IllegalArgumentException] crash.
 *
 * Extracted as a top-level function so that gesture lambdas inside [pointerInput] can call
 * it on [localList] inline — those lambdas cannot access a `remember`-cached value that may
 * be stale between recompositions.
 */
private fun buildManualSections(habits: List<HabitUiState>): List<ManualSection> {
    val result = mutableListOf<ManualSection>()
    // Tracks the result-list index at which each group name first appeared.
    val seenGroupIndex = mutableMapOf<String, Int>()
    var i = 0
    while (i < habits.size) {
        val group = habits[i].manualGroup
        if (group != null) {
            val groupHabits = mutableListOf<HabitUiState>()
            while (i < habits.size && habits[i].manualGroup == group) {
                groupHabits.add(habits[i++])
            }
            val existingIdx = seenGroupIndex[group]
            if (existingIdx != null) {
                // Non-contiguous recurrence: merge habits into the first section so we
                // never emit two sections with the same groupName / LazyColumn key.
                result[existingIdx] = result[existingIdx].copy(
                    habits = result[existingIdx].habits + groupHabits
                )
            } else {
                seenGroupIndex[group] = result.size
                result.add(ManualSection(groupName = group, habits = groupHabits))
            }
        } else {
            result.add(ManualSection(groupName = null, habits = listOf(habits[i++])))
        }
    }
    return result
}

/**
 * Supports tap-to-increment, long-press context menu, and collapsible category groups.
 *
 * @param onNavigateToAddHabit Callback to open the Add Habit screen
 * @param onNavigateToEditHabit Callback to open the Edit Habit screen for a given habit id
 * @param onNavigateToSettings Callback to open Settings
 * @param onNavigateToStatistics Callback to open Statistics
 * @param onNavigateToHistory Callback to open the History screen for a given habit id (Phase 3.1)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onNavigateToAddHabit: () -> Unit = {},
    onNavigateToEditHabit: (Int) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStatistics: () -> Unit = {},
    onNavigateToHistory: (Int, String) -> Unit = { _, _ -> },
    onNavigateToInbox: () -> Unit = {},
    habitViewModel: HabitViewModel
) {
    // Reset daily/weekly/monthly/yearly progress whenever the screen resumes
    // (covers: cold start, returning from background, navigating back to this screen).
    // Uses LifecycleEventObserver so the check runs on every ON_RESUME, not just
    // the first composition as LaunchedEffect(Unit) would.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                habitViewModel.checkAndResetProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect all reactive state from the ViewModel (Observer pattern via StateFlow)
    val allHabitsUiState by habitViewModel.allHabits.collectAsState()
    val sortMode        by habitViewModel.sortMode.collectAsState()
    val activeFilters   by habitViewModel.activeFilters.collectAsState()
    val availableCategories by habitViewModel.availableCategories.collectAsState()
    val searchQuery     by habitViewModel.searchQuery.collectAsState()

    // Phase 7.2v2 — observe daily-summary unread count for the bell-icon badge.
    // Scoped to MainScreen so the badge re-composes only when this screen is visible.
    val appCtx = LocalContext.current.applicationContext as android.app.Application
    val summaryViewModel: SummaryInboxViewModel = viewModel(
        factory = SummaryInboxViewModelFactory(appCtx)
    )
    val summaryUnread by summaryViewModel.unreadCount.collectAsState()

    // Local UI state: which category group headers are currently collapsed
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    // Controls visibility of the sort-order DropdownMenu in the top bar
    var sortMenuExpanded by remember { mutableStateOf(false) }
    // Controls visibility of the DEBUG-build action menu in the top bar (Phase 7.2v2).
    var debugMenuExpanded by remember { mutableStateOf(false) }
    // Controls whether the search field is expanded in the chip row
    var searchExpanded by remember { mutableStateOf(false) }
    // Reorder mode is now owned by the ViewModel so MainActivity can also observe it
    // (to hide the FAB). Auto-exit on sort change is handled inside setSortMode().
    val reorderMode by habitViewModel.reorderMode.collectAsState()

    // ── Manual-group management state ────────────────────────────────────────
    // Collapsed state for manual-mode group sections (independent of CATEGORY mode)
    var collapsedManualGroups by remember { mutableStateOf(emptySet<String>()) }
    // Whether the "New Group" name dialog is visible
    var showNewGroupDialog by remember { mutableStateOf(false) }
    // Text input for the new group name dialog
    var newGroupNameInput by remember { mutableStateOf("") }
    // Whether multi-select mode is active (user is picking habits for a new group)
    var multiSelectMode by remember { mutableStateOf(false) }
    // Name of the group being created during multi-select flow
    var pendingGroupName by remember { mutableStateOf("") }
    // IDs of habits selected for the pending group
    var selectedHabitIds by remember { mutableStateOf(emptySet<Int>()) }
    // Which group is currently being renamed inline (null = none)
    var editingGroupName by remember { mutableStateOf<String?>(null) }
    // Live text for the inline rename field
    var editingGroupNameInput by remember { mutableStateOf("") }
    // Group awaiting delete confirmation (null = no dialog). Holds the section so we can
    // display the habit count inside the dialog without re-querying the DB.
    var groupPendingDelete by remember { mutableStateOf<ManualSection?>(null) }
    // True when multi-select is used to EDIT an existing group (vs. creating a new one).
    var isEditingExistingGroup by remember { mutableStateOf(false) }
    // IDs the group had when the edit session started — used to compute the delta on confirm.
    var editGroupOriginalHabitIds by remember { mutableStateOf(emptySet<Int>()) }


    // ── Drag-and-drop state (MANUAL sort mode only) ───────────────────────────
    // ID of the item currently being dragged; -1 means no drag in progress.
    var draggingItemId by remember { mutableIntStateOf(-1) }
    // Accumulated pixel delta from the moment the drag started (Y axis only).
    // Never reset mid-drag — allows the overlay position to track the finger absolutely.
    var draggingDeltaY by remember { mutableFloatStateOf(0f) }
    // Y offset (in viewport px) of the dragged item at the moment the drag started.
    var draggingItemStartY by remember { mutableFloatStateOf(0f) }
    // Height (in px) of the dragged item — used for the drag-center hit-test.
    var draggingItemHeight by remember { mutableIntStateOf(0) }
    // LazyListState lets us query each item's viewport offset during the drag gesture.
    val listState = rememberLazyListState()

    // ── Group drag state (MANUAL sort mode only) ──────────────────────────────
    // Name of the group currently being dragged as a whole block; null = no group drag.
    // Declared before localList / LaunchedEffect so it is in scope for the guard check.
    var draggingGroupName by remember { mutableStateOf<String?>(null) }
    // Accumulated pixel delta for the group drag overlay (Y axis only).
    var draggingGroupDeltaY by remember { mutableFloatStateOf(0f) }
    // Y offset (in viewport px) of the dragged group header at the moment drag started.
    var draggingGroupStartY by remember { mutableFloatStateOf(0f) }
    // Height (in px) of the group header item — used for the overlay anchor calculation.
    var draggingGroupHeaderHeight by remember { mutableIntStateOf(0) }

    // Local mutable copy of the list, updated in real-time during drag.
    // Driving LazyColumn from this (not allHabitsUiState directly) lets animateItem()
    // produce the "spreading out to make room" animation as items shift positions.
    var localList by remember { mutableStateOf(allHabitsUiState) }
    // Keep localList in sync with ViewModel emissions when no drag is in progress.
    // Run the new value through buildManualSections so that any non-contiguous group
    // order persisted in the DB is coalesced before it reaches the LazyColumn.
    LaunchedEffect(allHabitsUiState) {
        if (draggingItemId == -1 && draggingGroupName == null) {
            localList = buildManualSections(allHabitsUiState).flatMap { it.habits }
        }
    }

    // When CATEGORY sort is active, group habits by categoryGroup (falls back to first
    // category tag, then "Other"). Groups are sorted alphabetically via toSortedMap().
    // Returns null in non-CATEGORY modes → flat list branch.
    val groupedHabits: Map<String, List<HabitUiState>>? = remember(allHabitsUiState, sortMode) {
        if (sortMode == SortMode.CATEGORY) {
            allHabitsUiState.groupBy {
                it.categoryGroup ?: it.categories.firstOrNull() ?: "Other"
            }.toSortedMap()
        } else null
    }

    // When CUSTOM sort is active, build an ordered list of sections from localList.
    // Consecutive habits sharing the same non-null manualGroup form one ManualSection;
    // ungrouped habits (null) each become their own single-habit section.
    // Returns null in all other modes — those branches use their own rendering.
    val manualSections: List<ManualSection>? = remember(localList, sortMode) {
        if (sortMode == SortMode.CUSTOM) buildManualSections(localList) else null
    }

    Scaffold(
        topBar = {
            // TopAppBar standardized across all screens (Composition over inheritance — Phase 1.2)
            TopAppBar(
                title = { Text(stringResource(R.string.screen_main_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0),
                actions = {
                    // While in reorder mode show a "Done" button that exits the mode.
                    // The normal sort + settings actions are hidden so the UI is unambiguous.
                    if (reorderMode) {
                        if (multiSelectMode) {
                            // Multi-select: confirm assignment or cancel.
                            // Button label and ViewModel call differ for create vs. edit flows.
                            TextButton(
                                enabled = selectedHabitIds.isNotEmpty(),
                                onClick = {
                                    if (isEditingExistingGroup) {
                                        habitViewModel.updateManualGroupMembers(
                                            groupName = pendingGroupName,
                                            newHabitIds = selectedHabitIds.toList(),
                                            previousHabitIds = editGroupOriginalHabitIds.toList()
                                        )
                                    } else {
                                        habitViewModel.createManualGroup(pendingGroupName, selectedHabitIds.toList())
                                    }
                                    multiSelectMode = false
                                    isEditingExistingGroup = false
                                    selectedHabitIds = emptySet()
                                    editGroupOriginalHabitIds = emptySet()
                                    pendingGroupName = ""
                                }
                            ) {
                                Text(
                                    if (isEditingExistingGroup)
                                        stringResource(R.string.btn_save)
                                    else
                                        pluralStringResource(R.plurals.btn_add_habits, selectedHabitIds.size, selectedHabitIds.size)
                                )
                            }
                            TextButton(onClick = {
                                multiSelectMode = false
                                isEditingExistingGroup = false
                                selectedHabitIds = emptySet()
                                editGroupOriginalHabitIds = emptySet()
                                pendingGroupName = ""
                            }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        } else {
                            // Normal reorder mode: offer group creation and done
                            TextButton(onClick = { showNewGroupDialog = true }) {
                                Text(stringResource(R.string.btn_new_group))
                            }
                            TextButton(onClick = { habitViewModel.exitReorderMode() }) {
                                Text(stringResource(R.string.btn_done))
                            }
                        }
                    } else {
                    // Sort-order picker — opens a DropdownMenu with 3 sort options
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.cd_sort_order))
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            val sortLabels = mapOf(
                                SortMode.DEFAULT   to stringResource(R.string.sort_default),
                                SortMode.NAME      to stringResource(R.string.sort_name_asc),
                                SortMode.NAME_DESC to stringResource(R.string.sort_name_desc),
                                SortMode.FREQ_ASC  to stringResource(R.string.sort_cadence_asc),
                                SortMode.FREQ_DESC to stringResource(R.string.sort_cadence_desc),
                                SortMode.CATEGORY  to stringResource(R.string.sort_by_category),
                                SortMode.CUSTOM    to stringResource(R.string.sort_custom)
                            )
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(sortLabels[mode] ?: mode.name) },
                                    onClick = {
                                        habitViewModel.setSortMode(mode)
                                        sortMenuExpanded = false
                                    },
                                    leadingIcon = {
                                        // Checkmark on the currently active sort mode
                                        if (sortMode == mode) {
                                            Icon(Icons.Filled.Check, contentDescription = null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                    // ── Phase 7.2v2 — daily-summary inbox button with unread badge ──
                    BadgedBox(
                        badge = {
                            if (summaryUnread > 0) {
                                Badge { Text(summaryUnread.toString()) }
                            }
                        }
                    ) {
                        IconButton(onClick = onNavigateToInbox) {
                            Icon(Icons.Filled.Inbox, contentDescription = stringResource(R.string.cd_daily_summaries))
                        }
                    }
                    // ── Phase 7.2v2 — DEBUG-only quick-test menu ──
                    if (BuildConfig.DEBUG) {
                        val debugScope = rememberCoroutineScope()
                        Box {
                            IconButton(onClick = { debugMenuExpanded = true }) {
                                Icon(Icons.Filled.BugReport, contentDescription = stringResource(R.string.cd_debug_menu))
                            }
                            DropdownMenu(
                                expanded = debugMenuExpanded,
                                onDismissRequest = { debugMenuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Test reminder (3 s) — bypass gate") },
                                    onClick = {
                                        debugMenuExpanded = false
                                        val firstId = allHabitsUiState.firstOrNull()?.id
                                        if (firstId != null) {
                                            com.example.evolvix.notifications.DebugTriggers
                                                .fireReminderSoon(appCtx, firstId)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Seed test habit + real reminder (3 s)") },
                                    onClick = {
                                        debugMenuExpanded = false
                                        debugScope.launch(Dispatchers.IO) {
                                            com.example.evolvix.notifications.DebugTriggers
                                                .seedAndFireRealReminder(appCtx)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Test daily summary (3 s)") },
                                    onClick = {
                                        debugMenuExpanded = false
                                        com.example.evolvix.notifications.DebugTriggers
                                            .fireDailySummarySoon(appCtx)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Simulate 7 dismissals → auto-disable") },
                                    onClick = {
                                        debugMenuExpanded = false
                                        com.example.evolvix.notifications.DebugTriggers
                                            .simulateAutoDisable(appCtx)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset summary disable flag") },
                                    onClick = {
                                        debugMenuExpanded = false
                                        com.example.evolvix.notifications.DebugTriggers
                                            .resetSummaryDisable(appCtx)
                                    }
                                )
                            }
                        }
                    }
                    } // end of else (normal mode actions)
                }
            )
        }
    ) { paddingValues ->

        // ── New Group dialog ──────────────────────────────────────────────────
        // Shown when the user taps "New Group" in reorder mode.
        // On confirm, transitions to multi-select mode for habit assignment.
        if (showNewGroupDialog) {
            AlertDialog(
                onDismissRequest = {
                    showNewGroupDialog = false
                    newGroupNameInput = ""
                },
                title = { Text(stringResource(R.string.dialog_new_group_title)) },
                text = {
                    OutlinedTextField(
                        value = newGroupNameInput,
                        onValueChange = { newGroupNameInput = it },
                        label = { Text(stringResource(R.string.hint_group_name)) },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = newGroupNameInput.isNotBlank(),
                        onClick = {
                            pendingGroupName = newGroupNameInput.trim()
                            newGroupNameInput = ""
                            showNewGroupDialog = false
                            multiSelectMode = true
                            selectedHabitIds = emptySet()
                        }
                    ) { Text(stringResource(R.string.btn_create)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showNewGroupDialog = false
                        newGroupNameInput = ""
                    }) { Text(stringResource(R.string.btn_cancel)) }
                }
            )
        }

        // ── Delete group confirmation dialog ──────────────────────────────────
        // Two variants:
        //   • Non-empty group: warns that habits inside will also be deleted.
        //   • Empty group: simple confirmation (no data loss beyond the group label).
        val pendingDeleteSection = groupPendingDelete
        if (pendingDeleteSection != null) {
            val habitCount = pendingDeleteSection.habits.size
            AlertDialog(
                onDismissRequest = { groupPendingDelete = null },
                title = {
                    if (pendingDeleteSection.groupName != null)
                        Text(stringResource(R.string.dialog_delete_group_title, pendingDeleteSection.groupName))
                    else
                        Text(stringResource(R.string.dialog_delete_group_empty))
                },
                text = {
                    if (habitCount > 0) {
                        Text(pluralStringResource(R.plurals.dialog_delete_group_body, habitCount, habitCount))
                    } else {
                        Text(stringResource(R.string.dialog_delete_group_empty))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = pendingDeleteSection.groupName ?: return@TextButton
                            if (habitCount > 0) {
                                habitViewModel.deleteManualGroupWithHabits(name)
                            }
                            // Empty group: no habits to delete; the group ceases to exist
                            // automatically once no habits reference it.
                            groupPendingDelete = null
                        }
                    ) { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { groupPendingDelete = null }) { Text(stringResource(R.string.btn_cancel)) }
                }
            )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Chip row: search toggle + category filters ─────────────────────
            // Hidden in reorder mode — filters are irrelevant while dragging,
            // and hiding them prevents accidental changes to the visible list.
            if (!reorderMode) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search chip / expanded search field (always first)
                item {
                    if (searchExpanded) {
                        // Inline search field replaces the icon chip while active
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { habitViewModel.setSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.hint_search)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    habitViewModel.setSearchQuery("")
                                    searchExpanded = false
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close_search))
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.width(220.dp)
                        )
                    } else {
                        // Collapsed state: icon-only chip that opens the field
                        InputChip(
                            selected = searchQuery.isNotEmpty(),
                            onClick = { searchExpanded = true },
                            label = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = stringResource(R.string.cd_search_habits),
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                }

                // "Clear filters" chip — visible only while at least one filter is active
                if (activeFilters.isNotEmpty()) {
                    item {
                        InputChip(
                            selected = false,
                            onClick = { habitViewModel.clearFilters() },
                            label = { Text(stringResource(R.string.btn_clear_filters)) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_clear_filters),
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                }

                // One FilterChip per unique category; highlighted when active
                items(availableCategories.sorted()) { category ->
                    FilterChip(
                        selected = category in activeFilters,
                        onClick = { habitViewModel.toggleFilter(category) },
                        label = { Text(category) }
                    )
                }
            }
            } // end if (!reorderMode) filter row

            // ── Habit list ────────────────────────────────────────────────────
            // Box wraps the LazyColumn so the floating overlay can be drawn on top
            // of all items. This is the community-standard solution to the LazyColumn
            // zIndex limitation — the overlay is a sibling of the list, not a child.
            Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // No global spacedBy — spacing between groups is handled per-item below
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (groupedHabits != null) {
                    // CATEGORY mode: each group is ONE LazyColumn item (a Column) so there
                    // is no lazy item boundary between the header and the habits — gap-free.
                    // reorderMode is inactive in this branch (auto-exited via LaunchedEffect).
                    groupedHabits.entries.forEachIndexed { groupIndex, (group, habits) ->
                        if (groupIndex > 0) {
                            item(key = "gap_$group") { Spacer(Modifier.height(12.dp)) }
                        }
                        item(key = "group_$group") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                            ) {
                                // Header row — background comes from the parent Column
                                CategoryGroupHeader(
                                    title = group,
                                    isCollapsed = group in collapsedGroups,
                                    onToggle = {
                                        collapsedGroups = if (group in collapsedGroups)
                                            collapsedGroups - group
                                        else
                                            collapsedGroups + group
                                    }
                                )
                                if (group !in collapsedGroups) {
                                    habits.forEach { habit ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 8.dp, end = 8.dp, top = 0.dp, bottom = 4.dp)
                                        ) {
                                            HabitRow(
                                                habit = habit,
                                                viewModel = habitViewModel,
                                                onNavigateToStatistics = onNavigateToStatistics,
                                                onNavigateToHistory = onNavigateToHistory,
                                                onNavigateToEditHabit = onNavigateToEditHabit,
                                            isManualSortActive = false,
                                            reorderMode = false,
                                            onTriggerReorder = {}
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                } else if (manualSections != null) {
                    // MANUAL mode: section-based rendering.
                    // Named groups appear as collapsible cards (matching CATEGORY mode visually).
                    // Habits within a group are individual LazyColumn items so drag-and-drop
                    // position tracking via listState.layoutInfo still works per-item.
                    // Drag is constrained to items sharing the same manualGroup.
                    manualSections.forEachIndexed { sectionIdx, section ->
                        // Gap between sections after a named group.
                        // Ungrouped habits already carry their own 8.dp bottom spacer, so no
                        // extra gap is needed when transitioning from ungrouped → group.
                        if (sectionIdx > 0) {
                            val prevSection = manualSections[sectionIdx - 1]
                            if (prevSection.groupName != null) {
                                item(key = "manual_gap_$sectionIdx") {
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }

                        if (section.groupName != null) {
                            val groupName = section.groupName
                            // Group header item — background shape is computed at compose time
                            // so it reacts to collapse/expand state changes automatically.
                            item(key = "manual_header_$groupName") {
                                val isCollapsed = groupName in collapsedManualGroups
                                // Hide this header (and the overlay takes over) while it is being dragged.
                                val isDraggingThisGroup = draggingGroupName == groupName
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .alpha(if (isDraggingThisGroup) 0f else 1f)
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = if (isCollapsed || section.habits.isEmpty())
                                                RoundedCornerShape(12.dp)
                                            else
                                                RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                                        )
                                        .then(
                                            // Attach the group-level drag gesture only in reorder mode.
                                            // Long-press on the header starts dragging the entire group block.
                                            if (reorderMode && !multiSelectMode)
                                                Modifier.pointerInput("group_drag_$groupName") {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { _ ->
                                                            val info = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == "manual_header_$groupName" }
                                                            draggingGroupStartY = info?.offset?.toFloat() ?: 0f
                                                            draggingGroupHeaderHeight = info?.size ?: 0
                                                            draggingGroupName = groupName
                                                            draggingGroupDeltaY = 0f
                                                        },
                                                        onDrag = { _, dragAmount ->
                                                            draggingGroupDeltaY += dragAmount.y
                                                            val currentGroupName = draggingGroupName
                                                                ?: return@detectDragGesturesAfterLongPress
                                                            // Rebuild sections from the live localList so we always
                                                            // work with the latest order (gesture lambdas don't recompose).
                                                            val sections = buildManualSections(localList)
                                                            val fromSectionIdx = sections.indexOfFirst {
                                                                it.groupName == currentGroupName
                                                            }
                                                            if (fromSectionIdx == -1) return@detectDragGesturesAfterLongPress

                                                            val draggedCenter = draggingGroupStartY.toInt() +
                                                                draggingGroupHeaderHeight / 2 +
                                                                draggingGroupDeltaY.toInt()

                                                            // Visible items that are NOT part of the dragged group
                                                            // and are valid reorder anchors (habits or group headers).
                                                            val relevantItems = listState.layoutInfo.visibleItemsInfo
                                                                .filter { info ->
                                                                    val key = info.key
                                                                    val isOwn = when (key) {
                                                                        is Int -> localList.firstOrNull { it.id == key }
                                                                            ?.manualGroup == currentGroupName
                                                                        is String -> key == "manual_header_$currentGroupName"
                                                                        else -> true  // gaps: always exclude
                                                                    }
                                                                    !isOwn && (key is Int ||
                                                                        (key is String &&
                                                                            (key as String).startsWith("manual_header_")))
                                                                }

                                                            // Find the item whose Y range contains the drag center.
                                                            val targetInfo = relevantItems
                                                                .firstOrNull { info ->
                                                                    draggedCenter in info.offset until (info.offset + info.size)
                                                                }

                                                            // Map the hit item (or edge position) to a section index.
                                                            val toSectionIdx: Int = if (targetInfo != null) {
                                                                when (val key = targetInfo.key) {
                                                                    is Int -> sections.indexOfFirst { s ->
                                                                        s.habits.any { it.id == key }
                                                                    }
                                                                    is String -> {
                                                                        val tgn = (key as String).removePrefix("manual_header_")
                                                                        sections.indexOfFirst { it.groupName == tgn }
                                                                    }
                                                                    else -> -1
                                                                }
                                                            } else {
                                                                // Drag center is outside all visible items:
                                                                // resolve to list-top (0) or list-bottom (last section).
                                                                when {
                                                                    relevantItems.isNotEmpty() &&
                                                                        draggedCenter < relevantItems.first().offset -> 0
                                                                    relevantItems.isNotEmpty() &&
                                                                        draggedCenter >= relevantItems.last().offset +
                                                                            relevantItems.last().size ->
                                                                        sections.size - 1
                                                                    else -> -1
                                                                }
                                                            }

                                                            if (toSectionIdx != -1 &&
                                                                toSectionIdx != fromSectionIdx
                                                            ) {
                                                                // Section-level move: remove the group from its current
                                                                // position and insert at toSectionIdx.
                                                                // Using toSectionIdx directly (no -1 adjustment) gives
                                                                // the correct "slide-past" behavior: the dragged group
                                                                // overtakes the target and can reach any position
                                                                // including the very first and very last slot.
                                                                val newSections = sections.toMutableList()
                                                                val movedSection = newSections.removeAt(fromSectionIdx)
                                                                newSections.add(
                                                                    toSectionIdx.coerceIn(0, newSections.size),
                                                                    movedSection
                                                                )
                                                                localList = newSections.flatMap { it.habits }
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            // Commit the new sort order to the DB.
                                                            // manualGroup values are unchanged so applyNewOrderWithGroups
                                                            // behaves identically to applyNewOrder here.
                                                            habitViewModel.applyNewOrderWithGroups(localList)
                                                            draggingGroupName = null
                                                            draggingGroupDeltaY = 0f
                                                        },
                                                        onDragCancel = {
                                                            localList = allHabitsUiState
                                                            draggingGroupName = null
                                                            draggingGroupDeltaY = 0f
                                                        }
                                                    )
                                                }
                                            else Modifier
                                        )
                                ) {
                                    CategoryGroupHeader(
                                        title = groupName,
                                        isCollapsed = isCollapsed,
                                        onToggle = {
                                            collapsedManualGroups =
                                                if (groupName in collapsedManualGroups)
                                                    collapsedManualGroups - groupName
                                                else
                                                    collapsedManualGroups + groupName
                                        },
                                        showEditIcon = reorderMode && !multiSelectMode,
                                        showDragHandle = reorderMode && !multiSelectMode,
                                        showDeleteIcon = reorderMode && !multiSelectMode,
                                        showEditHabitsIcon = reorderMode && !multiSelectMode,
                                        isEditing = editingGroupName == groupName,
                                        editText = editingGroupNameInput,
                                        onEditTextChange = { editingGroupNameInput = it },
                                        onEditStart = {
                                            editingGroupName = groupName
                                            editingGroupNameInput = groupName
                                        },
                                        onEditConfirm = {
                                            val trimmed = editingGroupNameInput.trim()
                                            if (trimmed.isNotEmpty()) {
                                                habitViewModel.renameManualGroup(groupName, trimmed)
                                                if (groupName in collapsedManualGroups) {
                                                    collapsedManualGroups =
                                                        collapsedManualGroups - groupName + trimmed
                                                }
                                            }
                                            editingGroupName = null
                                        },
                                        onDeleteGroup = {
                                            // Capture the current section snapshot for the dialog.
                                            groupPendingDelete = section
                                        },
                                        onEditGroupHabits = {
                                            // Enter multi-select to edit existing group membership.
                                            // Pre-check the habits already belonging to this group.
                                            pendingGroupName = groupName
                                            editGroupOriginalHabitIds = section.habits.map { it.id }.toSet()
                                            selectedHabitIds = editGroupOriginalHabitIds
                                            isEditingExistingGroup = true
                                            multiSelectMode = true
                                        }
                                    )
                                }
                            }

                            // Individual habit items within the group (only when expanded).
                            // Keeping each habit as its own LazyColumn item preserves
                            // listState position tracking needed for drag-and-drop.
                            if (groupName !in collapsedManualGroups) {
                                section.habits.forEachIndexed { habitIdx, habit ->
                                    val isLastInGroup = habitIdx == section.habits.size - 1
                                    item(key = habit.id) {
                                        val isDragging = draggingItemId == habit.id
                                        // Also ghost this habit when its whole group is being dragged.
                                        val isInDraggedGroup = draggingGroupName == groupName
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animateItem()
                                                .background(
                                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                    shape = if (isLastInGroup)
                                                        RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                                    else
                                                        RoundedCornerShape(0.dp)
                                                )
                                                .then(
                                                    if (reorderMode && !multiSelectMode)
                                                        Modifier.pointerInput(habit.id) {
                                                            detectDragGesturesAfterLongPress(
                                                                onDragStart = { _ ->
                                                                    val info = listState.layoutInfo.visibleItemsInfo
                                                                        .firstOrNull { it.key == habit.id }
                                                                    draggingItemStartY = info?.offset?.toFloat() ?: 0f
                                                                    draggingItemHeight = info?.size ?: 0
                                                                    draggingItemId = habit.id
                                                                    draggingDeltaY = 0f
                                                                },
                                                                onDrag = { _, dragAmount ->
                                                                    draggingDeltaY += dragAmount.y
                                                                    val fromIdx = localList.indexOfFirst { it.id == draggingItemId }
                                                                    if (fromIdx != -1) {
                                                                        val draggedCenter = draggingItemStartY.toInt() +
                                                                            draggingItemHeight / 2 +
                                                                            draggingDeltaY.toInt()
                                                                        val targetInfo = listState.layoutInfo.visibleItemsInfo
                                                                            .firstOrNull { info ->
                                                                                info.key != draggingItemId &&
                                                                                draggedCenter in info.offset until (info.offset + info.size)
                                                                            }
                                                                        if (targetInfo != null) {
                                                                            val toIdx = localList.indexOfFirst { it.id == targetInfo.key }
                                                                            val fromHabit = localList.getOrNull(fromIdx)
                                                                            val toHabit = localList.getOrNull(toIdx)
                                                                            // Only swap within the same group (including ungrouped → ungrouped)
                                                                            if (toIdx != -1 && toIdx != fromIdx &&
                                                                                fromHabit?.manualGroup == toHabit?.manualGroup) {
                                                                                val newList = localList.toMutableList()
                                                                                val moved = newList.removeAt(fromIdx)
                                                                                newList.add(toIdx, moved)
                                                                                localList = newList
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                onDragEnd = {
                                                                    habitViewModel.applyNewOrder(localList.map { it.id })
                                                                    draggingItemId = -1
                                                                    draggingDeltaY = 0f
                                                                },
                                                                onDragCancel = {
                                                                    localList = allHabitsUiState
                                                                    draggingItemId = -1
                                                                    draggingDeltaY = 0f
                                                                }
                                                            )
                                                        }
                                                    else Modifier
                                                )
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = 8.dp, end = 8.dp)
                                                    .alpha(if (isDragging || isInDraggedGroup) 0f else 1f)
                                            ) {
                                                if (reorderMode && !multiSelectMode) {
                                                    Icon(
                                                        imageVector = Icons.Filled.DragHandle,
                                                        contentDescription = stringResource(R.string.cd_drag_reorder),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        modifier = Modifier.padding(end = 4.dp)
                                                    )
                                                }
                                                if (multiSelectMode) {
                                                    // When editing an existing group, only show checkboxes for
                                                    // the group being edited — habits in other groups are not
                                                    // eligible for reassignment in this flow.
                                                    val isEligible = !isEditingExistingGroup ||
                                                        groupName == pendingGroupName
                                                    if (isEligible) {
                                                        Checkbox(
                                                            checked = habit.id in selectedHabitIds,
                                                            onCheckedChange = { checked ->
                                                                selectedHabitIds =
                                                                    if (checked) selectedHabitIds + habit.id
                                                                    else selectedHabitIds - habit.id
                                                            }
                                                        )
                                                    }
                                                }
                                                Box(modifier = Modifier.weight(1f)) {
                                                    HabitRow(
                                                        habit = habit,
                                                        viewModel = habitViewModel,
                                                        onNavigateToStatistics = onNavigateToStatistics,
                                                        onNavigateToHistory = onNavigateToHistory,
                                                        onNavigateToEditHabit = onNavigateToEditHabit,
                                                        isManualSortActive = true,
                                                        reorderMode = reorderMode,
                                                        onTriggerReorder = { habitViewModel.enterReorderMode() }
                                                    )
                                                }
                                            }
                                            //if (isLastInGroup) 
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            // Ungrouped single habit — same rendering as before with group constraint
                            val habit = section.habits.first()
                            item(key = habit.id) {
                                val isDragging = draggingItemId == habit.id
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .then(
                                            if (reorderMode && !multiSelectMode)
                                                Modifier.pointerInput(habit.id) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { _ ->
                                                            val info = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == habit.id }
                                                            draggingItemStartY = info?.offset?.toFloat() ?: 0f
                                                            draggingItemHeight = info?.size ?: 0
                                                            draggingItemId = habit.id
                                                            draggingDeltaY = 0f
                                                        },
                                                        onDrag = { _, dragAmount ->
                                                            draggingDeltaY += dragAmount.y
                                                            // Section-level reordering: treat the entire list as
                                                            // an ordered sequence of sections (single habits and
                                                            // groups). The dragged habit moves its whole section
                                                            // past other sections — including entire groups —
                                                            // without joining them. This lets ungrouped habits
                                                            // freely move above or below any group.
                                                            val sections = buildManualSections(localList)
                                                            val fromSectionIdx = sections.indexOfFirst { s ->
                                                                s.groupName == null && s.habits.any { it.id == draggingItemId }
                                                            }
                                                            if (fromSectionIdx != -1) {
                                                                val draggedCenter = draggingItemStartY.toInt() +
                                                                    draggingItemHeight / 2 +
                                                                    draggingDeltaY.toInt()

                                                                // Valid anchor items: habits (Int key) or group
                                                                // headers (String "manual_header_*"), excluding self
                                                                // and spacer/gap keys.
                                                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                                                val relevantItems = visibleItems.filter { info ->
                                                                    val key = info.key
                                                                    key != draggingItemId &&
                                                                    (key is Int || (key is String &&
                                                                        (key as String).startsWith("manual_header_")))
                                                                }
                                                                val targetInfo = relevantItems.firstOrNull { info ->
                                                                    draggedCenter in info.offset until (info.offset + info.size)
                                                                }

                                                                val toSectionIdx: Int = if (targetInfo != null) {
                                                                    when (val key = targetInfo.key) {
                                                                        is Int -> sections.indexOfFirst { s ->
                                                                            s.habits.any { it.id == key }
                                                                        }
                                                                        is String -> sections.indexOfFirst { s ->
                                                                            s.groupName == (key as String)
                                                                                .removePrefix("manual_header_")
                                                                        }
                                                                        else -> -1
                                                                    }
                                                                } else {
                                                                    // Edge case: drag center is above or below all items.
                                                                    when {
                                                                        relevantItems.isNotEmpty() &&
                                                                            draggedCenter < relevantItems.first().offset -> 0
                                                                        relevantItems.isNotEmpty() &&
                                                                            draggedCenter >= relevantItems.last().offset +
                                                                                relevantItems.last().size ->
                                                                            sections.size - 1
                                                                        else -> -1
                                                                    }
                                                                }

                                                                if (toSectionIdx != -1 && toSectionIdx != fromSectionIdx) {
                                                                    val newSections = sections.toMutableList()
                                                                    val movedSection = newSections.removeAt(fromSectionIdx)
                                                                    newSections.add(
                                                                        toSectionIdx.coerceIn(0, newSections.size),
                                                                        movedSection
                                                                    )
                                                                    localList = newSections.flatMap { it.habits }
                                                                }
                                                            }
                                                        },
                                                        onDragEnd = {
                                                            // manualGroup values are unchanged (section-level
                                                            // reorder never reassigns groups), so applyNewOrder
                                                            // is sufficient here.
                                                            habitViewModel.applyNewOrder(localList.map { it.id })
                                                            draggingItemId = -1
                                                            draggingDeltaY = 0f
                                                        },
                                                        onDragCancel = {
                                                            localList = allHabitsUiState
                                                            draggingItemId = -1
                                                            draggingDeltaY = 0f
                                                        }
                                                    )
                                                }
                                            else Modifier
                                        )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .alpha(if (isDragging) 0f else 1f)
                                    ) {
                                        if (reorderMode && !multiSelectMode) {
                                            Icon(
                                                imageVector = Icons.Filled.DragHandle,
                                                contentDescription = stringResource(R.string.cd_drag_reorder),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.padding(end = 4.dp)
                                            )
                                        }
                                        if (multiSelectMode) {
                                            // Ungrouped habits are always eligible — they can be added
                                            // to any group in both the create and edit flows.
                                            Checkbox(
                                                checked = habit.id in selectedHabitIds,
                                                onCheckedChange = { checked ->
                                                    selectedHabitIds =
                                                        if (checked) selectedHabitIds + habit.id
                                                        else selectedHabitIds - habit.id
                                                }
                                            )
                                        }
                                        Box(modifier = Modifier.weight(1f)) {
                                            HabitRow(
                                                habit = habit,
                                                viewModel = habitViewModel,
                                                onNavigateToStatistics = onNavigateToStatistics,
                                                onNavigateToHistory = onNavigateToHistory,
                                                onNavigateToEditHabit = onNavigateToEditHabit,
                                                isManualSortActive = true,
                                                reorderMode = reorderMode,
                                                onTriggerReorder = { habitViewModel.enterReorderMode() }
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                } else {
                    // NAME mode: flat list, no grouping, no reorder
                    items(localList, key = { it.id }) { habit ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                        ) {
                            HabitRow(
                                habit = habit,
                                viewModel = habitViewModel,
                                onNavigateToStatistics = onNavigateToStatistics,
                                onNavigateToHistory = onNavigateToHistory,
                                onNavigateToEditHabit = onNavigateToEditHabit,
                                isManualSortActive = false,
                                reorderMode = false,
                                onTriggerReorder = {}
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            } // end LazyColumn

            // ── Floating overlay ──────────────────────────────────────────────
            // Rendered as a sibling of LazyColumn inside the Box, so it is
            // guaranteed to be drawn on top of all list items regardless of
            // zIndex or layer ordering inside the LazyColumn.
            val draggedHabit = if (draggingItemId != -1)
                localList.firstOrNull { it.id == draggingItemId }
            else null

            if (draggedHabit != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Match the LazyColumn's horizontal content padding
                        .padding(horizontal = 16.dp)
                        // Position the overlay at the finger's absolute Y in the viewport
                        .offset { IntOffset(0, (draggingItemStartY + draggingDeltaY).roundToInt()) }
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    ProgressItem(
                        title = draggedHabit.name,
                        maxClicks = draggedHabit.target,
                        currentClickCount = draggedHabit.currentCount,
                        colorScheme = HabitColorScheme.fromHex(draggedHabit.colorHex),
                        isOverCompleted = draggedHabit.isOverCompleted,
                        isPaused = draggedHabit.pausedUntil != null,
                        isSystemInDarkTheme = LocalIsDarkTheme.current,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Group drag overlay ────────────────────────────────────────────
            // Shows a compact group header card that tracks the finger while the
            // user drags an entire group block to a new position.
            if (draggingGroupName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset {
                            IntOffset(
                                0,
                                (draggingGroupStartY + draggingGroupDeltaY).roundToInt()
                            )
                        }
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp)
                    )
                    Text(
                        text = draggingGroupName!!,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp)
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandLess,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }

            } // end Box
        }
    }
}

/**
 * Header row for a collapsible group section.
 * Used in both CATEGORY mode (read-only) and MANUAL mode (optionally editable).
 * Background and shape are provided by the parent container — this composable is transparent.
 *
 * @param showDragHandle When true, a DragHandle icon is shown at the leading edge (MANUAL reorder mode).
 * @param showEditIcon When true, an Edit icon is shown for inline rename (MANUAL reorder mode).
 * @param showDeleteIcon When true, a Delete icon is shown to trigger group deletion (MANUAL reorder mode).
 * @param showEditHabitsIcon When true, a list icon is shown to edit the group's habit members (MANUAL reorder mode).
 * @param isEditing When true, replaces the title [Text] with an inline [BasicTextField].
 * @param editText Current value of the inline rename field.
 * @param onEditTextChange Called on every keystroke in the rename field.
 * @param onEditStart Called when the user taps the Edit icon to start renaming.
 * @param onEditConfirm Called when the user confirms the rename (Done action or icon).
 * @param onDeleteGroup Called when the user taps the Delete icon.
 * @param onEditGroupHabits Called when the user taps the edit-habits icon.
 */
@Composable
private fun CategoryGroupHeader(
    title: String,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    showDragHandle: Boolean = false,
    showEditIcon: Boolean = false,
    showDeleteIcon: Boolean = false,
    showEditHabitsIcon: Boolean = false,
    isEditing: Boolean = false,
    editText: String = "",
    onEditTextChange: (String) -> Unit = {},
    onEditStart: () -> Unit = {},
    onEditConfirm: () -> Unit = {},
    onDeleteGroup: () -> Unit = {},
    onEditGroupHabits: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = if (showDragHandle) 8.dp else 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Drag handle shown in reorder mode — the outer Box has the pointerInput gesture,
        // this icon is purely visual feedback that the header is draggable.
        if (showDragHandle) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.cd_drag_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        if (isEditing) {
            // Inline rename field: replaces the title text during edit mode.
            // BasicTextField is used (not OutlinedTextField) to match the header's style.
            BasicTextField(
                value = editText,
                onValueChange = onEditTextChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEditConfirm() }),
                textStyle = MaterialTheme.typography.titleSmall.copy(
                    color = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.weight(1f)
            )
            // Confirm button for the rename
            IconButton(onClick = onEditConfirm) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.cd_edit_name),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            // Edit-habits icon: opens the habit-selection sheet to add/remove members
            if (showEditHabitsIcon) {
                IconButton(onClick = onEditGroupHabits) {
                    Icon(
                        imageVector = Icons.Filled.List,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Edit icon shown only in MANUAL reorder mode — opens inline rename
            if (showEditIcon) {
                IconButton(onClick = onEditStart) {
                    Icon(
                        imageVector = Icons.Filled.EditNote,
                        contentDescription = stringResource(R.string.cd_edit_name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Delete icon shown only in MANUAL reorder mode — triggers confirmation dialog
            if (showDeleteIcon) {
                IconButton(onClick = onDeleteGroup) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.btn_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = stringResource(if (isCollapsed) R.string.cd_expand else R.string.cd_collapse)
            )
        }
    }
}

/**
 * Single habit row wrapped in the long-press context menu.
 * Extracted to avoid repeating the same composition in both the grouped
 * and flat LazyColumn branches (DRY principle).
 */
@Composable
private fun HabitRow(
    habit: HabitUiState,
    viewModel: HabitViewModel,
    onNavigateToStatistics: () -> Unit,
    onNavigateToHistory: (Int, String) -> Unit,
    onNavigateToEditHabit: (Int) -> Unit,
    /** True when MANUAL sort is active — passed to [HabitContextMenu] to enable the reorder item. */
    isManualSortActive: Boolean,
    /** True while drag-and-drop reorder mode is active — disables tap/long-press interactions. */
    reorderMode: Boolean,
    onTriggerReorder: () -> Unit
) {
    // Long press opens the 7-action DropdownMenu (IDEAS.MD §4.4)
    HabitContextMenu(
        habit = habit,
        onMarkProgress = { viewModel.incrementHabitCompletion(habit.id) },
        onNavigateToStatistics = onNavigateToStatistics,
        onPauseUntil = { until -> viewModel.pauseHabit(habit.id, until) },
        onResume = { viewModel.resumeHabit(habit.id) },
        onNavigateToHistory = { onNavigateToHistory(habit.id, habit.name) },
        onNavigateToEdit = { onNavigateToEditHabit(habit.id) },
        onDelete = { viewModel.deleteHabit(habit.id, onSuccess = {}, onError = {}) },
        isManualSortActive = isManualSortActive,
        reorderMode = reorderMode,
        onTriggerReorder = onTriggerReorder
    ) {
        ProgressItem(
            title = habit.name,
            maxClicks = habit.target,
            currentClickCount = habit.currentCount,
            colorScheme = HabitColorScheme.fromHex(habit.colorHex),
            isOverCompleted = habit.isOverCompleted,
            isPaused = habit.pausedUntil != null,
            isSystemInDarkTheme = LocalIsDarkTheme.current,
            modifier = Modifier.fillMaxWidth()
        )
    }
}