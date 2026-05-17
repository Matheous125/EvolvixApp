package com.example.evolvix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A single point of data for the [ScrollableBarChart].
 *
 * @property date Calendar day this bar represents.
 * @property count Raw completion count for [date] (y-axis value).
 */
data class BarChartDay(val date: LocalDate, val count: Int)

/**
 * Scrollable, Canvas-based bar chart used in expanded habit cards on the Statistics screen.
 *
 * Each bar represents a single calendar day's completion count. The chart shows ~7 bars
 * in the visible viewport — additional days extend horizontally and become accessible by
 * scrolling left/right (matches STAT-SCREN-SUMMARY.MD: "always show 7 DAYS but user can
 * scroll right and left"). The most recent day is anchored to the right edge on first
 * composition.
 *
 * Architecture:
 *  - **Single** [LazyRow] that contains a per-day [Column] (count label · bar · date).
 *    Using one LazyRow guarantees the bars and date labels scroll in lockstep, which is
 *    the natural mental model: a "day" is one column the user swipes through.
 *  - Gridlines are drawn via [Modifier.drawBehind] on the static Box that wraps the
 *    LazyRow, so they stay anchored to the viewport while the bars scroll over them.
 *  - The y-axis tick legend lives outside the LazyRow (fixed column on the left).
 *
 * @param days Chronological list of [BarChartDay] (oldest → newest). May be empty.
 * @param color Bar fill color (typically the habit's accent color).
 * @param modifier Layout modifier; the chart enforces its own height for a stable look.
 */
@Composable
fun ScrollableBarChart(
    days: List<BarChartDay>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) {
        // Empty state — keeps the expanded card layout stable even without data.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No completion data in this range",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Max value defines the y-axis scale. Round up to at least 4 so an empty week still
    // draws visible gridlines (avoids divide-by-zero and degenerate single-bar charts).
    val maxValue = (days.maxOf { it.count }).coerceAtLeast(4)

    // Four evenly spaced y-axis ticks (top → bottom). Used for both the side legend and
    // the gridlines drawn behind the bars.
    val tickCount = 4
    val ticks: List<Int> = (tickCount downTo 0).map { i -> maxValue * i / tickCount }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val dateFormatter = DateTimeFormatter.ofPattern("dd.MM")

    // The bar-plot area is 120.dp; column adds 18.dp for top count label + 20.dp date row.
    val plotHeight = 120.dp
    val barColumnWidth = 44.dp

    // Auto-scroll so the newest day is visible by default — matches user expectation.
    val listState = rememberLazyListState()
    LaunchedEffect(days.size) {
        if (days.size > 7) listState.scrollToItem(days.size - 7)
    }

    Row(modifier = modifier) {
        // ---- Fixed y-axis tick legend (does NOT scroll with the bars) ----
        Column(
            modifier = Modifier
                .width(28.dp)
                .height(plotHeight)
                .padding(top = 18.dp), // align with the top of the bar area inside columns
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            ticks.forEach { v ->
                Text(
                    text = v.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // ---- Scrolling region: bars + dates move together inside ONE LazyRow ----
        // Gridlines are drawn behind this Box (anchored to the viewport, not the content),
        // so they remain at fixed y-positions while the bars slide horizontally.
        Box(
            modifier = Modifier
                .weight(1f)
                .height(plotHeight + 38.dp) // plot area + count label (18dp) + date row (20dp)
                .drawBehind {
                    // Convert plotHeight (120dp) at runtime — gridlines must align with bars.
                    val labelOffsetPx = 18.dp.toPx() // top space reserved for the count label
                    val plotPx = 120.dp.toPx()
                    ticks.forEach { v ->
                        val y = labelOffsetPx + plotPx * (1f - v.toFloat() / maxValue)
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }
                    // Bottom axis line right under the bars (above the date row).
                    val axisY = labelOffsetPx + plotPx
                    drawLine(
                        color = axisColor,
                        start = Offset(0f, axisY),
                        end = Offset(size.width, axisY),
                        strokeWidth = 2f
                    )
                }
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top
            ) {
                items(days) { day ->
                    DayColumn(
                        day = day,
                        maxValue = maxValue,
                        color = color,
                        columnWidth = barColumnWidth,
                        plotHeight = plotHeight,
                        dateFormatter = dateFormatter
                    )
                }
            }
        }
    }
}

/**
 * One vertical column inside [ScrollableBarChart]: numeric count on top, the bar in the
 * middle (height fraction = count/max), and the date label below.
 *
 * Bundling all three pieces into one column means the day's elements always move together
 * during scroll — there is only ever one [LazyRow] in play.
 */
@Composable
private fun DayColumn(
    day: BarChartDay,
    maxValue: Int,
    color: Color,
    columnWidth: androidx.compose.ui.unit.Dp,
    plotHeight: androidx.compose.ui.unit.Dp,
    dateFormatter: DateTimeFormatter
) {
    val fraction = if (maxValue == 0) 0f else day.count.toFloat() / maxValue

    Column(
        modifier = Modifier.width(columnWidth),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Count label on top — fixed-height slot keeps bars aligned across columns.
        Text(
            text = day.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(18.dp)
        )
        // Bar plot area: a fixed-height Box that bottom-aligns the bar inside it.
        Box(
            modifier = Modifier
                .width(columnWidth)
                .height(plotHeight),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .fillMaxHeight(fraction.coerceAtLeast(0.02f))
                    .background(if (day.count > 0) color else color.copy(alpha = 0.25f))
            )
        }
        // Date label below the axis.
        Text(
            text = day.date.format(dateFormatter),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
        )
    }
}
