package com.quintz.wifi.ui.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import com.quintz.wifi.telemetry.startsNewTelemetrySegment
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.model.BandType
import com.quintz.wifi.telemetry.CandidateMeta
import com.quintz.wifi.telemetry.TelemetryGraphState
import com.quintz.wifi.telemetry.TelemetrySample
import com.quintz.wifi.telemetry.isFreshCandidate
import com.quintz.wifi.telemetry.isVisibleCandidate
import com.quintz.wifi.telemetry.CANDIDATE_LINE_MAX_GAP_MS
import com.quintz.wifi.telemetry.readingsWithWindowPredecessor
import com.quintz.wifi.ui.components.CliBadge
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
    val plotSamples: List<TelemetrySample>,
    val candidates: List<Pair<String, List<com.quintz.wifi.telemetry.CandidateSample>>>
)

private data class GraphPaths(
    val candidates: List<CandidatePath>,
    val activeSegments: List<ActiveSegment>,
    val lastPoint: Offset?,
    val lastStatusTimestamp: Long,
    val roamLabels: List<Pair<Long, TextLayoutResult>>
)

private data class CandidatePath(val points: List<Offset>, val line: Path, val lastObservedAtMillis: Long)

/** Mark NOW with a steady tick that stays entirely inside the right edge. */
private fun DrawScope.drawLiveEdgeTick(rightEdgeX: Float, y: Float, color: Color) {
    val strokeWidth = 2.dp.toPx()
    val halfHeight = 4.dp.toPx()
    // The graph's 1 dp border is drawn over the canvas; keep the full tick inside it.
    val x = rightEdgeX - 1.dp.toPx() - strokeWidth / 2f
    drawLine(
        color = color,
        start = Offset(x, y - halfHeight),
        end = Offset(x, y + halfHeight),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Butt
    )
}

/** Keep the latest known value at the right edge without moving its measurement marker. */
private fun DrawScope.drawHeldReadingTail(point: Offset, rightEdgeX: Float, color: Color, width: Float) {
    val length = rightEdgeX - point.x
    if (length <= 0f) return
    // Align the dash pattern so a visible dash always meets the right border.
    val phase = (4f - length % 12f + 12f) % 12f
    drawLine(
        color = color,
        start = point,
        end = Offset(rightEdgeX, point.y),
        strokeWidth = width,
        cap = StrokeCap.Butt,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f), phase)
    )
}

private data class ActiveSegment(val band: BandType, val line: Path, val fill: Path, val color: Color, val fillBrush: Brush)

