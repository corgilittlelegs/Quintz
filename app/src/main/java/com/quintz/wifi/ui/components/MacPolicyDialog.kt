package com.quintz.wifi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.ui.theme.*

@Composable
fun MacPolicyDialog(
    targetSsid: String,
    onSelect: (MacAddressPolicy) -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onCancel() },
            contentAlignment = Alignment.Center
        ) {
            CliPanel(
                borderColor = CliAccent5GHz,
                containerColor = CliSurface,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {}
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("MAC ADDRESS IDENTITY", style = CliTypography.TelemetryLabel, color = CliAccent5GHz)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Network: $targetSsid", style = Typography.headlineSmall, color = CliTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Choose how Android identifies this device on this Wi-Fi network. This choice is remembered per SSID.",
                        style = Typography.bodyMedium,
                        color = CliTextSecondary
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    CliButton(
                        text = "USE DEVICE MAC (STATIC DHCP)",
                        variant = CliButtonVariant.Primary,
                        onClick = { onSelect(MacAddressPolicy.DEVICE) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CliButton(
                        text = "USE RANDOMIZED MAC (PRIVACY)",
                        variant = CliButtonVariant.Ghost,
                        onClick = { onSelect(MacAddressPolicy.RANDOMIZED) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CliButton(
                        text = "CANCEL",
                        variant = CliButtonVariant.Outlined,
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
