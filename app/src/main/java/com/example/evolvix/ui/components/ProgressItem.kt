package com.example.evolvix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.evolvix.ui.theme.HabitColorScheme
import androidx.compose.runtime.withFrameMillis
import kotlin.random.Random

// ── Mini-confetti particle system ────────────────────────────────────────────

private val MINI_COLORS = listOf(
    Color(0xFFFF6B35), Color(0xFFFFD700), Color(0xFF4ECDC4),
    Color(0xFF45B7D1), Color(0xFFFF6B9D), Color(0xFF96CEB4), Color(0xFFDDA0DD),
)

/** Duration of one mini-celebration burst in milliseconds. */
private const val MINI_DURATION_MS = 750L

/**
 * Gravity in normalized bar-height units per second squared.
 * Calibrated so particles with vy ≈ -3.0 reach ~64% bar height:
 *   max_height_frac = vy² / (2 * g) = 9 / 14 ≈ 0.64 ✓
 */
private const val MINI_GRAVITY = 7f

/**
 * Immutable launch parameters for one mini-celebration particle.
 * All positions/velocities are normalized to the bar's own dimensions.
 */
private data class MiniParticleSeed(
    val xFrac: Float,    // start X as fraction of bar width
    val vxFrac: Float,   // velocity X in bar-widths per second
    val vyFrac: Float,   // velocity Y in bar-heights per second (negative = upward)
    val color: Color,
    val sizeFrac: Float, // particle size as fraction of bar height
    val rot0: Float,     // initial rotation in degrees
    val rotVel: Float,   // rotation velocity in degrees per second
    val isRect: Boolean,
)

/**
 * Generates [count] mini particles spread across the full bar width,
 * launched upward to fill the 56 dp bar area during the burst.
 */
private fun makeMiniSeeds(count: Int, rng: Random): List<MiniParticleSeed> = List(count) {
    MiniParticleSeed(
        xFrac    = rng.nextFloat(),                        // spread full bar width
        vxFrac   = (rng.nextFloat() - 0.5f) * 0.6f,
        // vy range 2.5..3.8 → peaks at 45%..103% bar height (clipped at bar top)
        vyFrac   = -(rng.nextFloat() * 1.3f + 2.5f),
        color    = MINI_COLORS[rng.nextInt(MINI_COLORS.size)],
        sizeFrac = rng.nextFloat() * 0.15f + 0.10f,       // 10%–25% of bar height
        rot0     = rng.nextFloat() * 360f,
        rotVel   = (rng.nextFloat() - 0.5f) * 720f,
        isRect   = rng.nextBoolean(),
    )
}

