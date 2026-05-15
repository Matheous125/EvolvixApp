package com.example.evolvix.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.runtime.withFrameMillis
import kotlin.random.Random

// Saturated palette matching the screenshot: bright mixed confetti colors
private val CONFETTI_COLORS = listOf(
    Color(0xFFFF6B35), // orange
    Color(0xFFFFD700), // gold
    Color(0xFF4ECDC4), // teal
    Color(0xFF45B7D1), // sky blue
    Color(0xFFFF6B9D), // pink
    Color(0xFF96CEB4), // mint
    Color(0xFFDDA0DD), // plum
    Color(0xFFFFFFFF), // white
)

/** Total animation duration for the full-screen celebration in milliseconds. */
private const val FULL_DURATION_MS = 2800L

/**
 * Gravity constant in normalized screen-height units per second squared.
 * Calibrated so that particles with vy ≈ -0.95 reach ~50% screen height:
 *   max_height = vy² / (2 * g) → 0.95² / (2 * 0.9) ≈ 0.50 ✓
 */
private const val GRAVITY_FRAC = 0.9f

/**
 * Immutable launch parameters for one confetti particle.
 *
 * All positions/velocities are normalized to screen dimensions (0..1),
 * so the overlay renders correctly at any screen resolution.
 *
 * @param xFrac   Start X as a fraction of screen width (0 = left, 1 = right).
 * @param vxFrac  Horizontal velocity in screen-widths per second.
 * @param vyFrac  Vertical velocity in screen-heights per second (negative = upward).
 * @param color   Particle fill color.
 * @param sizeFrac Particle size as a fraction of screen width.
 * @param rot0    Initial rotation in degrees.
 * @param rotVel  Rotation velocity in degrees per second.
 * @param isRect  True = flat rectangle, false = circle.
 */
private data class ParticleSeed(
    val xFrac: Float,
    val vxFrac: Float,
    val vyFrac: Float,
    val color: Color,
    val sizeFrac: Float,
    val rot0: Float,
    val rotVel: Float,
    val isRect: Boolean,
)

/**
 * Generates [count] particles clustered near the horizontal center of the screen,
 * with upward velocities calibrated to reach approximately 25–50% screen height.
 */
private fun makeSeeds(count: Int, rng: Random): List<ParticleSeed> = List(count) {
    ParticleSeed(
        // Center cluster ± 25 % of screen width
        xFrac   = 0.5f + (rng.nextFloat() - 0.5f) * 0.5f,
        vxFrac  = (rng.nextFloat() - 0.5f) * 0.7f,
        // vy range gives peak heights from ~26% to ~52% of screen height
        vyFrac  = -(rng.nextFloat() * 0.4f + 0.75f),
        color   = CONFETTI_COLORS[rng.nextInt(CONFETTI_COLORS.size)],
        sizeFrac = rng.nextFloat() * 0.010f + 0.008f, // 0.8 %–1.8 % of screen width
        rot0    = rng.nextFloat() * 360f,
        rotVel  = (rng.nextFloat() - 0.5f) * 540f,
        isRect  = rng.nextBoolean(),
    )
}

/**
 * Full-screen confetti celebration overlay triggered when a habit reaches its daily target.
 *
 * Particles are launched from the bottom of the screen, travel upward through approximately
 * half the screen height, and fall back under simulated gravity. A single haptic pulse fires
 * at the moment the animation starts. The overlay auto-dismisses after [FULL_DURATION_MS] ms
 * by calling [onFinished], which the caller should use to flip [visible] back to false.
 *
 * Placement: inside the root [Box] in [AppContent], so it draws over every screen and
 * navigation element — the same layering pattern used by [AchievementBanner].
 *
 * Pattern: **Stateless Composable + Event Bus** — [visible] is owned by the caller;
 * the internal [LaunchedEffect] drives the frame-by-frame animation timeline.
 *
 * @param visible True while the confetti should be shown. Setting to false immediately
 *   removes the overlay (particles fade naturally before [onFinished] fires).
 * @param onFinished Called once the 2.8-second animation completes so the caller can
 *   reset [visible] and allow a subsequent celebration to re-trigger.
 */
@Composable
fun FullScreenConfettiOverlay(visible: Boolean, onFinished: () -> Unit) {
    if (!visible) return

    val haptic = LocalHapticFeedback.current

    // Particle seeds are stable for the lifetime of this composable instance.
    // A new set is generated each time the composable enters the tree (each new celebration).
    val seeds = remember { makeSeeds(65, Random(System.currentTimeMillis())) }

    // Single float 0→1 drives all particle positions, alpha, and rotations inside Canvas.
    // Only one state read per frame — minimal recomposition overhead.
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        // Haptic pulse fires immediately as the overlay appears — same moment the
        // user sees the confetti, so visual and tactile feedback are synchronised.
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

        val t0 = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            progress = ((now - t0).toFloat() / FULL_DURATION_MS).coerceIn(0f, 1f)
            if (progress >= 1f) break
        }
        onFinished()
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val tSec = progress * FULL_DURATION_MS / 1000f

        seeds.forEach { p ->
            // Projectile motion: startY = h (bottom edge), upward velocity (negative vy)
            val px = p.xFrac * w + p.vxFrac * w * tSec
            val py = h + p.vyFrac * h * tSec + 0.5f * GRAVITY_FRAC * h * tSec * tSec

            // Fade out during the final 35 % of the animation so particles disappear
            // gracefully rather than blinking out.
            val alpha = ((1f - progress) / 0.35f).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEach

            val color    = p.color.copy(alpha = alpha)
            val rotation = p.rot0 + p.rotVel * tSec
            val sz       = p.sizeFrac * w

            if (p.isRect) {
                // Rotating flat rectangle — the most recognisable confetti shape
                rotate(degrees = rotation, pivot = Offset(px, py)) {
                    drawRect(
                        color    = color,
                        topLeft  = Offset(px - sz / 2f, py - sz / 4f),
                        size     = Size(sz, sz / 2f),
                    )
                }
            } else {
                drawCircle(color = color, radius = sz / 2f, center = Offset(px, py))
            }
        }
    }
}
