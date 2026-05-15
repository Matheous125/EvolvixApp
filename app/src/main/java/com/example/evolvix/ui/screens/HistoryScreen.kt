package com.example.evolvix.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitCompletionEntity
import com.example.evolvix.domain.usecase.ExportHistoryUseCase
import com.example.evolvix.ui.viewmodel.HistoryViewModel
import com.example.evolvix.ui.viewmodel.HistoryViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// ─── Formatters ──────────────────────────────────────────────────────────────

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * History screen for a single habit, identified by [habitId].
 *
 * Displays all [HabitCompletionEntity] records grouped by Year > Month in a
 * collapsible [LazyColumn]. Each entry shows the timestamp, a target-reached
 * indicator, an edit icon, and a delete icon.
 *
 * A FAB opens a retroactive-add dialog where the user picks a past date and
 * time via the Material 3 [DatePicker] and a time-input row.
 *
 * (Pattern: MVVM — screen is a stateless consumer of [HistoryViewModel.groupedByYearMonth])
 *
 * @param habitId       Primary key of the habit whose history is shown.
 * @param habitName     Display name shown in the [TopAppBar].
 * @param onNavigateUp  Lambda called when the back arrow is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    habitId: Int,
    habitName: String,
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val dao = AppDatabase.getDatabase(context).habitDao()
    val viewModel: HistoryViewModel = viewModel(
        key = "history_$habitId",
        factory = HistoryViewModelFactory(dao, habitId)
    )

    // The ViewModel emits a fresh map whenever Room writes occur.
    // (Pattern: Observer via StateFlow — recomposition is driven by data changes)
    val groupedEntries by viewModel.groupedByYearMonth.collectAsState()

    // Flat list consumed by ExportHistoryUseCase to build the JSON payload.
    val flatCompletions by viewModel.flatCompletions.collectAsState()

    val scope = rememberCoroutineScope()
    val exportUseCase = remember { ExportHistoryUseCase() }

    // Launches the system "Save file" picker. When the user confirms, writes the
    // JSON export to the chosen URI via ContentResolver on the IO dispatcher.
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val json = exportUseCase(habitName, flatCompletions)
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            }
        }
    }

    // Controls the retroactive-add dialog visibility.
    var showAddDialog by remember { mutableStateOf(false) }

    // Tracks which completion is being edited (null = none).
    var editingEntry by remember { mutableStateOf<HabitCompletionEntity?>(null) }

    // Tracks which completion is pending deletion confirmation (null = none).
    var deletingEntry by remember { mutableStateOf<HabitCompletionEntity?>(null) }

    // Set of year keys whose sections are collapsed. Empty = all expanded.
    val collapsedYears = remember { mutableStateOf(emptySet<Int>()) }

    // Set of "year-month" keys whose sections are collapsed. Key = "$year-$month".
    val collapsedMonths = remember { mutableStateOf(emptySet<String>()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$habitName — History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Export button — triggers the system file-picker with a pre-filled
                    // filename. The user can rename it before saving.
                    IconButton(
                        onClick = {
                            // Sanitize the habit name so it is safe as a filename.
                            val safeName = habitName.replace(Regex("[^\\w\\-]"), "_")
                            exportLauncher.launch("${safeName}_history.json")
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = "Export history as JSON"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0)
            )
        },
        floatingActionButton = {
            // FAB opens the retroactive-add dialog.
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add retroactive entry")
            }
        }
    ) { innerPadding ->

        if (groupedEntries.isEmpty()) {
            // Empty state — shown before the first completion is logged.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No history yet.\nTap + to add a retroactive entry.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Outer loop: years (sorted descending by HistoryViewModel)
                groupedEntries.forEach { (year, byMonth) ->
                    val yearCollapsed = year in collapsedYears.value

                    // Year header — tapping toggles all months for that year.
                    item(key = "year_$year") {
                        YearHeader(
                            year = year,
                            collapsed = yearCollapsed,
                            onToggle = {
                                collapsedYears.value = if (yearCollapsed)
                                    collapsedYears.value - year
                                else
                                    collapsedYears.value + year
                            }
                        )
                    }

                    if (!yearCollapsed) {
                        // Inner loop: months within the year (sorted descending)
                        byMonth.forEach { (monthValue, entries) ->
                            val monthKey = "$year-$monthValue"
                            val monthCollapsed = monthKey in collapsedMonths.value

                            // Month header — tapping toggles only that month.
                            item(key = "month_$monthKey") {
                                MonthHeader(
                                    monthValue = monthValue,
                                    entryCount = entries.size,
                                    collapsed = monthCollapsed,
                                    onToggle = {
                                        collapsedMonths.value = if (monthCollapsed)
                                            collapsedMonths.value - monthKey
                                        else
                                            collapsedMonths.value + monthKey
                                    }
                                )
                            }

                            if (!monthCollapsed) {
                                // Entry rows for the expanded month.
                                items(entries, key = { it.id }) { entry ->
                                    CompletionEntryRow(
                                        entry = entry,
                                        onEdit = { editingEntry = entry },
                                        onDelete = { deletingEntry = entry }
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom padding so the FAB doesn't obscure the last item.
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    // ── Edit dialog ──────────────────────────────────────────────────────────
    editingEntry?.let { entry ->
        EditCompletionDialog(
            initial = entry,
            onConfirm = { updated ->
                viewModel.updateCompletion(updated)
                editingEntry = null
            },
            onDismiss = { editingEntry = null }
        )
    }

    // ── Delete confirmation dialog ───────────────────────────────────────────
    deletingEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deletingEntry = null },
            title = { Text("Delete entry?") },
            text = {
                Text("Remove completion from ${entry.progressUpdate.format(fullDateFormatter)}?")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteCompletion(entry.id)
                    deletingEntry = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingEntry = null }) { Text("Cancel") }
            }
        )
    }

    // ── Retroactive add dialog ───────────────────────────────────────────────
    if (showAddDialog) {
        RetroactiveAddDialog(
            onConfirm = { dateTime, isTargetReached ->
                viewModel.addRetroactiveEntry(dateTime, isTargetReached)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ─── Sub-composables ─────────────────────────────────────────────────────────

/**
 * Collapsible year header row.
 *
 * @param year      The calendar year to display.
 * @param collapsed Whether the year section is currently collapsed.
 * @param onToggle  Called when the user taps the header.
 */
@Composable
private fun YearHeader(year: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown
                              else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (collapsed) "Expand year" else "Collapse year"
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.primaryContainer)
}

/**
 * Collapsible month header row, indented one level below the year header.
 *
 * @param monthValue  Month number (1–12).
 * @param entryCount  Number of completion records in this month.
 * @param collapsed   Whether the month section is collapsed.
 * @param onToggle    Called when the user taps the header.
 */
@Composable
private fun MonthHeader(
    monthValue: Int,
    entryCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    val monthName = Month.of(monthValue).getDisplayName(TextStyle.FULL, Locale.getDefault())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$monthName  ($entryCount)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown
                              else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (collapsed) "Expand month" else "Collapse month"
            )
        }
    }
}

/**
 * A single row representing one [HabitCompletionEntity].
 *
 * Shows the time, a target-reached badge, an edit icon, and a delete icon.
 *
 * @param entry    The completion record to display.
 * @param onEdit   Called when the user taps the edit icon.
 * @param onDelete Called when the user taps the delete icon.
 */
@Composable
private fun CompletionEntryRow(
    entry: HabitCompletionEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Date + time
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.progressUpdate.format(
                        DateTimeFormatter.ofPattern("dd MMM, HH:mm")
                    ),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            // Target-reached badge
            if (entry.isTargetReached) {
                AssistChip(
                    onClick = {},
                    label = { Text("Target") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.padding(end = 4.dp)
                )
            }

            // Edit icon
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit entry")
            }

            // Delete icon
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete entry",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─── Dialogs ─────────────────────────────────────────────────────────────────

/**
 * Dialog for editing an existing completion's timestamp and target-reached flag.
 *
 * Presents a Material 3 [DatePicker] (in input mode for compact display inside a dialog)
 * and two [OutlinedTextField]s for hour and minute.
 *
 * @param initial   The entry to pre-populate the form with.
 * @param onConfirm Called with the updated [HabitCompletionEntity] on confirmation.
 * @param onDismiss Called when the user cancels.
 */
/**
 * Two-step edit dialog for an existing completion.
 *
 * Step 1 — [DatePickerDialog] lets the user pick a date, then taps "Next".
 * Step 2 — [AlertDialog] shows a [TimeInput] only; "Save" commits the result.
 * (Pattern: sequential modal flow — each dialog is sized for exactly one concern)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditCompletionDialog(
    initial: HabitCompletionEntity,
    onConfirm: (HabitCompletionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val todayUtcMillis = LocalDate.now()
        .atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val datePickerState = rememberDatePickerState(
        // DatePicker stores/reads dates as UTC midnight epoch millis.
        // Use ZoneOffset.UTC here so the calendar date is preserved correctly
        // regardless of the device's local timezone (UTC-X would otherwise
        // shift midnight-UTC to the previous calendar day).
        initialSelectedDateMillis = initial.progressUpdate
            .toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
        initialDisplayMode = DisplayMode.Picker,
        // Prevent selecting future dates.
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtcMillis
            override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
        }
    )
    val timePickerState = rememberTimePickerState(
        initialHour = initial.progressUpdate.hour,
        initialMinute = initial.progressUpdate.minute
    )
    // false = date step, true = time step
    var showTimePicker by remember { mutableStateOf(false) }

    if (!showTimePicker) {
        // ── Step 1: pick a date ───────────────────────────────────────────────
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { showTimePicker = true },
                    enabled = datePickerState.selectedDateMillis != null
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    } else {
        // ── Step 2: pick a time ───────────────────────────────────────────────
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Select time") },
            text = {
                // TimeInput respects the system 24h / AM-PM preference automatically.
                TimeInput(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val epochMillis = datePickerState.selectedDateMillis!!
                    // DatePicker gives UTC-midnight millis for the selected calendar date.
                    // Interpret with UTC so the date is never shifted by the device timezone.
                    val date = java.time.Instant.ofEpochMilli(epochMillis)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                    onConfirm(
                        initial.copy(
                            progressUpdate = LocalDateTime.of(
                                date,
                                LocalTime.of(timePickerState.hour, timePickerState.minute)
                            )
                        )
                    )
                }) { Text("Save") }
            },
            dismissButton = {
                // "Back" returns to the date step rather than closing the whole flow.
                TextButton(onClick = { showTimePicker = false }) { Text("Back") }
            }
        )
    }
}

/**
 * Dialog for adding a new retroactive completion entry.
 *
 * Identical layout to [EditCompletionDialog] but without a pre-populated entity.
 * Defaults to today's date and 09:00.
 *
 * @param onConfirm Called with the chosen [LocalDateTime] and target-reached flag.
 * @param onDismiss Called when the user cancels.
 */
/**
 * Two-step add dialog for a retroactive completion entry.
 *
 * Step 1 — date picker, Step 2 — time picker. Mirrors [EditCompletionDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RetroactiveAddDialog(
    onConfirm: (LocalDateTime, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val todayUtcMillis = LocalDate.now()
        .atStartOfDay(java.time.ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()
    val datePickerState = rememberDatePickerState(
        // DatePicker works in UTC midnight epoch millis — use ZoneOffset.UTC so the
        // pre-selected date matches today's calendar date in all timezones.
        initialSelectedDateMillis = todayUtcMillis,
        initialDisplayMode = DisplayMode.Picker,
        // Prevent selecting future dates.
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtcMillis
            override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
        }
    )
    val timePickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)
    var showTimePicker by remember { mutableStateOf(false) }

    if (!showTimePicker) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { showTimePicker = true },
                    enabled = datePickerState.selectedDateMillis != null
                ) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Select time") },
            text = {
                TimeInput(state = timePickerState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val epochMillis = datePickerState.selectedDateMillis!!
                    // DatePicker gives UTC-midnight millis for the selected calendar date.
                    // Interpret with UTC so the date is never shifted by the device timezone.
                    val date = java.time.Instant.ofEpochMilli(epochMillis)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                    onConfirm(
                        LocalDateTime.of(date, LocalTime.of(timePickerState.hour, timePickerState.minute)),
                        false
                    )
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Back") }
            }
        )
    }
}


