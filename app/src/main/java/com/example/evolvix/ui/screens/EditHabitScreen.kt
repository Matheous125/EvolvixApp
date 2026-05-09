package com.example.evolvix.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.domain.model.FormError
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import kotlinx.coroutines.launch

/**
 * A button that shows the selected emoji (or a prompt) and opens the AndroidX
 * [EmojiPickerView] inside a [ModalBottomSheet] when tapped.
 *
 * The selected emoji is stored as a plain Unicode String in [HabitEntity.iconKey],
 * so no schema change is required — it replaces the old Material-icon key string.
 *
 * @param selectedEmoji Currently stored emoji string, or null if none chosen.
 * @param onEmojiSelected Callback with the chosen emoji Unicode string.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerField(
    selectedEmoji: String?,
    onEmojiSelected: (String) -> Unit
) {
    // Drives ModalBottomSheet visibility. Kept as explicit MutableState so the
    // View-based listener lambda below can safely mutate it from the main thread.
    val showPickerState = remember { mutableStateOf(false) }
    // rememberUpdatedState ensures the listener always calls the latest callback
    // even if the composable recomposes before the user picks an emoji.
    val latestCallback = rememberUpdatedState(onEmojiSelected)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Emoji",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // OutlinedButton acts as the trigger — shows the current emoji or a prompt.
        OutlinedButton(
            onClick = { showPickerState.value = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (selectedEmoji != null) {
                Text(text = selectedEmoji, style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(text = "Tap to pick an emoji", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    if (showPickerState.value) {
        ModalBottomSheet(onDismissRequest = { showPickerState.value = false }) {
            // AndroidView bridges the View-based EmojiPickerView into the Compose tree
            // (Adapter pattern). The emoji2 library provides the full system-style picker.
            AndroidView(
                factory = { context ->
                    EmojiPickerView(context).apply {
                        setOnEmojiPickedListener { item ->
                            latestCallback.value(item.emoji)
                            showPickerState.value = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .navigationBarsPadding()
            )
        }
    }
}

/**
 * Screen for editing an existing habit.
 * Mirrors [AddNewHabitScreen] — replaces the Templates row with an Icon picker.
 * Sections: Icon picker · Name · Frequency builder · Target · Categories · Color · Reminder.
 *
 * @param habitId ID of the habit to edit.
 * @param onNavigateBack Callback for navigation after save or cancel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditHabitScreen(
    habitId: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            habitDao = AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
) {
    // ── Local text-field state ────────────────────────────────────────────────
    // Kept local for per-keystroke reactivity (same reasoning as AddNewHabitScreen).
    var habitName by remember { mutableStateOf("") }
    var targetString by remember { mutableStateOf("1") }
    var frequencyNString by remember { mutableStateOf("1") }

    // ── Local form state ──────────────────────────────────────────────────────
    var selectedFrequency by remember { mutableStateOf(HabitFrequency.Daily) }
    var selectedColor by remember { mutableStateOf(HabitColorScheme.GREEN) }
    var selectedCategories by remember { mutableStateOf(emptySet<String>()) }
    var selectedIconKey by remember { mutableStateOf<String?>(null) }
    // Custom categories not in the predefined list — restored from the habit on load.
    var customCategories by remember { mutableStateOf(listOf<String>()) }

    // ── UI control state ──────────────────────────────────────────────────────
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showNameError by remember { mutableStateOf(false) }
    var showTargetError by remember { mutableStateOf(false) }
    var targetTouched by remember { mutableStateOf(false) }
    var frequencyExpanded by remember { mutableStateOf(false) }
    // Reminder toggle is local for now — Phase 7 wires it to WorkManager scheduling.
    var reminderEnabled by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    // Observe form validation errors from the ViewModel (Observer pattern via StateFlow)
    val formError by habitViewModel.formError.collectAsState()

    // Load existing habit data when the screen opens
    LaunchedEffect(habitId) {
        habitViewModel.getHabitById(habitId)?.let { habit ->
            habitName = habit.name
            targetString = habit.target.toString()
            selectedFrequency = habit.frequency
            selectedColor = HabitColorScheme.fromHex(habit.colorHex)
            selectedCategories = habit.categories.toSet()
            selectedIconKey = habit.iconKey
            reminderEnabled = habit.reminderEnabled
            // Restore any custom categories saved with this habit
            customCategories = habit.categories.filter { it !in ALL_CATEGORIES }
            isLoading = false
        }
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Habit") },
            text = { Text("Are you sure you want to delete this habit? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        habitViewModel.deleteHabit(
                            habitId = habitId,
                            onSuccess = {
                                showDeleteDialog = false
                                onNavigateBack()
                            },
                            onError = {
                                showDeleteDialog = false
                                showErrorDialog = true
                            }
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Habit") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    // Overflow menu for destructive and secondary actions
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            // Placeholder — reset logic will be implemented in a later phase
                            DropdownMenuItem(
                                text = { Text("Reset progress") },
                                onClick = { showOverflowMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOverflowMenu = false
                                    showDeleteDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── 1. Emoji picker (replaces Material icon picker) ────────────────
            // The chosen emoji is stored in HabitEntity.iconKey as a Unicode string
            // and will be displayed on the Statistics screen next to the habit title.
            EmojiPickerField(
                selectedEmoji = selectedIconKey,
                onEmojiSelected = { selectedIconKey = it }
            )

            HorizontalDivider()

            // ── 2. Name ───────────────────────────────────────────────────────
            OutlinedTextField(
                value = habitName,
                onValueChange = {
                    habitName = it
                    showNameError = it.isEmpty()
                    habitViewModel.clearFormError()
                },
                label = { Text("Habit Name") },
                isError = showNameError || formError is FormError.DuplicateName,
                supportingText = when {
                    showNameError -> { { Text("Name cannot be empty") } }
                    formError is FormError.DuplicateName -> { { Text("A habit with this name already exists") } }
                    else -> null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        // excludeId prevents the habit conflicting with its own current name
                        if (!focusState.isFocused && habitName.isNotBlank()) {
                            coroutineScope.launch { habitViewModel.validateName(habitName, excludeId = habitId) }
                        }
                    }
            )

            // ── 3. Frequency builder — "Repeat every [ 1 ] [ day ▼ ]" ────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Repeat every", style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = frequencyNString,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() } || input.isEmpty()) {
                            frequencyNString = input
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(64.dp)
                )
                ExposedDropdownMenuBox(
                    expanded = frequencyExpanded,
                    onExpandedChange = { frequencyExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedFrequency.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyExpanded)
                        },
                        singleLine = true,
                        modifier = Modifier
                            .width(148.dp)
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = frequencyExpanded,
                        onDismissRequest = { frequencyExpanded = false }
                    ) {
                        HabitFrequency.entries.forEach { freq ->
                            DropdownMenuItem(
                                text = { Text(freq.displayName) },
                                onClick = {
                                    selectedFrequency = freq
                                    frequencyExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── 4. Target — "Target: [ 1 ] times" ────────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Target:", style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = targetString,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } || input.isEmpty()) {
                                targetString = input
                                showTargetError = input.isEmpty() || input.toIntOrNull() == 0
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = showTargetError,
                        singleLine = true,
                        modifier = Modifier
                            .width(64.dp)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) targetTouched = true
                                else if (targetTouched) {
                                    showTargetError =
                                        targetString.isEmpty() || (targetString.toIntOrNull() ?: 0) == 0
                                }
                            }
                    )
                    Text("times", style = MaterialTheme.typography.bodyLarge)
                }
                if (showTargetError) {
                    Text(
                        text = "Target must be greater than 0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ── 5. Categories ─────────────────────────────────────────────────
            CategoriesSection(
                allCategories = ALL_CATEGORIES + customCategories,
                selectedCategories = selectedCategories,
                onToggle = { cat ->
                    selectedCategories = if (cat in selectedCategories)
                        selectedCategories - cat else selectedCategories + cat
                },
                onAddCategory = { newLabel ->
                    if (newLabel !in ALL_CATEGORIES + customCategories) {
                        customCategories = customCategories + newLabel
                    }
                    selectedCategories = selectedCategories + newLabel
                }
            )

            // ── 6. Color picker ───────────────────────────────────────────────
            ColorSelectionGrid(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it }
            )

            // ── 7. Reminder switch (placeholder — wired to WorkManager in Phase 7) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Smart reminders", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "AI will notify you when it's the right moment",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
            }

            // ── Submit ────────────────────────────────────────────────────────
            Button(
                onClick = {
                    showNameError = habitName.isBlank()
                    showTargetError =
                        targetString.isBlank() || (targetString.toIntOrNull() ?: 0) == 0
                    if (!showNameError && !showTargetError) {
                        isSaving = true
                        coroutineScope.launch {
                            val validation = habitViewModel.validateName(habitName, excludeId = habitId)
                            if (validation.isSuccess) {
                                habitViewModel.updateHabit(
                                    id = habitId,
                                    name = habitName,
                                    target = targetString.toInt(),
                                    frequency = selectedFrequency,
                                    colorHex = selectedColor.toHex(),
                                    categories = selectedCategories.toList(),
                                    iconKey = selectedIconKey,
                                    reminderEnabled = reminderEnabled,
                                    onSuccess = { onNavigateBack() },
                                    onError = {
                                        showErrorDialog = true
                                        isSaving = false
                                    }
                                )
                            } else {
                                isSaving = false
                                // formError StateFlow already holds DuplicateName
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Save Changes")
                }
            }

            if (showErrorDialog) {
                AlertDialog(
                    onDismissRequest = { showErrorDialog = false },
                    title = { Text("Error") },
                    text = { Text("Failed to update habit. Please try again.") },
                    confirmButton = {
                        TextButton(onClick = { showErrorDialog = false }) { Text("OK") }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

