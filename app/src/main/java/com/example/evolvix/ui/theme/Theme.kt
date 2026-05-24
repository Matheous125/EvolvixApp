package com.example.evolvix.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * CompositionLocal that exposes the effective dark-mode flag to the entire
 * composition tree. Consumed by [ProgressItem] and any other composable that
 * needs to adapt colors based on the *app* theme (not the OS system theme).
 *
 * This is necessary because [SettingsViewModel] allows the user to force
 * Light or Dark mode independently of the system setting, so
 * [androidx.compose.foundation.isSystemInDarkTheme] is insufficient.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/**
 * Dark theme color scheme definition.
 * Used when dynamic colors are not available or disabled.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * Main theme composable for the application.
 * Supports Light, Dark, and System-default themes.
 * On Android 12+ (API 31), Material You dynamic colors are used when available.
 *
 * Also handles status bar icon contrast via [WindowCompat]: when [darkTheme] is
 * false (light theme), status bar icons are rendered dark so they remain readable
 * against the light background. This updates reactively whenever the user switches
 * theme inside the app without restarting the Activity.
 *
 * @param darkTheme     Whether to use dark theme; driven by [SettingsViewModel.themeMode].
 * @param dynamicColor  Whether to use dynamic system colors (Android 12+).
 * @param content       Content to be themed.
 */
@Composable
fun EvolvixTheme(
    darkTheme: Boolean = true,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            // Choose the dynamic palette that matches the requested brightness.
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    // Update status bar icon appearance whenever the theme changes.
    // isAppearanceLightStatusBars = true → dark icons (needed on light backgrounds).
    // isAppearanceLightStatusBars = false → light/white icons (needed on dark backgrounds).
    // SideEffect runs after every successful recomposition, so theme switches are instant.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}