package com.example.namufarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Forest700,
    onPrimary = White,
    primaryContainer = Forest100,
    onPrimaryContainer = Forest900,
    secondary = Leaf600,
    onSecondary = White,
    secondaryContainer = Leaf100,
    onSecondaryContainer = Forest900,
    tertiary = Amber600,
    onTertiary = White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Amber600,
    error = Red600,
    onError = White,
    errorContainer = Red100,
    onErrorContainer = Red800,
    background = Canvas,
    onBackground = Ink900,
    surface = White,
    onSurface = Ink900,
    surfaceVariant = SurfaceSoft,
    onSurfaceVariant = Ink700,
    outline = Line,
    outlineVariant = Line
)

private val DarkColorScheme = darkColorScheme(
    primary = ColorTokens.primaryDark,
    onPrimary = Forest900,
    primaryContainer = Forest800,
    onPrimaryContainer = Forest50,
    secondary = ColorTokens.secondaryDark,
    onSecondary = Forest900,
    background = ColorTokens.darkBackground,
    onBackground = ColorTokens.darkOnBackground,
    surface = ColorTokens.darkSurface,
    onSurface = ColorTokens.darkOnBackground,
    surfaceVariant = ColorTokens.darkSurfaceVariant,
    onSurfaceVariant = ColorTokens.darkOnSurfaceVariant,
    outline = ColorTokens.darkOutline
)

private object ColorTokens {
    val primaryDark = androidx.compose.ui.graphics.Color(0xFF9BCDB8)
    val secondaryDark = androidx.compose.ui.graphics.Color(0xFFB7CCBA)
    val darkBackground = androidx.compose.ui.graphics.Color(0xFF101713)
    val darkSurface = androidx.compose.ui.graphics.Color(0xFF17201B)
    val darkSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF202B25)
    val darkOnBackground = androidx.compose.ui.graphics.Color(0xFFE4EBE7)
    val darkOnSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFBEC9C3)
    val darkOutline = androidx.compose.ui.graphics.Color(0xFF45524B)
}

@Composable
fun NamuFarmTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = NamuFarmShapes,
        content = content
    )
}
