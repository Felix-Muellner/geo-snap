package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF4D6D),
    onPrimary = Color(0xFF2B0009),
    primaryContainer = Color(0xFF800F2F),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF38BDF8),
    background = SlateNavy900,
    onBackground = Color(0xFFF8FAFC),
    surface = SlateNavy800,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = SlateNavy700,
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val LightColorScheme = lightColorScheme(
    primary = PinCrimson,
    onPrimary = Color.White,
    primaryContainer = PinCrimsonLight,
    onPrimaryContainer = PinCrimsonDark,
    secondary = CartoTeal,
    onSecondary = Color.White,
    secondaryContainer = CartoTealLight,
    onSecondaryContainer = CartoTealDark,
    tertiary = Color(0xFF0284C7),
    background = WarmSand50,
    onBackground = SlateNavy900,
    surface = Color.White,
    onSurface = SlateNavy900,
    surfaceVariant = WarmSand100,
    onSurfaceVariant = SlateNavy700,
    outline = Color(0xFFCBD5E1),
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorRedContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
