package com.example.evolvix.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Minimal Canvas-based sparkline used in collapsed habit cards on the Statistics screen.
 *
 * Renders one bar per data point with height proportional to a binary "reached" flag —
 * reached days render at full height in [color], missed days render as a low stub in a
 * muted variant. No axes, no labels: the goal is to convey the shape of the recent week
 * at a glance (see STAT-SCREN-SUMMARY.MD "📈 Trend: ▂▃▅▆▇▇▇").
 *
 * The component is stateless — pass it the data and it draws. Width is filled, height is
 * caller-controlled via [Modifier].
 *
 * @param reachedFlags Ordered list (oldest → newest) of booleans, one per day; typically 7.
 * @param color Bar color for reached days. Missed days are drawn in a translucent gray.
 * @param modifier Standard layout modifier; height is normally fixed (e.g. 24.dp) by caller.
 */
@Composable
fun Sparkline(
    reachedFlags: List<Boolean>,
    color: Color,
    modifier: Modifier = Modifier
) {
    // Capture theme color outside Canvas (Canvas DrawScope has no @Composable access).
    val missedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
    ) {
        if (reachedFlags.isEmpty()) return@Canvas

        // Layout: one bar per flag, with a small gap (~25% of bar width) between bars.
        val slot = size.width / reachedFlags.size
        val barWidth = slot * 0.75f
        val gap = (slot - barWidth) / 2f

        reachedFlags.forEachIndexed { i, reached ->
            // Reached = full-height; missed = 25% stub at the baseline.
            val barHeight = if (reached) size.height else size.height * 0.25f
            val x = i * slot + gap
            val y = size.height - barHeight
            drawRect(
                color = if (reached) color else missedColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight)
            )
        }
    }
}
