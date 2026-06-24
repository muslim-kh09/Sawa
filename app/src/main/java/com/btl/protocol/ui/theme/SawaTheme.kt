package com.btl.protocol.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.core.view.WindowCompat

// --- TACTICAL TELEMETRY / BRUTALIST TOKENS ---

val LocalDarkTheme = compositionLocalOf { true } // Forced Dark Mode

val TacticalBackground = Color(0xFF0A0A0A) // Deactivated CRT
val TacticalSurface = Color(0xFF121212)    // Surface depth
val TacticalPrimary = Color(0xFFE61919)    // Aviation Red
val TacticalOnPrimary = Color(0xFF0A0A0A)
val TacticalText = Color(0xFFEAEAEA)       // White phosphor
val TacticalMuted = Color(0xFF6B6B6B)
val TacticalGreen = Color(0xFF4AF626)      // Terminal Green
val TacticalBorder = Color(0xFF333333)

private val BrutalistColorScheme = darkColorScheme(
    primary = TacticalPrimary,
    onPrimary = TacticalOnPrimary,
    background = TacticalBackground,
    surface = TacticalSurface,
    onBackground = TacticalText,
    onSurface = TacticalText,
    secondary = TacticalGreen,
    onSecondary = TacticalBackground,
    surfaceVariant = TacticalSurface,
    onSurfaceVariant = TacticalMuted,
    outline = TacticalBorder
)

// --- TACTICAL TYPOGRAPHY ---
val BrutalistTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.05).em
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.03).em
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.05.em
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.05.em
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.02.em
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.02.em
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.em
    )
)

val BrutalistShapes = Shapes(
    small = CutCornerShape(0.dp),
    medium = CutCornerShape(0.dp),
    large = CutCornerShape(0.dp),
    extraLarge = CutCornerShape(0.dp)
)

@Composable
fun SawaTheme(
    themePreference: Int = 0, // Ignored, forced tactical dark
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            window.statusBarColor = TacticalBackground.value.toInt()
            window.navigationBarColor = TacticalBackground.value.toInt()
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides true
    ) {
        MaterialTheme(
            colorScheme = BrutalistColorScheme,
            typography = BrutalistTypography,
            shapes = BrutalistShapes,
            content = content
        )
    }
}

