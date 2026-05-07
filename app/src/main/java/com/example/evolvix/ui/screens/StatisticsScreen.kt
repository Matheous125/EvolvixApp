package com.example.evolvix.ui.screens

import androidx.compose.foundation.background
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
import com.example.evolvix.domain.model.HabitUiState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.material.icons.filled.DateRange
import java.time.format.DateTimeFormatter.ofPattern
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.rememberDatePickerState
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import android.content.Context
import android.os.Environment
import androidx.compose.material.icons.filled.Print
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import java.io.File
import android.widget.Toast

/**
 * Statistics screen displaying habit progress and analytics.
 * Features:
 * - Habit selection dropdown
 * - Date range selection
 * - Progress visualization
 * - PDF export functionality
 * - Interactive bar chart
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    modifier: Modifier = Modifier,
    habitViewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            AppDatabase.getDatabase(LocalContext.current).habitDao()
        )
    )
) {
    // State management
    val habits by habitViewModel.allHabits.collectAsState(initial = emptyList())
    var selectedHabit by remember { mutableStateOf<HabitUiState?>(null) }
    var expanded by remember { mutableStateOf(false) }

    // Date range state
    var startDate by remember { mutableStateOf(LocalDateTime.now().minusDays(6)) }
    var endDate by remember { mutableStateOf(LocalDateTime.now()) }

    // Progress updates state with memoization
    val selectedHabitProgressUpdates by remember(selectedHabit, startDate, endDate) {
        selectedHabit?.let { habit ->
            habitViewModel.getProgressHistory(
                habitId = habit.id,
                startDate = startDate,
                endDate = endDate
            )
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())


    // Calculate daily counts
    val dailyCounts = remember(selectedHabitProgressUpdates, startDate, endDate) {
        if (selectedHabit != null) {
            val daysBetween = ChronoUnit.DAYS.between(startDate.toLocalDate(), endDate.toLocalDate()).toInt()
            (0..daysBetween).map { dayOffset ->
                val date = endDate.minusDays(dayOffset.toLong())
                selectedHabitProgressUpdates.count { update ->
                    update.progressUpdate.toLocalDate() == date.toLocalDate()
                }.toFloat()
            }.reversed()
        } else {
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Habit Statistics") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                windowInsets = WindowInsets(0),
                actions = {
                    selectedHabit?.let { habit ->
                        val context = LocalContext.current
                        IconButton(
                            onClick = {
                                exportStatsToPdf(
                                    context = context,
                                    habit = habit,
                                    progressUpdates = dailyCounts.map { it.toInt() },
                                    startDate = startDate,
                                    endDate = endDate
                                )
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Print,
                                contentDescription = "Export to PDF"
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
            // Habit Selector Dropdown — ExposedDropdownMenuBox handles anchor and width natively
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedHabit?.name ?: "Select a habit",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Habit") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    habits.forEach { habit ->
                        DropdownMenuItem(
                            text = { Text(habit.name) },
                            onClick = {
                                selectedHabit = habit
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Statistics Cards
            selectedHabit?.let { habit ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Target Reaches Card
                    OutlinedCard(
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Target Reaches",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = habit.totalTargetReaches.toString(),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }

                    // Progress Updates Card
                    OutlinedCard(
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Progress Updates",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = habit.totalProgressUpdates.toString(),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                //DateRangeSelector
                DateRangeSelector(
                    startDate = startDate,
                    endDate = endDate,
                    onStartDateSelected = { startDate = it },
                    onEndDateSelected = { endDate = it },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Show chart only when a habit is selected
                selectedHabit?.let { selected ->
                    ProgressUpdateChart(
                        selectedHabit = selected,
                        startDate = startDate,
                        endDate = endDate,
                        dailyCounts = dailyCounts,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressUpdateChart(
    selectedHabit: HabitUiState?,
    startDate: LocalDateTime,
    endDate: LocalDateTime,
    dailyCounts: List<Float>,
    modifier: Modifier = Modifier
) {

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Progress Updates (${startDate.format(ofPattern("MMM dd"))} - ${endDate.format(ofPattern("MMM dd"))})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            BarChart(
                values = dailyCounts,
                color = selectedHabit?.let { habit ->
                    runCatching { Color(android.graphics.Color.parseColor(habit.colorHex)) }
                        .getOrElse { MaterialTheme.colorScheme.primary }
                } ?: MaterialTheme.colorScheme.primary,
                startDate = startDate,
                endDate = endDate,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun BarChart(
    values: List<Float>,
    color: Color,
    startDate: LocalDateTime,
    endDate: LocalDateTime,
    modifier: Modifier = Modifier
) {
    val borderColor = color
    val density = LocalDensity.current
    val strokeWidth = with(density) { 1.dp.toPx() }
    val maxValue = (values.maxOrNull() ?: 1f).let { max ->
        if (max <= 4) 4f else (max + (4 - max % 4))
    }
    val dateFormatter = ofPattern("dd.MM")
    val thresholds = (0..4).map { i -> 
        (maxValue * (4 - i) / 4f)
    }
    

    Column(
        modifier = modifier
    ) {
        // Y-axis labels and chart
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Y-axis labels
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    thresholds.forEach { threshold ->
                        Text(
                            text = threshold.toInt().toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
        }

            // Chart with bars
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        // draw X-Axis
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = strokeWidth
                        )
                        // draw Y-Axis
                        drawLine(
                            color = borderColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = strokeWidth
                        )
                        // draw horizontal grid lines
                        thresholds.forEach { threshold ->
                            val y = size.height * (1 - threshold / maxValue)
                            drawLine(
                                color = borderColor.copy(alpha = 0.2f),
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = strokeWidth
                            )
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                values.forEach { value ->
                    Bar(
                        value = value,
                        color = color,
                        maxValue = maxValue
                    )
                }
            }
        }

        // X-axis date labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val daysBetween = ChronoUnit.DAYS.between(startDate, endDate).toInt()
            (daysBetween downTo 0).forEach { day ->
                Text(
                    text = endDate.minusDays(day.toLong()).format(dateFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RowScope.Bar(
    value: Float,
    color: Color,
    maxValue: Float
) {
    val fraction = if (maxValue > 0) value / maxValue else 0f

    Spacer(
        modifier = Modifier
            .padding(horizontal = 5.dp)
            .fillMaxHeight(fraction)
            .weight(1f)
            .background(
                color = color,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
            )
    )
}


@Composable
private fun DateRangeSelector(
    startDate: LocalDateTime,
    endDate: LocalDateTime,
    onStartDateSelected: (LocalDateTime) -> Unit,
    onEndDateSelected: (LocalDateTime) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    // Show date picker dialogs when needed
    if (showStartDatePicker) {
        HabitDatePickerDialog(
            selectedDate = startDate,
            onDateSelected = onStartDateSelected,
            onDismiss = { showStartDatePicker = false }
        )
    }

    if (showEndDatePicker) {
        HabitDatePickerDialog(
            selectedDate = endDate,
            onDateSelected = onEndDateSelected,
            onDismiss = { showEndDatePicker = false }
        )
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start Date
            Column {
                Text(
                    "From",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showStartDatePicker = true }
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = "Select start date",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        startDate.format(ofPattern("MMM dd, yyyy")),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // End Date
            Column {
                Text(
                    "To",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { showEndDatePicker = true }
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = "Select end date",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        endDate.format(ofPattern("MMM dd, yyyy")),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitDatePickerDialog(
    selectedDate: LocalDateTime,
    onDateSelected: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli(),
        initialDisplayMode = DisplayMode.Picker
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val newDate = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(millis),
                        ZoneId.systemDefault()
                    )
                    onDateSelected(newDate)
                }
                onDismiss()
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}

private fun exportStatsToPdf(
    context: Context,
    habit: HabitUiState,
    progressUpdates: List<Int>,
    startDate: LocalDateTime,
    endDate: LocalDateTime
) {
    try {
        val filename = "habit_stats_${habit.name}_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.pdf"
        val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(path, filename)
        
        PdfWriter(file).use { writer ->
            val pdf = PdfDocument(writer)
            val document = Document(pdf)

            // Add title
            document.add(Paragraph("Habit Statistics: ${habit.name}"))
            document.add(Paragraph("Period: ${startDate.format(ofPattern("MMM dd, yyyy"))} - ${endDate.format(ofPattern("MMM dd, yyyy"))}"))
            
            // Add summary stats
            document.add(Paragraph("Total Progress Updates: ${habit.totalProgressUpdates}"))
            document.add(Paragraph("Total Target Reaches: ${habit.totalTargetReaches}"))
            
            // Add daily progress table
            val table = Table(2)
            table.addCell("Date")
            table.addCell("Updates")
            
            val daysBetween = ChronoUnit.DAYS.between(startDate, endDate).toInt()
            (daysBetween downTo 0).forEach { day ->
                val date = endDate.minusDays(day.toLong())
                table.addCell(date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                table.addCell(progressUpdates[daysBetween - day].toString())
            }
            
            document.add(table)
            document.close()
            Toast.makeText(
            context,
            "Statistics exported to Downloads folder",
            Toast.LENGTH_LONG
            ).show()
        }
    } catch (e: Exception) {
        // Show error toast if export fails
        Toast.makeText(
            context,
            "Failed to export statistics: ${e.message}",
            Toast.LENGTH_LONG
        ).show()
        e.printStackTrace()
    }
}