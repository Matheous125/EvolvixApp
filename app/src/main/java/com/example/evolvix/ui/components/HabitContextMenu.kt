package com.example.evolvix.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.evolvix.domain.model.HabitUiState

/**
 * Wraps [content] in a long-press–activated context menu for a single habit row.
 *
 * A single tap still calls [onMarkProgress], preserving the existing tap-to-increment
 * behaviour. A long press opens a [DropdownMenu] anchored to the item with the 7 actions
 * defined in IDEAS.MD §4.4 (Pattern: **Command** — each menu item encapsulates one mutation).
 *
 * Inline overlay state (delete dialog, pause sheet) is managed here so [MainScreen] stays
 * free of per-habit UI state (Pattern: **Unidirectional Data Flow** — mutations are
 * forwarded outward via callbacks, never initiated inside this composable).
 *
 * @param habit The habit driving the menu — used to flip the Pause/Resume label.
 * @param onMarkProgress Action 1 — record +1 completion.
 * @param onNavigateToStatistics Action 2 — open Statistics screen.
 * @param onPauseUntil Action 3 (pause path) — receives epoch-millis deadline.
 * @param onResume Action 3 (resume path) — clears the pause.
 * @param onNavigateToHistory Action 4 — open History screen (Phase 3.1 stub).
 * @param onNavigateToEdit Action 5 — open Edit screen.
 * @param onDelete Action 6 — called after the user confirms deletion.
 * @param onTriggerReorder Action 7 — activate drag & drop mode (Phase 2.4 stub).
 * @param content The habit row composable to render (e.g. [ProgressItem]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitContextMenu(
    habit: HabitUiState,
    onMarkProgress: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onPauseUntil: (Long) -> Unit,
    onResume: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onDelete: () -> Unit,
    onTriggerReorder: () -> Unit,
    content: @Composable () -> Unit
) {
    // Local UI state — none of these leave this composable
    var menuExpanded by remember { mutableStateOf(false) }
    var showPauseSheet by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ── Pause Bottom Sheet ────────────────────────────────────────────────────
    // Shown when the user picks "Pause habit" from the menu
    if (showPauseSheet) {
        PauseBottomSheet(
            onDismiss = { showPauseSheet = false },
            onPauseUntil = { until ->
                onPauseUntil(until)
                showPauseSheet = false
                // Toast confirms the action without requiring the user to stay on screen
                val msg = if (until == Long.MAX_VALUE) "Habit paused indefinitely"
                          else "Habit paused until chosen date"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── Delete Confirmation Dialog ────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete habit") },
            text = { Text("\"${habit.name}\" will be permanently removed.") },
            confirmButton = {
                // Error color (red) on the destructive action matches M3 convention
                // and mirrors the delete dialog in EditHabitScreen
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Item + anchored DropdownMenu ──────────────────────────────────────────
    // combinedClickable replaces the raw pointerInput/detectTapGestures block.
    // The DropdownMenu anchors itself to the enclosing Box so it always appears
    // near the long-pressed item, regardless of scroll position.
    Box(
        modifier = Modifier.combinedClickable(
            onClick = onMarkProgress,
            onLongClick = { menuExpanded = true }
        )
    ) {
        content()

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            // 1. Mark progress — same as a single tap
            DropdownMenuItem(
                text = { Text("Mark progress") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onMarkProgress()
                }
            )

            // 2. Go to statistics
            DropdownMenuItem(
                text = { Text("Go to statistics") },
                leadingIcon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onNavigateToStatistics()
                }
            )

            // 3. Pause / Resume — label and icon flip based on current pause state
            if (habit.pausedUntil != null) {
                DropdownMenuItem(
                    text = { Text("Resume habit") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onResume()
                        Toast.makeText(context, "Habit resumed", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Pause habit") },
                    leadingIcon = { Icon(Icons.Filled.Pause, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        showPauseSheet = true
                    }
                )
            }

            // 4. View history — stub until Phase 3.1 adds HistoryScreen
            DropdownMenuItem(
                text = { Text("View history") },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onNavigateToHistory()
                }
            )

            // 5. Edit habit
            DropdownMenuItem(
                text = { Text("Edit habit") },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onNavigateToEdit()
                }
            )

            // 6. Delete — triggers the confirmation dialog, not an immediate deletion
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    showDeleteDialog = true
                }
            )

            // 7. Reorder habits — stub until Phase 2.4 adds drag & drop
            DropdownMenuItem(
                text = { Text("Reorder habits") },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onTriggerReorder()
                }
            )
        }
    }
}
