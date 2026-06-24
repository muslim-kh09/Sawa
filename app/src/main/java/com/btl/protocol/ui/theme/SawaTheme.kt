package com.btl.protocol.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.core.view.WindowCompat

// --- APPLE / iOS INSPIRED TOKENS ---
private val IosBlue = Color(0xFF007AFF)
private val IosBlueDark = Color(0xFF0A84FF)
private val IosGreen = Color(0xFF34C759)
private val IosRed = Color(0xFFFF3B30)

private val IosLightBackground = Color(0xFFF2F2F7)
private val IosLightSurface = Color(0xFFFFFFFF)
private val IosLightOnSurface = Color(0xFF000000)
private val IosLightOnSurfaceVariant = Color(0xFF8E8E93)
private val IosLightDivider = Color(0xFFC6C6C8)

private val IosDarkBackground = Color(0xFF000000)
private val IosDarkSurface = Color(0xFF1C1C1E)
private val IosDarkOnSurface = Color(0xFFFFFFFF)
private val IosDarkOnSurfaceVariant = Color(0xFF98989D)
private val IosDarkDivider = Color(0xFF38383A)

private val LightColorScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    background = IosLightBackground,
    onBackground = IosLightOnSurface,
    surface = IosLightSurface,
    onSurface = IosLightOnSurface,
    surfaceVariant = IosLightSurface,
    onSurfaceVariant = IosLightOnSurfaceVariant,
    error = IosRed,
    onError = Color.White,
    outline = IosLightDivider
)

private val DarkColorScheme = darkColorScheme(
    primary = IosBlueDark,
    onPrimary = Color.White,
    background = IosDarkBackground,
    onBackground = IosDarkOnSurface,
    surface = IosDarkSurface,
    onSurface = IosDarkOnSurface,
    surfaceVariant = IosDarkSurface,
    onSurfaceVariant = IosDarkOnSurfaceVariant,
    error = IosRed,
    onError = Color.White,
    outline = IosDarkDivider
)

// --- iOS-LIKE TYPOGRAPHY ---
val LocalThemePreference = compositionLocalOf { 0 }

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp
    )
)

// --- ROUNDED SHAPES ---
val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

@Composable
fun SawaTheme(
    themePreference: Int = 0,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.value.toInt()
            window.navigationBarColor = Color.Transparent.value.toInt()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalThemePreference provides themePreference) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
