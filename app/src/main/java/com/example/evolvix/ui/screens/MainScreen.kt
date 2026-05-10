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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.alpha
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.domain.model.SortMode
import com.example.evolvix.ui.components.HabitContextMenu
import com.example.evolvix.ui.components.ProgressItem
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.ui.viewmodel.HabitViewModel
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
    onNavigateToHistory: (Int) -> Unit = {},
    habitViewModel: HabitViewModel
) {
    // Reset daily/weekly/monthly progress periods on first composition
    LaunchedEffect(Unit) {
        habitViewModel.checkAndResetProgress()
    }

    // Collect all reactive state from the ViewModel (Observer pattern via StateFlow)
    val allHabitsUiState by habitViewModel.allHabits.collectAsState()
    val sortMode        by habitViewModel.sortMode.collectAsState()
    val activeFilters   by habitViewModel.activeFilters.collectAsState()
    val availableCategories by habitViewModel.availableCategories.collectAsState()
    val searchQuery     by habitViewModel.searchQuery.collectAsState()

    // Local UI state: which category group headers are currently collapsed
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    // Controls visibility of the sort-order DropdownMenu in the top bar
    var sortMenuExpanded by remember { mutableStateOf(false) }
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
    // category tag, then "Other"). Returns null in MANUAL/NAME modes → flat list branch.
    val groupedHabits: Map<String, List<HabitUiState>>? = remember(allHabitsUiState, sortMode) {
        if (sortMode == SortMode.CATEGORY) {
            allHabitsUiState.groupBy {
                it.categoryGroup ?: it.categories.firstOrNull() ?: "Other"
            }
        } else null
    }

    // When MANUAL sort is active, build an ordered list of sections from localList.
    // Consecutive habits sharing the same non-null manualGroup form one ManualSection;
    // ungrouped habits (null) each become their own single-habit section.
    // Returns null in CATEGORY/NAME modes — those branches use their own rendering.
    val manualSections: List<ManualSection>? = remember(localList, sortMode) {
        if (sortMode == SortMode.MANUAL) buildManualSections(localList) else null
    }

    Scaffold(
        topBar = {
            // TopAppBar standardized across all screens (Composition over inheritance — Phase 1.2)
            TopAppBar(
                title = { Text("My Habits") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0),
                actions = {
                    // While in reorder mode show a "Done" button that exits the mode.
                    // The normal sort + settings actions are hidden so the UI is unambiguous.
                    if (reorderMode) {
                        if (multiSelectMode) {
                            // Multi-select: confirm assignment or cancel the group creation
                            TextButton(
                                enabled = selectedHabitIds.isNotEmpty(),
                                onClick = {
                                    habitViewModel.createManualGroup(pendingGroupName, selectedHabitIds.toList())
                                    multiSelectMode = false
                                    selectedHabitIds = emptySet()
                                    pendingGroupName = ""
                                }
                            ) {
                                Text("Add ${selectedHabitIds.size} habits")
                            }
                            TextButton(onClick = {
                                multiSelectMode = false
                                selectedHabitIds = emptySet()
                                pendingGroupName = ""
                            }) {
                                Text("Cancel")
                            }
                        } else {
                            // Normal reorder mode: offer group creation and done
                            TextButton(onClick = { showNewGroupDialog = true }) {
                                Text("New Group")
                            }
                            TextButton(onClick = { habitViewModel.exitReorderMode() }) {
                                Text("Done")
                            }
                        }
                    } else {
                    // Sort-order picker — opens a DropdownMenu with 3 sort options
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort order")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false }
                        ) {
                            val sortLabels = mapOf(
                                SortMode.MANUAL   to "Manual order",
                                SortMode.NAME     to "Name (A–Z)",
                                SortMode.CATEGORY to "By category"
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
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                title = { Text("New Group") },
                text = {
                    OutlinedTextField(
                        value = newGroupNameInput,
                        onValueChange = { newGroupNameInput = it },
                        label = { Text("Group name") },
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
                    ) { Text("Create") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showNewGroupDialog = false
                        newGroupNameInput = ""
                    }) { Text("Cancel") }
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
                            placeholder = { Text("Search…") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    habitViewModel.setSearchQuery("")
                                    searchExpanded = false
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close search")
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
                                    contentDescription = "Search habits",
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
                            label = { Text("Clear") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear all filters",
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
                        // Gap between sections when adjacent to a named group
                        if (sectionIdx > 0) {
                            val prevSection = manualSections[sectionIdx - 1]
                            if (prevSection.groupName != null || section.groupName != null) {
                                item(key = "manual_gap_$sectionIdx") {
                                    Spacer(Modifier.height(12.dp))
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
                                                        contentDescription = "Drag to reorder",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                        modifier = Modifier.padding(end = 4.dp)
                                                    )
                                                }
                                                if (multiSelectMode) {
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
                                                contentDescription = "Drag to reorder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                                modifier = Modifier.padding(end = 4.dp)
                                            )
                                        }
                                        if (multiSelectMode) {
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
                        isSystemInDarkTheme = true,
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
 * @param isEditing When true, replaces the title [Text] with an inline [BasicTextField].
 * @param editText Current value of the inline rename field.
 * @param onEditTextChange Called on every keystroke in the rename field.
 * @param onEditStart Called when the user taps the Edit icon to start renaming.
 * @param onEditConfirm Called when the user confirms the rename (Done action or icon).
 */
@Composable
private fun CategoryGroupHeader(
    title: String,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    showDragHandle: Boolean = false,
    showEditIcon: Boolean = false,
    isEditing: Boolean = false,
    editText: String = "",
    onEditTextChange: (String) -> Unit = {},
    onEditStart: () -> Unit = {},
    onEditConfirm: () -> Unit = {}
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
                contentDescription = "Drag group to reorder",
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
                    contentDescription = "Confirm rename",
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
            // Edit icon shown only in MANUAL reorder mode — opens inline rename
            if (showEditIcon) {
                IconButton(onClick = onEditStart) {
                    Icon(
                        imageVector = Icons.Filled.EditNote,
                        contentDescription = "Rename group",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (isCollapsed) "Expand group" else "Collapse group"
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
    onNavigateToHistory: (Int) -> Unit,
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
        onNavigateToHistory = { onNavigateToHistory(habit.id) },
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
            isSystemInDarkTheme = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}