package com.example.evolvix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.evolvix.ui.theme.HabitColorScheme

/**
 * A composable that displays a habit's progress as an animated bar with text.
 * 
 * @param title The name of the habit
 * @param maxClicks Maximum number of completions needed
 * @param modifier Optional Modifier for customizing the layout
 * @param currentClickCount Current number of completions
 * @param colorScheme Color scheme to use for the item
 * @param isSystemInDarkTheme Whether dark mode is enabled
 */
@Composable
fun ProgressItem(
    title: String,
    maxClicks: Int,
    modifier: Modifier = Modifier,
    currentClickCount: Int,
    colorScheme: HabitColorScheme,
    isSystemInDarkTheme: Boolean = isSystemInDarkTheme()
) {
    val progressFraction = if (maxClicks > 0) currentClickCount.toFloat() / maxClicks else 0f

    // Animate progress changes
    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction,
        label = "progressAnimation"
    )

    // Container box with rounded corners
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colorScheme.getBackgroundColor(isSystemInDarkTheme))
    ) {
        // Progress bar fill
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedFraction)
                .background(colorScheme.getProgressColor(isSystemInDarkTheme))
        )

        // Content row with title and progress text
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colorScheme.getTextColor(isSystemInDarkTheme)
            )
            Text(
                text = "${currentClickCount}/$maxClicks",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}