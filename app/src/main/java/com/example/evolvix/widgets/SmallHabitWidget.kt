package com.example.evolvix.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.evolvix.MainActivity
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.model.HabitCompletionEntity
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDateTime

/**
 * Single-habit Glance widget showing one habit's name + today's progress and a tap
 * target to increment it.
 *
 * The widget pulls data from the same [com.example.evolvix.data.local.HabitDao] the
 * app uses (Pattern: **Single Source of Truth**). The first active (non-paused) habit
 * is shown — a richer "choose habit" configuration screen is intentionally out of
 * scope for this thesis phase.
 */
class SmallHabitWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getDatabase(context).habitDao()
        val habit = dao.getActiveHabits(System.currentTimeMillis()).firstOrNull()?.firstOrNull()

        provideContent {
            GlanceTheme {
                if (habit == null) {
                    EmptyState()
                } else {
                    Column(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(GlanceTheme.colors.surface)
                            .padding(12.dp)
                            .clickable(
                                actionRunCallback<IncrementHabitAction>(
                                    actionParametersOf(KEY_HABIT_ID to habit.id)
                                )
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = habit.name,
                            style = TextStyle(
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GlanceTheme.colors.onSurface
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = "${habit.currentCount} / ${habit.target}",
                            style = TextStyle(
                                fontSize = 24.sp,
                                color = GlanceTheme.colors.primary
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        Text(
                            text = "Tap to complete",
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = GlanceTheme.colors.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.surface)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No habits yet",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
            )
        }
    }

    companion object {
        val KEY_HABIT_ID = ActionParameters.Key<Int>("widget_habit_id")
    }
}

/**
 * Receiver entry point registered in `AndroidManifest.xml`. Glance routes the
 * AppWidget lifecycle through here to the [SmallHabitWidget] composable.
 */
class SmallHabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallHabitWidget()
}

/**
 * Action callback executed when the user taps the widget. Writes a completion via
 * the DAO — same data path as the in-app increment button — then asks Glance to
 * recompose the widget so the new count appears.
 */
class IncrementHabitAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val habitId = parameters[SmallHabitWidget.KEY_HABIT_ID] ?: return
        val dao = AppDatabase.getDatabase(context).habitDao()
        val habit = dao.getHabitById(habitId) ?: return
        val newCount = habit.currentCount + 1
        val targetHit = newCount == habit.target
        dao.updateHabit(
            habit.copy(
                currentCount = newCount,
                totalProgressUpdates = habit.totalProgressUpdates + 1,
                totalTargetReaches = if (targetHit) habit.totalTargetReaches + 1
                                     else habit.totalTargetReaches
            )
        )
        dao.insertCompletion(
            HabitCompletionEntity(
                habitId = habitId,
                progressUpdate = LocalDateTime.now(),
                isTargetReached = targetHit
            )
        )
        // Refresh every installed instance of both widgets so counts stay in sync.
        SmallHabitWidget().updateAll(context)
        MediumHabitListWidget().updateAll(context)
    }
}
