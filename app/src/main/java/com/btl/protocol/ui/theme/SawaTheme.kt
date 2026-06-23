package com.btl.protocol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SawaColorScheme = darkColorScheme(
    primary          = Color(0xFF00A884),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF075E54),
    secondary        = Color(0xFF25D366),
    background       = Color(0xFF111B21),
    surface          = Color(0xFF1F2C34),
    onBackground     = Color(0xFFE9EDEF),
    onSurface        = Color(0xFFE9EDEF),
)

@Composable
fun SawaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SawaColorScheme,
        content = content
    )
}
