package com.quintz.wifi.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.model.BandType
import com.quintz.wifi.telemetry.CandidateMeta
import com.quintz.wifi.telemetry.TelemetryGraphState
import com.quintz.wifi.telemetry.TelemetrySample
import com.quintz.wifi.ui.components.CliBadge
import com.quintz.wifi.ui.components.CliButton
import com.quintz.wifi.ui.components.CliButtonVariant
import com.quintz.wifi.ui.components.CliPanel
import com.quintz.wifi.ui.theme.*
import kotlin.math.max
import kotlin.math.min

// Candidate AP color palette
private val CandidatePalette = listOf(
    Color(0xFFA78BFA), // Violet
    Color(0xFFF59E0B), // Industrial Amber
    Color(0xFF34D399), // Emerald
    Color(0xFFF472B6), // Pink
    Color(0xFF60A5FA)  // Blue
)

@Composable
fun WifiGraphView(
    state: TelemetryGraphState,
    onTogglePause: () -> Unit,
    onClearHistory: () -> Unit,
    onSelectCandidate: (String?) -> Unit,
    onLockBssid: (String) -> Unit,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true
) {
    val textMeasurer = rememberTextMeasurer()

    CliPanel(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        containerColor = CliSurface,
        contentPadding = PaddingValues(16.dp)
    ) {
        // ── 1. Header Bar: Title, Live Telemetry Status & Controls ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "TACTICAL RF TELEMETRY",
                    style = CliTypography.TelemetryLabel
                )
                Text(
                    text = if (isConnected && state.activeSsid.isNotEmpty()) {
                        "${state.activeSsid} [${state.activeBssid.take(8)}...]"
                    } else if (isConnected) {
                        "STREAMING TELEMETRY..."
                    } else {
                        "OFFLINE — WI-FI DISCONNECTED"
                    },
                    style = CliTypography.CodeMono,
                    color = if (isConnected) CliTextSecondary else CliAccentRed
                )
            }

            // Pause / Resume & Clear Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CliButton(
                    onClick = onTogglePause,
                    text = if (state.isPaused) "LIVE" else "PAUSE",
                    variant = if (state.isPaused) CliButtonVariant.Primary else CliButtonVariant.Outlined,
                    leadingIcon = {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (state.isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )

                CliButton(
                    onClick = onClearHistory,
                    text = "CLEAR",
                    variant = CliButtonVariant.Outlined,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Clear History",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── 2. Metric Pills & Roaming Health Indicator ──
        if (isConnected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // RSSI Pill
                val rssiColor = when {
                    state.activeRssi >= -65 -> CliAccentGreen
                    state.activeRssi >= -75 -> CliAccent24GHz
                    else -> CliAccentRed
                }
                CliBadge(
                    text = "RSSI: ${state.activeRssi} dBm",
                    accentColor = rssiColor,
                    backgroundColor = rssiColor.copy(alpha = 0.12f),
                    borderColor = rssiColor.copy(alpha = 0.4f)
                )

                // Link Speed Pill
                if (state.activeLinkSpeedMbps > 0) {
                    CliBadge(
                        text = "LINK: ${state.activeLinkSpeedMbps} Mbps",
                        accentColor = CliAccent5GHz,
                        backgroundColor = CliAccent5GHzBg,
                        borderColor = CliAccent5GHz.copy(alpha = 0.4f)
                    )
                }

                // Min / Max in Window
                if (state.samples.isNotEmpty()) {
                    CliBadge(
                        text = "MIN: ${state.minRssi} / MAX: ${state.maxRssi} dBm",
                        accentColor = CliTextSecondary,
                        backgroundColor = CliSurfaceElevated,
                        borderColor = CliBorder
                    )
                }

                // Roam Status Pill
                if (state.roamAdvantageDbm >= 6) {
                    CliBadge(
                        text = "ROAM ADVANTAGE: +${state.roamAdvantageDbm} dBm",
                        accentColor = CliAccent24GHz,
                        backgroundColor = CliAccent24GHzBg,
                        borderColor = CliAccent24GHz.copy(alpha = 0.5f)
                    )
                } else if (state.samples.isNotEmpty()) {
                    CliBadge(
                        text = "OPTIMAL AP",
                        accentColor = CliAccentGreen,
                        backgroundColor = CliAccentGreenBg,
                        borderColor = CliAccentGreen.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // ── 3. Main Real-Time Oscilloscope Graph Canvas ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CliBackground)
                .border(1.dp, CliBorder, RoundedCornerShape(6.dp))
        ) {
            if (!isConnected) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "NO WI-FI TELEMETRY STREAM",
                            style = CliTypography.TelemetryValue,
                            color = CliTextTertiary
                        )
                        Text(
                            text = "Connect to an access point to monitor real-time RF signals.",
                            style = CliTypography.CodeMono,
                            color = CliTextTertiary
                        )
                    }
                }
            } else if (state.samples.size < 2) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "BUFFERING TELEMETRY STREAM (${state.samples.size}/2)...",
                        style = CliTypography.TelemetryLabel,
                        color = CliAccent5GHz
                    )
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    drawTelemetryGraph(
                        samples = state.samples,
                        activeBssid = state.activeBssid,
                        activeBand = state.activeBand,
                        selectedCandidateBssid = state.selectedCandidateBssid,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── 4. Candidate APs & Roaming Legend / Steering Card ──
        if (state.candidates.isNotEmpty()) {
            Text(
                text = "SAME-NETWORK CANDIDATE RADIOS (${state.candidates.size})",
                style = CliTypography.TelemetryLabel
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.candidates.forEach { candidate ->
                    CandidateApCard(
                        candidate = candidate,
                        activeRssi = state.activeRssi,
                        isSelected = state.selectedCandidateBssid == candidate.bssid,
                        onSelect = {
                            if (state.selectedCandidateBssid == candidate.bssid) {
                                onSelectCandidate(null)
                            } else {
                                onSelectCandidate(candidate.bssid)
                            }
                        },
                        onLock = { onLockBssid(candidate.bssid) }
                    )
                }
            }
        } else if (isConnected) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CliSurfaceElevated, RoundedCornerShape(4.dp))
                    .border(1.dp, CliBorderSubtle, RoundedCornerShape(4.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = "Single AP detected for this network. Roaming crossover monitoring will display once multiple BSSIDs (mesh or secondary bands) are in range.",
                    style = CliTypography.CodeMono,
                    color = CliTextTertiary
                )
            }
        }
    }
}

