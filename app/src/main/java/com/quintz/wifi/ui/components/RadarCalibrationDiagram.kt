package com.quintz.wifi.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.ui.theme.*
import kotlin.math.*

/**
 * Visual schematic demonstrating the "Human RF Body Shielding" principle
 * and step-by-step guidance for first-time Wi-Fi Radar users.
 */
@Composable
fun RadarCalibrationDiagram(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "diagramAnimation")

    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulsePhase"
    )

    val rotateAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotateAngle"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(CliSurfaceElevated)
            .border(1.dp, CliBorderActive.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "HOW IT WORKS: BODY-SHIELDING RADAR",
            style = CliTypography.TelemetryLabel,
            color = CliAccent5GHz
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Dual-pane layout on wide screens, stacked on narrow screens
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Left: Vector Schematic Canvas ──
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(180.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CliBackground)
                    .border(1.dp, CliBorderSubtle, RoundedCornerShape(4.dp))
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // 1. Router at Top
                    val routerX = w / 2f
                    val routerY = 22.dp.toPx()

                    // Router icon box
                    drawRoundRect(
                        color = CliAccent5GHz,
                        topLeft = Offset(routerX - 16.dp.toPx(), routerY - 8.dp.toPx()),
                        size = Size(32.dp.toPx(), 16.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                    // Antenna sticks
                    drawLine(
                        color = CliAccent5GHz,
                        start = Offset(routerX - 8.dp.toPx(), routerY - 8.dp.toPx()),
                        end = Offset(routerX - 8.dp.toPx(), routerY - 16.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = CliAccent5GHz,
                        start = Offset(routerX + 8.dp.toPx(), routerY - 8.dp.toPx()),
                        end = Offset(routerX + 8.dp.toPx(), routerY - 16.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx()
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        "ROUTER",
                        routerX - 16.dp.toPx(),
                        routerY + 18.dp.toPx(),
                        Paint().apply {
                            color = android.graphics.Color.argb(200, 161, 161, 170)
                            textSize = 8.dp.toPx()
                            typeface = Typeface.MONOSPACE
                            isFakeBoldText = true
                        }
                    )

                    // Emitting Wi-Fi Waves downward
                    for (i in 1..3) {
                        val waveRadius = 24.dp.toPx() + (i * 12.dp.toPx()) + (pulsePhase * 10.dp.toPx())
                        val alpha = (1f - (waveRadius / 70.dp.toPx())).coerceIn(0.1f, 0.6f)
                        drawArc(
                            color = CliAccentGreen.copy(alpha = alpha),
                            startAngle = 45f,
                            sweepAngle = 90f,
                            useCenter = false,
                            topLeft = Offset(routerX - waveRadius, routerY - waveRadius),
                            size = Size(waveRadius * 2, waveRadius * 2),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }

                    // 2. User & Tablet (Center-Bottom)
                    val userX = w / 2f
                    val userY = h - 52.dp.toPx()

                    // Attenuation Shadow behind user (dashed red/amber cone)
                    val path = Path().apply {
                        moveTo(userX - 14.dp.toPx(), userY + 4.dp.toPx())
                        lineTo(userX - 26.dp.toPx(), h - 4.dp.toPx())
                        lineTo(userX + 26.dp.toPx(), h - 4.dp.toPx())
                        lineTo(userX + 14.dp.toPx(), userY + 4.dp.toPx())
                        close()
                    }
                    drawPath(
                        path = path,
                        color = CliAccentRed.copy(alpha = 0.15f)
                    )

                    drawContext.canvas.nativeCanvas.drawText(
                        "SHADOW (-10dB)",
                        userX - 28.dp.toPx(),
                        h - 8.dp.toPx(),
                        Paint().apply {
                            color = android.graphics.Color.argb(160, 248, 113, 113)
                            textSize = 7.5.dp.toPx()
                            typeface = Typeface.MONOSPACE
                        }
                    )

                    // Shoulders / Torso oval (top-down view of person)
                    drawOval(
                        color = CliTextSecondary,
                        topLeft = Offset(userX - 22.dp.toPx(), userY - 9.dp.toPx()),
                        size = Size(44.dp.toPx(), 18.dp.toPx())
                    )
                    // Head circle
                    drawCircle(
                        color = CliTextPrimary,
                        radius = 7.dp.toPx(),
                        center = Offset(userX, userY)
                    )

                    // Hands holding Tablet in front of chest
                    val tabletY = userY - 16.dp.toPx()
                    drawRoundRect(
                        color = CliAccentGreen,
                        topLeft = Offset(userX - 14.dp.toPx(), tabletY),
                        size = Size(28.dp.toPx(), 6.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )

                    // Arms linking shoulders to tablet
                    drawLine(
                        color = CliTextSecondary,
                        start = Offset(userX - 16.dp.toPx(), userY - 3.dp.toPx()),
                        end = Offset(userX - 10.dp.toPx(), tabletY + 3.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )
                    drawLine(
                        color = CliTextSecondary,
                        start = Offset(userX + 16.dp.toPx(), userY - 3.dp.toPx()),
                        end = Offset(userX + 10.dp.toPx(), tabletY + 3.dp.toPx()),
                        strokeWidth = 2.dp.toPx()
                    )

                    // Direct Line-of-sight label
                    drawContext.canvas.nativeCanvas.drawText(
                        "PEAK (-55dBm)",
                        userX - 26.dp.toPx(),
                        tabletY - 6.dp.toPx(),
                        Paint().apply {
                            color = android.graphics.Color.argb(220, 74, 222, 128)
                            textSize = 7.5.dp.toPx()
                            typeface = Typeface.MONOSPACE
                            isFakeBoldText = true
                        }
                    )

                    // Rotating Circular Arrow around the person
                    val arrowRadius = 34.dp.toPx()
                    drawArc(
                        color = CliAccent5GHz.copy(alpha = 0.45f),
                        startAngle = rotateAngle,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(userX - arrowRadius, userY - arrowRadius),
                        size = Size(arrowRadius * 2, arrowRadius * 2),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f))
                    )
                }
            }

            // ── Right: 3 Actionable Step Cards ──
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StepRow(
                    stepNumber = "1",
                    title = "HOLD AGAINST CHEST",
                    desc = "Position your tablet firmly against your torso. Your body naturally absorbs Wi-Fi signals arriving from behind you."
                )

                StepRow(
                    stepNumber = "2",
                    title = "ROTATE 360° IN PLACE",
                    desc = "Turn slowly in a complete circle over 10 seconds. Watch the radar heatmap paint each direction from amber to green."
                )

                StepRow(
                    stepNumber = "3",
                    title = "FACE THE GREEN BLIP",
                    desc = "The strongest sector marks your router. Turn until the blip reaches 12 o'clock (▲ AHEAD) to see [TARGET LOCKED]."
                )
            }
        }
    }
}

@Composable
private fun StepRow(
    stepNumber: String,
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(CliBackground.copy(alpha = 0.6f))
            .border(1.dp, CliBorderSubtle, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(CliAccent5GHz.copy(alpha = 0.2f))
                .border(1.dp, CliAccent5GHz, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                style = CliTypography.CodeMono,
                color = CliAccent5GHz,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column {
            Text(
                text = title,
                style = CliTypography.CodeMono,
                color = CliTextPrimary,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = desc,
                style = CliTypography.CodeMono,
                color = CliTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}
