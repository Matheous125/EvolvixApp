package com.example.evolvix.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.domain.model.SortMode
import com.example.evolvix.ui.components.HabitContextMenu
import com.example.evolvix.ui.components.ProgressItem
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import kotlin.math.roundToInt

/**
 * Main screen displaying the full habit list with search, category filtering, and sorting.
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
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            habitDao = AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
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
    // True while the user is in drag-and-drop reorder mode (activated from context menu)
    var reorderMode by remember { mutableStateOf(false) }

    // Auto-exit reorder mode if the user switches away from MANUAL sort while it is active.
    LaunchedEffect(sortMode) {
        if (sortMode != SortMode.MANUAL) reorderMode = false
    }

    // ── Drag-and-drop state (MANUAL sort mode only) ───────────────────────────
    // ID of the item currently being dragged; -1 means no drag in progress.
    var draggingItemId by remember { mutableIntStateOf(-1) }
    // Accumulated pixel offset from the drag start position (Y axis only).
    var draggingDeltaY by remember { mutableFloatStateOf(0f) }
    // ID of the item that the dragged item is hovering over (the drop target).
    var hoveredItemId  by remember { mutableIntStateOf(-1) }
    // LazyListState lets us query each item's viewport offset during the drag gesture.
    val listState = rememberLazyListState()

    // When CATEGORY sort is active, group habits by categoryGroup (falls back to first
    // category tag, then "Other"). Returns null in MANUAL/NAME modes → flat list branch.
    val groupedHabits: Map<String, List<HabitUiState>>? = remember(allHabitsUiState, sortMode) {
        if (sortMode == SortMode.CATEGORY) {
            allHabitsUiState.groupBy {
                it.categoryGroup ?: it.categories.firstOrNull() ?: "Other"
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
                        TextButton(onClick = { reorderMode = false }) {
                            Text("Done")
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
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Chip row: search toggle + category filters ─────────────────────
            // The magnifying glass is the first item in the LazyRow.
            // Tapping it expands into an inline text field; tapping the X collapses it.
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

            // ── Habit list ────────────────────────────────────────────────────
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
                                            onTriggerReorder = {}
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                } else {
                    // MANUAL or NAME mode: flat list.
                    // In MANUAL mode a DragHandle icon is shown on the left of each row.
                    // Long-pressing the handle triggers detectDragGesturesAfterLongPress
                    // (Pattern: Command — the index swap is an encapsulated ViewModel action).
                    items(allHabitsUiState, key = { it.id }) { habit ->
                        val isDragging = draggingItemId == habit.id
                        val isTarget   = hoveredItemId  == habit.id && !isDragging

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                // Raise the dragged item above its siblings while floating
                                .zIndex(if (isDragging) 1f else 0f)
                                // Translate the item visually without disturbing layout flow
                                .offset { IntOffset(0, if (isDragging) draggingDeltaY.roundToInt() else 0) }
                        ) {
                            // Drag handle: only visible while reorder mode is active
                            if (reorderMode) {
                                Icon(
                                    imageVector = Icons.Filled.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = if (isDragging) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        // pointerInput scoped to this habit's ID so the
                                        // lambda is stable and only restarts when the ID changes.
                                        .pointerInput(habit.id) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { _ ->
                                                    draggingItemId = habit.id
                                                    draggingDeltaY = 0f
                                                },
                                                onDrag = { _, dragAmount ->
                                                    draggingDeltaY += dragAmount.y
                                                    // Find which item the drag center is over
                                                    // using LazyListState's layout snapshot.
                                                    val dragInfo = listState.layoutInfo
                                                        .visibleItemsInfo
                                                        .firstOrNull { it.key == draggingItemId }
                                                    if (dragInfo != null) {
                                                        val center = dragInfo.offset.toFloat() +
                                                                     dragInfo.size / 2f +
                                                                     draggingDeltaY
                                                        hoveredItemId = listState.layoutInfo
                                                            .visibleItemsInfo
                                                            .firstOrNull { info ->
                                                                center >= info.offset &&
                                                                center < info.offset + info.size
                                                            }?.key as? Int ?: -1
                                                    }
                                                },
                                                onDragEnd = {
                                                    val from = draggingItemId
                                                    val to   = hoveredItemId
                                                    if (from != -1 && to != -1 && from != to) {
                                                        habitViewModel.reorderHabits(from, to)
                                                    }
                                                    draggingItemId = -1
                                                    draggingDeltaY = 0f
                                                    hoveredItemId  = -1
                                                },
                                                onDragCancel = {
                                                    draggingItemId = -1
                                                    draggingDeltaY = 0f
                                                    hoveredItemId  = -1
                                                }
                                            )
                                        }
                                )
                            }

                            // Highlight the drop target with a primary-color border
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (isTarget) Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(8.dp)
                                        ) else Modifier
                                    )
                            ) {
                                HabitRow(
                                    habit = habit,
                                    viewModel = habitViewModel,
                                    onNavigateToStatistics = onNavigateToStatistics,
                                    onNavigateToHistory = onNavigateToHistory,
                                    onNavigateToEditHabit = onNavigateToEditHabit,
                                    isManualSortActive = (sortMode == SortMode.MANUAL),
                                    onTriggerReorder = { reorderMode = true }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/**
 * Header row for a collapsible category group.
 * Background and shape are provided by the parent [Column] so this composable
 * is fully transparent — no gap can appear between it and the rows below.
 */
@Composable
private fun CategoryGroupHeader(
    title: String,
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (isCollapsed) "Expand group" else "Collapse group"
        )
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