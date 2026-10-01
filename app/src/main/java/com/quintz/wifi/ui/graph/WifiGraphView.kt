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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
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
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min

// Candidate AP color palette
private val DarkCandidatePalette = listOf(
    Color(0xFFA78BFA), // Violet
    Color(0xFFF59E0B), // Industrial Amber
    Color(0xFF34D399), // Emerald
    Color(0xFFF472B6), // Pink
    Color(0xFF60A5FA)  // Blue
)

private val LightCandidatePalette = listOf(
    Color(0xFF6D28D9), // Violet
    Color(0xFF92400E), // Amber
    Color(0xFF047857), // Emerald
    Color(0xFFBE185D), // Pink
    Color(0xFF1D4ED8)  // Blue
)

private fun candidateColor(index: Int, palette: CliPalette): Color {
    val colors = if (palette == LightCliPalette) LightCandidatePalette else DarkCandidatePalette
    return colors[index % colors.size]
}

private data class GraphSeries(
    val visibleSamples: List<TelemetrySample>,
    val candidates: List<Pair<String, List<com.quintz.wifi.telemetry.CandidateSample>>>
)

private data class GraphPaths(
    val candidatePaths: List<Path>,
    val activePath: Path,
    val fillPath: Path,
    val lastPoint: Offset?,
    val lastStatusTimestamp: Long
)

private fun buildGraphPaths(series: GraphSeries, now: Long, width: Float, height: Float): GraphPaths {
    val start = now - 60_000L
    fun x(timestamp: Long) = ((timestamp - start).toFloat() / 60_000f).coerceIn(0f, 1f) * width
    fun y(rssi: Int) = height * (1f - (rssi.toFloat().coerceIn(-95f, -30f) + 95f) / 65f)
    val candidatePaths = series.candidates.map { (_, readings) ->
        Path().apply {
            var previous = 0L
            readings.forEach { reading ->
                if (reading.rssi in -110..-20) {
                    if (previous == 0L || reading.observedAtMillis - previous > 20_000L) {
                        moveTo(x(reading.observedAtMillis), y(reading.rssi))
                    } else {
                        lineTo(x(reading.observedAtMillis), y(reading.rssi))
                    }
                    previous = reading.observedAtMillis
                }
            }
        }
    }
    val active = Path()
    val fill = Path()
    var lastPoint: Offset? = null
    var lastTimestamp = 0L
    series.visibleSamples.forEach { sample ->
        if (sample.activeRssi in -110..-20) {
            val point = Offset(x(sample.timestamp), y(sample.activeRssi))
            if (lastPoint == null || sample.timestamp - lastTimestamp > 7_500L) {
                lastPoint?.let { fill.lineTo(it.x, height); fill.close() }
                active.moveTo(point.x, point.y)
                fill.moveTo(point.x, height)
                fill.lineTo(point.x, point.y)
            } else {
                active.lineTo(point.x, point.y)
                fill.lineTo(point.x, point.y)
            }
            lastPoint = point
            lastTimestamp = sample.timestamp
        }
    }
    lastPoint?.let { fill.lineTo(it.x, height); fill.close() }
    return GraphPaths(candidatePaths, active, fill, lastPoint, lastTimestamp)
}

private fun prepareGraphSeries(samples: List<TelemetrySample>, now: Long): GraphSeries {
    val windowStart = now - 60_000L
    val visible = samples.filter { it.timestamp in windowStart..now }
    val candidates = visible.asSequence().flatMap { it.candidates.values.asSequence() }
        .filter { it.observedAtMillis in windowStart..now }
        .groupBy { it.bssid.lowercase() }
        .toSortedMap()
        .map { (bssid, readings) ->
            bssid to readings.distinctBy { it.observedAtMillis }.sortedBy { it.observedAtMillis }
        }
    return GraphSeries(visible, candidates)
}

