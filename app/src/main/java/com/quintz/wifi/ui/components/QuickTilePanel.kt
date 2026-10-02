package com.quintz.wifi.ui.components

import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.quintz.wifi.ui.theme.*

@Composable
fun CliQuickTilePanel(
    isTileAdded: Boolean,
    onAddTile: () -> Unit,
    onRemoveTile: () -> Unit
) {
    CliPanel(
        containerColor = CliSurface,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "QUICK SETTINGS TILE",
                        style = CliTypography.TelemetryLabel
                    )
                    if (isTileAdded) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CliBadge(
                            text = "ACTIVE",
                            accentColor = CliAccentGreen,
                            backgroundColor = CliAccentGreenBg,
                            borderColor = CliAccentGreen.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isTileAdded)
                        "Tile is active in Quick Settings. Pull down notification shade to toggle 5 GHz lock."
                    else
                        "Toggle 5 GHz lock directly from Android's notification shade with one tap.",
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            if (isTileAdded) {
                CliButton(
                    text = "REMOVE",
                    size = CliButtonSize.Compact,
                    variant = CliButtonVariant.Ghost,
                    onClick = onRemoveTile
                )
            } else {
                CliButton(
                    text = "ADD TILE",
                    size = CliButtonSize.Compact,
                    variant = CliButtonVariant.Outlined,
                    onClick = onAddTile
                )
            }
        }
    }
}