/**
 * A composable that displays a habit's progress as an animated bar with text.
 *
 * Fires mini confetti bursts (bounded inside the bar) when the completion count crosses
 * the 25 %, 50 %, or 75 % thresholds — but only when the transition between consecutive
 * integer counts actually crosses that threshold, so sparse targets (e.g. target = 3) still
 * celebrate at the right moments without fabricating extra milestones.
 *
 * When [isOverCompleted] is true, the progress bar fills completely and the count label
 * switches to a surplus format (+N).
 *
 * @param title             The name of the habit.
 * @param maxClicks         Maximum completions required (the target).
 * @param modifier          Optional Modifier for customising the layout.
 * @param currentClickCount Current number of completions.
 * @param colorScheme       Color scheme for this habit row.
 * @param isOverCompleted   True when completions exceed the target.
 * @param isPaused          True when the habit is currently paused.
 * @param isSystemInDarkTheme Whether dark mode is active.
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
    isSystemInDarkTheme: Boolean = isSystemInDarkTheme(),
) {
    // Clamp at 1f when over-completed so the bar doesn't overflow its container
    val progressFraction = if (maxClicks > 0) (currentClickCount.toFloat() / maxClicks).coerceAtMost(1f) else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = progressFraction,
        label = "progressAnimation"
    )

    val shape = RoundedCornerShape(8.dp)
    val progressColor = colorScheme.getProgressColor(isSystemInDarkTheme)
    val borderColor   = colorScheme.getTextColor(isSystemInDarkTheme)

    val countLabel = if (isOverCompleted) {
        "+${currentClickCount - maxClicks}"
    } else {
        "$currentClickCount/$maxClicks"
    }

    // ── Mini-celebration state ────────────────────────────────────────────────
    // prevClickCount tracks the count from the previous composition so threshold crossings
    // can be detected as a delta (not just as an absolute value).
    var prevClickCount by remember { mutableIntStateOf(currentClickCount) }
    // Incrementing miniSeedKey re-generates particle seeds AND re-triggers the animation
    // LaunchedEffect — one increment per milestone crossing.
    var miniSeedKey by remember { mutableIntStateOf(0) }
    // True while the mini-confetti Canvas should be drawn over the bar.
    var showMiniConfetti by remember { mutableStateOf(false) }
    // Tracks animation progress 0→1 inside the frame-by-frame coroutine.
    var miniAnimTime by remember { mutableFloatStateOf(0f) }

    // Particle seeds are stable within a seed key — regenerated on each new celebration.
    val miniSeeds = remember(miniSeedKey) {
        if (miniSeedKey == 0) emptyList()
        else makeMiniSeeds(18, Random(miniSeedKey.toLong()))
    }

    // Detects whether [currentClickCount] just crossed one of the 25 / 50 / 75 % thresholds.
    // Only fires for forward increments (curr > prev) to ignore daily resets and decrements.
    // At exactly target (100 %) no mini-celebration fires — the full-screen overlay handles it.
    LaunchedEffect(currentClickCount) {
        val prev = prevClickCount
        prevClickCount = currentClickCount
        if (currentClickCount > prev && maxClicks > 0 && currentClickCount < maxClicks) {
            val prevFrac = prev.toFloat() / maxClicks
            val currFrac = currentClickCount.toFloat() / maxClicks
            val crossed = listOf(0.25f, 0.50f, 0.75f).any { t -> prevFrac < t && currFrac >= t }
            if (crossed) miniSeedKey++
        }
    }

    // Frame-by-frame animation driver for the mini confetti burst.
    // Cancelled and restarted each time miniSeedKey changes (new milestone hit).
    LaunchedEffect(miniSeedKey) {
        if (miniSeedKey == 0) return@LaunchedEffect
        showMiniConfetti = true
        miniAnimTime = 0f
        val t0 = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            miniAnimTime = ((now - t0).toFloat() / MINI_DURATION_MS).coerceIn(0f, 1f)
            if (miniAnimTime >= 1f) break
        }
        showMiniConfetti = false
        miniAnimTime = 0f
    }
    // ─────────────────────────────────────────────────────────────────────────

    // Border must be applied before clip — otherwise the clip masks the stroke out.
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
                    color = colorScheme.getTextColor(isSystemInDarkTheme)
                        .let { if (isOverCompleted) it else it.copy(alpha = 0.7f) }
                )
            }
        }

        // Mini confetti — drawn last so it renders on top of the text row.
        // The outer Box's .clip(shape) already constrains all drawing to the bar's
        // rounded-rectangle bounds, so no additional clip modifier is needed here.
        if (showMiniConfetti) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w    = size.width
                val h    = size.height
                val tSec = miniAnimTime * MINI_DURATION_MS / 1000f

                miniSeeds.forEach { p ->
                    // Projectile motion: particles start at y = h (bottom of bar) and
                    // travel upward, pulled back down by MINI_GRAVITY.
                    val px = p.xFrac * w + p.vxFrac * w * tSec
                    val py = h + p.vyFrac * h * tSec + 0.5f * MINI_GRAVITY * h * tSec * tSec

                    // Fade out in the final 40 % of the burst so particles disappear smoothly.
                    val alpha = ((1f - miniAnimTime) / 0.4f).coerceIn(0f, 1f)
                    if (alpha <= 0f) return@forEach

                    val color    = p.color.copy(alpha = alpha)
                    val rotation = p.rot0 + p.rotVel * tSec
                    val sz       = p.sizeFrac * h

                    if (p.isRect) {
                        rotate(degrees = rotation, pivot = Offset(px, py)) {
                            drawRect(
                                color   = color,
                                topLeft = Offset(px - sz / 2f, py - sz / 4f),
                                size    = Size(sz, sz / 2f),
                            )
                        }
                    } else {
                        drawCircle(color = color, radius = sz / 2f, center = Offset(px, py))
                    }
                }
            }
        }
    }
}