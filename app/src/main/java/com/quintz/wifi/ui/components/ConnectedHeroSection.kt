package com.quintz.wifi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.ui.theme.*

@Composable
fun CliConnectedHeroPanel(
    status: WifiStatus,
    isOperating: Boolean,
    recoveryThresholdRssi: Int,
    onToggleLock: () -> Unit,
    onOpenGraph: (() -> Unit)? = null,
    onGetMacPolicy: ((String) -> MacAddressPolicy?)? = null,
    onToggleMacPolicy: ((String) -> Unit)? = null,
    isWatchdogActive: Boolean = false,
    batteryOptimizationExempt: Boolean? = null,
    onBatterySettings: (() -> Unit)? = null
) {
    val is5G = status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
    val isLocked = status.isSteeredOrLocked
    val lockAccent = when {
        status.isPreferred5GHzFallback -> CliAccent24GHz
        is5G -> CliAccent5GHz
        else -> CliAccent24GHz
    }

    val borderColor by animateColorAsState(
        targetValue = if (isLocked) lockAccent.copy(alpha = 0.6f) else CliBorder,
        label = "heroBorder"
    )

    CliPanel(
        borderColor = borderColor,
        containerColor = CliSurface,
        contentPadding = PaddingValues(18.dp)
    ) {
        // Section Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CURRENTLY CONNECTED",
                style = CliTypography.TelemetryLabel
            )

            if (status.isConnected) {
                val bandColor = if (is5G) CliAccent5GHz else CliAccent24GHz
                val bandBg = if (is5G) CliAccent5GHzBg else CliAccent24GHzBg
                CliBadge(
                    text = if (status.band != BandType.UNKNOWN) status.band.displayName else "WI-FI",
                    accentColor = bandColor,
                    backgroundColor = bandBg,
                    borderColor = bandColor.copy(alpha = 0.4f)
                )
            } else if (isOperating) {
                CliBadge(
                    text = "STEERING...",
                    accentColor = CliAccent5GHz,
                    backgroundColor = CliAccent5GHzBg,
                    borderColor = CliAccent5GHz.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SSID Title
        Text(
            text = if (status.isConnected && status.ssid.isNotEmpty()) status.ssid
                   else if (status.isConnected) "Wi-Fi Connected"
                   else if (isOperating) "Negotiating Band..."
                   else "Not Connected",
            style = Typography.headlineMedium,
            color = CliTextPrimary
        )

        Text(
            text = if (status.isConnected && status.ipAddress.isNotEmpty()) "IP: ${status.ipAddress}"
                   else if (status.isConnected) "Connected • Wi-Fi details unavailable"
                   else if (isOperating) "Binding to target AP & verifying DHCP..."
                   else "Connect to Wi-Fi",
            style = CliTypography.CodeMono,
            color = CliTextTertiary
        )

        if (status.isConnected && status.nativeIdentityLimited) {
            val hiddenFields = listOfNotNull(
                "SSID".takeIf { status.ssid.isEmpty() },
                "BSSID".takeIf { status.bssid.isEmpty() }
            ).joinToString("/")
            Text(
                text = "Basic status • $hiddenFields not provided by Android",
                style = CliTypography.CodeMono,
                color = CliTextTertiary
            )
        }

        if (isOperating) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = CliAccent5GHz,
                trackColor = CliSurfaceElevated
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Telemetry Grid
        if (status.isConnected) {
            CliPanel(
                borderColor = CliBorderSubtle,
                containerColor = CliSurfaceElevated,
                contentPadding = PaddingValues(12.dp),
                shape = RoundedCornerShape(4.dp)
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isNarrow = maxWidth < 460.dp

                    if (isNarrow) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "SIGNAL",
                                        content = { CliSignalBars(status.rssi, showDbmText = true) }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "LINK SPEED",
                                        value = "${status.linkSpeedMbps} Mbps"
                                    )
                                }
                            }

                            CliDivider(color = CliBorderSubtle)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "CHANNEL",
                                        value = if (status.frequency > 0) {
                                            "Ch ${AccessPointRadio.frequencyToChannel(status.frequency)} (${status.frequency} MHz)"
                                        } else "--"
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "STANDARD",
                                        value = status.standard.uppercase().ifEmpty { "Wi-Fi" }
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            CliTelemetryMetric(
                                label = "SIGNAL",
                                content = { CliSignalBars(status.rssi, showDbmText = true) }
                            )
                            CliTelemetryMetric(
                                label = "LINK SPEED",
                                value = "${status.linkSpeedMbps} Mbps"
                            )
                            CliTelemetryMetric(
                                label = "CHANNEL",
                                value = if (status.frequency > 0) {
                                    "Ch ${AccessPointRadio.frequencyToChannel(status.frequency)} (${status.frequency} MHz)"
                                } else "--"
                            )
                            CliTelemetryMetric(
                                label = "STANDARD",
                                value = status.standard.uppercase().ifEmpty { "Wi-Fi" }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // BSSID Lock & Routing Card
            CliPanel(
                borderColor = CliBorderSubtle,
                containerColor = CliSurfaceElevated,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(4.dp)
            ) {
                // Top Row: Lock/Steer Status (Left) + Graph Quick-Launch (Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        val badgeText = when {
                            status.isLockedToBssid -> "LOCKED TO BSSID"
                            status.isPreferred5GHz -> "PREFERRED 5 GHz (ROAM ALLOWED)"
                            status.isPreferred5GHzFallback -> "PREFERRED 5 GHz (FALLBACK ACTIVE)"
                            else -> "ROUTER AUTO-STEER"
                        }
                        Icon(
                            imageVector = if (status.isLockedToBssid || status.isPreferred5GHz || status.isPreferred5GHzFallback) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (status.isSteeredOrLocked) lockAccent else CliTextTertiary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = badgeText,
                            style = CliTypography.BadgeText,
                            color = if (status.isSteeredOrLocked) lockAccent else CliTextSecondary
                        )
                    }

                    if (onOpenGraph != null && status.isConnected) {
                        CliBadge(
                            text = "GRAPH ↗",
                            accentColor = CliAccent5GHz,
                            backgroundColor = CliAccent5GHzBg,
                            borderColor = CliAccent5GHz.copy(alpha = 0.5f),
                            modifier = Modifier.clickable { onOpenGraph() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (status.isPreferred5GHz || status.isPreferred5GHzFallback) {
                    Text(
                        text = "5 GHz RECOVERY ≥ $recoveryThresholdRssi dBm",
                        style = CliTypography.CodeMono,
                        color = CliTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                CliDivider(color = CliBorderSubtle)
                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Row: Active BSSID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACTIVE BSSID",
                        style = CliTypography.TelemetryLabel
                    )

                    Text(
                        text = if (status.bssid.isNotEmpty()) status.bssid else "Hidden (limited native status)",
                        style = CliTypography.CodeMono,
                        color = if (status.bssid.isNotEmpty()) CliTextSecondary else CliTextTertiary,
                        fontSize = 11.5.sp
                    )
                }

                if (status.isConnected && status.ssid.isNotEmpty() && onGetMacPolicy != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CliDivider(color = CliBorderSubtle)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MAC IDENTITY",
                            style = CliTypography.TelemetryLabel
                        )

                        val currentPolicy = onGetMacPolicy(status.ssid) ?: MacAddressPolicy.DEVICE
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (currentPolicy == MacAddressPolicy.DEVICE) CliAccent5GHzBg else CliSurface)
                                .border(1.dp, if (currentPolicy == MacAddressPolicy.DEVICE) CliAccent5GHz.copy(alpha = 0.4f) else CliBorder, RoundedCornerShape(4.dp))
                                .clickable { onToggleMacPolicy?.invoke(status.ssid) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "${currentPolicy.displayName.uppercase()} ⇄",
                                style = CliTypography.BadgeText,
                                color = if (currentPolicy == MacAddressPolicy.DEVICE) CliAccent5GHz else CliTextSecondary
                            )
                        }
                    }
                }

                if (status.isSteeredOrLocked || isWatchdogActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CliDivider(color = CliBorderSubtle)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WATCHDOG",
                            style = CliTypography.TelemetryLabel
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isWatchdogActive && status.isPreferred5GHzFallback) CliAccent24GHz else if (isWatchdogActive) CliAccentGreen else CliTextTertiary)
                            )
                            Text(
                                text = when {
                                    !isWatchdogActive -> "OFF"
                                    status.isPreferred5GHzFallback -> "ON · 5 GHz recovery pending"
                                    status.isLockedToBssid -> "ON · monitoring pin"
                                    status.isPreferred5GHz -> "ON · monitoring 5 GHz"
                                    else -> "ON · Auto on this network"
                                },
                                style = CliTypography.CodeMono,
                                color = if (isWatchdogActive && status.isPreferred5GHzFallback) CliAccent24GHz else if (isWatchdogActive) CliAccentGreen else CliTextTertiary,
                                fontSize = 11.5.sp
                            )
                        }
                    }

                    if (isWatchdogActive && batteryOptimizationExempt == false) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                    onBatterySettings?.invoke()
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "BATTERY OPTIMIZED · CONFIGURE",
                                style = CliTypography.CodeMono,
                                color = CliAccent24GHz
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Command Button
            CliButton(
                onClick = onToggleLock,
                enabled = !isOperating,
                loading = isOperating,
                variant = if (isLocked) CliButtonVariant.Outlined else CliButtonVariant.Primary,
                text = if (isOperating) "SWITCHING BAND..." else if (isLocked) "UNLOCK TO AUTO-ROAM" else "PREFER 5 GHz (ROAM ALLOWED)",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CliTelemetryMetric(
    label: String,
    value: String? = null,
    content: (@Composable () -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = CliTypography.TelemetryLabel
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (content != null) {
            content()
        } else if (value != null) {
            Text(
                text = value,
                style = CliTypography.TelemetryValue
            )
        }
    }
}


