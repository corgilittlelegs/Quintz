package com.quintz.wifi.ui.radar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.radar.RadarEngine
import com.quintz.wifi.radar.RadarState
import com.quintz.wifi.ui.components.*
import com.quintz.wifi.ui.theme.*

@Composable
fun WifiRadarView(
    radarEngine: RadarEngine,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true,
    isShizukuReady: Boolean = true,
    onOpenShizuku: (() -> Unit)? = null
) {
    DisposableEffect(radarEngine) {
        radarEngine.start()
        onDispose { radarEngine.stop() }
    }

    val radarState = radarEngine.radarState
    var showExplanation by rememberSaveable { mutableStateOf(false) }

    val screenHeight = LocalConfiguration.current.screenHeightDp
    val isCompactHeight = screenHeight < 500
    val canvasHeight = if (isCompactHeight) 200.dp else 310.dp

    val hasValidRssi = radarState.rssi in -110..-20
    val rssiText = if (hasValidRssi) {
        "${radarState.rssi} / ${if (radarState.peakRssi in -110..-20) "${radarState.peakRssi} dBm" else "--"}"
    } else {
        "-- / -- dBm"
    }
    val rssiColor = when {
        !hasValidRssi -> CliTextTertiary
        radarState.rssi >= -65 -> CliAccentGreen
        radarState.rssi >= -78 -> CliAccent5GHz
        else -> CliAccent24GHz
    }

    CliPanel(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        containerColor = CliSurface,
        contentPadding = PaddingValues(16.dp)
    ) {
        // ── 1. Header Bar: Title, Target, Status Badge & Guide Button ──
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isNarrow = maxWidth < 540.dp

            if (isNarrow) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TACTICAL WI-FI RADAR",
                            style = CliTypography.TelemetryLabel
                        )

                        VectorStatusBadge(radarState, isConnected, isShizukuReady)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = when {
                                    !isConnected -> "Wi-Fi Disconnected"
                                    radarState.targetSsid.isNotEmpty() -> radarState.targetSsid
                                    !isShizukuReady -> "Wi-Fi Connected"
                                    else -> "Target Scanning..."
                                },
                                style = Typography.titleMedium,
                                color = CliTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (radarState.targetBssid.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "[${radarState.targetBssid}]",
                                    style = CliTypography.CodeMono,
                                    color = CliTextTertiary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        GuideToggleButton(
                            showExplanation = showExplanation,
                            onToggle = { showExplanation = !showExplanation }
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TACTICAL WI-FI RADAR",
                            style = CliTypography.TelemetryLabel
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    !isConnected -> "Wi-Fi Disconnected"
                                    radarState.targetSsid.isNotEmpty() -> radarState.targetSsid
                                    !isShizukuReady -> "Wi-Fi Connected"
                                    else -> "Target Scanning..."
                                },
                                style = Typography.titleMedium,
                                color = CliTextPrimary
                            )
                            if (radarState.targetBssid.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "[${radarState.targetBssid}]",
                                    style = CliTypography.CodeMono,
                                    color = CliTextTertiary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GuideToggleButton(
                            showExplanation = showExplanation,
                            onToggle = { showExplanation = !showExplanation }
                        )

                        VectorStatusBadge(radarState, isConnected, isShizukuReady)
                    }
                }
            }
        }

        // ── 2. Collapsible "How It Works" Visual Diagram & Guide Card ──
        AnimatedVisibility(
            visible = showExplanation,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            RadarCalibrationDiagram(
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── 3. Radar Guidance Action Banner ──
        val bannerBg = when {
            !isConnected -> CliSurfaceElevated
            !isShizukuReady -> CliAccent24GHzBg
            radarState.isAlignedAhead -> CliAccentGreenBg
            radarState.isCalibrated -> CliSurfaceElevated
            else -> CliAccent24GHzBg
        }
        val bannerBorder = when {
            !isConnected -> CliBorderSubtle
            !isShizukuReady -> CliAccent24GHz.copy(alpha = 0.5f)
            radarState.isAlignedAhead -> CliAccentGreen.copy(alpha = 0.5f)
            radarState.isCalibrated -> CliAccent5GHz.copy(alpha = 0.4f)
            else -> CliAccent24GHz.copy(alpha = 0.4f)
        }
        val bannerText = when {
            !isConnected -> "NO WI-FI CONNECTION — CONNECT TO A NETWORK TO ENABLE RADAR"
            !isShizukuReady -> "SHIZUKU DAEMON STOPPED — START SHIZUKU FOR BSSID & BEARING TELEMETRY"
            radarState.rssi == 0 -> "WAITING FOR RSSI SIGNAL TELEMETRY..."
            !radarState.isCalibrated -> "CALIBRATION IN PROGRESS: ROTATE DEVICE 360° SLOWLY (${radarState.visitedSectors.size}/36 SECTORS)"
            radarState.isAlignedAhead -> "TARGET LOCKED DIRECTLY AHEAD — WALK FORWARD"
            else -> "ACTION: ${radarState.turnRecommendation} TO FACE ROUTER"
        }
        val bannerTextColor = when {
            !isConnected -> CliTextSecondary
            !isShizukuReady -> CliAccent24GHz
            radarState.isAlignedAhead -> CliAccentGreen
            radarState.isCalibrated -> CliAccent5GHz
            else -> CliAccent24GHz
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(bannerBg)
                .border(1.dp, bannerBorder, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (isConnected && isShizukuReady && radarState.isCalibrated && !radarState.isAlignedAhead) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = "Turn Indicator",
                        tint = bannerTextColor,
                        modifier = Modifier
                            .size(15.dp)
                            .rotate(radarState.relativeAngle)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = bannerText,
                    style = CliTypography.CodeMono,
                    color = bannerTextColor,
                    fontSize = 11.5.sp
                )
            }

            if (!isShizukuReady && onOpenShizuku != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "OPEN ↗",
                    style = CliTypography.BadgeText,
                    color = CliAccent24GHz,
                    modifier = Modifier
                        .clickable { onOpenShizuku() }
                        .padding(4.dp)
                )
            } else if (isConnected && isShizukuReady && hasValidRssi && !radarState.isCalibrated) {
                Text(
                    text = "${radarState.calibrationPercent}%",
                    style = CliTypography.CodeMono,
                    color = bannerTextColor,
                    fontSize = 12.sp
                )
            }
        }

        if (isConnected && isShizukuReady && hasValidRssi && !radarState.isCalibrated) {
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { radarState.calibrationPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp)),
                color = CliAccent24GHz,
                trackColor = CliSurfaceElevated
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── 4. Radar Canvas Scope ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(CliBackground)
                .border(1.dp, CliBorderSubtle, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            WifiRadarCanvas(
                radarState = radarState,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── 5. Telemetry Metric Readout Panel ──
        CliPanel(
            borderColor = CliBorderSubtle,
            containerColor = CliSurfaceElevated,
            contentPadding = PaddingValues(12.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isNarrow = maxWidth < 460.dp

                if (isNarrow) {
                    // 2x2 Grid for Narrow Screens & Phones
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "CURRENT FACING", style = CliTypography.TelemetryLabel)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = RadarEngine.formatBearingCompass(radarState.currentHeading),
                                    style = CliTypography.TelemetryValue
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "ROUTER BEARING", style = CliTypography.TelemetryLabel)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when {
                                        !isConnected -> "--"
                                        radarState.isCalibrated -> RadarEngine.formatBearingCompass(radarState.targetBearing)
                                        !isShizukuReady -> "PAUSED"
                                        else -> "MAPPING..."
                                    },
                                    style = CliTypography.TelemetryValue,
                                    color = if (radarState.isAlignedAhead) CliAccentGreen else if (radarState.isCalibrated) CliAccent5GHz else CliTextTertiary
                                )
                            }
                        }

                        CliDivider(color = CliBorderSubtle)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "EST. DISTANCE", style = CliTypography.TelemetryLabel)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isConnected && radarState.distanceMeters > 0) "~${radarState.distanceMeters} m" else "N/A",
                                    style = CliTypography.TelemetryValue
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "LIVE / PEAK RSSI", style = CliTypography.TelemetryLabel)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = rssiText,
                                    style = CliTypography.TelemetryValue,
                                    color = rssiColor
                                )
                            }
                        }
                    }
                } else {
                    // 4-Column Row for Tablets / Landscape
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "CURRENT FACING", style = CliTypography.TelemetryLabel)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = RadarEngine.formatBearingCompass(radarState.currentHeading),
                                style = CliTypography.TelemetryValue
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "ROUTER BEARING", style = CliTypography.TelemetryLabel)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    !isConnected -> "--"
                                    radarState.isCalibrated -> RadarEngine.formatBearingCompass(radarState.targetBearing)
                                    !isShizukuReady -> "PAUSED"
                                    else -> "MAPPING..."
                                },
                                style = CliTypography.TelemetryValue,
                                color = if (radarState.isAlignedAhead) CliAccentGreen else if (radarState.isCalibrated) CliAccent5GHz else CliTextTertiary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "EST. DISTANCE", style = CliTypography.TelemetryLabel)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isConnected && radarState.distanceMeters > 0) "~${radarState.distanceMeters} m" else "N/A",
                                style = CliTypography.TelemetryValue
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "LIVE / PEAK RSSI", style = CliTypography.TelemetryLabel)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = rssiText,
                                style = CliTypography.TelemetryValue,
                                color = rssiColor
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ── 6. Controls Row ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CliButton(
                text = "RE-CALIBRATE BEARING",
                variant = CliButtonVariant.Outlined,
                enabled = isConnected && isShizukuReady && hasValidRssi,
                onClick = onResetCalibration,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun GuideToggleButton(
    showExplanation: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (showExplanation) CliSurfaceActive else CliSurfaceElevated)
            .border(1.dp, if (showExplanation) CliAccent5GHz else CliBorder, RoundedCornerShape(4.dp))
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Radar Guide",
                tint = if (showExplanation) CliAccent5GHz else CliTextSecondary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (showExplanation) "HIDE GUIDE" else "HOW IT WORKS",
                style = CliTypography.BadgeText,
                color = if (showExplanation) CliAccent5GHz else CliTextSecondary
            )
        }
    }
}