@Composable
fun WifiGraphView(
    state: TelemetryGraphState,
    ageClock: StateFlow<Long>,
    onTogglePause: () -> Unit,
    onClearHistory: () -> Unit,
    onSelectCandidate: (String?) -> Unit,
    onLockBssid: (String) -> Unit,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true
) {
    val palette = LocalCliPalette.current
    val textMeasurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val gridLabelStyle = remember(palette) {
        TextStyle(color = palette.textTertiary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
    val gridLabels = remember(textMeasurer, gridLabelStyle) {
        listOf("-40 dBm", "-50 dBm", "-65 [GOOD]", "-75 [ROAM]", "-90 dBm", "-60s", "-30s", "NOW")
            .associateWith { textMeasurer.measure(it, gridLabelStyle) }
    }
    val graphSeries = remember(state.samples, state.nowTimestampMillis) {
        prepareGraphSeries(state.samples, state.nowTimestampMillis)
    }

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
                ObservationAgeText(state, ageClock)
            }

            // Pause / Resume & Clear Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pause / Resume Icon Button
                Surface(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            1.dp,
                            if (state.isPaused) CliAccent24GHz.copy(alpha = 0.6f) else CliBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTogglePause()
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = if (state.isPaused) CliAccent24GHzBg else CliSurfaceElevated,
                    contentColor = if (state.isPaused) CliAccent24GHz else CliTextSecondary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (state.isPaused) "Resume" else "Pause",
                            tint = if (state.isPaused) CliAccent24GHz else CliTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Clear History Icon Button
                Surface(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, CliBorder, RoundedCornerShape(6.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClearHistory()
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = CliSurfaceElevated,
                    contentColor = CliTextSecondary
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear History",
                            tint = CliTextSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
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
                Spacer(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)
                    .drawWithCache {
                        val paths = buildGraphPaths(graphSeries, state.nowTimestampMillis, size.width, size.height)
                        onDrawBehind {
                            drawTelemetryGraph(
                                series = graphSeries,
                                paths = paths,
                                nowTimestampMillis = state.nowTimestampMillis,
                                activeBand = state.activeBand,
                                selectedCandidateBssid = state.selectedCandidateBssid?.lowercase(),
                                palette = palette,
                                textMeasurer = textMeasurer,
                                gridLabels = gridLabels
                            )
                        }
                    })
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

@Composable
private fun ObservationAgeText(state: TelemetryGraphState, ageClock: StateFlow<Long>) {
    val now by ageClock.collectAsState()
    Text(
        text = observationAgeSummary(state, now),
        style = CliTypography.CodeMono,
        color = CliTextTertiary
    )
}

private fun observationAgeSummary(state: TelemetryGraphState, now: Long): String {
    fun age(timestamp: Long): String = if (timestamp <= 0L) "--" else "${((now - timestamp).coerceAtLeast(0L) / 1000L)}s"
    val status = if (state.lastStatusObservedAtMillis > 0L) "STATUS ${age(state.lastStatusObservedAtMillis)}" else "STATUS waiting"
    val scan = when (state.lastScanSucceeded) {
        true -> "SCAN ${if (state.lastScanWasCoalesced) "reused" else "command ok"} ${age(state.lastScanCompletedAtMillis)} · ${state.lastScanDurationMillis ?: 0L}ms"
        false -> "SCAN failed ${age(state.lastScanAttemptedAtMillis)}"
        null -> "SCAN waiting"
    }
    return "$status · $scan"
}

/**
 * Draws the real-time RF graph on Compose Canvas.
 */
private fun DrawScope.drawTelemetryGraph(
    series: GraphSeries,
    paths: GraphPaths,
    nowTimestampMillis: Long,
    activeBand: BandType,
    selectedCandidateBssid: String?,
    palette: CliPalette,
    textMeasurer: TextMeasurer,
    gridLabels: Map<String, TextLayoutResult>
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

    val windowStartMillis = nowTimestampMillis - 60_000L
    val visibleSamples = series.visibleSamples
    fun timestampToX(timestamp: Long): Float =
        ((timestamp - windowStartMillis).toFloat() / 60_000f).coerceIn(0f, 1f) * canvasWidth

    // 1. Draw Background Quality Zones
    val yMinus65 = dbmToY(-65f)
    val yMinus75 = dbmToY(-75f)

    // Green zone (-30 to -65 dBm)
    drawRect(
        color = palette.accentGreen.copy(alpha = 0.04f),
        topLeft = Offset(0f, 0f),
        size = Size(canvasWidth, yMinus65)
    )
    // Amber zone (-65 to -75 dBm)
    drawRect(
        color = palette.accent24GHz.copy(alpha = 0.04f),
        topLeft = Offset(0f, yMinus65),
        size = Size(canvasWidth, yMinus75 - yMinus65)
    )
    // Red zone (-75 to -95 dBm)
    drawRect(
        color = palette.accentRed.copy(alpha = 0.04f),
        topLeft = Offset(0f, yMinus75),
        size = Size(canvasWidth, canvasHeight - yMinus75)
    )

    // 2. Draw Horizontal Gridlines & dBm Labels
    val gridDbms = listOf(-40f, -50f, -65f, -75f, -90f)

    gridDbms.forEach { dbm ->
        val y = dbmToY(dbm)
        val isThreshold = dbm == -65f || dbm == -75f
        val lineColor = if (isThreshold) {
            if (dbm == -65f) palette.accentGreen.copy(alpha = 0.35f) else palette.accent24GHz.copy(alpha = 0.35f)
        } else {
            palette.borderSubtle
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
        val measured = gridLabels.getValue(label)
        drawText(textLayoutResult = measured, topLeft = Offset(6f, y - measured.size.height - 2f))
    }

    // 3. Draw Candidate AP Lines (Background/Secondary)
    series.candidates.forEachIndexed { candIndex, (candidateKey, _) ->
        val candBssid = candidateKey
        val isHighlighted = selectedCandidateBssid == null || selectedCandidateBssid == candBssid
        val candColor = candidateColor(candIndex, palette)
        val alpha = if (isHighlighted) 0.65f else 0.15f
        val strokeWidth = if (selectedCandidateBssid == candBssid) 2.5f else 1.5f

        drawPath(
            path = paths.candidatePaths[candIndex],
            color = candColor.copy(alpha = alpha),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            )
        )
    }

    // 4. Draw Roam Event Vertical Markers
    visibleSamples.forEach { sample ->
        sample.roamEvent?.let { roam ->
            val x = timestampToX(sample.timestamp)
            drawLine(
                color = palette.accent5GHz,
                start = Offset(x, 0f),
                end = Offset(x, canvasHeight),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )

            val tag = "ROAM [${roam.fromBand.displayName} → ${roam.toBand.displayName}]"
            val style = TextStyle(color = palette.accent5GHz, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val measuredTag = textMeasurer.measure(tag, style)
            drawText(
                textLayoutResult = measuredTag,
                topLeft = Offset(min(x + 4f, canvasWidth - measuredTag.size.width - 4f), 8f)
            )
        }
    }

    // 5. Draw Active AP Primary Curve & Gradient Fill
    val activeColor = if (activeBand == BandType.BAND_5_GHZ || activeBand == BandType.BAND_6_GHZ) {
        palette.accent5GHz
    } else {
        palette.accent24GHz
    }

    if (paths.lastPoint != null) {

        // Subtle glowing gradient fill under curve
        drawPath(
            path = paths.fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(activeColor.copy(alpha = 0.22f), activeColor.copy(alpha = 0.02f)),
                startY = dbmToY(-40f),
                endY = canvasHeight
            )
        )

        // Bold active line
        drawPath(
            path = paths.activePath,
            color = activeColor,
            style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Show a live marker only while the last measurement is recent.
        if (nowTimestampMillis - paths.lastStatusTimestamp in 0L..7_500L) {
            drawCircle(
                color = activeColor,
                radius = 5.5f,
                center = paths.lastPoint
            )
            drawCircle(
                color = Color.White,
                radius = 2.5f,
                center = paths.lastPoint
            )
        }
    }

    // 6. Draw Bottom Time Labels (e.g. -60s, -30s, NOW)
    val timeLabels = listOf("-60s" to (windowStartMillis), "-30s" to (windowStartMillis + 30_000L), "NOW" to nowTimestampMillis)
    timeLabels.forEach { (text, timestamp) ->
        val x = timestampToX(timestamp)
        val measured = gridLabels.getValue(text)
        val drawX = (x - measured.size.width / 2f).coerceIn(4f, canvasWidth - measured.size.width - 4f)
        drawText(textLayoutResult = measured, topLeft = Offset(drawX, canvasHeight - measured.size.height - 2f))
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
    val candColor = candidateColor(candidate.colorIndex, LocalCliPalette.current)

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
                    text = "Signal: ${candidate.latestRssi} dBm (Advantage: $deltaText) · measured ${((System.currentTimeMillis() - candidate.observedAtMillis).coerceAtLeast(0L) / 1000L)}s ago",
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
