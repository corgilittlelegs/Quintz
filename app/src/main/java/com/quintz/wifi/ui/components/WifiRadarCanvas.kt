package com.quintz.wifi.ui.components

import android.graphics.Paint
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
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
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pingPulse"
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = (min(size.width, size.height) / 2f) - 24.dp.toPx()

        if (maxRadius <= 0f) return@Canvas

        // 1. Concentric Distance Rings (2m, 5m, 10m, 20m)
        val rings = listOf(
            0.25f to "2m",
            0.50f to "5m",
            0.75f to "10m",
            1.00f to "20m"
        )

        for ((fraction, label) in rings) {
            val r = maxRadius * fraction
            drawCircle(
                color = CliBorderActive.copy(alpha = 0.35f),
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Draw distance label on the vertical axis
            drawContext.canvas.nativeCanvas.drawText(
                label,
                center.x + 4.dp.toPx(),
                center.y - r + 12.dp.toPx(),
                Paint().apply {
                    color = android.graphics.Color.argb(120, 161, 161, 170)
                    textSize = 10.dp.toPx()
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            )
        }

        // 2. Crosshairs & 45-degree angle lines
        drawLine(
            color = CliBorderActive.copy(alpha = 0.35f),
            start = Offset(center.x, center.y - maxRadius),
            end = Offset(center.x, center.y + maxRadius),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = CliBorderActive.copy(alpha = 0.35f),
            start = Offset(center.x - maxRadius, center.y),
            end = Offset(center.x + maxRadius, center.y),
            strokeWidth = 1.dp.toPx()
        )

        // 3. Cardinal Labels
        val labelOffset = maxRadius + 14.dp.toPx()
        drawCardinal("N", Offset(center.x, center.y - labelOffset), CliAccent5GHz)
        drawCardinal("S", Offset(center.x, center.y + labelOffset), CliTextTertiary)
        drawCardinal("E", Offset(center.x + labelOffset, center.y), CliTextTertiary)
        drawCardinal("W", Offset(center.x - labelOffset, center.y), CliTextTertiary)

        // 4. Rotating Sweep Beam with Trailing Glow Arc
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
                1.0f to CliAccent5GHz.copy(alpha = 0.22f),
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
            color = CliAccent5GHz.copy(alpha = 0.8f),
            start = center,
            end = sweepEnd,
            strokeWidth = 1.5.dp.toPx()
        )

        // Center origin dot
        drawCircle(
            color = CliAccent5GHz,
            radius = 3.dp.toPx(),
            center = center
        )

        // 5. Target Router Blip & Ping Ripple
        if (radarState.distanceMeters > 0f) {
            // Normalize distance to radar radius (capped at 20 meters)
            val distFraction = (radarState.distanceMeters / 20f).coerceIn(0.12f, 0.96f)
            val targetRadius = maxRadius * distFraction

            // Angle on scope: 12 o'clock is device heading forward, so relative angle maps directly
            val targetScopeRad = Math.toRadians((radarState.relativeAngle - 90.0)).toFloat()

            val targetX = center.x + targetRadius * cos(targetScopeRad)
            val targetY = center.y + targetRadius * sin(targetScopeRad)
            val targetOffset = Offset(targetX, targetY)

            val blipColor = if (radarState.isCalibrated) CliAccentGreen else CliAccent5GHz

            // Pulsing ripple
            drawCircle(
                color = blipColor.copy(alpha = (1f - pingPulse) * 0.6f),
                radius = 6.dp.toPx() + (pingPulse * 16.dp.toPx()),
                center = targetOffset,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Core Blip
            drawCircle(
                color = blipColor,
                radius = 5.dp.toPx(),
                center = targetOffset
            )

            // Blip Target Label
            drawContext.canvas.nativeCanvas.drawText(
                "${radarState.distanceMeters}m",
                targetX + 8.dp.toPx(),
                targetY + 4.dp.toPx(),
                Paint().apply {
                    color = android.graphics.Color.argb(230, 250, 250, 250)
                    textSize = 10.dp.toPx()
                    typeface = android.graphics.Typeface.MONOSPACE
                    isFakeBoldText = true
                }
            )

            // Directional Header Chevron (highlights when pointing directly at router)
            val angleDiff = abs(if (radarState.relativeAngle > 180f) radarState.relativeAngle - 360f else radarState.relativeAngle)
            if (angleDiff < 25f && radarState.isCalibrated) {
                drawCircle(
                    color = CliAccentGreen.copy(alpha = 0.2f),
                    radius = 8.dp.toPx(),
                    center = Offset(center.x, center.y - maxRadius - 2.dp.toPx())
                )
            }
        }
    }
}

private fun DrawScope.drawCardinal(text: String, pos: Offset, color: Color) {
    drawContext.canvas.nativeCanvas.drawText(
        text,
        pos.x - 4.dp.toPx(),
        pos.y + 4.dp.toPx(),
        Paint().apply {
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt()
            )
            textSize = 11.dp.toPx()
            typeface = android.graphics.Typeface.MONOSPACE
            isFakeBoldText = true
        }
    )
}
