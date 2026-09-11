package com.quintz.wifi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.radar.RadarEngine
import com.quintz.wifi.ui.components.*
import com.quintz.wifi.ui.radar.WifiRadarView
import com.quintz.wifi.ui.theme.*

enum class RadioFilter {
    ALL,
    BAND_5G,
    BAND_24G
}

enum class RightPaneView {
    SCANNER,
    RADAR
}

enum class PhoneTab {
    CONTROLS,
    SCANNER,
    RADAR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val shizukuState by viewModel.shizukuState.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val radios by viewModel.radios.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val message by viewModel.message.collectAsState()
    val watchdogActive by viewModel.watchdogActive.collectAsState()
    val isTileAdded by viewModel.isTileAdded.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var targetRadioForPassword by remember { mutableStateOf<AccessPointRadio?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedFilter by remember { mutableStateOf(RadioFilter.ALL) }
    var selectedRightPane by remember { mutableStateOf(RightPaneView.SCANNER) }
    var selectedPhoneTab by remember { mutableStateOf(PhoneTab.CONTROLS) }

    // Initialize Radar Sensor Engine
    val radarEngine = remember { RadarEngine(context) }
    DisposableEffect(Unit) {
        radarEngine.start()
        onDispose { radarEngine.stop() }
    }

    LaunchedEffect(wifiStatus) {
        if (wifiStatus.isConnected) {
            radarEngine.updateWifiMetrics(
                ssid = wifiStatus.ssid,
                bssid = wifiStatus.bssid,
                rssi = wifiStatus.rssi,
                frequencyMhz = wifiStatus.frequency
            )
        }
    }

    val filteredRadios = remember(radios, selectedFilter) {
        when (selectedFilter) {
            RadioFilter.ALL -> radios
            RadioFilter.BAND_5G -> radios.filter { it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ }
            RadioFilter.BAND_24G -> radios.filter { it.band == BandType.BAND_2_4_GHZ }
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = CliBackground,
        contentWindowInsets = WindowInsets.systemBars,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                CliPanel(
                    borderColor = CliBorderActive,
                    containerColor = CliSurfaceElevated,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "> ${data.visuals.message}",
                        style = CliTypography.CodeMono,
                        color = CliTextPrimary
                    )
                }
            }
        },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CliBackground)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CliStatusDot(
                            color = if (shizukuState.isPermissionGranted) CliAccentGreen else CliAccent24GHz,
                            isPulsing = isOperating
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Quintz",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            letterSpacing = (-0.3).sp,
                            color = CliTextPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shizukuState.isPermissionGranted) {
                            CliBadge(
                                text = "SHIZUKU OK",
                                accentColor = CliAccentGreen,
                                backgroundColor = CliAccentGreenBg,
                                borderColor = CliAccentGreen.copy(alpha = 0.3f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        IconButton(
                            onClick = { viewModel.refreshAll() },
                            enabled = !isOperating && shizukuState.isPermissionGranted,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Telemetry",
                                tint = if (isOperating) CliTextTertiary else CliTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                CliDivider()
            }
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val isWideScreen = maxWidth >= 760.dp

            if (isWideScreen) {
                // ── Two-Pane Mission Control (Tablets & Landscape) ──
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Left Pane: Telemetry & Controls
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (!shizukuState.isPermissionGranted) {
                            CliShizukuBanner(
                                shizukuState = shizukuState,
                                onRequestPermission = { viewModel.requestShizukuPermission() }
                            )
                        }

                        CliConnectedHeroPanel(
                            status = wifiStatus,
                            isOperating = isOperating,
                            onToggleLock = {
                                val saved = viewModel.getSavedPassword(wifiStatus.ssid)
                                if (saved.isNotEmpty()) {
                                    if (wifiStatus.isLockedToBssid && (wifiStatus.band == BandType.BAND_5_GHZ || wifiStatus.band == BandType.BAND_6_GHZ)) {
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
                            onOpenRadar = { selectedRightPane = RightPaneView.RADAR }
                        )

                        CliWatchdogPanel(
                            isActive = watchdogActive,
                            onToggle = { viewModel.toggleWatchdog(it) }
                        )

                        CliQuickTilePanel(
                            isTileAdded = isTileAdded,
                            onAddTile = { viewModel.requestAddQuickTile() },
                            onRemoveTile = { viewModel.removeQuickTile() }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 1dp Vertical Divider
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 1.dp,
                        color = CliBorderSubtle
                    )

                    // Right Pane: View Switcher (Scanner vs Radar)
                    Column(
                        modifier = Modifier
                            .weight(1.25f)
                            .fillMaxHeight()
                    ) {
                        // Switcher Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Tab Toggle
                            Row(
                                modifier = Modifier
                                    .background(CliSurfaceElevated, RoundedCornerShape(4.dp))
                                    .border(1.dp, CliBorder, RoundedCornerShape(4.dp))
                                    .padding(2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (selectedRightPane == RightPaneView.SCANNER) CliSurfaceActive else Color.Transparent)
                                        .clickable { selectedRightPane = RightPaneView.SCANNER }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "AP SCANNER (${radios.size})",
                                        style = CliTypography.BadgeText,
                                        color = if (selectedRightPane == RightPaneView.SCANNER) CliTextPrimary else CliTextTertiary
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(if (selectedRightPane == RightPaneView.RADAR) CliSurfaceActive else Color.Transparent)
                                        .clickable { selectedRightPane = RightPaneView.RADAR }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "WI-FI RADAR",
                                        style = CliTypography.BadgeText,
                                        color = if (selectedRightPane == RightPaneView.RADAR) CliAccent5GHz else CliTextTertiary
                                    )
                                }
                            }

                            if (selectedRightPane == RightPaneView.SCANNER) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CliFilterChip(
                                        label = "ALL (${radios.size})",
                                        isSelected = selectedFilter == RadioFilter.ALL,
                                        onClick = { selectedFilter = RadioFilter.ALL }
                                    )
                                    CliFilterChip(
                                        label = "5 GHz",
                                        isSelected = selectedFilter == RadioFilter.BAND_5G,
                                        onClick = { selectedFilter = RadioFilter.BAND_5G }
                                    )
                                    CliFilterChip(
                                        label = "2.4 GHz",
                                        isSelected = selectedFilter == RadioFilter.BAND_24G,
                                        onClick = { selectedFilter = RadioFilter.BAND_24G }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Crossfade(targetState = selectedRightPane, label = "rightPaneCrossfade") { pane ->
                            when (pane) {
                                RightPaneView.SCANNER -> {
                                    if (filteredRadios.isEmpty()) {
                                        CliPanel(
                                            containerColor = CliSurface,
                                            contentPadding = PaddingValues(24.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (shizukuState.isPermissionGranted)
                                                        "No access points match filter. Tap refresh [r] to poll."
                                                    else
                                                        "Authorize Shizuku to unlock AP radio telemetry.",
                                                    style = CliTypography.CodeMono,
                                                    color = CliTextTertiary
                                                )
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            items(filteredRadios) { radio ->
                                                CliRadioRow(
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
                                            item {
                                                Spacer(modifier = Modifier.height(16.dp))
                                            }
                                        }
                                    }
                                }

                                RightPaneView.RADAR -> {
                                    WifiRadarView(
                                        radarState = radarEngine.radarState,
                                        onResetCalibration = { radarEngine.resetCalibration() }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Single Column with Segmented Switcher (Phones & Portrait) ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Phone Segmented Navigation Tab Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CliSurfaceElevated, RoundedCornerShape(6.dp))
                            .border(1.dp, CliBorder, RoundedCornerShape(6.dp))
                            .padding(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedPhoneTab == PhoneTab.CONTROLS) CliSurfaceActive else Color.Transparent)
                                .clickable { selectedPhoneTab = PhoneTab.CONTROLS }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "CONTROLS",
                                style = CliTypography.BadgeText,
                                color = if (selectedPhoneTab == PhoneTab.CONTROLS) CliTextPrimary else CliTextTertiary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedPhoneTab == PhoneTab.SCANNER) CliSurfaceActive else Color.Transparent)
                                .clickable { selectedPhoneTab = PhoneTab.SCANNER }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "SCANNER (${radios.size})",
                                style = CliTypography.BadgeText,
                                color = if (selectedPhoneTab == PhoneTab.SCANNER) CliTextPrimary else CliTextTertiary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedPhoneTab == PhoneTab.RADAR) CliSurfaceActive else Color.Transparent)
                                .clickable { selectedPhoneTab = PhoneTab.RADAR }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "RADAR",
                                style = CliTypography.BadgeText,
                                color = if (selectedPhoneTab == PhoneTab.RADAR) CliAccent5GHz else CliTextTertiary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Crossfade(targetState = selectedPhoneTab, label = "tabCrossfade") { tab ->
                        when (tab) {
                            PhoneTab.CONTROLS -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    if (!shizukuState.isPermissionGranted) {
                                        CliShizukuBanner(
                                            shizukuState = shizukuState,
                                            onRequestPermission = { viewModel.requestShizukuPermission() }
                                        )
                                    }

                                    CliConnectedHeroPanel(
                                        status = wifiStatus,
                                        isOperating = isOperating,
                                        onToggleLock = {
                                            val saved = viewModel.getSavedPassword(wifiStatus.ssid)
                                            if (saved.isNotEmpty()) {
                                                if (wifiStatus.isLockedToBssid && (wifiStatus.band == BandType.BAND_5_GHZ || wifiStatus.band == BandType.BAND_6_GHZ)) {
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
                                        onOpenRadar = { selectedPhoneTab = PhoneTab.RADAR }
                                    )

                                    CliWatchdogPanel(
                                        isActive = watchdogActive,
                                        onToggle = { viewModel.toggleWatchdog(it) }
                                    )

                                    CliQuickTilePanel(
                                        isTileAdded = isTileAdded,
                                        onAddTile = { viewModel.requestAddQuickTile() },
                                        onRemoveTile = { viewModel.removeQuickTile() }
                                    )

                                    Spacer(modifier = Modifier.height(24.dp))
                                }
                            }

                            PhoneTab.SCANNER -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "DETECTED RADIOS",
                                            style = CliTypography.TelemetryLabel
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            CliFilterChip(
                                                label = "ALL",
                                                isSelected = selectedFilter == RadioFilter.ALL,
                                                onClick = { selectedFilter = RadioFilter.ALL }
                                            )
                                            CliFilterChip(
                                                label = "5 GHz",
                                                isSelected = selectedFilter == RadioFilter.BAND_5G,
                                                onClick = { selectedFilter = RadioFilter.BAND_5G }
                                            )
                                            CliFilterChip(
                                                label = "2.4 GHz",
                                                isSelected = selectedFilter == RadioFilter.BAND_24G,
                                                onClick = { selectedFilter = RadioFilter.BAND_24G }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    if (filteredRadios.isEmpty()) {
                                        CliPanel(
                                            containerColor = CliSurface,
                                            contentPadding = PaddingValues(24.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = if (shizukuState.isPermissionGranted)
                                                        "No access points match filter."
                                                    else
                                                        "Authorize Shizuku to unlock BSSID telemetry.",
                                                    style = CliTypography.CodeMono,
                                                    color = CliTextTertiary
                                                )
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            items(filteredRadios) { radio ->
                                                CliRadioRow(
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
                                            item {
                                                Spacer(modifier = Modifier.height(24.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            PhoneTab.RADAR -> {
                                WifiRadarView(
                                    radarState = radarEngine.radarState,
                                    onResetCalibration = { radarEngine.resetCalibration() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Industrial Passphrase Prompt Dialog
    if (showPasswordDialog) {
        Dialog(onDismissRequest = { showPasswordDialog = false }) {
            CliPanel(
                borderColor = CliBorderActive,
                containerColor = CliSurface,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(20.dp)
            ) {
                Text(
                    text = "AUTHENTICATE BSSID",
                    style = CliTypography.TelemetryLabel,
                    color = CliAccent5GHz
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Passphrase for \"${wifiStatus.ssid}\"",
                    style = Typography.titleMedium,
                    color = CliTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Android requires credentials to enforce specific BSSID binding. Stored securely on-device.",
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    placeholder = {
                        Text("Enter passphrase :_", style = CliTypography.CodeMono, color = CliTextTertiary)
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    textStyle = CliTypography.CodeMono.copy(color = CliTextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CliTextPrimary,
                        unfocusedTextColor = CliTextPrimary,
                        focusedContainerColor = CliSurfaceElevated,
                        unfocusedContainerColor = CliSurfaceElevated,
                        focusedBorderColor = CliAccent5GHz,
                        unfocusedBorderColor = CliBorder
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CliButton(
                        text = "CANCEL",
                        variant = CliButtonVariant.Ghost,
                        onClick = { showPasswordDialog = false },
                        modifier = Modifier.weight(1f)
                    )
                    CliButton(
                        text = "BIND & LOCK",
                        variant = CliButtonVariant.Primary,
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
                        modifier = Modifier.weight(1.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun CliShizukuBanner(
    shizukuState: ShizukuState,
    onRequestPermission: () -> Unit
) {
    val (code, desc, showBtn) = when {
        !shizukuState.isInstalled -> Triple(
            "ERR_SHIZUKU_NOT_INSTALLED",
            "Shizuku service is required to bypass Android network restrictions.",
            false
        )
        !shizukuState.isRunning -> Triple(
            "ERR_SHIZUKU_DAEMON_STOPPED",
            "Start Shizuku via Wireless Debugging to activate Wi-Fi steering.",
            false
        )
        else -> Triple(
            "SYS_AUTH_REQUIRED",
            "Grant Shizuku permission to allow Quintz to bind specific BSSIDs.",
            true
        )
    }

    CliPanel(
        borderColor = if (showBtn) CliAccent24GHz.copy(alpha = 0.5f) else CliAccentRed.copy(alpha = 0.5f),
        containerColor = if (showBtn) CliAccent24GHzBg else CliAccentRedBg
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "[$code]",
                    style = CliTypography.BadgeText,
                    color = if (showBtn) CliAccent24GHz else CliAccentRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
            }

            if (showBtn) {
                Spacer(modifier = Modifier.width(12.dp))
                CliButton(
                    text = "GRANT",
                    variant = CliButtonVariant.Primary,
                    onClick = onRequestPermission
                )
            }
        }
    }
}

@Composable
fun CliConnectedHeroPanel(
    status: WifiStatus,
    isOperating: Boolean,
    onToggleLock: () -> Unit,
    onOpenRadar: (() -> Unit)? = null
) {
    val is5G = status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
    val isLocked = status.isLockedToBssid && is5G

    val borderColor by animateColorAsState(
        targetValue = if (isLocked) CliAccent5GHz.copy(alpha = 0.6f) else CliBorder,
        label = "heroBorder"
    )

    CliPanel(
        borderColor = borderColor,
        containerColor = CliSurface,
        contentPadding = PaddingValues(18.dp)
    ) {
        // Section Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CURRENTLY CONNECTED",
                style = CliTypography.TelemetryLabel
            )

            if (status.isConnected) {
                val bandColor = if (is5G) CliAccent5GHz else CliAccent24GHz
                val bandBg = if (is5G) CliAccent5GHzBg else CliAccent24GHzBg
                CliBadge(
                    text = status.band.displayName,
                    accentColor = bandColor,
                    backgroundColor = bandBg,
                    borderColor = bandColor.copy(alpha = 0.4f)
                )
            } else if (isOperating) {
                CliBadge(
                    text = "STEERING...",
                    accentColor = CliAccent5GHz,
                    backgroundColor = CliAccent5GHzBg,
                    borderColor = CliAccent5GHz.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // SSID Title
        Text(
            text = if (status.isConnected) status.ssid
                   else if (isOperating) "Negotiating Band..."
                   else "Not Connected",
            style = Typography.headlineMedium,
            color = CliTextPrimary
        )

        Text(
            text = if (status.isConnected) "IP: ${status.ipAddress}"
                   else if (isOperating) "Binding to target AP & verifying DHCP..."
                   else "Connect to Wi-Fi",
            style = CliTypography.CodeMono,
            color = CliTextTertiary
        )

        if (isOperating) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp)),
                color = CliAccent5GHz,
                trackColor = CliSurfaceElevated
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Telemetry Grid
        if (status.isConnected) {
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
                    CliTelemetryMetric(
                        label = "SIGNAL",
                        content = { CliSignalBars(status.rssi, showDbmText = true) }
                    )
                    CliTelemetryMetric(
                        label = "LINK SPEED",
                        value = "${status.linkSpeedMbps} Mbps"
                    )
                    CliTelemetryMetric(
                        label = "CHANNEL",
                        value = "Ch ${status.frequency} MHz"
                    )
                    CliTelemetryMetric(
                        label = "STANDARD",
                        value = status.standard.uppercase().ifEmpty { "Wi-Fi" }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // BSSID Lock Bar + Radar Trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CliSurfaceElevated, RoundedCornerShape(4.dp))
                    .border(1.dp, CliBorderSubtle, RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (status.isLockedToBssid) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (status.isLockedToBssid) CliAccent5GHz else CliTextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (status.isLockedToBssid) "LOCKED TO BSSID" else "ROUTER AUTO-STEER",
                        style = CliTypography.BadgeText,
                        color = if (status.isLockedToBssid) CliAccent5GHz else CliTextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = status.bssid,
                        style = CliTypography.CodeMono,
                        color = CliTextSecondary
                    )
                    if (onOpenRadar != null && status.isConnected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CliBadge(
                            text = "RADAR",
                            accentColor = CliAccent5GHz,
                            backgroundColor = CliAccent5GHzBg,
                            borderColor = CliAccent5GHz.copy(alpha = 0.5f),
                            modifier = Modifier.clickable { onOpenRadar() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Command Button
            CliButton(
                onClick = onToggleLock,
                enabled = !isOperating,
                loading = isOperating,
                variant = if (isLocked) CliButtonVariant.Outlined else CliButtonVariant.Primary,
                text = if (isOperating) "SWITCHING BAND..." else if (isLocked) "UNLOCK TO AUTO-ROAM" else "LOCK TO 5 GHz BSSID",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CliTelemetryMetric(
    label: String,
    value: String? = null,
    content: (@Composable () -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = CliTypography.TelemetryLabel
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (content != null) {
            content()
        } else if (value != null) {
            Text(
                text = value,
                style = CliTypography.TelemetryValue
            )
        }
    }
}

@Composable
fun CliWatchdogPanel(
    isActive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    CliPanel(
        containerColor = CliSurface,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SMART FALLBACK WATCHDOG",
                    style = CliTypography.TelemetryLabel
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Auto-unlocks to 2.4 GHz if 5 GHz signal drops below -82 dBm to safeguard internet access.",
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isActive,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CliButtonPrimary,
                    checkedTrackColor = CliAccent5GHz,
                    uncheckedThumbColor = CliTextTertiary,
                    uncheckedTrackColor = CliSurfaceElevated,
                    uncheckedBorderColor = CliBorder
                )
            )
        }
    }
}

@Composable
fun CliQuickTilePanel(
    isTileAdded: Boolean,
    onAddTile: () -> Unit,
    onRemoveTile: () -> Unit
) {
    CliPanel(
        containerColor = CliSurface,
        contentPadding = PaddingValues(16.dp)
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
                    variant = CliButtonVariant.Ghost,
                    onClick = onRemoveTile
                )
            } else {
                CliButton(
                    text = "ADD TILE",
                    variant = CliButtonVariant.Outlined,
                    onClick = onAddTile
                )
            }
        }
    }
}

@Composable
fun CliRadioRow(
    radio: AccessPointRadio,
    onLockClick: () -> Unit
) {
    val is5G = radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ
    val bandColor = if (is5G) CliAccent5GHz else CliAccent24GHz
    val bandBg = if (is5G) CliAccent5GHzBg else CliAccent24GHzBg

    CliPanel(
        borderColor = if (radio.isCurrent) bandColor.copy(alpha = 0.5f) else CliBorder,
        containerColor = CliSurface,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
                        color = CliTextPrimary
                    )
                    if (radio.isCurrent) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "● ACTIVE",
                            style = CliTypography.CodeMono,
                            color = CliAccentGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = radio.bssid,
                    style = CliTypography.CodeMono,
                    color = CliTextTertiary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CliSignalBars(radio.rssi, showDbmText = true)

                CliButton(
                    text = if (radio.isCurrent) "LOCKED" else "BIND",
                    variant = if (radio.isCurrent) CliButtonVariant.Ghost else CliButtonVariant.Outlined,
                    enabled = !radio.isCurrent,
                    onClick = onLockClick
                )
            }
        }
    }
}
