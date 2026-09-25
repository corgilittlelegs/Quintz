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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.quintz.wifi.BuildConfig
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.components.*
import com.quintz.wifi.ui.graph.WifiGraphView
import com.quintz.wifi.ui.theme.*

enum class RadioFilter {
    ALL,
    BAND_5G,
    BAND_24G
}

enum class RightPaneView {
    SCANNER,
    GRAPH
}

enum class PhoneTab {
    CONTROLS,
    SCANNER,
    GRAPH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val shizukuState by viewModel.shizukuState.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val radios by viewModel.radios.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val message by viewModel.message.collectAsState()
    val watchdogActive by viewModel.watchdogActive.collectAsState()
    val isTileAdded by viewModel.isTileAdded.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showMacPolicyDialog by remember { mutableStateOf(false) }
    var targetSsidForMacPolicy by remember { mutableStateOf("") }
    var pendingLockAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var targetRadioForPassword by remember { mutableStateOf<AccessPointRadio?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun executeWithMacPolicyCheck(ssid: String, action: () -> Unit) {
        if (ssid.isEmpty() || viewModel.getMacPolicy(ssid) != null) {
            action()
        } else {
            targetSsidForMacPolicy = ssid
            pendingLockAction = action
            showMacPolicyDialog = true
        }
    }

    fun logPrimaryWifiControl(source: String, status: WifiStatus): String {
        val correlationId = DiagnosticLogger.newCorrelationId()
        val action = if (status.isSteeredOrLocked) "unlock_to_auto" else "prefer_5ghz"
        DiagnosticLogger.log(
            "USER_ACTION",
            "id=$correlationId source=$source callback=connected_hero_primary_button action=$action connected=${status.isConnected} ssid='${status.ssid}' bssid=${status.bssid} band=${status.band.displayName} rssi=${status.rssi} locked=${status.isLockedToBssid} preferred5G=${status.isPreferred5GHz} operating=$isOperating"
        )
        return correlationId
    }

    var selectedFilter by rememberSaveable { mutableStateOf(RadioFilter.ALL) }
    var selectedRightPane by rememberSaveable { mutableStateOf(RightPaneView.SCANNER) }
    var selectedPhoneTab by rememberSaveable { mutableStateOf(PhoneTab.CONTROLS) }

    // RF Telemetry Graph State from ViewModel
    val telemetryState by viewModel.telemetryState.collectAsState()

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
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 56.dp, start = 20.dp, end = 20.dp)
                    .widthIn(max = 520.dp)
            ) { data ->
                CliPanel(
                    borderColor = CliBorderActive,
                    containerColor = CliSurfaceElevated,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CliStatusDot(color = CliAccentGreen, isPulsing = false)
                        Text(
                            text = data.visuals.message,
                            style = CliTypography.CodeMono,
                            color = CliTextPrimary
                        )
                    }
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
                        // Interactive Shizuku Status Badge: click jumps directly into Shizuku or Play Store
                        val shizukuBadgeColor = if (shizukuState.isPermissionGranted) CliAccentGreen else CliAccent24GHz
                        val shizukuBadgeBg = if (shizukuState.isPermissionGranted) CliAccentGreenBg else CliAccent24GHzBg
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(shizukuBadgeBg)
                                .border(1.dp, shizukuBadgeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .clickable { ShizukuManager.launchOrInstall(context) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    shizukuState.isPermissionGranted -> "SHIZUKU OK"
                                    shizukuState.isRunning -> "AUTH NEEDED"
                                    shizukuState.isInstalled -> "DAEMON OFF"
                                    else -> "GET SHIZUKU"
                                },
                                style = CliTypography.BadgeText,
                                color = shizukuBadgeColor
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = "Open Shizuku",
                                tint = shizukuBadgeColor,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                        if (BuildConfig.DEBUG) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CliAccent24GHzBg)
                                    .border(1.dp, CliAccent24GHz.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    .clickable { showDiagnosticsDialog = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "DIAG",
                                    style = CliTypography.BadgeText,
                                    color = CliAccent24GHz
                                )
                            }
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

                        CliConnectedHeroPanel(
                            status = wifiStatus,
                            isOperating = isOperating,
                            onGetMacPolicy = { viewModel.getMacPolicy(it) },
                            onToggleMacPolicy = { ssid ->
                                val current = viewModel.getMacPolicy(ssid) ?: MacAddressPolicy.DEVICE
                                val next = if (current == MacAddressPolicy.DEVICE) MacAddressPolicy.RANDOMIZED else MacAddressPolicy.DEVICE
                                viewModel.setMacPolicy(ssid, next)
                            },
                            onToggleLock = {
                                val source = "main_screen_wide_primary_button"
                                val correlationId = logPrimaryWifiControl(source, wifiStatus)
                                if (wifiStatus.isSteeredOrLocked) {
                                    viewModel.unlockToAuto(source, correlationId)
                                } else {
                                    val saved = viewModel.getSavedPassword(wifiStatus.ssid)
                                    val isCurrentOpen = wifiStatus.securityType == "0" || wifiStatus.securityType == "open"
                                    if (saved.isNotEmpty() || isCurrentOpen) {
                                        executeWithMacPolicyCheck(wifiStatus.ssid) {
                                            viewModel.forceLock5Ghz(saved, requestSource = source, correlationId = correlationId)
                                        }
                                    } else {
                                        passwordInput = ""
                                        targetRadioForPassword = null
                                        showPasswordDialog = true
                                    }
                                }
                            },
                            onOpenGraph = { selectedRightPane = RightPaneView.GRAPH },
                            isWatchdogActive = watchdogActive
                        )

                        CliQuickTilePanel(
                            isTileAdded = isTileAdded,
                            onAddTile = { viewModel.requestAddQuickTile() },
                            onRemoveTile = { viewModel.removeQuickTile() }
                        )

                        CliShizukuPanel(
                            shizukuState = shizukuState,
                            onOpenShizuku = { ShizukuManager.openShizukuApp(context) },
                            onOpenPlayStore = { ShizukuManager.openPlayStore(context) },
                            onRequestPermission = { viewModel.requestShizukuPermission() }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 1dp Vertical Divider
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        thickness = 1.dp,
                        color = CliBorderSubtle
                    )

                    // Right Pane: View Switcher (Scanner vs Telemetry Graph)
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
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
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
                                        .background(if (selectedRightPane == RightPaneView.GRAPH) CliSurfaceActive else Color.Transparent)
                                        .clickable { selectedRightPane = RightPaneView.GRAPH }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "RF TELEMETRY",
                                        style = CliTypography.BadgeText,
                                        color = if (selectedRightPane == RightPaneView.GRAPH) CliAccent5GHz else CliTextTertiary
                                    )
                                }
                            }

                            if (selectedRightPane == RightPaneView.SCANNER) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
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
                                    CliScannerRefreshButton(
                                        isScanning = isScanning,
                                        enabled = !isOperating && shizukuState.isPermissionGranted,
                                        onRefresh = { viewModel.refreshAll() },
                                        showLabel = true
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
                                                val isCurrent = wifiStatus.isConnected && radio.bssid.equals(wifiStatus.bssid, ignoreCase = true)
                                                CliRadioRow(
                                                    radio = radio,
                                                    isCurrent = isCurrent,
                                                    isPinned = wifiStatus.isLockedToBssid && wifiStatus.lockedBssid?.equals(radio.bssid, ignoreCase = true) == true,
                                                    onLockClick = {
                                                        val source = "main_screen_wide_radio_lock"
                                                        val correlationId = DiagnosticLogger.newCorrelationId()
                                                        DiagnosticLogger.log(
                                                            "USER_ACTION",
                                                            "id=$correlationId source=$source callback=radio_lock_button targetSsid='${radio.ssid}' targetBssid=${radio.bssid} band=${radio.band.displayName} rssi=${radio.rssi} currentSsid='${wifiStatus.ssid}' currentBssid=${wifiStatus.bssid} currentBand=${wifiStatus.band.displayName} currentRssi=${wifiStatus.rssi}"
                                                        )
                                                        val isOpen = radio.flags.uppercase().let {
                                                            !it.contains("PSK") && !it.contains("SAE") && !it.contains("WEP")
                                                        }
                                                        val targetSsid = radio.ssid.ifEmpty { wifiStatus.ssid }
                                                        if (isOpen) {
                                                            executeWithMacPolicyCheck(targetSsid) {
                                                                viewModel.lockToSpecificRadio(radio, "", requestSource = source, correlationId = correlationId)
                                                            }
                                                        } else {
                                                            val saved = viewModel.getSavedPassword(targetSsid)
                                                            if (saved.isNotEmpty()) {
                                                                executeWithMacPolicyCheck(targetSsid) {
                                                                    viewModel.lockToSpecificRadio(radio, saved, requestSource = source, correlationId = correlationId)
                                                                }
                                                            } else {
                                                                targetRadioForPassword = radio
                                                                passwordInput = ""
                                                                showPasswordDialog = true
                                                            }
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

                                RightPaneView.GRAPH -> {
                                    WifiGraphView(
                                        state = telemetryState,
                                        onTogglePause = { viewModel.togglePauseTelemetry() },
                                        onClearHistory = { viewModel.clearTelemetryHistory() },
                                        onSelectCandidate = { viewModel.selectCandidateBssid(it) },
                                        onLockBssid = { bssid ->
                                            val radio = radios.find { it.bssid.equals(bssid, ignoreCase = true) }
                                            if (radio != null) {
                                                val targetSsid = radio.ssid.ifEmpty { wifiStatus.ssid }
                                                val saved = viewModel.getSavedPassword(targetSsid)
                                                val correlationId = DiagnosticLogger.newCorrelationId()
                                                executeWithMacPolicyCheck(targetSsid) {
                                                    viewModel.lockToSpecificRadio(radio, saved, requestSource = "graph", correlationId = correlationId)
                                                }
                                            }
                                        },
                                        isConnected = wifiStatus.isConnected
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
                                .background(if (selectedPhoneTab == PhoneTab.GRAPH) CliSurfaceActive else Color.Transparent)
                                .clickable { selectedPhoneTab = PhoneTab.GRAPH }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "TELEMETRY",
                                style = CliTypography.BadgeText,
                                color = if (selectedPhoneTab == PhoneTab.GRAPH) CliAccent5GHz else CliTextTertiary
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

                                    CliConnectedHeroPanel(
                                        status = wifiStatus,
                                        isOperating = isOperating,
                                        onGetMacPolicy = { viewModel.getMacPolicy(it) },
                                        onToggleMacPolicy = { ssid ->
                                            val current = viewModel.getMacPolicy(ssid) ?: MacAddressPolicy.DEVICE
                                            val next = if (current == MacAddressPolicy.DEVICE) MacAddressPolicy.RANDOMIZED else MacAddressPolicy.DEVICE
                                            viewModel.setMacPolicy(ssid, next)
                                        },
                                        onToggleLock = {
                                            val source = "main_screen_controls_primary_button"
                                            val correlationId = logPrimaryWifiControl(source, wifiStatus)
                                            if (wifiStatus.isSteeredOrLocked) {
                                                viewModel.unlockToAuto(source, correlationId)
                                            } else {
                                                val saved = viewModel.getSavedPassword(wifiStatus.ssid)
                                                val isCurrentOpen = wifiStatus.securityType == "0" || wifiStatus.securityType == "open"
                                                if (saved.isNotEmpty() || isCurrentOpen) {
                                                    executeWithMacPolicyCheck(wifiStatus.ssid) {
                                                        viewModel.forceLock5Ghz(saved, requestSource = source, correlationId = correlationId)
                                                    }
                                                } else {
                                                    passwordInput = ""
                                                    targetRadioForPassword = null
                                                    showPasswordDialog = true
                                                }
                                            }
                                        },
                                        onOpenGraph = { selectedPhoneTab = PhoneTab.GRAPH },
                                        isWatchdogActive = watchdogActive
                                    )

                                    CliQuickTilePanel(
                                        isTileAdded = isTileAdded,
                                        onAddTile = { viewModel.requestAddQuickTile() },
                                        onRemoveTile = { viewModel.removeQuickTile() }
                                    )

                                    CliShizukuPanel(
                                        shizukuState = shizukuState,
                                        onOpenShizuku = { ShizukuManager.openShizukuApp(context) },
                                        onOpenPlayStore = { ShizukuManager.openPlayStore(context) },
                                        onRequestPermission = { viewModel.requestShizukuPermission() }
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "DETECTED RADIOS",
                                                style = CliTypography.TelemetryLabel
                                            )
                                        }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
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
                                            CliScannerRefreshButton(
                                                isScanning = isScanning,
                                                enabled = !isOperating && shizukuState.isPermissionGranted,
                                                onRefresh = { viewModel.refreshAll() },
                                                showLabel = false
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
                                            val isCurrent = wifiStatus.isConnected && radio.bssid.equals(wifiStatus.bssid, ignoreCase = true)
                                            CliRadioRow(
                                                radio = radio,
                                                isCurrent = isCurrent,
                                                isPinned = wifiStatus.isLockedToBssid && wifiStatus.lockedBssid?.equals(radio.bssid, ignoreCase = true) == true,
                                                onLockClick = {
                                                    val source = "main_screen_controls_radio_lock"
                                                    val correlationId = DiagnosticLogger.newCorrelationId()
                                                    DiagnosticLogger.log(
                                                        "USER_ACTION",
                                                        "id=$correlationId source=$source callback=radio_lock_button targetSsid='${radio.ssid}' targetBssid=${radio.bssid} band=${radio.band.displayName} rssi=${radio.rssi} currentSsid='${wifiStatus.ssid}' currentBssid=${wifiStatus.bssid} currentBand=${wifiStatus.band.displayName} currentRssi=${wifiStatus.rssi}"
                                                    )
                                                    val isOpen = radio.flags.uppercase().let {
                                                            !it.contains("PSK") && !it.contains("SAE") && !it.contains("WEP")
                                                        }
                                                        val targetSsid = radio.ssid.ifEmpty { wifiStatus.ssid }
                                                        if (isOpen) {
                                                        executeWithMacPolicyCheck(targetSsid) {
                                                            viewModel.lockToSpecificRadio(radio, "", requestSource = source, correlationId = correlationId)
                                                            }
                                                        } else {
                                                            val saved = viewModel.getSavedPassword(targetSsid)
                                                            if (saved.isNotEmpty()) {
                                                                executeWithMacPolicyCheck(targetSsid) {
                                                                    viewModel.lockToSpecificRadio(radio, saved, requestSource = source, correlationId = correlationId)
                                                                }
                                                            } else {
                                                                targetRadioForPassword = radio
                                                                passwordInput = ""
                                                                showPasswordDialog = true
                                                            }
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

                            PhoneTab.GRAPH -> {
                                WifiGraphView(
                                    state = telemetryState,
                                    onTogglePause = { viewModel.togglePauseTelemetry() },
                                    onClearHistory = { viewModel.clearTelemetryHistory() },
                                    onSelectCandidate = { viewModel.selectCandidateBssid(it) },
                                    onLockBssid = { bssid ->
                                        val radio = radios.find { it.bssid.equals(bssid, ignoreCase = true) }
                                        if (radio != null) {
                                            val targetSsid = radio.ssid.ifEmpty { wifiStatus.ssid }
                                            val saved = viewModel.getSavedPassword(targetSsid)
                                            val correlationId = DiagnosticLogger.newCorrelationId()
                                            executeWithMacPolicyCheck(targetSsid) {
                                                viewModel.lockToSpecificRadio(radio, saved, requestSource = "graph", correlationId = correlationId)
                                            }
                                        }
                                    },
                                    isConnected = wifiStatus.isConnected
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
        Dialog(onDismissRequest = {
            showPasswordDialog = false
            isPasswordVisible = false
        }) {
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
                val isTargetOpen = targetRadioForPassword?.let {
                    val upper = it.flags.uppercase()
                    !upper.contains("PSK") && !upper.contains("SAE") && !upper.contains("WEP")
                } ?: (wifiStatus.securityType == "0" || wifiStatus.securityType == "open")

                val promptSsid = targetRadioForPassword?.ssid?.ifEmpty { wifiStatus.ssid } ?: wifiStatus.ssid
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (promptSsid.isNotEmpty()) "Network: \"$promptSsid\"" else "Network Credentials",
                    style = Typography.titleMedium,
                    color = CliTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isTargetOpen)
                        "This network is Open / Unsecured. No passphrase required."
                    else
                        "Android requires credentials to enforce specific BSSID binding. Stored securely on-device.",
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (!isTargetOpen) {
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        placeholder = {
                            Text("Enter passphrase :_", style = CliTypography.CodeMono, color = CliTextTertiary)
                        },
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { isPasswordVisible = !isPasswordVisible },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isPasswordVisible) "Hide passphrase" else "Show passphrase",
                                    tint = if (isPasswordVisible) CliAccent5GHz else CliTextTertiary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
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
                }

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
                            if (isTargetOpen || passwordInput.isNotBlank()) {
                                val source = "main_screen_password_dialog_bind_lock"
                                val correlationId = DiagnosticLogger.newCorrelationId()
                                showPasswordDialog = false
                                val target = targetRadioForPassword
                                val pass = if (isTargetOpen) "" else passwordInput
                                val targetSsid = target?.ssid?.ifEmpty { wifiStatus.ssid } ?: wifiStatus.ssid
                                DiagnosticLogger.log(
                                    "USER_ACTION",
                                    "id=$correlationId source=$source callback=bind_and_lock targetSsid='$targetSsid' targetBssid=${target?.bssid ?: "auto_5ghz"} connected=${wifiStatus.isConnected} currentBssid=${wifiStatus.bssid} band=${wifiStatus.band.displayName} rssi=${wifiStatus.rssi}"
                                )
                                executeWithMacPolicyCheck(targetSsid) {
                                    if (target != null) {
                                        viewModel.lockToSpecificRadio(target, pass, requestSource = source, correlationId = correlationId)
                                    } else {
                                        viewModel.forceLock5Ghz(pass, requestSource = source, correlationId = correlationId)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1.5f)
                    )
                }
            }
        }
    }

    if (showMacPolicyDialog) {
        Dialog(onDismissRequest = {
            showMacPolicyDialog = false
            pendingLockAction = null
        }) {
            CliPanel(
                borderColor = CliAccent5GHz,
                containerColor = CliSurface,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "MAC ADDRESS IDENTITY",
                        style = CliTypography.TelemetryLabel,
                        color = CliAccent5GHz
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Network: $targetSsidForMacPolicy",
                        style = Typography.headlineSmall,
                        color = CliTextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Choose how Android identifies this device on this Wi-Fi network. This choice is remembered per SSID.",
                        style = Typography.bodyMedium,
                        color = CliTextSecondary
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    CliButton(
                        text = "USE DEVICE MAC  •  DHCP RESERVATIONS",
                        variant = CliButtonVariant.Primary,
                        onClick = {
                            viewModel.setMacPolicy(targetSsidForMacPolicy, MacAddressPolicy.DEVICE)
                            showMacPolicyDialog = false
                            pendingLockAction?.invoke()
                            pendingLockAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    CliButton(
                        text = "USE RANDOMIZED MAC  •  PRIVACY",
                        variant = CliButtonVariant.Ghost,
                        onClick = {
                            viewModel.setMacPolicy(targetSsidForMacPolicy, MacAddressPolicy.RANDOMIZED)
                            showMacPolicyDialog = false
                            pendingLockAction?.invoke()
                            pendingLockAction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (BuildConfig.DEBUG && showDiagnosticsDialog) {
        val currentLogs = remember { mutableStateOf(DiagnosticLogger.getRecentLogs()) }
        val statusSummary = "SSID: ${wifiStatus.ssid}, BSSID: ${wifiStatus.bssid}, Band: ${wifiStatus.band.displayName}, RSSI: ${wifiStatus.rssi} dBm, Locked: ${wifiStatus.isLockedToBssid}, Shizuku: ${shizukuState.isPermissionGranted}"
        val report = remember(currentLogs.value) { DiagnosticLogger.buildDiagnosticReport(context, statusSummary) }

        Dialog(onDismissRequest = { showDiagnosticsDialog = false }) {
            CliPanel(
                borderColor = CliAccent24GHz,
                containerColor = CliSurface,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DIAGNOSTICS (DEBUG)",
                            style = CliTypography.TelemetryLabel,
                            color = CliAccent24GHz
                        )
                        Text(
                            text = "${currentLogs.value.size} EVENTS",
                            style = CliTypography.BadgeText,
                            color = CliTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(CliSurfaceElevated, RoundedCornerShape(4.dp))
                            .border(1.dp, CliBorder, RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    ) {
                        if (currentLogs.value.isEmpty()) {
                            Text(
                                text = "No diagnostic events recorded yet.",
                                style = CliTypography.CodeMono,
                                color = CliTextTertiary
                            )
                        } else {
                            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                            LaunchedEffect(currentLogs.value.size) {
                                if (currentLogs.value.isNotEmpty()) {
                                    listState.scrollToItem(currentLogs.value.size - 1)
                                }
                            }
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(currentLogs.value) { line ->
                                    val color = when {
                                        line.contains("[CRASH]") -> CliAccentRed
                                        line.contains("✗") -> CliAccentRed
                                        line.contains("✓") -> CliAccentGreen
                                        line.contains("[CMD]") -> CliAccent5GHz
                                        line.contains("[WATCHDOG]") -> CliAccent24GHz
                                        else -> CliTextSecondary
                                    }
                                    Text(
                                        text = line,
                                        style = CliTypography.CodeMono.copy(fontSize = 11.sp, lineHeight = 14.sp),
                                        color = color,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CliButton(
                            text = "COPY",
                            variant = CliButtonVariant.Ghost,
                            onClick = { DiagnosticLogger.copyToClipboard(context, report) },
                            modifier = Modifier.weight(1f)
                        )
                        CliButton(
                            text = "SHARE",
                            variant = CliButtonVariant.Primary,
                            onClick = { DiagnosticLogger.shareReport(context, report) },
                            modifier = Modifier.weight(1f)
                        )
                        CliButton(
                            text = "CLEAR",
                            variant = CliButtonVariant.Ghost,
                            onClick = {
                                DiagnosticLogger.clearLogs()
                                currentLogs.value = emptyList()
                            },
                            modifier = Modifier.weight(0.8f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    CliButton(
                        text = "CLOSE",
                        variant = CliButtonVariant.Ghost,
                        onClick = { showDiagnosticsDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun CliShizukuPanel(
    shizukuState: ShizukuState,
    onOpenShizuku: () -> Unit,
    onOpenPlayStore: () -> Unit,
    onRequestPermission: () -> Unit
) {
    val isAlert = !shizukuState.isInstalled
    val isWarning = !shizukuState.isPermissionGranted
    val (cardBorderColor, cardBgColor) = when {
        isAlert -> Pair(CliAccentRed.copy(alpha = 0.5f), CliAccentRedBg)
        isWarning -> Pair(CliAccent24GHz.copy(alpha = 0.5f), CliAccent24GHzBg)
        else -> Pair(CliBorder, CliSurface)
    }

    CliPanel(
        containerColor = cardBgColor,
        borderColor = cardBorderColor,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SHIZUKU PRIVILEGED SERVICE",
                    style = CliTypography.TelemetryLabel
                )
                Spacer(modifier = Modifier.height(4.dp))
                when {
                    shizukuState.isPermissionGranted -> {
                        CliBadge(
                            text = if (shizukuState.version > 0) "ACTIVE v${shizukuState.version}" else "ACTIVE",
                            accentColor = CliAccentGreen,
                            backgroundColor = CliAccentGreenBg,
                            borderColor = CliAccentGreen.copy(alpha = 0.5f)
                        )
                    }
                    shizukuState.isRunning -> {
                        CliBadge(
                            text = "AUTH REQUIRED",
                            accentColor = CliAccent24GHz,
                            backgroundColor = CliAccent24GHzBg,
                            borderColor = CliAccent24GHz.copy(alpha = 0.5f)
                        )
                    }
                    shizukuState.isInstalled -> {
                        CliBadge(
                            text = "DAEMON STOPPED",
                            accentColor = CliAccent24GHz,
                            backgroundColor = CliAccent24GHzBg,
                            borderColor = CliAccent24GHz.copy(alpha = 0.5f)
                        )
                    }
                    else -> {
                        CliBadge(
                            text = "NOT INSTALLED",
                            accentColor = CliAccentRed,
                            backgroundColor = CliAccentRedBg,
                            borderColor = CliAccentRed.copy(alpha = 0.5f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when {
                        shizukuState.isPermissionGranted ->
                            "Privileged API active. Quintz executes low-level Wi-Fi steering and BSSID binding without root."
                        shizukuState.isRunning ->
                            "Daemon is running. Authorize Quintz to unlock BSSID binding and RF telemetry."
                        shizukuState.isInstalled ->
                            "App installed, but service daemon is stopped. Start via Wireless Debugging in Shizuku."
                        else ->
                            "Shizuku service is required to bypass Android network restrictions and bind BSSIDs."
                    },
                    style = Typography.bodyMedium,
                    color = CliTextSecondary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            when {
                !shizukuState.isInstalled -> {
                    CliButton(
                        text = "GET SHIZUKU ↗",
                        variant = CliButtonVariant.Primary,
                        onClick = onOpenPlayStore
                    )
                }
                !shizukuState.isRunning -> {
                    CliButton(
                        text = "OPEN SHIZUKU ↗",
                        variant = CliButtonVariant.Primary,
                        onClick = onOpenShizuku
                    )
                }
                !shizukuState.isPermissionGranted -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CliButton(
                            text = "OPEN ↗",
                            variant = CliButtonVariant.Outlined,
                            onClick = onOpenShizuku
                        )
                        CliButton(
                            text = "GRANT",
                            variant = CliButtonVariant.Primary,
                            onClick = onRequestPermission
                        )
                    }
                }
                else -> {
                    CliButton(
                        text = "OPEN APP ↗",
                        variant = CliButtonVariant.Outlined,
                        onClick = onOpenShizuku
                    )
                }
            }
        }
    }
}

@Composable
fun CliConnectedHeroPanel(
    status: WifiStatus,
    isOperating: Boolean,
    onToggleLock: () -> Unit,
    onOpenGraph: (() -> Unit)? = null,
    onGetMacPolicy: ((String) -> MacAddressPolicy?)? = null,
    onToggleMacPolicy: ((String) -> Unit)? = null,
    isWatchdogActive: Boolean = false
) {
    val is5G = status.band == BandType.BAND_5_GHZ || status.band == BandType.BAND_6_GHZ
    val isLocked = status.isSteeredOrLocked
    val lockAccent = when {
        status.isPreferred5GHzFallback -> CliAccent24GHz
        is5G -> CliAccent5GHz
        else -> CliAccent24GHz
    }

    val borderColor by animateColorAsState(
        targetValue = if (isLocked) lockAccent.copy(alpha = 0.6f) else CliBorder,
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
                    text = if (status.band != BandType.UNKNOWN) status.band.displayName else "WI-FI",
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
            text = if (status.isConnected && status.ssid.isNotEmpty()) status.ssid
                   else if (status.isConnected) "Wi-Fi Connected"
                   else if (isOperating) "Negotiating Band..."
                   else "Not Connected",
            style = Typography.headlineMedium,
            color = CliTextPrimary
        )

        Text(
            text = if (status.isConnected && status.ipAddress.isNotEmpty()) "IP: ${status.ipAddress}"
                   else if (status.isConnected) "Connected • Start Shizuku for BSSID"
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
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isNarrow = maxWidth < 460.dp

                    if (isNarrow) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "SIGNAL",
                                        content = { CliSignalBars(status.rssi, showDbmText = true) }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "LINK SPEED",
                                        value = "${status.linkSpeedMbps} Mbps"
                                    )
                                }
                            }

                            CliDivider(color = CliBorderSubtle)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "CHANNEL",
                                        value = if (status.frequency > 0) {
                                            "Ch ${AccessPointRadio.frequencyToChannel(status.frequency)} (${status.frequency} MHz)"
                                        } else "--"
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    CliTelemetryMetric(
                                        label = "STANDARD",
                                        value = status.standard.uppercase().ifEmpty { "Wi-Fi" }
                                    )
                                }
                            }
                        }
                    } else {
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
                                value = if (status.frequency > 0) {
                                    "Ch ${AccessPointRadio.frequencyToChannel(status.frequency)} (${status.frequency} MHz)"
                                } else "--"
                            )
                            CliTelemetryMetric(
                                label = "STANDARD",
                                value = status.standard.uppercase().ifEmpty { "Wi-Fi" }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // BSSID Lock & Routing Card
            CliPanel(
                borderColor = CliBorderSubtle,
                containerColor = CliSurfaceElevated,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                shape = RoundedCornerShape(4.dp)
            ) {
                // Top Row: Lock/Steer Status (Left) + Graph Quick-Launch (Right)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        val badgeText = when {
                            status.isLockedToBssid -> "LOCKED TO BSSID"
                            status.isPreferred5GHz -> "PREFERRED 5 GHz (ROAM ALLOWED)"
                            status.isPreferred5GHzFallback -> "PREFERRED 5 GHz (FALLBACK ACTIVE)"
                            else -> "ROUTER AUTO-STEER"
                        }
                        Icon(
                            imageVector = if (status.isLockedToBssid || status.isPreferred5GHz || status.isPreferred5GHzFallback) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (status.isSteeredOrLocked) lockAccent else CliTextTertiary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = badgeText,
                            style = CliTypography.BadgeText,
                            color = if (status.isSteeredOrLocked) lockAccent else CliTextSecondary
                        )
                    }

                    if (onOpenGraph != null && status.isConnected) {
                        CliBadge(
                            text = "GRAPH ↗",
                            accentColor = CliAccent5GHz,
                            backgroundColor = CliAccent5GHzBg,
                            borderColor = CliAccent5GHz.copy(alpha = 0.5f),
                            modifier = Modifier.clickable { onOpenGraph() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                CliDivider(color = CliBorderSubtle)
                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Row: Active BSSID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACTIVE BSSID",
                        style = CliTypography.TelemetryLabel
                    )

                    Text(
                        text = if (status.bssid.isNotEmpty()) status.bssid else "Hidden (Requires Shizuku)",
                        style = CliTypography.CodeMono,
                        color = if (status.bssid.isNotEmpty()) CliTextSecondary else CliTextTertiary,
                        fontSize = 11.5.sp
                    )
                }

                if (status.isConnected && status.ssid.isNotEmpty() && onGetMacPolicy != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CliDivider(color = CliBorderSubtle)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MAC IDENTITY",
                            style = CliTypography.TelemetryLabel
                        )

                        val currentPolicy = onGetMacPolicy(status.ssid) ?: MacAddressPolicy.DEVICE
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (currentPolicy == MacAddressPolicy.DEVICE) CliAccent5GHzBg else CliSurface)
                                .border(1.dp, if (currentPolicy == MacAddressPolicy.DEVICE) CliAccent5GHz.copy(alpha = 0.4f) else CliBorder, RoundedCornerShape(4.dp))
                                .clickable { onToggleMacPolicy?.invoke(status.ssid) }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "${currentPolicy.displayName.uppercase()} ⇄",
                                style = CliTypography.BadgeText,
                                color = if (currentPolicy == MacAddressPolicy.DEVICE) CliAccent5GHz else CliTextSecondary
                            )
                        }
                    }
                }

                if (status.isSteeredOrLocked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    CliDivider(color = CliBorderSubtle)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SMART FALLBACK",
                            style = CliTypography.TelemetryLabel
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (status.isPreferred5GHzFallback) CliAccent24GHz else if (isWatchdogActive) CliAccentGreen else CliTextTertiary)
                            )
                            Text(
                                text = if (status.isPreferred5GHzFallback) "ACTIVE (Auto-recovery pending)" else if (isWatchdogActive) "ARMED (-82 dBm safety)" else "STANDBY",
                                style = CliTypography.CodeMono,
                                color = if (status.isPreferred5GHzFallback) CliAccent24GHz else if (isWatchdogActive) CliAccentGreen else CliTextTertiary,
                                fontSize = 11.5.sp
                            )
                        }
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
                text = if (isOperating) "SWITCHING BAND..." else if (isLocked) "UNLOCK TO AUTO-ROAM" else "PREFER 5 GHz (ROAM ALLOWED)",
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
