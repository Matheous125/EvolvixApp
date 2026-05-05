package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.ui.viewmodel.HabitViewModel
import com.example.evolvix.ui.viewmodel.HabitViewModelFactory
import androidx.compose.material.icons.Icons
import com.example.evolvix.data.model.HabitFrequency
import com.example.evolvix.ui.theme.HabitColorScheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete

/**
 * Screen for editing an existing habit.
 * Provides form fields for updating habit properties and deletion option.
 *
 * @param habitId ID of the habit to edit
 * @param onNavigateBack Callback for navigation
 * @param modifier Optional modifier for layout customization
 * @param habitViewModel ViewModel for habit operations
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditHabitScreen(
    habitId: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
) {
    // State for form fields
    var habitName by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var selectedFrequency by remember { mutableStateOf(HabitFrequency.Daily) }
    var selectedColor by remember { mutableStateOf(HabitColorScheme.GREEN) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var showErrorDialog by remember { mutableStateOf(false) }

    // Load existing habit data when screen opens
    LaunchedEffect(habitId) {
        habitViewModel.getHabitById(habitId)?.let { habit ->
            // Initialize form with habit data
            habitName = habit.name
            target = habit.target.toString()
            selectedFrequency = habit.frequency
            selectedColor = habit.colorScheme
            isLoading = false
        }
    }

    var showNameError by remember { mutableStateOf(false) }
    var showTargetError by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    //Delete confirmation dialog
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
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
            title = { Text("Edit Habit") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back"
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        showDeleteDialog = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete habit",
                        tint = MaterialTheme.colorScheme.error
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
            
            // Save button
            Button(
                onClick = {
                    // Validate inputs
                    showNameError = habitName.isEmpty()
                    showTargetError = target.isEmpty() || target.toIntOrNull() == 0

                    if (!showNameError && !showTargetError) {
                        isSaving = true
                        habitViewModel.updateHabit(
                            id = habitId,
                            name = habitName,
                            target = target.toInt(),
                            frequency = selectedFrequency,
                            colorScheme = selectedColor,
                            onSuccess = {
                                onNavigateBack()
                            },
                            onError = {
                                showErrorDialog = true
                                isSaving = false
                            }
                        )
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
            // Error dialog
                if (showErrorDialog) {
                    AlertDialog(
                        onDismissRequest = { showErrorDialog = false },
                        title = { Text("Error") },
                        text = { Text("Failed to update habit. Please try again.") },
                        confirmButton = {
                            TextButton(onClick = { showErrorDialog = false }) {
                                Text("OK")
                            }
                        }
                    )
                }
            }
        }
    }
}