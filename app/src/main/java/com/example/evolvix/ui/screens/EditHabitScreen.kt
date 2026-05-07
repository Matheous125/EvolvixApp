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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.focus.onFocusChanged
import com.example.evolvix.domain.model.FormError
import kotlinx.coroutines.launch

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
    // rememberCoroutineScope ties the scope to this composable's lifecycle
    val coroutineScope = rememberCoroutineScope()
    // Observe form validation errors from the ViewModel (Observer pattern)
    val formError by habitViewModel.formError.collectAsState()

    // Load existing habit data when screen opens
    LaunchedEffect(habitId) {
        habitViewModel.getHabitById(habitId)?.let { habit ->
            // Initialize form with habit data
            habitName = habit.name
            target = habit.target.toString()
            selectedFrequency = habit.frequency
            selectedColor = HabitColorScheme.fromHex(habit.colorHex)
            isLoading = false
        }
    }

    var showNameError by remember { mutableStateOf(false) }
    var showTargetError by remember { mutableStateOf(false) }
    // True once the target field has been focused at least once; prevents
    // premature validation when the screen first opens with focus on name field
    var targetTouched by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

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
                            text = {
                                Text(
                                    text = "Delete",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
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
                        // excludeId prevents the habit conflicting with its own current name
                        if (!focusState.isFocused && habitName.isNotBlank()) {
                            coroutineScope.launch { habitViewModel.validateName(habitName, excludeId = habitId) }
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
            
            // Save button
            Button(
                onClick = {
                    // Validate inputs
                    showNameError = habitName.isEmpty()
                    showTargetError = target.isEmpty() || target.toIntOrNull() == 0

                    if (!showNameError && !showTargetError) {
                        isSaving = true
                        // Launch coroutine to call suspend validateName() before updating
                        coroutineScope.launch {
                            val validation = habitViewModel.validateName(habitName, excludeId = habitId)
                            if (validation.isSuccess) {
                                habitViewModel.updateHabit(
                                    id = habitId,
                                    name = habitName,
                                    target = target.toInt(),
                                    frequency = selectedFrequency,
                                    colorHex = selectedColor.toHex(),
                                    onSuccess = {
                                        onNavigateBack()
                                    },
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