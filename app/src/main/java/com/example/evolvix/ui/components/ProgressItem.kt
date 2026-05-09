package com.example.evolvix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.evolvix.ui.theme.HabitColorScheme

/**
 * A composable that displays a habit's progress as an animated bar with text.
 *
 * When [isOverCompleted] is true, the progress bar fills completely using the M3 tertiary
 * color role (acting as an "achievement" accent), and the count label switches to a
 * surplus format (+N) so the user immediately understands they exceeded the target.
 *
 * @param title The name of the habit
 * @param maxClicks Maximum number of completions needed
 * @param modifier Optional Modifier for customizing the layout
 * @param currentClickCount Current number of completions
 * @param colorScheme Color scheme to use for the item
 * @param isOverCompleted Whether the habit has been completed more than the target
 * @param isPaused Whether the habit is currently paused
 * @param isSystemInDarkTheme Whether dark mode is enabled
 */
@Composable
fun ProgressItem(
    title: String,
    maxClicks: Int,
    modifier: Modifier = Modifier,
    currentClickCount: Int,
    colorScheme: HabitColorScheme,
    isOverCompleted: Boolean = false,
    isPaused: Boolean = false,
    isSystemInDarkTheme: Boolean = isSystemInDarkTheme()
) {
    // Clamp at 1f when over-completed so the bar doesn't overflow its container
    val progressFraction = if (maxClicks > 0) (currentClickCount.toFloat() / maxClicks).coerceAtMost(1f) else 0f

    // Animate progress changes
    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction,
        label = "progressAnimation"
    )

    val shape = RoundedCornerShape(8.dp)
    val progressColor = colorScheme.getProgressColor(isSystemInDarkTheme)
    // Text color is designed to contrast against the progress fill — use it as the border
    // so the stroke is always visible even when the bar is 100% filled.
    val borderColor = colorScheme.getTextColor(isSystemInDarkTheme)

    // +N shows the surplus completions; normal mode shows currentCount/target
    val countLabel = if (isOverCompleted) {
        "+${currentClickCount - maxClicks}"
    } else {
        "${currentClickCount}/$maxClicks"
    }

    // Container box with rounded corners.
    // Border must be applied before clip — otherwise the clip masks the stroke out.
    // When over-completed, a 2.dp border in the habit's own progress color acts as a
    // "celebratory frame" that respects each habit's color identity.
    // When paused, alpha(0.4f) dims the entire item to signal inactivity.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (isPaused) Modifier.alpha(0.4f) else Modifier)
            .then(
                if (isOverCompleted) Modifier.border(2.dp, borderColor, shape)
                else Modifier
            )
            .clip(shape)
            .background(colorScheme.getBackgroundColor(isSystemInDarkTheme))
    ) {
        // Progress bar fill — always uses the habit's own color scheme
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedFraction)
                .background(progressColor)
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
            // When paused, show a pause icon instead of the count — the icon signals
            // state without cluttering the row with an extra text label.
            if (isPaused) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = "Paused",
                    tint = colorScheme.getTextColor(isSystemInDarkTheme),
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    // +N uses the habit's text color so it's always readable regardless of bar color
                    color = colorScheme.getTextColor(isSystemInDarkTheme)
                        .let { if (isOverCompleted) it else it.copy(alpha = 0.7f) }
                )
            }
        }
    }
}