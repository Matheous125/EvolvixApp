package com.example.evolvix.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A Modal Bottom Sheet (M3) for pausing a habit.
 *
 * Two options are presented:
 * - **Pause indefinitely** — calls [onPauseUntil] with [Long.MAX_VALUE].
 * - **Choose end date** — shows an M3 [DatePickerDialog], then calls [onPauseUntil]
 *   with the selected date's epoch-milliseconds.
 *
 * This is a pure UI composable; all persistence is handled by the caller via the
 * provided callbacks (Pattern: **Unidirectional Data Flow**).
 *
 * @param onDismiss Called when the sheet is dismissed without selecting an option.
 * @param onPauseUntil Called with the chosen epoch-millis pause deadline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseBottomSheet(
    onDismiss: () -> Unit,
    onPauseUntil: (Long) -> Unit
) {
    // Track whether the date picker overlay is visible
    var showDatePicker by remember { mutableStateOf(false) }

    // DatePickerState holds the selected date; initialized to today
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = System.currentTimeMillis()
    )

    // ── Date Picker Dialog ────────────────────────────────────────────────────
    // Shown as an overlay when the user taps "Choose end date"
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedMillis = datePickerState.selectedDateMillis
                        if (selectedMillis != null) {
                            onPauseUntil(selectedMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            // The M3 DatePicker widget inside the dialog
            DatePicker(state = datePickerState)
        }
    }

    // ── Bottom Sheet ──────────────────────────────────────────────────────────
    // ModalBottomSheet is the standard M3 bottom sheet component
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pause habit",
                style = MaterialTheme.typography.titleMedium
            )

            // Primary action: pause with no end date
            Button(
                onClick = { onPauseUntil(Long.MAX_VALUE) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pause indefinitely")
            }

            // Secondary action: open the date picker to choose a resume date
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose end date")
            }
        }
    }
}
