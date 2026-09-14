package com.quintz.wifi.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.quintz.wifi.radar.RadarState
import com.quintz.wifi.ui.theme.*
import kotlin.math.*

/**
 * Tactical Heads-Up Wi-Fi Radar Canvas.
 * - 12 o'clock is always "AHEAD" (device heading direction).
 * - Outer Compass Rose (N, NE, E, etc.) rotates with device heading.
 * - 360° Polar Sector Heatmap visualizes measured signal strengths per bearing.
 * - Dynamic Vector Line and Target Blip show estimated router position.
 * - Interactive lock reticle highlights when the device is aimed at the router.
 */
@Composable
fun WifiRadarCanvas(
    radarState: RadarState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    val pingPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pingPulse"
    )

    val lockPulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lockPulse"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = (min(size.width, size.height) / 2f) - 34.dp.toPx()

        if (maxRadius <= 0f) return@Canvas

        // ── 1. Sector Signal Heatmap (360° Polar Map) ──
        val heatmapRadius = maxRadius * 0.94f
        val heatmapThickness = 14.dp.toPx()
        val heatmapTopLeft = Offset(center.x - heatmapRadius, center.y - heatmapRadius)
        val heatmapSize = Size(heatmapRadius * 2, heatmapRadius * 2)

        for (sector in 0 until 36) {
            // Sector compass bearing range: sector * 10° to (sector + 1) * 10°
            // In Heads-Up mode, 0° compass aligns to -currentHeading relative to 12 o'clock (-90°)
            val startCompass = sector * 10f
            val screenStartAngle = (startCompass - radarState.currentHeading - 90f + 360f) % 360f

            val isVisited = radarState.visitedSectors.contains(sector)
            val rssi = if (sector < radarState.sectorPeakRssi.size) radarState.sectorPeakRssi[sector] else -120f

            if (isVisited && rssi > -115f) {
                // Color mapping according to RF signal strength
                val sectorColor = when {
                    rssi >= -62f -> CliAccentGreen.copy(alpha = 0.55f)
                    rssi >= -74f -> CliAccent5GHz.copy(alpha = 0.45f)
                    rssi >= -84f -> CliAccent24GHz.copy(alpha = 0.40f)
                    else -> CliAccentRed.copy(alpha = 0.35f)
                }

                drawArc(
                    color = sectorColor,
                    startAngle = screenStartAngle + 0.8f,
                    sweepAngle = 8.4f,
                    useCenter = false,
                    topLeft = heatmapTopLeft,
                    size = heatmapSize,
                    style = Stroke(width = heatmapThickness)
                )
            } else {
                // Unvisited sector slot (subtle dark outline showing gap)
                drawArc(
                    color = CliBorderSubtle.copy(alpha = 0.25f),
                    startAngle = screenStartAngle + 1.2f,
                    sweepAngle = 7.6f,
                    useCenter = false,
                    topLeft = heatmapTopLeft,
                    size = heatmapSize,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // ── 2. Concentric Distance Rings (2m, 5m, 10m, 20m) ──
        val rings = listOf(
            0.25f to "2m",
            0.50f to "5m",
            0.75f to "10m",
            1.00f to "20m"
        )

        for ((fraction, label) in rings) {
            val r = maxRadius * fraction
            drawCircle(
                color = CliBorderActive.copy(alpha = 0.25f),
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Distance labels along the vertical forward axis
            drawContext.canvas.nativeCanvas.drawText(
                label,
                center.x + 6.dp.toPx(),
                center.y - r + 13.dp.toPx(),
                Paint().apply {
                    color = android.graphics.Color.argb(130, 161, 161, 170)
                    textSize = 9.5.dp.toPx()
                    typeface = Typeface.MONOSPACE
                }
            )
        }

        // ── 3. Crosshairs & 45° Angle Reference Lines ──
        drawLine(
            color = CliBorderActive.copy(alpha = 0.22f),
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = CliBorderActive.copy(alpha = 0.22f),
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 1.dp.toPx()
        )

        // ── 4. Rotating Compass Rose (N, NE, E, SE, S, SW, W, NW) ──
        // In Heads-Up display, the compass rotates relative to device forward
        val cardinalOffset = maxRadius + 13.dp.toPx()
        val cardinals = listOf(
            0f to ("N" to true),
            45f to ("NE" to false),
            90f to ("E" to false),
            135f to ("SE" to false),
            180f to ("S" to false),
            225f to ("SW" to false),
            270f to ("W" to false),
            315f to ("NW" to false)
        )

        for ((compassDeg, item) in cardinals) {
            val (cardinalText, isNorth) = item
            val rad = Math.toRadians(((compassDeg - radarState.currentHeading - 90.0))).toFloat()
            val textPos = Offset(
                center.x + cardinalOffset * cos(rad),
                center.y + cardinalOffset * sin(rad)
            )

            val color = when {
                isNorth -> CliAccentGreen
                cardinalText.length == 1 -> CliTextSecondary
                else -> CliTextTertiary.copy(alpha = 0.6f)
            }

            drawContext.canvas.nativeCanvas.drawText(
                cardinalText,
                textPos.x - (if (cardinalText.length > 1) 9.dp.toPx() else 5.dp.toPx()),
                textPos.y + 4.dp.toPx(),
                Paint().apply {
                    this.color = android.graphics.Color.argb(
                        (color.alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt()
                    )
                    textSize = if (isNorth) 11.5.dp.toPx() else if (cardinalText.length == 1) 9.5.dp.toPx() else 8.dp.toPx()
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = isNorth || cardinalText.length == 1
                }
            )
        }

        // ── 5. Fixed Forward Reticle at 12 o'clock ──
        val aheadColor = if (radarState.isAlignedAhead) CliAccentGreen else CliAccent5GHz

        // Forward indicator tick mark
        drawLine(
            color = aheadColor,
            start = Offset(center.x, center.y - maxRadius - 2.dp.toPx()),
            end = Offset(center.x, center.y - maxRadius + 5.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )

        // Forward label placed cleanly above the compass ring
        val aheadLabel = if (radarState.isAlignedAhead) "[ LOCKED AHEAD ]" else "▲ AHEAD"
        val labelWidthApprox = if (radarState.isAlignedAhead) 42.dp.toPx() else 20.dp.toPx()

        drawContext.canvas.nativeCanvas.drawText(
            aheadLabel,
            center.x - labelWidthApprox,
            center.y - maxRadius - 22.dp.toPx(),
            Paint().apply {
                color = android.graphics.Color.argb(
                    (aheadColor.alpha * (if (radarState.isAlignedAhead) lockPulse else 1f) * 255).toInt(),
                    (aheadColor.red * 255).toInt(),
                    (aheadColor.green * 255).toInt(),
                    (aheadColor.blue * 255).toInt()
                )
                textSize = 9.dp.toPx()
                typeface = Typeface.MONOSPACE
                isFakeBoldText = true
            }
        )

        // ── 6. Rotating Sweep Beam with Trailing Phosphor Glow ──
        val sweepRad = Math.toRadians((sweepAngle - 90.0)).toFloat()
        val sweepEnd = Offset(
            center.x + maxRadius * cos(sweepRad),
            center.y + maxRadius * sin(sweepRad)
        )

        // Trailing radar arc
        drawArc(
            brush = Brush.sweepGradient(
                0.0f to Color.Transparent,
                0.85f to Color.Transparent,
                0.97f to CliAccent5GHz.copy(alpha = 0.08f),
                1.0f to CliAccent5GHz.copy(alpha = 0.24f),
                center = center
            ),
            startAngle = sweepAngle - 90f - 40f,
            sweepAngle = 40f,
            useCenter = true,
            topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
            size = Size(maxRadius * 2, maxRadius * 2)
        )

        // Sharp sweep line
        drawLine(
            color = CliAccent5GHz.copy(alpha = 0.75f),
            start = center,
            end = sweepEnd,
            strokeWidth = 1.2.dp.toPx()
        )

        // Center origin dot
        drawCircle(
            color = aheadColor,
            radius = 3.dp.toPx(),
            center = center
        )

        // ── 7. Target Router Blip, Vector Line & Guidance Reticle ──
        if (radarState.distanceMeters > 0f) {
            // Distance fraction mapped to scope (capped at 20m)
            val distFraction = (radarState.distanceMeters / 20f).coerceIn(0.18f, 0.90f)
            val targetRadius = maxRadius * distFraction

            // Angle on Heads-Up scope: relativeAngle 0° is 12 o'clock (-90° in standard Cartesian)
            val targetScopeRad = Math.toRadians((radarState.relativeAngle - 90.0)).toFloat()

            val targetX = center.x + targetRadius * cos(targetScopeRad)
            val targetY = center.y + targetRadius * sin(targetScopeRad)
            val targetOffset = Offset(targetX, targetY)

            val blipColor = if (radarState.isAlignedAhead) CliAccentGreen else if (radarState.isCalibrated) CliAccent5GHz else CliAccent24GHz

            // Dotted Vector Laser Line from Center to Target
            drawLine(
                color = blipColor.copy(alpha = if (radarState.isAlignedAhead) 0.8f else 0.4f),
                start = center,
                end = targetOffset,
                strokeWidth = if (radarState.isAlignedAhead) 2.dp.toPx() else 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )

            // Pulsing ripple
            drawCircle(
                color = blipColor.copy(alpha = (1f - pingPulse) * 0.7f),
                radius = 6.dp.toPx() + (pingPulse * 18.dp.toPx()),
                center = targetOffset,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Core Blip
            drawCircle(
                color = blipColor,
                radius = if (radarState.isAlignedAhead) 6.dp.toPx() else 4.5.dp.toPx(),
                center = targetOffset
            )

            // Blip Target Label (Distance & RSSI)
            drawContext.canvas.nativeCanvas.drawText(
                "~${radarState.distanceMeters}m (${radarState.rssi}dBm)",
                targetX + 9.dp.toPx(),
                targetY + 4.dp.toPx(),
                Paint().apply {
                    color = android.graphics.Color.argb(240, 245, 245, 245)
                    textSize = 9.5.dp.toPx()
                    typeface = Typeface.MONOSPACE
                    isFakeBoldText = true
                }
            )

            // ── 8. Dynamic Target Lock Reticle at 12 o'clock ──
            if (radarState.isAlignedAhead) {
                // Brackets surrounding the 12 o'clock reticle
                val bracketColor = CliAccentGreen.copy(alpha = lockPulse)
                val lockBoxY = center.y - maxRadius - 14.dp.toPx()

                drawCircle(
                    color = CliAccentGreen.copy(alpha = 0.2f * lockPulse),
                    radius = 12.dp.toPx(),
                    center = Offset(center.x, center.y - maxRadius + 2.dp.toPx())
                )

                drawContext.canvas.nativeCanvas.drawText(
                    "[ LOCKED ]",
                    center.x - 24.dp.toPx(),
                    lockBoxY - 4.dp.toPx(),
                    Paint().apply {
                        color = android.graphics.Color.argb(
                            (bracketColor.alpha * 255).toInt(),
                            (bracketColor.red * 255).toInt(),
                            (bracketColor.green * 255).toInt(),
                            (bracketColor.blue * 255).toInt()
                        )
                        textSize = 9.dp.toPx()
                        typeface = Typeface.MONOSPACE
                        isFakeBoldText = true
                    }
                )
            } else if (radarState.isCalibrated) {
                // Directional Turn Indicator Chevron on Bezel
                val chevronAngleRad = targetScopeRad
                val chevronPos = Offset(
                    center.x + (maxRadius + 4.dp.toPx()) * cos(chevronAngleRad),
                    center.y + (maxRadius + 4.dp.toPx()) * sin(chevronAngleRad)
                )
                drawCircle(
                    color = blipColor.copy(alpha = 0.7f),
                    radius = 3.5.dp.toPx(),
                    center = chevronPos
                )
            }
        }
    }
}
