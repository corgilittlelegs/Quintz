package com.quintz.wifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onLockClick: () -> Unit,
    onUnpinClick: () -> Unit,
    isOperating: Boolean,
    modifier: Modifier = Modifier,
    asCard: Boolean = false
) {
    val is5G = radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ
    val bandColor = if (is5G) CliAccent5GHz else CliAccent24GHz
    val bandBg = if (is5G) CliAccent5GHzBg else CliAccent24GHzBg

    val rowContent = @Composable {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Column: Network Name, Band/Channel & BSSID
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                // Line 1: Network Name & Active/Pinned Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = radio.ssid.ifEmpty { "(Hidden Network)" },
                        style = CliTypography.CodeMono,
                        color = CliTextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isCurrent) {
                        CliBadge(
                            text = "ACTIVE",
                            accentColor = CliAccentGreen,
                            backgroundColor = CliAccentGreenBg,
                            borderColor = CliAccentGreen.copy(alpha = 0.5f)
                        )
                    }
                    if (isPinned) {
                        CliBadge(
                            text = "PINNED",
                            accentColor = CliAccent5GHz,
                            backgroundColor = CliAccent5GHzBg,
                            borderColor = CliAccent5GHz.copy(alpha = 0.5f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(5.dp))

                // Line 2: Band Details
                CliBadge(
                    text = radio.band.displayName,
                    accentColor = bandColor,
                    backgroundColor = bandBg,
                    borderColor = bandColor.copy(alpha = 0.4f)
                )

                Spacer(modifier = Modifier.height(5.dp))

                // Line 3: Channel Info before Hardware BSSID / MAC Address
                val channelPrefix = if (radio.channel > 0) "Ch ${radio.channel} · " else ""
                Text(
                    text = "$channelPrefix${radio.bssid}",
                    style = CliTypography.CodeMono,
                    color = CliTextTertiary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right Row: Signal Bars & Action Button Side-by-Side
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CliSignalBars(radio.rssi, showDbmText = true)

                CliButton(
                    text = when {
                        isPinned -> "UNPIN"
                        isCurrent -> "PIN"
                        else -> "BIND"
                    },
                    size = CliButtonSize.Compact,
                    variant = CliButtonVariant.Outlined,
                    enabled = !isOperating,
                    onClick = if (isPinned) onUnpinClick else onLockClick
                )
            }
        }
    }

    if (asCard) {
        CliPanel(
            borderColor = if (isCurrent) bandColor.copy(alpha = 0.5f) else CliBorder,
            containerColor = CliSurface,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            modifier = modifier
        ) {
            rowContent()
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (isCurrent) Modifier
                        .background(bandBg.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                        .border(1.dp, bandColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    else Modifier
                )
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            rowContent()
        }
    }
}
