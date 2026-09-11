package com.quintz.wifi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val shizukuState by viewModel.shizukuState.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val radios by viewModel.radios.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val message by viewModel.message.collectAsState()
    val watchdogActive by viewModel.watchdogActive.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var targetRadioForPassword by remember { mutableStateOf<AccessPointRadio?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(PrimaryIndigoContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = Accent5GHz,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "Quintz",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                "5 GHz Wi-Fi Steering",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshAll() },
                        enabled = !isOperating && shizukuState.isPermissionGranted
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = if (isOperating) TextTertiary else TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Shizuku Status Banner
            item {
                ShizukuStatusCard(
                    shizukuState = shizukuState,
                    onRequestPermission = { viewModel.requestShizukuPermission() }
                )
            }

            // 2. Main Live Status Hero Card
            item {
                LiveStatusHeroCard(
                    status = wifiStatus,
                    onToggleLock = {
                        val saved = viewModel.getSavedPassword(wifiStatus.ssid)
                        if (saved.isNotEmpty()) {
                            if (wifiStatus.isLockedToBssid && wifiStatus.band == BandType.BAND_5_GHZ) {
                                viewModel.unlockToAuto()
                            } else {
                                viewModel.forceLock5Ghz(saved)
                            }
                        } else {
                            passwordInput = ""
                            targetRadioForPassword = null
                            showPasswordDialog = true
                        }
                    },
                    isOperating = isOperating
                )
            }

            // 3. Smart Watchdog Card
            item {
                WatchdogCard(
                    isActive = watchdogActive,
                    onToggle = { viewModel.toggleWatchdog(it) }
                )
            }

            // 4. Quick Settings Tile Card
            item {
                QuickSettingsTileCard(
                    onAddTile = { viewModel.requestAddQuickTile() }
                )
            }

            // 5. Detected AP Radios List
            item {
                Text(
                    text = if (wifiStatus.ssid.isNotEmpty()) "Available Radios for \"${wifiStatus.ssid}\"" else "Available Radios",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }

            if (radios.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (shizukuState.isPermissionGranted) "No scan data yet. Tap Refresh above to scan."
                                else "Authorize Shizuku above to view AP radios.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                items(radios) { radio ->
                    RadioCard(
                        radio = radio,
                        onLockClick = {
                            val saved = viewModel.getSavedPassword(wifiStatus.ssid)
                            if (saved.isNotEmpty()) {
                                viewModel.lockToSpecificRadio(radio, saved)
                            } else {
                                targetRadioForPassword = radio
                                passwordInput = ""
                                showPasswordDialog = true
                            }
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Password Prompt Dialog
    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            containerColor = DarkSurfaceElevated,
            title = {
                Text(
                    "Enter Password for \"${wifiStatus.ssid}\"",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Android requires your Wi-Fi password to force association to specific BSSIDs. It will be stored securely on your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Wi-Fi Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = Accent5GHz,
                            unfocusedBorderColor = DarkSurfaceBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (passwordInput.isNotBlank()) {
                            showPasswordDialog = false
                            val target = targetRadioForPassword
                            if (target != null) {
                                viewModel.lockToSpecificRadio(target, passwordInput)
                            } else {
                                viewModel.forceLock5Ghz(passwordInput)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent5GHz, contentColor = Color.Black)
                ) {
                    Text("Connect & Lock", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
fun ShizukuStatusCard(
    shizukuState: ShizukuState,
    onRequestPermission: () -> Unit
) {
    if (shizukuState.isPermissionGranted) return

    val (cardColor, title, description, buttonText) = when {
        !shizukuState.isInstalled -> Quadruple(
            AccentRed.copy(alpha = 0.15f),
            "Shizuku Not Installed",
            "Shizuku is required to bypass Android network restrictions without root.",
            null
        )
        !shizukuState.isRunning -> Quadruple(
            Accent24GHz.copy(alpha = 0.15f),
            "Shizuku Service Stopped",
            "Please open the Shizuku app and start the service via Wireless Debugging.",
            null
        )
        else -> Quadruple(
            PrimaryIndigoContainer,
            "Shizuku Permission Required",
            "Grant permission so Quintz can steer and lock your Wi-Fi frequencies.",
            "Grant Permission"
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = if (shizukuState.isPermissionGranted) AccentGreen else Accent24GHz,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                if (buttonText != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(buttonText, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveStatusHeroCard(
    status: WifiStatus,
    onToggleLock: () -> Unit,
    isOperating: Boolean
) {
    val is5Ghz = status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
    val cardBorderColor by animateColorAsState(
        targetValue = if (status.isLockedToBssid && is5Ghz) Accent5GHz else DarkSurfaceBorder,
        label = "heroBorder"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, cardBorderColor, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: SSID & Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (status.isConnected) status.ssid
                               else if (isOperating) "Switching Band..."
                               else "Not Connected",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (status.isConnected) "IP: ${status.ipAddress}"
                               else if (isOperating) "Binding to target AP & verifying DHCP..."
                               else "Connect to Wi-Fi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                if (status.isConnected) {
                    val badgeColor = if (is5Ghz) Accent5GHz else Accent24GHz
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(badgeColor.copy(alpha = 0.2f))
                            .border(1.dp, badgeColor, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = status.band.displayName,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                } else if (isOperating) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryIndigoContainer)
                            .border(1.dp, Accent5GHz, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Switching...",
                            color = Accent5GHz,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            if (!status.isConnected && isOperating) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Accent5GHz,
                    trackColor = DarkSurfaceElevated
                )
            }

            // Telemetry Grid
            if (status.isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(
                        label = "Signal",
                        value = "${status.rssi} dBm",
                        icon = Icons.Default.SignalWifi4Bar,
                        tint = if (status.rssi > -65) AccentGreen else Accent24GHz
                    )
                    MetricItem(
                        label = "Link Speed",
                        value = "${status.linkSpeedMbps} Mbps",
                        icon = Icons.Default.Speed,
                        tint = Accent5GHz
                    )
                    MetricItem(
                        label = "Channel",
                        value = "${status.frequency} MHz",
                        icon = Icons.Default.Radio,
                        tint = TextPrimary
                    )
                    MetricItem(
                        label = "Standard",
                        value = status.standard.uppercase().ifEmpty { "Wi-Fi" },
                        icon = Icons.Default.SettingsInputAntenna,
                        tint = TextSecondary
                    )
                }

                // Current BSSID & Lock Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceElevated)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (status.isLockedToBssid) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (status.isLockedToBssid) Accent5GHz else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (status.isLockedToBssid) "LOCKED TO BSSID" else "ROUTER AUTO-STEERING",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (status.isLockedToBssid) Accent5GHz else TextSecondary
                            )
                        }
                        Text(
                            text = status.bssid,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }

                // Giant Action Button
                val isLockedTo5G = status.isLockedToBssid && is5Ghz
                val buttonBg = if (isLockedTo5G) DarkSurfaceElevated else Accent5GHz
                val buttonContentColor = if (isLockedTo5G) TextPrimary else Color(0xFF001A24)

                Button(
                    onClick = onToggleLock,
                    enabled = !isOperating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = buttonBg,
                        contentColor = buttonContentColor,
                        disabledContainerColor = DarkSurfaceElevated,
                        disabledContentColor = TextPrimary
                    )
                ) {
                    if (isOperating) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Accent5GHz,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Switching & settling connection...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = TextPrimary
                            )
                        }
                    } else {
                        Icon(
                            imageVector = if (isLockedTo5G) Icons.Default.LockOpen else Icons.Default.Lock,
                            contentDescription = null,
                            tint = buttonContentColor
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isLockedTo5G) "Unlock to Auto (Router Default)" else "Force 5 GHz Now",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = buttonContentColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetricItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
    }
}

@Composable
fun WatchdogCard(
    isActive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isActive) Accent5GHz else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Smart Out-of-Range Fallback",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Automatically unlocks to 2.4 GHz if 5 GHz fades (<-82 dBm), preserving internet access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Switch(
                checked = isActive,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent5GHz
                )
            )
        }
    }
}

@Composable
fun QuickSettingsTileCard(
    onAddTile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = Accent5GHz,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Quick Settings Tile",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Switch between 5 GHz Lock and Auto-Roam directly from your notification shade with one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onAddTile,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Accent5GHz.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent5GHz),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Tile", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun RadioCard(
    radio: AccessPointRadio,
    onLockClick: () -> Unit
) {
    val is5G = radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ
    val bandColor = if (is5G) Accent5GHz else Accent24GHz

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (radio.isCurrent) 1.dp else 0.dp,
                color = if (radio.isCurrent) bandColor else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(bandColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = radio.band.displayName,
                            color = bandColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ch ${radio.channel} (${radio.frequency} MHz)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (radio.isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "• Connected",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = radio.bssid,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${radio.rssi} dBm",
                        fontWeight = FontWeight.Bold,
                        color = if (radio.rssi > -65) AccentGreen else Accent24GHz,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(
                    onClick = onLockClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (is5G) Accent5GHz.copy(alpha = 0.15f) else DarkSurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock to AP",
                        tint = if (is5G) Accent5GHz else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
