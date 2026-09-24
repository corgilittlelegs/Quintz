package com.quintz.wifi.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.ui.theme.*

/**
 * Lumo UI-inspired Minimalist Industrial CLI Panel.
 * Flat, opaque surface with a crisp 1dp border and zero glassmorphism.
 */
@Composable
fun CliPanel(
    modifier: Modifier = Modifier,
    borderColor: Color = CliBorder,
    borderWidth: Dp = 1.dp,
    containerColor: Color = CliSurface,
    shape: Shape = RoundedCornerShape(6.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .border(borderWidth, borderColor, shape)
            .clip(shape),
        shape = shape,
        color = containerColor,
        contentColor = CliTextPrimary,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * Monospace tag badge with a crisp border (e.g. [ 5 GHz ], [ LOCKED ], [ DFS ]).
 */
@Composable
fun CliBadge(
    text: String,
    modifier: Modifier = Modifier,
    accentColor: Color = CliTextSecondary,
    backgroundColor: Color = CliSurfaceElevated,
    borderColor: Color = CliBorder
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = CliTypography.BadgeText,
            color = accentColor
        )
    }
}

enum class CliButtonVariant {
    Primary,
    Outlined,
    Ghost,
    Destructive
}

/**
 * Stark, high-contrast industrial CLI button with tactile haptic feedback.
 */
@Composable
fun CliButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    variant: CliButtonVariant = CliButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(6.dp),
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current

    val (bg, contentColor, border) = when (variant) {
        CliButtonVariant.Primary -> Triple(
            if (enabled) CliButtonPrimary else CliSurfaceElevated,
            if (enabled) CliButtonPrimaryText else CliTextTertiary,
            null
        )
        CliButtonVariant.Outlined -> Triple(
            CliSurfaceElevated,
            if (enabled) CliTextPrimary else CliTextSecondary,
            BorderStroke(1.dp, if (enabled) CliBorderActive else CliBorder)
        )
        CliButtonVariant.Ghost -> Triple(
            Color.Transparent,
            if (enabled) CliTextSecondary else CliTextTertiary,
            null
        )
        CliButtonVariant.Destructive -> Triple(
            CliAccentRedBg,
            CliAccentRed,
            BorderStroke(1.dp, CliAccentRed.copy(alpha = 0.4f))
        )
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .then(
                if (border != null) Modifier.border(border.width, border.brush, shape) else Modifier
            )
            .clip(shape)
            .clickable(
                enabled = enabled && !loading,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
        shape = shape,
        color = bg,
        contentColor = contentColor,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (content != null) {
                content()
            } else if (text != null) {
                Text(
                    text = text,
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.4.sp,
                    color = contentColor
                )
            }
        }
    }
}

/**
 * Discrete 4-bar signal strength meter with exact monospace dBm readout.
 */
@Composable
fun CliSignalBars(
    rssi: Int,
    modifier: Modifier = Modifier,
    showDbmText: Boolean = true
) {
    val activeBars = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    val activeColor = when {
        activeBars >= 3 -> CliAccentGreen
        activeBars == 2 -> CliAccent24GHz
        else -> CliAccentRed
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(14.dp)
        ) {
            for (i in 1..4) {
                val heightPercent = (i * 0.25f)
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(heightPercent)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (i <= activeBars) activeColor else CliBorder)
                )
            }
        }

        if (showDbmText) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$rssi dBm",
                style = CliTypography.CodeMono,
                color = activeColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Pulsing or solid CLI operational status dot.
 */
@Composable
fun CliStatusDot(
    color: Color = CliAccentGreen,
    isPulsing: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (isPulsing) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha))
        )
    } else {
        Box(
            modifier = modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * 1dp clean horizontal divider.
 */
@Composable
fun CliDivider(
    modifier: Modifier = Modifier,
    color: Color = CliBorderSubtle
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = color
    )
}

/**
 * Minimalist interactive filter chip for band selection.
 */
@Composable
fun CliFilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(
                1.dp,
                if (isSelected) CliAccent5GHz.copy(alpha = 0.6f) else CliBorder,
                RoundedCornerShape(4.dp)
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        shape = RoundedCornerShape(4.dp),
        color = if (isSelected) CliAccent5GHzBg else CliSurfaceElevated,
        contentColor = if (isSelected) CliAccent5GHz else CliTextSecondary
    ) {
        Text(
            text = label,
            style = CliTypography.BadgeText,
            color = if (isSelected) CliAccent5GHz else CliTextSecondary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

/**
 * Industrial CLI Scanner Refresh Button.
 * Used exclusively in the AP Scanner header in portrait and landscape modes.
 */
@Composable
fun CliScannerRefreshButton(
    isScanning: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    showLabel: Boolean = false,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val infiniteTransition = rememberInfiniteTransition(label = "scannerRefreshSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerRefreshAngle"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .border(
                1.dp,
                if (isScanning) CliAccentGreen.copy(alpha = 0.6f) else CliBorder,
                RoundedCornerShape(4.dp)
            )
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onRefresh()
            },
        shape = RoundedCornerShape(4.dp),
        color = if (isScanning) CliAccentGreenBg else CliSurfaceElevated,
        contentColor = if (isScanning) CliAccentGreen else if (!enabled) CliTextTertiary else CliTextSecondary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showLabel) 8.dp else 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Scan Nearby Radios",
                tint = if (isScanning) CliAccentGreen else if (!enabled) CliTextTertiary else CliTextSecondary,
                modifier = Modifier
                    .size(12.dp)
                    .then(if (isScanning) Modifier.rotate(rotation) else Modifier)
            )
            if (showLabel) {
                Text(
                    text = if (isScanning) "SCANNING" else "SCAN",
                    style = CliTypography.BadgeText,
                    color = if (isScanning) CliAccentGreen else if (!enabled) CliTextTertiary else CliTextSecondary
                )
            }
        }
    }
}
