package com.quintz.wifi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colors used by both Material and the custom CLI components. */
data class CliPalette(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceActive: Color,
    val border: Color,
    val borderSubtle: Color,
    val borderActive: Color,
    val borderAccent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent5GHz: Color,
    val accent5GHzBg: Color,
    val accent24GHz: Color,
    val accent24GHzBg: Color,
    val accentGreen: Color,
    val accentGreenBg: Color,
    val accentRed: Color,
    val accentRedBg: Color,
    val buttonPrimary: Color,
    val buttonPrimaryText: Color
)

val DarkCliPalette = CliPalette(
    background = Color(0xFF09090B),
    surface = Color(0xFF121215),
    surfaceElevated = Color(0xFF18181B),
    surfaceActive = Color(0xFF202024),
    border = Color(0xFF27272A),
    borderSubtle = Color(0xFF1F1F23),
    borderActive = Color(0xFF3F3F46),
    borderAccent = Color(0xFF38BDF8),
    textPrimary = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFA1A1AA),
    textTertiary = Color(0xFF71717A),
    accent5GHz = Color(0xFF38BDF8),
    accent5GHzBg = Color(0xFF0C2433),
    accent24GHz = Color(0xFFF59E0B),
    accent24GHzBg = Color(0xFF291B07),
    accentGreen = Color(0xFF4ADE80),
    accentGreenBg = Color(0xFF0C2316),
    accentRed = Color(0xFFF87171),
    accentRedBg = Color(0xFF2E1010),
    buttonPrimary = Color(0xFFFAFAFA),
    buttonPrimaryText = Color(0xFF09090B)
)

val LightCliPalette = CliPalette(
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF1F5F9),
    surfaceActive = Color(0xFFE2E8F0),
    border = Color(0xFFCBD5E1),
    borderSubtle = Color(0xFFE2E8F0),
    borderActive = Color(0xFF64748B),
    borderAccent = Color(0xFF0369A1),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF334155),
    textTertiary = Color(0xFF475569),
    accent5GHz = Color(0xFF075985),
    accent5GHzBg = Color(0xFFE0F2FE),
    accent24GHz = Color(0xFF92400E),
    accent24GHzBg = Color(0xFFFEF3C7),
    accentGreen = Color(0xFF166534),
    accentGreenBg = Color(0xFFDCFCE7),
    accentRed = Color(0xFFB91C1C),
    accentRedBg = Color(0xFFFEE2E2),
    buttonPrimary = Color(0xFF0F172A),
    buttonPrimaryText = Color(0xFFFFFFFF)
)

val LocalCliPalette = staticCompositionLocalOf { DarkCliPalette }

val CliBackground: Color @Composable get() = LocalCliPalette.current.background
val CliSurface: Color @Composable get() = LocalCliPalette.current.surface
val CliSurfaceElevated: Color @Composable get() = LocalCliPalette.current.surfaceElevated
val CliSurfaceActive: Color @Composable get() = LocalCliPalette.current.surfaceActive
val CliBorder: Color @Composable get() = LocalCliPalette.current.border
val CliBorderSubtle: Color @Composable get() = LocalCliPalette.current.borderSubtle
val CliBorderActive: Color @Composable get() = LocalCliPalette.current.borderActive
val CliBorderAccent: Color @Composable get() = LocalCliPalette.current.borderAccent
val CliTextPrimary: Color @Composable get() = LocalCliPalette.current.textPrimary
val CliTextSecondary: Color @Composable get() = LocalCliPalette.current.textSecondary
val CliTextTertiary: Color @Composable get() = LocalCliPalette.current.textTertiary
val CliAccent5GHz: Color @Composable get() = LocalCliPalette.current.accent5GHz
val CliAccent5GHzBg: Color @Composable get() = LocalCliPalette.current.accent5GHzBg
val CliAccent24GHz: Color @Composable get() = LocalCliPalette.current.accent24GHz
val CliAccent24GHzBg: Color @Composable get() = LocalCliPalette.current.accent24GHzBg
val CliAccentGreen: Color @Composable get() = LocalCliPalette.current.accentGreen
val CliAccentGreenBg: Color @Composable get() = LocalCliPalette.current.accentGreenBg
val CliAccentRed: Color @Composable get() = LocalCliPalette.current.accentRed
val CliAccentRedBg: Color @Composable get() = LocalCliPalette.current.accentRedBg
val CliButtonPrimary: Color @Composable get() = LocalCliPalette.current.buttonPrimary
val CliButtonPrimaryText: Color @Composable get() = LocalCliPalette.current.buttonPrimaryText
