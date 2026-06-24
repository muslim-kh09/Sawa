package com.btl.protocol.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// --- Elite Premium Colors ---

// DARK THEME
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkPrimary = Color(0xFF4A90E2) // Premium subtle blue
val DarkOnPrimary = Color(0xFFFFFFFF)
val DarkTextPrimary = Color(0xFFE0E0E0)
val DarkTextSecondary = Color(0xFF9E9E9E)
val DarkBubbleMe = Color(0xFF1D3B5E) // Muted elegant blue
val DarkBubblePeer = Color(0xFF2C2C2E) // Deep gray
val DarkInputBg = Color(0xFF1C1C1E)

private val SawaDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    background = DarkBackground,
    surface = DarkSurface,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    secondary = DarkBubbleMe,
    tertiary = DarkBubblePeer,
    surfaceVariant = DarkInputBg,
    onSurfaceVariant = DarkTextSecondary
)

// LIGHT THEME
val LightBackground = Color(0xFFF8F9FA)
val LightSurface = Color(0xFFFFFFFF)
val LightPrimary = Color(0xFF007AFF) // Premium Apple-like blue
val LightOnPrimary = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF1C1C1E)
val LightTextSecondary = Color(0xFF8E8E93)
val LightBubbleMe = Color(0xFFE1F0FF) // Soft light blue
val LightBubblePeer = Color(0xFFF2F2F7) // Soft clean gray
val LightInputBg = Color(0xFFF2F2F7)

private val SawaLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    secondary = LightBubbleMe,
    tertiary = LightBubblePeer,
    surfaceVariant = LightInputBg,
    onSurfaceVariant = LightTextSecondary
)

// AMOLED THEME
val AmoledBackground = Color(0xFF000000)
val AmoledSurface = Color(0xFF0A0A0A)
val AmoledPrimary = Color(0xFF4A90E2)
val AmoledOnPrimary = Color(0xFFFFFFFF)
val AmoledTextPrimary = Color(0xFFE0E0E0)
val AmoledTextSecondary = Color(0xFF888888)
val AmoledBubbleMe = Color(0xFF152A43)
val AmoledBubblePeer = Color(0xFF1C1C1E)
val AmoledInputBg = Color(0xFF111111)

private val SawaAmoledColorScheme = darkColorScheme(
    primary = AmoledPrimary,
    onPrimary = AmoledOnPrimary,
    background = AmoledBackground,
    surface = AmoledSurface,
    onBackground = AmoledTextPrimary,
    onSurface = AmoledTextPrimary,
    secondary = AmoledBubbleMe,
    tertiary = AmoledBubblePeer,
    surfaceVariant = AmoledInputBg,
    onSurfaceVariant = AmoledTextSecondary
)

@Composable
fun SawaTheme(
    themePreference: Int = 0, // 0 = System, 1 = Light, 2 = Dark, 3 = AMOLED
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        1 -> false
        2, 3 -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when (themePreference) {
        1 -> SawaLightColorScheme
        2 -> SawaDarkColorScheme
        3 -> SawaAmoledColorScheme
        else -> if (darkTheme) SawaDarkColorScheme else SawaLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
