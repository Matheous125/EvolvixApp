package com.example.evolvix.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.evolvix.R

/**
 * Single-page onboarding screen shown once on first launch.
 *
 * Layout (top → bottom):
 *  1. Welcome title + one-sentence pitch above the mascot
 *  2. Transparent mascot illustration as the centrepiece (already contains the
 *     "EVOLVIX" logotype baked into the artwork)
 *  3. "Get Started" CTA button below the mascot
 *
 * [Surface] uses [MaterialTheme.colorScheme.background] so the screen colour
 * adapts automatically to the system Light / Dark theme — the transparent PNG
 * blends against whatever colour the theme provides.
 *
 * This composable is stateless — [onGetStarted] delegates persistence to the
 * caller ([NavGraph] → [OnboardingPreferences]).
 *
 * @param onGetStarted Callback invoked when the user taps "Get Started".
 */
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    // Surface with background (not surface) colour — slightly different tone in
    // M3, and semantically correct for a full-screen page background.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(56.dp))

            // ── Welcome title ─────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.onboarding_welcome),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── One-sentence pitch ────────────────────────────────────────────
            Text(
                text = stringResource(R.string.onboarding_pitch),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // ── Mascot illustration ───────────────────────────────────────────
            // weight(1f) expands the image to fill all space between the pitch
            // text above and the button below — button is always visible.
            // ContentScale.Fit shows the full artwork without cropping; the
            // transparent background blends against the theme colour behind it.
            // The image asset already contains the "EVOLVIX" logotype.
            Image(
                painter = painterResource(R.drawable.welcome_image),
                contentDescription = null, // decorative — text is in the artwork
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // ── Get Started CTA ───────────────────────────────────────────────
            // M3 filled Button — highest-emphasis action on this screen.
            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = stringResource(R.string.onboarding_get_started),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
