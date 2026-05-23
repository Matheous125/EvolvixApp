package com.example.evolvix.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.evolvix.R

/**
 * Single-page onboarding screen shown once on first launch.
 *
 * Displays the app name, a short one-sentence pitch, and a "Get Started" CTA.
 * When the user taps the button, [onGetStarted] is invoked — the caller
 * ([NavGraph]) persists the completion flag via [OnboardingPreferences] and
 * navigates to the main habits screen, popping this screen off the back stack so
 * the user can never navigate back to it.
 *
 * **Pattern: Preferences as Repository** — this composable is stateless; all
 * persistence is delegated to [OnboardingPreferences], keeping the View layer
 * free of storage concerns (classic MVVM separation).
 *
 * @param onGetStarted Callback invoked when the user taps "Get Started".
 */
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    // M3 Surface ensures correct background color in both Light and Dark themes.
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Brand icon ────────────────────────────────────────────────────
            // A simple emoji serves as a lightweight brand mark. No custom
            // drawable needed — keeps the screen asset-free and easy to change.
            Text(
                text = "🌱",
                fontSize = 80.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            // ── App name ──────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── One-sentence pitch ────────────────────────────────────────────
            Text(
                text = stringResource(R.string.onboarding_pitch),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Push the button to the bottom third of the screen.
            Spacer(modifier = Modifier.weight(1f))

            // ── Get Started CTA ───────────────────────────────────────────────
            // M3 filled Button — the highest-emphasis action on this screen.
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
        }
    }
}
