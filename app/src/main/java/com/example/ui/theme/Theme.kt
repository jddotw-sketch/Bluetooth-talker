package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CyanNeon,
    onPrimary = Color.Black,
    primaryContainer = SlateSurface,
    onPrimaryContainer = CyanNeon,
    secondary = AmberTransmitting,
    onSecondary = Color.Black,
    secondaryContainer = SlateLight,
    onSecondaryContainer = AmberTransmitting,
    tertiary = GreenConnected,
    onTertiary = Color.Black,
    background = SlateDark,
    onBackground = TextPrimary,
    surface = SlateCard,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurface,
    onSurfaceVariant = TextSecondary,
    outline = SlateCardBorder
)

private val LightColorScheme = lightColorScheme(
    primary = BlueAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = AmberTransmitting,
    onSecondary = Color.White,
    tertiary = GreenConnected,
    onTertiary = Color.White,
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E5EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFFC4C8D0)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep tactical theme consistent for walkie-talkie
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme // Walkie-talkie looks exceptionally premium in dark tactical theme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
