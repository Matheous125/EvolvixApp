package com.example.evolvix.ui.screens

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.R
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.data.model.HabitTemplate
import com.example.evolvix.domain.model.FormError
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.ui.theme.HabitColorScheme
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import kotlinx.coroutines.launch

/** Predefined category labels available as FilterChips in the form. */
val ALL_CATEGORIES = listOf(
    "Health", "Fitness", "Learning", "Mindfulness",
    "Productivity", "Social", "Finance"
)

/**
 * Maps an English category key (as stored in the database) to its localized display name.
 * Custom user-created categories are returned as-is.
 */
@Composable
fun categoryDisplayName(key: String): String = when (key) {
    "Health"        -> stringResource(R.string.category_health)
    "Fitness"       -> stringResource(R.string.category_fitness)
    "Learning"      -> stringResource(R.string.category_learning)
    "Mindfulness"   -> stringResource(R.string.category_mindfulness)
    "Productivity"  -> stringResource(R.string.category_productivity)
    "Social"        -> stringResource(R.string.category_social)
    "Finance"       -> stringResource(R.string.category_finance)
    else            -> key
}

/**
 * Horizontal scrollable row of habit template suggestion chips.
 * Tapping a chip dispatches the selected template up via [onTemplateSelected],
 * which pre-fills name, frequency, target and color in the form.
 */
@Composable
private fun TemplatesRow(
    templates: List<HabitTemplate>,
    onTemplateSelected: (HabitTemplate) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.section_templates),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(templates) { template ->
                SuggestionChip(
                    onClick = { onTemplateSelected(template) },
                    label = { Text(template.name) }
                )
            }
        }
    }
}

/**
 * Wrapping row of [FilterChip] items for category multi-selection.
 * Includes a trailing [AssistChip] to add custom categories via [AlertDialog].
 *
 * @param allCategories Full list of available categories (predefined + custom).
 * @param selectedCategories Currently selected category labels.
 * @param onToggle Dispatched when the user taps a category chip.
 * @param onAddCategory Dispatched with the new label when the user confirms the Add dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoriesSection(
    allCategories: List<String>,
    selectedCategories: Set<String>,
    onToggle: (String) -> Unit,
    onAddCategory: (String) -> Unit
) {
    // Local dialog state — lives here because it is pure UI, not domain state.
    var showAddDialog by remember { mutableStateOf(false) }
    var newCategoryInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.section_categories),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            allCategories.forEach { category ->
                FilterChip(
                    selected = category in selectedCategories,
                    onClick = { onToggle(category) },
                    label = { Text(categoryDisplayName(category)) }
                )
            }
            // [+ Add] chip opens a dialog to create a one-off custom category.
            AssistChip(
                onClick = { showAddDialog = true },
                label = { Text(stringResource(R.string.btn_add_category)) }
            )
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newCategoryInput = ""
            },
            title = { Text(stringResource(R.string.dialog_new_category_title)) },
            text = {
                OutlinedTextField(
                    value = newCategoryInput,
                    onValueChange = { newCategoryInput = it },
                    label = { Text(stringResource(R.string.hint_category_name)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newCategoryInput.trim()
                        if (trimmed.isNotEmpty()) onAddCategory(trimmed)
                        showAddDialog = false
                        newCategoryInput = ""
                    }
                ) { Text(stringResource(R.string.btn_add)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    newCategoryInput = ""
                }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }
}

/**
 * A composable that displays a grid of color options for habit themes.
 * Shared with [EditHabitScreen] — signature must remain stable.
 *
 * @param selectedColor Currently selected color scheme.
 * @param onColorSelected Callback when a new color is selected.
 * @param modifier Optional modifier for customizing the layout.
 */
@Composable
fun ColorSelectionGrid(
    selectedColor: HabitColorScheme,
    onColorSelected: (HabitColorScheme) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.section_color_theme),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(100.dp)
        ) {
            items(HabitColorScheme.entries.toTypedArray()) { color ->
                ColorPreviewItem(
                    color = color,
                    isSelected = selectedColor == color,
                    onSelect = onColorSelected
                )
            }
        }
    }
}

