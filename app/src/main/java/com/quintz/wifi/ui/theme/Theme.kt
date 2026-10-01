package com.quintz.wifi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkCliPalette else LightCliPalette
    val colors = remember(palette) {
        val base = if (darkTheme) darkColorScheme() else lightColorScheme()
        base.copy(
            primary = palette.buttonPrimary,
            onPrimary = palette.buttonPrimaryText,
            primaryContainer = palette.surfaceElevated,
            onPrimaryContainer = palette.textPrimary,
            secondary = palette.accent5GHz,
            onSecondary = palette.background,
            secondaryContainer = palette.accent5GHzBg,
            onSecondaryContainer = palette.accent5GHz,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            surfaceVariant = palette.surfaceElevated,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.border,
            outlineVariant = palette.borderSubtle,
            error = palette.accentRed,
            onError = palette.background,
            errorContainer = palette.accentRedBg,
            onErrorContainer = palette.accentRed
        )
    }
    CompositionLocalProvider(LocalCliPalette provides palette) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography,
            content = content
        )
    }
}