/**
 * Draws the real-time RF graph on Compose Canvas.
 */
private fun DrawScope.drawTelemetryGraph(
    samples: List<TelemetrySample>,
    activeBssid: String,
    activeBand: BandType,
    selectedCandidateBssid: String?,
    textMeasurer: TextMeasurer
) {
    val canvasWidth = size.width
    val canvasHeight = size.height

    // Grid dBm bounds: -30 dBm at top, -95 dBm at bottom
    val minDbm = -95f
    val maxDbm = -30f
    val dbmRange = maxDbm - minDbm

    // Coordinate helper: maps dBm to Y-coordinate
    fun dbmToY(dbm: Float): Float {
        val clamped = dbm.coerceIn(minDbm, maxDbm)
        val normalized = (clamped - minDbm) / dbmRange
        return canvasHeight * (1f - normalized)
    }

    // Coordinate helper: maps sample index to X-coordinate
    val totalSlots = 60 // Fixed time slot window
    val sampleCount = samples.size
    fun indexToX(index: Int): Float {
        val offset = (totalSlots - sampleCount).coerceAtLeast(0)
        val pos = offset + index
        return (pos.toFloat() / (totalSlots - 1).toFloat()) * canvasWidth
    }

    // 1. Draw Background Quality Zones
    val yMinus65 = dbmToY(-65f)
    val yMinus75 = dbmToY(-75f)

    // Green zone (-30 to -65 dBm)
    drawRect(
        color = CliAccentGreen.copy(alpha = 0.04f),
        topLeft = Offset(0f, 0f),
        size = Size(canvasWidth, yMinus65)
    )
    // Amber zone (-65 to -75 dBm)
    drawRect(
        color = CliAccent24GHz.copy(alpha = 0.04f),
        topLeft = Offset(0f, yMinus65),
        size = Size(canvasWidth, yMinus75 - yMinus65)
    )
    // Red zone (-75 to -95 dBm)
    drawRect(
        color = CliAccentRed.copy(alpha = 0.04f),
        topLeft = Offset(0f, yMinus75),
        size = Size(canvasWidth, canvasHeight - yMinus75)
    )

    // 2. Draw Horizontal Gridlines & dBm Labels
    val gridDbms = listOf(-40f, -50f, -65f, -75f, -90f)
    val labelStyle = TextStyle(
        color = CliTextTertiary.copy(alpha = 0.7f),
        fontSize = 9.sp,
        fontFamily = FontFamily.Monospace
    )

    gridDbms.forEach { dbm ->
        val y = dbmToY(dbm)
        val isThreshold = dbm == -65f || dbm == -75f
        val lineColor = if (isThreshold) {
            if (dbm == -65f) CliAccentGreen.copy(alpha = 0.35f) else CliAccent24GHz.copy(alpha = 0.35f)
        } else {
            CliBorderSubtle
        }
        val strokeWidth = if (isThreshold) 1.5f else 1f

        drawLine(
            color = lineColor,
            start = Offset(0f, y),
            end = Offset(canvasWidth, y),
            strokeWidth = strokeWidth,
            pathEffect = if (!isThreshold) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null
        )

        val label = if (dbm == -65f) "-65 [GOOD]" else if (dbm == -75f) "-75 [ROAM]" else "${dbm.toInt()} dBm"
        val measured = textMeasurer.measure(label, labelStyle)
        drawText(
            textMeasurer = textMeasurer,
            text = label,
            topLeft = Offset(6f, y - measured.size.height - 2f),
            style = labelStyle
        )
    }

    // 3. Draw Candidate AP Lines (Background/Secondary)
    val candidateBssids = samples.flatMap { it.candidates.keys }.toSet()
    candidateBssids.forEachIndexed { candIndex, candBssid ->
        val isHighlighted = selectedCandidateBssid == null || selectedCandidateBssid == candBssid
        val candColor = CandidatePalette[candIndex % CandidatePalette.size]
        val alpha = if (isHighlighted) 0.65f else 0.15f
        val strokeWidth = if (selectedCandidateBssid == candBssid) 2.5f else 1.5f

        val candPath = Path()
        var hasStarted = false

        for (i in samples.indices) {
            val cand = samples[i].candidates[candBssid]
            if (cand != null && cand.rssi in -110..-20) {
                val x = indexToX(i)
                val y = dbmToY(cand.rssi.toFloat())
                if (!hasStarted) {
                    candPath.moveTo(x, y)
                    hasStarted = true
                } else {
                    candPath.lineTo(x, y)
                }
            } else {
                hasStarted = false
            }
        }

        drawPath(
            path = candPath,
            color = candColor.copy(alpha = alpha),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
        )
    }

    // 4. Draw Roam Event Vertical Markers
    samples.forEachIndexed { idx, sample ->
        sample.roamEvent?.let { roam ->
            val x = indexToX(idx)
            drawLine(
                color = CliAccent5GHz,
                start = Offset(x, 0f),
                end = Offset(x, canvasHeight),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )

            val tag = "ROAM [${roam.fromBand.displayName} → ${roam.toBand.displayName}]"
            val measuredTag = textMeasurer.measure(
                tag,
                TextStyle(color = CliAccent5GHz, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = tag,
                topLeft = Offset(min(x + 4f, canvasWidth - measuredTag.size.width - 4f), 8f),
                style = TextStyle(color = CliAccent5GHz, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            )
        }
    }

    // 5. Draw Active AP Primary Curve & Gradient Fill
    val activeColor = if (activeBand == BandType.BAND_5_GHZ || activeBand == BandType.BAND_6_GHZ) {
        CliAccent5GHz
    } else {
        CliAccent24GHz
    }

    val activePath = Path()
    val fillPath = Path()
    var activeStarted = false
    var lastX = 0f
    var lastY = 0f

    for (i in samples.indices) {
        val rssi = samples[i].activeRssi
        if (rssi in -110..-20) {
            val x = indexToX(i)
            val y = dbmToY(rssi.toFloat())
            if (!activeStarted) {
                activePath.moveTo(x, y)
                fillPath.moveTo(x, canvasHeight)
                fillPath.lineTo(x, y)
                activeStarted = true
            } else {
                activePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
    }

    if (activeStarted) {
        fillPath.lineTo(lastX, canvasHeight)
        fillPath.close()

        // Subtle glowing gradient fill under curve
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(activeColor.copy(alpha = 0.22f), activeColor.copy(alpha = 0.02f)),
                startY = dbmToY(-40f),
                endY = canvasHeight
            )
        )

        // Bold active line
        drawPath(
            path = activePath,
            color = activeColor,
            style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Current point pulse circle
        drawCircle(
            color = activeColor,
            radius = 5.5f,
            center = Offset(lastX, lastY)
        )
        drawCircle(
            color = Color.White,
            radius = 2.5f,
            center = Offset(lastX, lastY)
        )
    }

    // 6. Draw Bottom Time Labels (e.g. -60s, -30s, NOW)
    val timeLabels = listOf("-60s" to 0f, "-30s" to 0.5f, "NOW" to 1f)
    timeLabels.forEach { (text, fraction) ->
        val x = fraction * canvasWidth
        val measured = textMeasurer.measure(text, labelStyle)
        val drawX = (x - measured.size.width / 2f).coerceIn(4f, canvasWidth - measured.size.width - 4f)
        drawText(
            textMeasurer = textMeasurer,
            text = text,
            topLeft = Offset(drawX, canvasHeight - measured.size.height - 2f),
            style = labelStyle
        )
    }
}

/**
 * Interactive card displaying a candidate AP under the same network.
 */
@Composable
private fun CandidateApCard(
    candidate: CandidateMeta,
    activeRssi: Int,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLock: () -> Unit
) {
    val delta = candidate.latestRssi - activeRssi
    val candColor = CandidatePalette[candidate.colorIndex % CandidatePalette.size]

    val deltaText = if (delta > 0) "+$delta dBm" else "$delta dBm"
    val deltaColor = if (delta >= 6) CliAccentGreen else if (delta > 0) CliAccent24GHz else CliTextTertiary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) CliSurfaceActive else CliSurfaceElevated)
            .border(
                1.dp,
                if (isSelected) candColor else CliBorderSubtle,
                RoundedCornerShape(4.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color marker
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(candColor)
            )

            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = candidate.bssid,
                        style = CliTypography.BadgeText,
                        color = CliTextPrimary
                    )
                    Text(
                        text = "[${candidate.band.displayName} · Ch ${candidate.channel}]",
                        style = CliTypography.CodeMono,
                        color = CliTextSecondary
                    )
                }

                Text(
                    text = "Signal: ${candidate.latestRssi} dBm (Advantage: $deltaText)",
                    style = CliTypography.CodeMono,
                    color = deltaColor
                )
            }
        }

        // Lock button
        CliButton(
            onClick = onLock,
            text = "LOCK",
            variant = if (delta >= 6) CliButtonVariant.Primary else CliButtonVariant.Outlined,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock to BSSID",
                    modifier = Modifier.size(12.dp)
                )
            }
        )
    }
}
