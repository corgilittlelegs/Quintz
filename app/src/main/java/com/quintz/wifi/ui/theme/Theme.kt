package com.quintz.wifi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val IndustrialCliColorScheme = darkColorScheme(
    primary = CliButtonPrimary,
    onPrimary = CliButtonPrimaryText,
    primaryContainer = CliSurfaceElevated,
    onPrimaryContainer = CliTextPrimary,
    secondary = CliAccent5GHz,
    onSecondary = CliBackground,
    secondaryContainer = CliAccent5GHzBg,
    onSecondaryContainer = CliAccent5GHz,
    background = CliBackground,
    onBackground = CliTextPrimary,
    surface = CliSurface,
    onSurface = CliTextPrimary,
    surfaceVariant = CliSurfaceElevated,
    onSurfaceVariant = CliTextSecondary,
    outline = CliBorder,
    outlineVariant = CliBorderSubtle,
    error = CliAccentRed,
    onError = CliBackground,
    errorContainer = CliAccentRedBg,
    onErrorContainer = CliAccentRed
)

@Composable
fun AppTheme(
    darkTheme: Boolean = true, // Force crisp industrial dark mode
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IndustrialCliColorScheme,
        typography = Typography,
        content = content
    )
}
