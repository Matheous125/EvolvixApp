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
    // Local mutable copy of the list, updated in real-time during drag.
    // Driving LazyColumn from this (not allHabitsUiState directly) lets animateItem()
    // produce the "spreading out to make room" animation as items shift positions.
    var localList by remember { mutableStateOf(allHabitsUiState) }
    // Keep localList in sync with ViewModel emissions when no drag is in progress.
    LaunchedEffect(allHabitsUiState) {
        if (draggingItemId == -1) localList = allHabitsUiState
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
        if (sortMode == SortMode.MANUAL) {
            buildList {
                var i = 0
                while (i < localList.size) {
                    val habit = localList[i]
                    val group = habit.manualGroup
                    if (group != null) {
                        val groupHabits = mutableListOf<HabitUiState>()
                        while (i < localList.size && localList[i].manualGroup == group) {
                            groupHabits.add(localList[i])
                            i++
                        }
                        add(ManualSection(groupName = group, habits = groupHabits))
                    } else {
                        add(ManualSection(groupName = null, habits = listOf(habit)))
                        i++
                    }
                }
            }
        } else null
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
                            Icon(Icons.Filled.Sort, contentDescription = "Sort order")
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
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateItem()
                                        .background(
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            shape = if (isCollapsed || section.habits.isEmpty())
                                                RoundedCornerShape(12.dp)
                                            else
                                                RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
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
                                            if (isLastInGroup) Spacer(Modifier.height(4.dp))
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
                                                                    // Only swap with other ungrouped habits (both have null manualGroup)
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
            } // end Box
        }
    }
}

/**
 * Header row for a collapsible group section.
 * Used in both CATEGORY mode (read-only) and MANUAL mode (optionally editable).
 * Background and shape are provided by the parent container — this composable is transparent.
 *
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
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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