package com.example.evolvix.widgets

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.evolvix.MainActivity
import com.example.evolvix.data.local.AppDatabase
import kotlinx.coroutines.flow.firstOrNull

/**
 * Multi-habit Glance widget rendering a scrollable list of today's active habits.
 * Each row shows the habit name, current/target counts, and is tappable to increment.
 *
 * Both widgets share the same [IncrementHabitAction] callback and [AppDatabase]
 * instance, so a tap here updates the in-app list, the small widget, and any later
 * Phase 10 cloud sync simultaneously (Pattern: **Single Source of Truth via DAO**).
 */
class MediumHabitListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = AppDatabase.getDatabase(context).habitDao()
        val habits = dao.getActiveHabits(System.currentTimeMillis()).firstOrNull().orEmpty()

        provideContent {
            GlanceTheme {
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(GlanceTheme.colors.surface)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Today's habits",
                        style = TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = GlanceTheme.colors.onSurface
                        ),
                        modifier = GlanceModifier.padding(start = 4.dp, bottom = 6.dp)
                    )
                    if (habits.isEmpty()) {
                        Box(
                            modifier = GlanceModifier
                                .fillMaxSize()
                                .clickable(actionStartActivity<MainActivity>()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Add a habit to get started",
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)
                            )
                        }
                    } else {
                        LazyColumn {
                            items(habits, itemId = { it.id.toLong() }) { habit ->
                                Row(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp, horizontal = 4.dp)
                                        .clickable(
                                            actionRunCallback<IncrementHabitAction>(
                                                actionParametersOf(
                                                    SmallHabitWidget.KEY_HABIT_ID to habit.id
                                                )
                                            )
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = habit.name,
                                        style = TextStyle(
                                            color = GlanceTheme.colors.onSurface,
                                            fontSize = 14.sp
                                        ),
                                        modifier = GlanceModifier.defaultWeight()
                                    )
                                    Spacer(modifier = GlanceModifier.height(1.dp))
                                    Text(
                                        text = "${habit.currentCount}/${habit.target}",
                                        style = TextStyle(
                                            color = GlanceTheme.colors.primary,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Receiver entry point registered in `AndroidManifest.xml`. Maps the system AppWidget
 * lifecycle to the [MediumHabitListWidget] composable.
 */
class MediumHabitListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MediumHabitListWidget()
}