private fun buildGraphPaths(series: GraphSeries, now: Long, width: Float, height: Float, palette: CliPalette, textMeasurer: TextMeasurer): GraphPaths {
    val start = now - 60_000L
    // Allow the predecessor to lie off-canvas; clipping preserves the real crossing segment.
    fun x(timestamp: Long) = (timestamp - start).toFloat() / 60_000f * width
    fun y(rssi: Int) = height * (1f - (rssi.toFloat().coerceIn(-95f, -30f) + 95f) / 65f)
    val candidatePaths = series.candidates.map { (_, readings) ->
        val points = mutableListOf<Offset>()
        val line = Path()
        var previous: com.quintz.wifi.telemetry.CandidateSample? = null
        readings.forEach { reading ->
            if (reading.rssi in -110..-20) {
                val point = Offset(x(reading.observedAtMillis), y(reading.rssi))
                val last = previous
                if (last == null || reading.observedAtMillis - last.observedAtMillis !in 1L..CANDIDATE_LINE_MAX_GAP_MS ||
                    reading.band != last.band || reading.channel != last.channel) {
                    line.moveTo(point.x, point.y)
                } else {
                    line.lineTo(point.x, point.y)
                }
                points.add(point)
                previous = reading
            } else {
                previous = null
            }
        }
        CandidatePath(points, line, previous?.observedAtMillis ?: 0L)
    }
    val segments = mutableListOf<ActiveSegment>()
    var active = Path()
    var fill = Path()
    var segmentBand = BandType.UNKNOWN
    var lastPoint: Offset? = null
    var lastTimestamp = 0L
    var previousSample: TelemetrySample? = null
    fun finishSegment() {
        lastPoint?.let {
            fill.lineTo(it.x, height)
            fill.close()
            val color = if (segmentBand == BandType.BAND_5_GHZ || segmentBand == BandType.BAND_6_GHZ) palette.accent5GHz else palette.accent24GHz
            segments.add(ActiveSegment(segmentBand, active, fill, color, Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.22f), color.copy(alpha = 0.02f)),
                startY = height * (1f - 55f / 65f), endY = height)))
        }
    }
    series.plotSamples.forEach { sample ->
        if (sample.activeRssi in -110..-20) {
            val point = Offset(x(sample.timestamp), y(sample.activeRssi))
            if (lastPoint == null || startsNewTelemetrySegment(previousSample, sample)) {
                finishSegment()
                active = Path()
                fill = Path()
                segmentBand = sample.activeBand
                active.moveTo(point.x, point.y)
                fill.moveTo(point.x, height)
                fill.lineTo(point.x, point.y)
            } else {
                val previous = lastPoint!!
                val middleX = (previous.x + point.x) / 2f
                active.cubicTo(middleX, previous.y, middleX, point.y, point.x, point.y)
                fill.cubicTo(middleX, previous.y, middleX, point.y, point.x, point.y)
            }
            lastPoint = point
            lastTimestamp = sample.timestamp
            previousSample = sample
        }
    }
    finishSegment()
    val roamStyle = TextStyle(color = palette.accent5GHz, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    val roamLabels = series.plotSamples.filter { it.timestamp in start..now }.mapNotNull { sample ->
        sample.roamEvent?.let { roam -> sample.timestamp to textMeasurer.measure(
            "ROAM [${roam.fromBand.displayName} → ${roam.toBand.displayName}]", roamStyle) }
    }
    return GraphPaths(candidatePaths, segments, lastPoint, lastTimestamp, roamLabels)
}

private fun prepareGraphSeries(samples: List<TelemetrySample>, now: Long, liveBssids: Set<String>): GraphSeries {
    val windowStart = now - 60_000L
    val visible = readingsWithWindowPredecessor(samples, windowStart, now) { it.timestamp }
    val candidates = samples.asSequence().flatMap { it.candidates.values.asSequence() }
        .filter { it.observedAtMillis <= now && it.bssid.lowercase() in liveBssids }
        .groupBy { it.bssid.lowercase() }
        .toSortedMap()
        .map { (bssid, readings) ->
            val sorted = readings.distinctBy { it.observedAtMillis }.sortedBy { it.observedAtMillis }
            bssid to readingsWithWindowPredecessor(sorted, windowStart, now) { it.observedAtMillis }
        }
    return GraphSeries(visible, candidates)
}

