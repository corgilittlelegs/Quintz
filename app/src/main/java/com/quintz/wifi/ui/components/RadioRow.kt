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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (radio.ssid.isNotEmpty()) {
                        Text(
                            text = radio.ssid,
                            style = CliTypography.CodeMono,
                            color = CliTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (isCurrent) {
                        CliBadge(
                            text = "ACTIVE",
                            accentColor = CliAccentGreen,
                            backgroundColor = CliAccentGreenBg,
                            borderColor = CliAccentGreen.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = radio.bssid,
                    style = CliTypography.CodeMono,
                    color = CliTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CliSignalBars(radio.rssi, showDbmText = true)

                CliButton(
                    text = when {
                        isPinned -> "PINNED"
                        isCurrent -> "CONNECTED"
                        else -> "BIND"
                    },
                    variant = if (isPinned) CliButtonVariant.Ghost else CliButtonVariant.Outlined,
                    enabled = !isPinned,
                    onClick = onLockClick
                )
            }
        }
    }
}
