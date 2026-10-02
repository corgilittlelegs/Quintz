package com.quintz.wifi.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.ui.theme.*

@Composable
fun CliRadioRow(
    radio: AccessPointRadio,
    isCurrent: Boolean,
    isPinned: Boolean,
    onLockClick: () -> Unit
) {
    val is5G = radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ
    val bandColor = if (is5G) CliAccent5GHz else CliAccent24GHz
    val bandBg = if (is5G) CliAccent5GHzBg else CliAccent24GHzBg

    CliPanel(
        borderColor = if (isCurrent) bandColor.copy(alpha = 0.5f) else CliBorder,
        containerColor = CliSurface,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Row 1: Network Name, Active Badge & Signal Strength
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
            ) {
                Text(
                    text = radio.ssid.ifEmpty { "(Hidden Network)" },
                    style = CliTypography.CodeMono,
                    color = CliTextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrent) {
                    CliBadge(
                        text = "ACTIVE",
                        accentColor = CliAccentGreen,
                        backgroundColor = CliAccentGreenBg,
                        borderColor = CliAccentGreen.copy(alpha = 0.5f)
                    )
                } else if (isPinned) {
                    CliBadge(
                        text = "PINNED",
                        accentColor = CliAccent5GHz,
                        backgroundColor = CliAccent5GHzBg,
                        borderColor = CliAccent5GHz.copy(alpha = 0.5f)
                    )
                }
            }

            CliSignalBars(radio.rssi, showDbmText = true)
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Row 2: Band & Channel Details + Action Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                CliBadge(
                    text = radio.band.displayName,
                    accentColor = bandColor,
                    backgroundColor = bandBg,
                    borderColor = bandColor.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Ch ${radio.channel} (${radio.frequency} MHz)",
                    style = CliTypography.CodeMono,
                    color = if (radio.ssid.isNotEmpty()) CliTextSecondary else CliTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isCurrent && !isPinned) {
                CliButton(
                    text = "BIND",
                    size = CliButtonSize.Compact,
                    variant = CliButtonVariant.Outlined,
                    onClick = onLockClick
                )
            } else if (isCurrent) {
                CliBadge(
                    text = "CONNECTED",
                    accentColor = bandColor,
                    backgroundColor = bandBg,
                    borderColor = bandColor.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Row 3: Hardware BSSID
        Text(
            text = radio.bssid,
            style = CliTypography.CodeMono,
            color = CliTextTertiary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
