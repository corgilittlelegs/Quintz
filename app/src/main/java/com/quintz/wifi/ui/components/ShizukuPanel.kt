package com.quintz.wifi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.ui.theme.*

@Composable
fun CliShizukuPanel(
    shizukuState: ShizukuState,
    onOpenShizuku: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val isAlert = !shizukuState.isInstalled
    val isWarning = !shizukuState.isPermissionGranted
    val (cardBorderColor, cardBgColor) = when {
        isAlert -> Pair(CliAccentRed.copy(alpha = 0.5f), CliAccentRedBg)
        isWarning -> Pair(CliAccent24GHz.copy(alpha = 0.5f), CliAccent24GHzBg)
        else -> Pair(CliBorder, CliSurface)
    }

    CliPanel(
        containerColor = cardBgColor,
        borderColor = cardBorderColor,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SHIZUKU PRIVILEGED SERVICE",
                    style = CliTypography.TelemetryLabel
                )
                Spacer(modifier = Modifier.height(4.dp))
                when {
                    shizukuState.isPermissionGranted -> {
                        CliBadge(
                            text = if (shizukuState.version > 0) "ACTIVE v${shizukuState.version}" else "ACTIVE",
                            accentColor = CliAccentGreen,
                            backgroundColor = CliAccentGreenBg,
                            borderColor = CliAccentGreen.copy(alpha = 0.5f)
                        )
                    }
                    shizukuState.isRunning -> {
                        CliBadge(
                            text = "AUTH REQUIRED",
                            accentColor = CliAccent24GHz,
                            backgroundColor = CliAccent24GHzBg,
                            borderColor = CliAccent24GHz.copy(alpha = 0.5f)
                        )
                    }
                    shizukuState.isInstalled -> {
                        CliBadge(
                            text = "DAEMON STOPPED",
                            accentColor = CliAccent24GHz,
                            backgroundColor = CliAccent24GHzBg,
                            borderColor = CliAccent24GHz.copy(alpha = 0.5f)
                        )
                    }
                    else -> {
                        CliBadge(
                            text = "NOT INSTALLED",
                            accentColor = CliAccentRed,
                            backgroundColor = CliAccentRedBg,
                            borderColor = CliAccentRed.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        shizukuState.isPermissionGranted ->
                            "Privileged API active. Quintz executes low-level Wi-Fi steering and BSSID binding without root."
                        shizukuState.isRunning ->
                            "Daemon is running. Authorize Quintz to unlock BSSID binding and RF telemetry."
                        shizukuState.isInstalled ->
                            "App installed, but service daemon is stopped. Start via Wireless Debugging in Shizuku."
                        else ->
                            "Shizuku service is required to bypass Android network restrictions and bind BSSIDs."
                    },
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            when {
                !shizukuState.isInstalled -> {
                    CliButton(
                        text = "GET SHIZUKU ↗",
                        size = CliButtonSize.Compact,
                        variant = CliButtonVariant.Primary,
                        onClick = onOpenPlayStore
                    )
                }
                !shizukuState.isRunning -> {
                    CliButton(
                        text = "OPEN SHIZUKU ↗",
                        size = CliButtonSize.Compact,
                        variant = CliButtonVariant.Primary,
                        onClick = onOpenShizuku
                    )
                }
                !shizukuState.isPermissionGranted -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CliButton(
                            text = "OPEN ↗",
                            size = CliButtonSize.Compact,
                            variant = CliButtonVariant.Outlined,
                            onClick = onOpenShizuku
                        )
                        CliButton(
                            text = "GRANT",
                            size = CliButtonSize.Compact,
                            variant = CliButtonVariant.Primary,
                            onClick = onRequestPermission
                        )
                    }
                }
                else -> {
                    CliButton(
                        text = "OPEN APP ↗",
                        size = CliButtonSize.Compact,
                        variant = CliButtonVariant.Outlined,
                        onClick = onOpenShizuku
                    )
                }
            }
        }
    }
}

