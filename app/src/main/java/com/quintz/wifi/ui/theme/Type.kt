package com.quintz.wifi.ui.theme

import androidx.compose.material3.Typography as MaterialTypography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.quintz.wifi.R

val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

val InterFont = GoogleFont("Inter")
val JetBrainsMonoFont = GoogleFont("JetBrains Mono")

val InterFamily = FontFamily(
    Font(googleFont = InterFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = InterFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = InterFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = InterFont, fontProvider = fontProvider, weight = FontWeight.Bold)
)

val JetBrainsMonoFamily = FontFamily(
    Font(googleFont = JetBrainsMonoFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = JetBrainsMonoFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = JetBrainsMonoFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = JetBrainsMonoFont, fontProvider = fontProvider, weight = FontWeight.Bold)
)

val Typography: MaterialTypography
    @Composable get() {
        val palette = LocalCliPalette.current
        return remember(palette) {
            MaterialTypography(
                headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold,
                    fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp, color = palette.textPrimary),
                headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Bold,
                    fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp, color = palette.textPrimary),
                titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp, color = palette.textPrimary),
                titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium,
                    fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = palette.textPrimary),
                bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal,
                    fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp, color = palette.textSecondary),
                bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal,
                    fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp, color = palette.textSecondary),
                labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp, color = palette.textPrimary),
                labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, color = palette.textTertiary),
                labelSmall = TextStyle(fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium,
                    fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.5.sp, color = palette.textTertiary)
            )
        }
    }

object CliTypography {
    val TerminalPrompt: TextStyle @Composable get() = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.5.sp, color = CliTextSecondary
    )
    val TelemetryValue: TextStyle @Composable get() = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp, color = CliTextPrimary
    )
    val TelemetryLabel: TextStyle @Composable get() = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Bold,
        fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.8.sp, color = CliTextTertiary
    )
    val CodeMono: TextStyle @Composable get() = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp, color = CliTextSecondary
    )
    val BadgeText: TextStyle @Composable get() = TextStyle(
        fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp, color = CliTextPrimary
    )
}
