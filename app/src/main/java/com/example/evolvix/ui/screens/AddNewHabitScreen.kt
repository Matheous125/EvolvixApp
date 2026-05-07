package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.evolvix.domain.model.HabitUiState
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import com.example.evolvix.data.local.AppDatabase
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.example.evolvix.data.model.HabitFrequency
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.evolvix.ui.theme.HabitColorScheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.focus.onFocusChanged
import com.example.evolvix.domain.model.FormError
import kotlinx.coroutines.launch

/**
 * A composable that displays a grid of color options for habit themes.
 * 
 * @param selectedColor Currently selected color scheme
 * @param onColorSelected Callback when a new color is selected
 * @param modifier Optional modifier for customizing the layout
 */
@Composable
fun ColorSelectionGrid(
    selectedColor: HabitColorScheme,
    onColorSelected: (HabitColorScheme) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = "Color Theme",
            style = MaterialTheme.typography.titleMedium,
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
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null,
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
 * Provides form fields for name, target, frequency, and color selection.
 *
 * @param onNavigateBack Callback to navigate back after saving or canceling
 * @param modifier Optional modifier for customizing the layout
 * @param habitViewModel ViewModel for habit operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNewHabitScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
) {
    var habitName by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var showNameError by remember { mutableStateOf(false) }
    var showTargetError by remember { mutableStateOf(false) }
    // True once the target field has been focused at least once; prevents
    // premature validation when the screen first opens with focus on name field
    var targetTouched by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var selectedFrequency by remember { mutableStateOf(HabitFrequency.Daily) }
    var selectedColor by remember { mutableStateOf(HabitColorScheme.GREEN) }
    // rememberCoroutineScope ties the scope to this composable's lifecycle
    val coroutineScope = rememberCoroutineScope()
    // Observe form validation errors from the ViewModel (Observer pattern)
    val formError by habitViewModel.formError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create New Habit") },
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
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Name input
            OutlinedTextField(
                value = habitName,
                onValueChange = {
                    habitName = it
                    showNameError = it.isEmpty()
                    habitViewModel.clearFormError() // dismiss duplicate error while typing
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
                        // Validate when focus leaves the field, not on every keystroke
                        if (!focusState.isFocused && habitName.isNotBlank()) {
                            coroutineScope.launch { habitViewModel.validateName(habitName) }
                        }
                    }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Target input
            OutlinedTextField(
                value = target,
                onValueChange = { 
                    if (it.all { char -> char.isDigit() } || it.isEmpty()) {
                        target = it
                        showTargetError = it.isEmpty() || it.toIntOrNull() == 0
                    }
                },
                label = { Text("Target") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = showTargetError,
                supportingText = if (showTargetError) {
                    { Text("Target must be greater than 0") }
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            targetTouched = true
                        } else if (targetTouched) {
                            // Validate when focus leaves the field
                            showTargetError = target.isEmpty() || (target.toIntOrNull() ?: 0) == 0
                        }
                    }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Frequency Dropdown — ExposedDropdownMenuBox handles anchor and width natively
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedFrequency.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Frequency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    HabitFrequency.entries.forEach { frequency ->
                        DropdownMenuItem(
                            text = { Text(frequency.name) },
                            onClick = {
                                selectedFrequency = frequency
                                expanded = false
                            }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Color selection grid
            ColorSelectionGrid(
                selectedColor = selectedColor,
                onColorSelected = { selectedColor = it }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Create button
            Button(
            onClick = {
                showNameError = habitName.isBlank()
                showTargetError = target.isBlank() || (target.toIntOrNull() ?: 0) == 0
                if (!showNameError && !showTargetError) {
                    // Launch coroutine to call suspend validateName() before inserting
                    coroutineScope.launch {
                        val validation = habitViewModel.validateName(habitName)
                        if (validation.isSuccess) {
                            val newHabit = HabitUiState(
                                id = 0,
                                name = habitName,
                                currentCount = 0,
                                target = target.toInt(),
                                frequency = selectedFrequency,
                                colorScheme = selectedColor
                            )
                            habitViewModel.addHabit(newHabit)
                            onNavigateBack()
                        }
                        // On failure, formError StateFlow already holds DuplicateName
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create Habit")
        }
        }
    }
}