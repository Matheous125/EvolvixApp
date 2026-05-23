package com.example.evolvix.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.evolvix.R
import com.example.evolvix.domain.model.AchievementDefinition
import com.example.evolvix.ui.screens.descResId
import com.example.evolvix.ui.screens.titleResId
import com.example.evolvix.ui.viewmodel.AchievementsViewModel
import kotlinx.coroutines.delay

/**
 * Top-anchored sliding banner that announces a newly unlocked achievement.
 *
 * Subscribes to [AchievementsViewModel.newlyUnlocked] — a `SharedFlow<AchievementDefinition>`
 * that acts as a fire-and-forget event channel (Pattern: **Event Bus via Flow**). Each emission
 * triggers an animated card that slides in from above the status bar, displays for 3.5 seconds,
 * then slides back out.
 *
 * Placement: rendered inside a `Box` overlay in [AppContent] (above the app `Scaffold`), so the
 * banner appears regardless of which screen the user is currently on.
 *
 * If a second achievement unlocks while the banner is visible, the current banner exits first
 * before the new one enters, preventing overlapping animations.
 *
 * Pattern: **Event Bus via SharedFlow** — the ViewModel emits once; this composable reacts
 * once per emission with no retained state coupling between the two layers.
 *
 * @param viewModel Activity-scoped [AchievementsViewModel] whose [AchievementsViewModel.newlyUnlocked]
 *   SharedFlow this composable subscribes to.
 */
@Composable
fun AchievementBanner(viewModel: AchievementsViewModel) {
    // `displayed` holds the definition currently rendered inside the card.
    // It is set before `visible` flips to true and cleared after the exit animation.
    var displayed by remember { mutableStateOf<AchievementDefinition?>(null) }
    var visible by remember { mutableStateOf(false) }

    // LaunchedEffect(Unit) — lives as long as this composable remains in the composition.
    // Collecting a SharedFlow here is the standard Compose Event Bus pattern:
    // the ViewModel emits once, the UI reacts once per emission, with no replay on resubscription.
    LaunchedEffect(Unit) {
        viewModel.newlyUnlocked.collect { achievement ->
            // Dismiss any currently visible banner before showing the incoming one.
            if (visible) {
                visible = false
                delay(350L) // let the exit animation complete
            }
            displayed = achievement
            visible = true
            delay(3_500L)   // display duration
            visible = false
            delay(350L)     // let exit animation complete before clearing content
            displayed = null
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit  = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        displayed?.let { def ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Emoji icon from the achievement definition.
                    Text(
                        text = def.icon,
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.banner_achievement_unlocked),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(def.titleResId()),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(def.descResId()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    // Points badge — visually matches the badge in AchievementRow.
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = stringResource(R.string.banner_achievement_points, def.points),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