@Composable
fun WifiGraphView(
    state: TelemetryGraphState,
    ageClock: StateFlow<Long>,
    scrollState: ScrollState,
    onTogglePause: () -> Unit,
    onClearHistory: () -> Unit,
    onSelectCandidate: (String?) -> Unit,
    modifier: Modifier = Modifier,
    isConnected: Boolean = true
) {
    val palette = LocalCliPalette.current
    val textMeasurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current
    val gridLabelStyle = remember(palette) {
        TextStyle(color = palette.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
    val gridLabels = remember(textMeasurer, gridLabelStyle) {
        listOf("-40 dBm", "-50 dBm", "-65 [GOOD]", "-75 [ROAM]", "-90 dBm", "-60s", "-30s", "NOW")
            .associateWith { textMeasurer.measure(it, gridLabelStyle) }
    }
    // Read the frame clock only in drawing: the cards and layout do not recompose per frame.
    val frameNow = remember { mutableLongStateOf(state.nowTimestampMillis) }
    LaunchedEffect(state.isPaused, isConnected) {
        if (!state.isPaused && isConnected) {
            val wallStart = System.currentTimeMillis()
            val frameStart = withFrameNanos { it }
            while (true) {
                withFrameNanos { frameNow.longValue = wallStart + (it - frameStart) / 1_000_000L }
            }
        }
    }
    val graphSeries = remember(state.samples, state.nowTimestampMillis, state.candidates) {
        prepareGraphSeries(state.samples, state.nowTimestampMillis, state.candidates.map { it.bssid.lowercase() }.toSet())
    }

    CliPanel(
        modifier = modifier.fillMaxSize(),
        containerColor = CliSurface,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
        scrollState = scrollState
    ) {
        // ── 1. Header Bar: Title, Live Telemetry Status & Controls ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
                    color = if (isConnected) CliTextSecondary else CliAccentRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                        .size(48.dp)
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
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Clear History Icon Button
                Surface(
                    modifier = Modifier
                        .size(48.dp)
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
                            modifier = Modifier.size(20.dp)
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
                        text = if (state.candidates.none { it.isInLatestScan && isFreshCandidate(it.observedAtMillis, state.nowTimestampMillis) })
                            "NO FRESH ALTERNATIVE" else "NO 6 dBm ADVANTAGE",
                        accentColor = CliTextSecondary,
                        backgroundColor = CliSurfaceElevated,
                        borderColor = CliBorder
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
                Spacer(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)
                    .drawWithCache {
                        val paths = buildGraphPaths(graphSeries, state.nowTimestampMillis, size.width, size.height, palette, textMeasurer)
                        onDrawBehind {
                            drawTelemetryGraph(
                                series = graphSeries,
                                paths = paths,
                                referenceTimestampMillis = state.nowTimestampMillis,
                                nowTimestampMillis = if (state.isPaused) state.nowTimestampMillis else frameNow.longValue,
                                selectedCandidateBssid = state.selectedCandidateBssid?.lowercase(),
                                palette = palette,
                                gridLabels = gridLabels
                            )
                        }
                    })
            }
        }

        Text(
            text = "Scan dots mark readings · dashed tails hold the last known value",
            style = CliTypography.CodeMono,
            color = CliTextTertiary
        )
        Spacer(modifier = Modifier.height(12.dp))
        LinkSpeedHistory(graphSeries.plotSamples, state.nowTimestampMillis, frameNow, state.isPaused, palette)

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
                        nowTimestampMillis = state.nowTimestampMillis,
                        isSelected = state.selectedCandidateBssid == candidate.bssid,
                        onSelect = {
                            if (state.selectedCandidateBssid == candidate.bssid) {
                                onSelectCandidate(null)
                            } else {
                                onSelectCandidate(candidate.bssid)
                            }
                        }
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
                    text = "No recent same-network scan reading. Candidate points appear when another radio is measured.",
                    style = CliTypography.CodeMono,
                    color = CliTextTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ObservationAgeText(state: TelemetryGraphState, ageClock: StateFlow<Long>) {
    val liveNow by ageClock.collectAsState()
    val now = if (state.isPaused) state.nowTimestampMillis else liveNow
    Text(
        text = observationAgeSummary(state, now),
        style = CliTypography.CodeMono,
        color = CliTextTertiary
    )
}

@Composable
private fun LinkSpeedHistory(samples: List<TelemetrySample>, now: Long, frameNow: State<Long>, paused: Boolean, palette: CliPalette) {
    val readings = remember(samples, now) {
        readingsWithWindowPredecessor(samples.filter { it.activeLinkSpeedMbps > 0 }, now - 60_000L, now) { it.timestamp }
    }
    val scaleMbps = maxOf(100, ((readings.filter { it.timestamp >= now - 60_000L }.maxOfOrNull { it.activeLinkSpeedMbps } ?: 0) / 100 + 1) * 100)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "PHY LINK SPEED · LAST 60s", style = CliTypography.TelemetryLabel)
        Spacer(
            modifier = Modifier.fillMaxWidth().height(100.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CliBackground)
                .border(1.dp, CliBorder, RoundedCornerShape(6.dp))
                .padding(vertical = 8.dp)
                .drawWithCache {
                    val baseline = size.height
                    val path = Path()
                    var previous: TelemetrySample? = null
                    var lastPoint: Offset? = null
                    readings.forEach { reading ->
                        val point = Offset(
                            (reading.timestamp - (now - 60_000L)).toFloat() / 60_000f * size.width,
                            baseline * (1f - reading.activeLinkSpeedMbps.toFloat().coerceIn(0f, scaleMbps.toFloat()) / scaleMbps)
                        )
                        if (startsNewTelemetrySegment(previous, reading)) {
                            path.moveTo(point.x, point.y)
                        } else {
                            val middleX = (lastPoint!!.x + point.x) / 2f
                            path.cubicTo(middleX, lastPoint!!.y, middleX, point.y, point.x, point.y)
                        }
                        previous = reading
                        lastPoint = point
                    }
                    onDrawBehind {
                        val liveNow = if (paused) now else frameNow.value
                        val latestPoint = lastPoint
                        drawLine(palette.borderSubtle, Offset(0f, baseline), Offset(size.width, baseline), 1f)
                        drawLine(palette.borderSubtle, Offset(0f, baseline / 2f), Offset(size.width, baseline / 2f), 1f)
                        clipRect {
                            translate(left = -(liveNow - now).toFloat() / 60_000f * size.width) {
                                drawPath(path, color = palette.accent5GHz, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                                if (latestPoint != null && liveNow - (previous?.timestamp ?: 0L) in 0L..7_500L) {
                                    val rightEdgeX = size.width + (liveNow - now).toFloat() / 60_000f * size.width
                                    drawLine(
                                        palette.accent5GHz, latestPoint, Offset(rightEdgeX, latestPoint.y),
                                        strokeWidth = 2.5f
                                    )
                                    drawLiveEdgeTick(rightEdgeX, latestPoint.y, palette.accent5GHz)
                                }
                            }
                        }
                    }
                }
        )
        Text(text = "0–$scaleMbps Mbps · PHY rate, not internet throughput", style = CliTypography.CodeMono, color = CliTextTertiary)
    }
}

private fun observationAgeSummary(state: TelemetryGraphState, now: Long): String {
    if (!state.isConnected) return "Waiting for a Wi-Fi connection"
    if (state.lastStatusObservedAtMillis <= 0L) return "Waiting for a signal reading"
    val ageSeconds = (now - state.lastStatusObservedAtMillis).coerceAtLeast(0L) / 1000L
    val freshness = if (ageSeconds < 2L) "Signal updated just now" else "Signal updated ${ageSeconds}s ago"
    return if (state.isPaused) "Paused · $freshness" else freshness
}

/**
 * Draws the real-time RF graph on Compose Canvas.
 */
private fun DrawScope.drawTelemetryGraph(
    series: GraphSeries,
    paths: GraphPaths,
    referenceTimestampMillis: Long,
    nowTimestampMillis: Long,
    selectedCandidateBssid: String?,
    palette: CliPalette,
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

    clipRect {
      translate(left = -(nowTimestampMillis - referenceTimestampMillis).toFloat() / 60_000f * canvasWidth) {
    val rightEdgeX = canvasWidth + (nowTimestampMillis - referenceTimestampMillis).toFloat() / 60_000f * canvasWidth
    // Thin segments connect measured scan observations only; dots retain their actual times.
    series.candidates.forEachIndexed { candIndex, (candidateKey, _) ->
        val candBssid = candidateKey
        val isHighlighted = selectedCandidateBssid == null || selectedCandidateBssid == candBssid
        val candColor = candidateColor(com.quintz.wifi.telemetry.candidateColorIndex(candBssid), palette)
        val candidatePath = paths.candidates[candIndex]
        drawPath(
            path = candidatePath.line,
            color = candColor.copy(alpha = if (isHighlighted) 0.6f else 0.15f),
            style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        candidatePath.points.lastOrNull()?.let { lastPoint ->
            if (isVisibleCandidate(candidatePath.lastObservedAtMillis, nowTimestampMillis)) {
                val fresh = isFreshCandidate(candidatePath.lastObservedAtMillis, nowTimestampMillis)
                drawHeldReadingTail(
                    lastPoint, rightEdgeX,
                    candColor.copy(alpha = if (!isHighlighted) 0.1f else if (fresh) 0.45f else 0.25f),
                    1.6f
                )
            }
        }
        candidatePath.points.forEach { point ->
            drawCircle(candColor.copy(alpha = if (isHighlighted) 0.85f else 0.2f),
                radius = if (selectedCandidateBssid == candBssid) 4.5f else 3.5f,
                center = point)
        }
    }

    // 4. Draw Roam Event Vertical Markers
    paths.roamLabels.forEach { (timestamp, measuredTag) ->
            val x = (timestamp - (referenceTimestampMillis - 60_000L)).toFloat() / 60_000f * canvasWidth
            drawLine(
                color = palette.accent5GHz,
                start = Offset(x, 0f),
                end = Offset(x, canvasHeight),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )

            drawText(
                textLayoutResult = measuredTag,
                topLeft = Offset(min(x + 4f, canvasWidth - measuredTag.size.width - 4f), 8f)
            )
    }

    // 5. Color each connection segment by its measured band.
    paths.activeSegments.forEach { segment ->
        drawPath(path = segment.fill, brush = segment.fillBrush)

        // Bold active line
        drawPath(
            path = segment.line,
            color = segment.color,
            style = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

    }
    val lastPoint = paths.lastPoint
    if (lastPoint != null && nowTimestampMillis - paths.lastStatusTimestamp in 0L..7_500L) {
        paths.activeSegments.lastOrNull()?.let { latestSegment ->
            // Extend the same gradient beneath the held value, alongside its line to NOW.
            drawRect(
                brush = latestSegment.fillBrush,
                topLeft = lastPoint,
                size = Size((rightEdgeX - lastPoint.x).coerceAtLeast(0f), canvasHeight - lastPoint.y)
            )
        }
        val lastBand = series.plotSamples.lastOrNull()?.activeBand
        val activeColor = if (lastBand == BandType.BAND_5_GHZ || lastBand == BandType.BAND_6_GHZ) palette.accent5GHz else palette.accent24GHz
        // The live cursor stays at NOW; hold the most recent value between observations.
        drawLine(activeColor, lastPoint, Offset(rightEdgeX, lastPoint.y), strokeWidth = 3.2f)
        drawLiveEdgeTick(rightEdgeX, lastPoint.y, activeColor)
    }

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
    nowTimestampMillis: Long,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val delta = candidate.latestRssi - activeRssi
    val isFresh = candidate.isInLatestScan && isFreshCandidate(candidate.observedAtMillis, nowTimestampMillis)
    val ageSeconds = (nowTimestampMillis - candidate.observedAtMillis).coerceAtLeast(0L) / 1000L
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
            modifier = Modifier.weight(1f).padding(end = 8.dp),
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

            Column(modifier = Modifier.weight(1f)) {
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
                    text = "Signal: ${candidate.latestRssi} dBm · ${ageSeconds}s ago" +
                        if (isFresh) " · advantage $deltaText" else " · stale",
                    style = CliTypography.CodeMono,
                    color = if (isFresh) deltaColor else CliTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
