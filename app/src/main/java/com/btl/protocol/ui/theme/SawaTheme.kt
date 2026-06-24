package com.btl.protocol.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// --- Premium Elite Theme Tokens ---

val LocalDarkTheme = compositionLocalOf { false }

// DARK THEME (Zinc/Charcoal inspired)
val DarkBackground = Color(0xFF09090B) // Zinc 950
val DarkSurface = Color(0xFF18181B)    // Zinc 900
val DarkPrimary = Color(0xFF38BDF8)    // Sky 400 (vibrant, modern)
val DarkOnPrimary = Color(0xFF0F172A)
val DarkTextPrimary = Color(0xFFF8FAFC)
val DarkTextSecondary = Color(0xFF94A3B8)
val DarkBubbleMe = Color(0xFF0EA5E9)   // Sky 500
val DarkBubblePeer = Color(0xFF27272A) // Zinc 800
val DarkInputBg = Color(0xFF18181B)

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

// LIGHT THEME (Clean, airy, Apple-esque)
val LightBackground = Color(0xFFFAFAFA)
val LightSurface = Color(0xFFFFFFFF)
val LightPrimary = Color(0xFF0284C7)   // Sky 600
val LightOnPrimary = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF64748B)
val LightBubbleMe = Color(0xFFE0F2FE)  // Sky 100
val LightBubblePeer = Color(0xFFF1F5F9) // Slate 100
val LightInputBg = Color(0xFFF8FAFC)

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

// --- Premium Typography ---
// Even without custom font files, we can fake a premium feel via weight, tracking, and line height.
val PremiumTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun SawaTheme(
    themePreference: Int = 0, // 0 = System, 1 = Light, 2 = Dark
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when (themePreference) {
        1 -> SawaLightColorScheme
        2 -> SawaDarkColorScheme
        else -> if (darkTheme) SawaDarkColorScheme else SawaLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            window.statusBarColor = Color.Transparent.value.toInt()
            window.navigationBarColor = Color.Transparent.value.toInt()
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PremiumTypography,
            content = content
        )
    }
}
