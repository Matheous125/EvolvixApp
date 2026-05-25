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
import androidx.compose.material.icons.filled.RemoveCircle
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
import androidx.compose.ui.res.stringResource
import com.example.evolvix.R
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
 * @param onSkip         Action 1b (Phase 9.5) — log a skip and capture the reason via
 *   [com.example.evolvix.notifications.SkipReasonPickerActivity].
 * @param onNavigateToStatistics Action 2 — open Statistics screen.
 * @param onPauseUntil Action 3 (pause path) — receives epoch-millis deadline.
 * @param onResume Action 3 (resume path) — clears the pause.
 * @param onNavigateToHistory Action 4 — open History screen (Phase 3.1 stub).
 * @param onNavigateToEdit Action 5 — open Edit screen.
 * @param onDelete Action 6 — called after the user confirms deletion.
 * @param isManualSortActive True when MANUAL sort order is active. The "Reorder habits" item
 *   is enabled only in this mode; it is grayed out (but still visible) in all other modes.
 * @param reorderMode When true, all taps and long-presses are suppressed so only drag
 *   gestures are active. Progress recording and the context menu are unavailable.
 * @param onTriggerReorder Action 7 — activate drag & drop reorder mode.
 * @param content The habit row composable to render (e.g. [ProgressItem]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitContextMenu(
    habit: HabitUiState,
    onMarkProgress: () -> Unit,
    onSkip: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onPauseUntil: (Long) -> Unit,
    onResume: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToEdit: () -> Unit,
    onDelete: () -> Unit,
    isManualSortActive: Boolean,
    reorderMode: Boolean,
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
                val msg = if (until == Long.MAX_VALUE)
                              context.getString(R.string.toast_paused_indefinitely)
                          else
                              context.getString(R.string.toast_paused_until_date)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── Delete Confirmation Dialog ────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.dialog_context_delete_title)) },
            text = { Text(stringResource(R.string.dialog_context_delete_body, habit.name)) },
            confirmButton = {
                // Error color (red) on the destructive action matches M3 convention
                // and mirrors the delete dialog in EditHabitScreen
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // ── Item + anchored DropdownMenu ──────────────────────────────────────────
    // combinedClickable replaces the raw pointerInput/detectTapGestures block.
    // The DropdownMenu anchors itself to the enclosing Box so it always appears
    // near the long-pressed item, regardless of scroll position.
    // In reorder mode both interactions are suppressed — the Box becomes inert
    // so only the drag gesture handler on the parent item Column fires.
    Box(
        modifier = if (reorderMode) Modifier
                   else Modifier.combinedClickable(
                       onClick = onMarkProgress,
                       onLongClick = { menuExpanded = true }
                   )
    ) {
        content()

        if (!reorderMode) {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            // 1. Mark progress — same as a single tap
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_mark_progress)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onMarkProgress()
                }
            )

            // Phase 9.5: Skip today — opens SkipReasonPickerActivity so the user can tag
            // the skip with a reason (TOO_TIRED, TOO_BUSY, FORGOT, SICK, TRAVELING, NO_REASON).
            DropdownMenuItem(
                text = { Text("Skip today") },
                leadingIcon = { Icon(Icons.Filled.RemoveCircle, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onSkip()
                }
            )

            // 2. Go to statistics
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_go_to_statistics)) },
                leadingIcon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onNavigateToStatistics()
                }
            )

            // 3. Pause / Resume — label and icon flip based on current pause state
            if (habit.pausedUntil != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_resume_habit)) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onResume()
                        Toast.makeText(context, context.getString(R.string.toast_resumed), Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_pause_habit)) },
                    leadingIcon = { Icon(Icons.Filled.Pause, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        showPauseSheet = true
                    }
                )
            }

            // 4. View history — stub until Phase 3.1 adds HistoryScreen
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_view_history)) },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onNavigateToHistory()
                }
            )

            // 5. Edit habit
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_edit_habit)) },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    onNavigateToEdit()
                }
            )

            // 6. Delete — triggers the confirmation dialog, not an immediate deletion
            DropdownMenuItem(
                text = { Text(stringResource(R.string.btn_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    showDeleteDialog = true
                }
            )

            // 7. Reorder habits — enabled only when MANUAL sort is active.
            // In any other sort mode the item is grayed out to signal it is not applicable.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_reorder_habits)) },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                enabled = isManualSortActive,
                onClick = {
                    menuExpanded = false
                    onTriggerReorder()
                }
            )
        }
        } // end if (!reorderMode)
    }
}