@Composable
private fun VectorStatusBadge(
    radarState: RadarState,
    isConnected: Boolean = true,
    isShizukuReady: Boolean = true
) {
    when {
        !isConnected -> {
            CliBadge(
                text = "DISCONNECTED",
                accentColor = CliTextTertiary,
                backgroundColor = CliSurfaceElevated,
                borderColor = CliBorderSubtle
            )
        }
        !isShizukuReady -> {
            CliBadge(
                text = "SHIZUKU OFF",
                accentColor = CliAccent24GHz,
                backgroundColor = CliAccent24GHzBg,
                borderColor = CliAccent24GHz.copy(alpha = 0.5f)
            )
        }
        radarState.isAlignedAhead -> {
            CliBadge(
                text = "LOCKED AHEAD",
                accentColor = CliAccentGreen,
                backgroundColor = CliAccentGreenBg,
                borderColor = CliAccentGreen.copy(alpha = 0.5f)
            )
        }
        radarState.isCalibrated -> {
            CliBadge(
                text = "VECTOR ACQUIRED",
                accentColor = CliAccent5GHz,
                backgroundColor = CliAccent5GHzBg,
                borderColor = CliAccent5GHz.copy(alpha = 0.5f)
            )
        }
        radarState.rssi == 0 -> {
            CliBadge(
                text = "AWAITING SIGNAL",
                accentColor = CliAccent24GHz,
                backgroundColor = CliAccent24GHzBg,
                borderColor = CliAccent24GHz.copy(alpha = 0.5f)
            )
        }
        else -> {
            CliBadge(
                text = "CALIBRATING (${radarState.calibrationPercent}%)",
                accentColor = CliAccent24GHz,
                backgroundColor = CliAccent24GHzBg,
                borderColor = CliAccent24GHz.copy(alpha = 0.5f)
            )
        }
    }
}
