package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = StudioAmber,
    onSecondary = Color(0xFF452B00),
    secondaryContainer = Color(0xFF633F00),
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = StudioEmerald,
    onTertiary = Color(0xFF003921),
    tertiaryContainer = Color(0xFF005232),
    onTertiaryContainer = Color(0xFF6CF8B0),
    background = StudioDarkBg,
    onBackground = TextWhite,
    surface = StudioCardBg,
    onSurface = TextWhite,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = TextGray,
    error = SignalRed,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme, // Studio Dark theme always preferred for Pro Audio UX
        typography = Typography,
        content = content
    )
}
