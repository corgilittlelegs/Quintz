package com.quintz.wifi.ui.radar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.radar.RadarEngine
import com.quintz.wifi.radar.RadarState
import com.quintz.wifi.ui.components.*
import com.quintz.wifi.ui.theme.*

@Composable
fun WifiRadarView(
    radarState: RadarState,
    onResetCalibration: () -> Unit,
    modifier: Modifier = Modifier
) {
    CliPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = CliSurface,
        contentPadding = PaddingValues(16.dp)
    ) {
        // Top Header Row
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
                Text(
                    text = if (radarState.targetSsid.isNotEmpty()) radarState.targetSsid else "Target Scanning...",
                    style = Typography.titleMedium,
                    color = CliTextPrimary
                )
            }

            if (radarState.isCalibrated) {
                CliBadge(
                    text = "VECTOR LOCKED",
                    accentColor = CliAccentGreen,
                    backgroundColor = CliAccentGreenBg,
                    borderColor = CliAccentGreen.copy(alpha = 0.4f)
                )
            } else {
                CliBadge(
                    text = "SWEEP TO CALIBRATE",
                    accentColor = CliAccent24GHz,
                    backgroundColor = CliAccent24GHzBg,
                    borderColor = CliAccent24GHz.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Radar Canvas Scope
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(290.dp)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            WifiRadarCanvas(
                radarState = radarState,
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Calibration Progress Bar
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (radarState.isCalibrated) "CALIBRATION: COMPLETE (360° MAPPED)" else "CALIBRATING: ROTATE DEVICE SLOWLY",
                    style = CliTypography.TelemetryLabel,
                    color = if (radarState.isCalibrated) CliAccentGreen else CliAccent24GHz
                )
                Text(
                    text = "${radarState.calibrationPercent}%",
                    style = CliTypography.CodeMono,
                    color = if (radarState.isCalibrated) CliAccentGreen else CliAccent24GHz
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { radarState.calibrationPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp)),
                color = if (radarState.isCalibrated) CliAccentGreen else CliAccent24GHz,
                trackColor = CliSurfaceElevated
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Telemetry Grid
        CliPanel(
            borderColor = CliBorderSubtle,
            containerColor = CliSurfaceElevated,
            contentPadding = PaddingValues(12.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "EST. DISTANCE", style = CliTypography.TelemetryLabel)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (radarState.distanceMeters > 0) "~${radarState.distanceMeters} m" else "N/A",
                        style = CliTypography.TelemetryValue
                    )
                }

                Column {
                    Text(text = "PROXIMITY ZONE", style = CliTypography.TelemetryLabel)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = radarState.proximityZone,
                        style = CliTypography.TelemetryValue,
                        color = CliAccent5GHz
                    )
                }

                Column {
                    Text(text = "ROUTER BEARING", style = CliTypography.TelemetryLabel)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (radarState.isCalibrated) RadarEngine.formatBearingCompass(radarState.targetBearing) else "CALIBRATING",
                        style = CliTypography.TelemetryValue,
                        color = if (radarState.isCalibrated) CliAccentGreen else CliTextTertiary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CliButton(
                text = "RE-CALIBRATE BEARING",
                variant = CliButtonVariant.Outlined,
                onClick = onResetCalibration,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