/**
 * Single color preview item in the color selection grid.
 * Shows a circular preview with selected state indication.
 */
@Composable
private fun ColorPreviewItem(
    color: HabitColorScheme,
    isSelected: Boolean,
    onSelect: (HabitColorScheme) -> Unit
) {
    Surface(
        shape = CircleShape,
        color = color.getBackgroundColor(isDark = true),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .size(40.dp)
            .clickable { onSelect(color) }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = CircleShape,
                color = color.getProgressColor(isDark = true),
                modifier = Modifier.size(20.dp)
            ) {}
        }
    }
}

/**
 * Screen for creating a new habit.
 * Sections: Templates row · Name · Frequency builder · Target · Categories · Color · Reminder.
 *
 * Form state for categories, color, frequency, and templates is owned by
 * [HabitViewModel.addHabitFormState] (State Holder / Unidirectional Data Flow pattern).
 * Local [remember] state is only used for text field strings (name, target) which require
 * per-keystroke reactivity without a round-trip through the ViewModel on each character.
 *
 * @param onNavigateBack Callback to navigate back after saving or canceling.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddNewHabitScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            application = LocalContext.current.applicationContext as Application,
            habitDao = AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
) {
    // ── ViewModel state (State Holder / UDF) ──────────────────────────────────
    val formState by habitViewModel.addHabitFormState.collectAsState()
    val formError by habitViewModel.formError.collectAsState()

    // ── Local text-field state ────────────────────────────────────────────────
    // Kept local because text fields need per-keystroke reactivity.
    // Template selection updates these directly alongside the ViewModel state.
    var habitName by remember { mutableStateOf("") }
    var targetString by remember { mutableStateOf("1") }
    // String representation of frequencyN for the narrow numeric field in the builder.
    var frequencyNString by remember { mutableStateOf("1") }

    // ── Local UI state ────────────────────────────────────────────────────────
    var showNameError by remember { mutableStateOf(false) }
    var showTargetError by remember { mutableStateOf(false) }
    var targetTouched by remember { mutableStateOf(false) }
    var frequencyExpanded by remember { mutableStateOf(false) }
    // Reminder toggle defaults to true — user can opt out.
    var reminderEnabled by remember { mutableStateOf(true) }
    // Custom categories entered via the [+ Add] dialog; merged with ALL_CATEGORIES for display.
    var customCategories by remember { mutableStateOf(listOf<String>()) }

    val coroutineScope = rememberCoroutineScope()

    // Reset form to a blank state every time this screen opens (Create, not Edit).
    LaunchedEffect(Unit) {
        habitViewModel.resetFormState()
        // Random color pre-selection per IDEAS.MD spec — reduces decision fatigue.
        habitViewModel.selectColor(HabitColorScheme.entries.random().toHex())
        habitName = ""
        targetString = "1"
        frequencyNString = "1"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_add_habit_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        // verticalScroll — the form is now taller than a typical screen
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── 1. Templates row ──────────────────────────────────────────────
            TemplatesRow(
                templates = formState.templates,
                onTemplateSelected = { template ->
                    // ViewModel updates color, frequency, target in formState (UDF)
                    habitViewModel.selectTemplate(template)
                    // Local text fields updated directly so they reflect the template instantly
                    habitName = template.name
                    targetString = template.target.toString()
                    habitViewModel.clearFormError()
                }
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
                label = { Text(stringResource(R.string.label_habit_name)) },
                isError = showNameError || formError is FormError.DuplicateName,
                supportingText = when {
                    showNameError -> { { Text(stringResource(R.string.error_name_empty)) } }
                    formError is FormError.DuplicateName -> { { Text(stringResource(R.string.error_name_duplicate)) } }
                    else -> null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        // Run duplicate check only when focus leaves a non-blank field
                        if (!focusState.isFocused && habitName.isNotBlank()) {
                            coroutineScope.launch { habitViewModel.validateName(habitName) }
                        }
                    }
            )

            // ── 3. Frequency builder — natural language: "Repeat every [ 1 ] [ day ▼ ]" ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.label_repeat_every), style = MaterialTheme.typography.bodyLarge)
                // Narrow numeric field for the repetition count (e.g. "3" in "every 3 weeks")
                OutlinedTextField(
                    value = frequencyNString,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() } || input.isEmpty()) {
                            frequencyNString = input
                            val n = input.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            habitViewModel.setFrequency(n, formState.frequencyUnit)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(64.dp)
                )
                // Compact dropdown for the time-unit (day / week / month / year)
                ExposedDropdownMenuBox(
                    expanded = frequencyExpanded,
                    onExpandedChange = { frequencyExpanded = it }
                ) {
                    OutlinedTextField(
                        value = formState.frequencyUnit.displayName,
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
                                    habitViewModel.setFrequency(
                                        frequencyNString.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                        freq
                                    )
                                    frequencyExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // ── 4. Target — natural language: "Target: [ 1 ] times" ──────────
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.label_target), style = MaterialTheme.typography.bodyLarge)
                    // Narrow numeric field — same validation logic as before
                    OutlinedTextField(
                        value = targetString,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() } || input.isEmpty()) {
                                targetString = input
                                showTargetError = input.isEmpty() || input.toIntOrNull() == 0
                                // Push numeric value to ViewModel so formState.targetCount stays in sync
                                input.toIntOrNull()?.let { habitViewModel.setTargetCount(it) }
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
                    Text(stringResource(R.string.label_times), style = MaterialTheme.typography.bodyLarge)
                }
                if (showTargetError) {
                    Text(
                        text = stringResource(R.string.error_target_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ── 5. Categories ─────────────────────────────────────────────────
            CategoriesSection(
                allCategories = ALL_CATEGORIES + customCategories,
                selectedCategories = formState.selectedCategories,
                onToggle = { habitViewModel.toggleCategory(it) },
                onAddCategory = { newLabel ->
                    // Only add if not already in the list (case-insensitive guard)
                    if (newLabel !in ALL_CATEGORIES + customCategories) {
                        customCategories = customCategories + newLabel
                    }
                    // Auto-select the newly created category
                    habitViewModel.toggleCategory(newLabel)
                }
            )

            // ── 6. Color picker ───────────────────────────────────────────────
            // Bridge: ViewModel stores a hex string; ColorSelectionGrid uses HabitColorScheme.
            // fromHex/toHex keep the two representations in sync without duplicating the palette.
            ColorSelectionGrid(
                selectedColor = HabitColorScheme.fromHex(formState.selectedColor),
                onColorSelected = { habitViewModel.selectColor(it.toHex()) }
            )

            // ── 7. Reminder switch ────────────────────────────────────────────
            // UI placeholder — WorkManager scheduling is wired in Phase 7.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = stringResource(R.string.label_smart_reminders), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.subtitle_smart_reminders),
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
                        coroutineScope.launch {
                            val validation = habitViewModel.validateName(habitName)
                            if (validation.isSuccess) {
                                habitViewModel.addHabit(
                                    HabitUiState(
                                        name = habitName,
                                        currentCount = 0,
                                        // targetCount is kept in sync by the target field's onValueChange
                                        target = formState.targetCount.coerceAtLeast(1),
                                        frequency = formState.frequencyUnit,
                                        // Pass frequencyN so it is persisted for frequency-based sorting
                                        frequencyN = formState.frequencyN.coerceAtLeast(1),
                                        colorHex = formState.selectedColor,
                                        selectedCategories = formState.selectedCategories,
                                        reminderEnabled = reminderEnabled
                                    )
                                )
                                habitViewModel.resetFormState()
                                onNavigateBack()
                            }
                            // On failure formError StateFlow already holds DuplicateName
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.btn_create_habit))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}