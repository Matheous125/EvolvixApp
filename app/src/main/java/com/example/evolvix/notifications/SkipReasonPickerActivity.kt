package com.example.evolvix.notifications

import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.evolvix.R
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.example.evolvix.data.model.SkipReason
import com.example.evolvix.ui.theme.EvolvixTheme

/**
 * Translucent trampoline Activity (Phase 9.5) that hosts the skip-reason
 * [ModalBottomSheet] shown after the user taps the "Skip" notification action.
 *
 * **Why a separate Activity and not a Compose dialog:** Notification actions
 * fire [android.content.BroadcastReceiver] callbacks, not ViewModel events.
 * There is no live Compose hierarchy to attach a dialog to at that point.
 * A transparent Activity is the standard Android solution for launching UI
 * from a [BroadcastReceiver] (used widely in e.g. quick-action notification patterns).
 *
 * **Lifecycle:**
 *  1. [HabitActionReceiver] launches this Activity with [FLAG_ACTIVITY_NEW_TASK].
 *  2. The window background is set to transparent; the bottom sheet fills the scrim.
 *  3. The user taps one of the six [FilterChip]s or dismisses the sheet.
 *  4. [RecordHabitActionWorker] is enqueued via WorkManager — write survives process death
 *     (Phase 9.6.2 hardening; replaces the old fire-and-forget coroutine).
 *  5. The Activity finishes immediately; nothing lingers in the back stack.
 *
 * Default on dismiss (back gesture / tap outside sheet) = [SkipReason.NO_REASON].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
class SkipReasonPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Transparent window background so only the bottom sheet scrim is visible.
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val habitId = intent.getIntExtra(HabitActionReceiver.EXTRA_HABIT_ID, -1)
        if (habitId < 0) {
            finish()
            return
        }

        // Dismiss the reminder notification and reset the snooze counter immediately.
        // Previously done in HabitActionReceiver.ACTION_SKIP, but moved here because
        // the Skip PendingIntent now routes directly to this Activity (no receiver hop).
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(habitId)
        SnoozePreferences.reset(applicationContext, habitId)

        setContent {
            EvolvixTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var showSheet by remember { mutableStateOf(true) }

                if (showSheet) {
                    ModalBottomSheet(
                        onDismissRequest = {
                            // Dismissed without a selection → default to NO_REASON.
                            showSheet = false
                            recordSkip(habitId, SkipReason.NO_REASON)
                            finish()
                        },
                        sheetState = sheetState
                    ) {
                        SkipReasonSheetContent(
                            onReasonSelected = { reason ->
                                showSheet = false
                                recordSkip(habitId, reason)
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * Enqueues a [RecordHabitActionWorker] to persist the skip reason.
     * WorkManager guarantees the write survives process death (Phase 9.6.2).
     */
    private fun recordSkip(habitId: Int, reason: SkipReason) {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            RecordHabitActionWorker.uniqueName(habitId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            RecordHabitActionWorker.buildRequest(
                habitId    = habitId,
                action     = RecordHabitActionWorker.ACTION_SKIP,
                skipReason = reason.name
            )
        )
    }
}

/**
 * Six [FilterChip]s laid out in a wrapping [FlowRow], one per [SkipReason] value.
 * Strictly declarative — no business logic; all state is managed by [SkipReasonPickerActivity].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkipReasonSheetContent(onReasonSelected: (SkipReason) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.skip_reason_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        // Each entry maps a SkipReason enum value to a user-readable label.
        val reasons = listOf(
            SkipReason.TOO_TIRED  to "Too tired",
            SkipReason.TOO_BUSY   to "Too busy",
            SkipReason.FORGOT     to "Forgot",
            SkipReason.SICK       to "Not feeling well",
            SkipReason.TRAVELING  to "Traveling",
            SkipReason.NO_REASON  to "No particular reason"
        )

        // FlowRow wraps chips onto the next line automatically — avoids fixed-column grids
        // that break on narrow screens or large font sizes (accessibility requirement).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            reasons.forEach { (reason, label) ->
                FilterChip(
                    selected = false,
                    onClick = { onReasonSelected(reason) },
                    label = { Text(label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
