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
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
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
    var showError by remember { mutableStateOf(false) }
    var showNameError by remember { mutableStateOf(false) }
    var showTargetError by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var selectedFrequency by remember { mutableStateOf(HabitFrequency.Daily) }
    var selectedColor by remember { mutableStateOf(HabitColorScheme.GREEN) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create New Habit") },
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
                },
                label = { Text("Habit Name") },
                isError = showNameError,
                supportingText = if (showNameError) {
                    { Text("Name cannot be empty") }
                } else null,
                modifier = Modifier.fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Frequency Dropdown
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                var textFieldSize by remember { mutableStateOf(DpSize.Zero) }
                val density = LocalDensity.current

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            textFieldSize = with(density) {
                                DpSize(
                                    coordinates.size.width.toDp(),
                                    coordinates.size.height.toDp()
                                )
                            }
                        }
                        .clickable { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedFrequency.name,
                        onValueChange = {},
                        readOnly = true,
                        enabled = false,
                        label = { Text("Frequency") },
                        trailingIcon = {
                            Icon(
                                imageVector = if (expanded)
                                    Icons.Filled.ArrowDropUp
                                else
                                    Icons.Filled.ArrowDropDown,
                                contentDescription = if (expanded) "Show less" else "Show more"
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = LocalContentColor.current,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.width(textFieldSize.width)
                ) {
                    HabitFrequency.entries.forEach { frequency ->
                        DropdownMenuItem(
                            text = { Text(frequency.name) },
                            onClick = {
                                selectedFrequency = frequency
                                expanded = false
                            },
                            modifier = Modifier.fillMaxWidth()
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
                if (habitName.isNotBlank() && target.isNotBlank()) {
                    val habitTarget = target.toIntOrNull() ?: 0
                    if (habitTarget > 0) {
                        val newHabit = HabitUiState(
                            id = 0,
                            name = habitName,
                            currentCount = 0,
                            target = habitTarget,
                            frequency = selectedFrequency,
                            colorScheme = selectedColor
                        )
                        habitViewModel.addHabit(newHabit)
                        onNavigateBack()
                    } else {
                        showError = true
                    }
                } else {
                    showError = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Create Habit")
        }
        }
    }
}