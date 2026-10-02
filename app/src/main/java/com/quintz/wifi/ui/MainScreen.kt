package com.quintz.wifi.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import com.quintz.wifi.BuildConfig
import com.quintz.wifi.R
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.core.WifiSecurityPolicy
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.MacAddressPolicy
import com.quintz.wifi.model.ShizukuState
import com.quintz.wifi.model.WifiStatus
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.components.*
import com.quintz.wifi.ui.graph.WifiGraphView
import com.quintz.wifi.ui.theme.*
import kotlinx.coroutines.launch

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
fun MainScreen(
    viewModel: MainViewModel,
    isDarkTheme: Boolean = LocalCliPalette.current == DarkCliPalette,
    onToggleTheme: () -> Unit = { viewModel.toggleTheme(isDarkTheme) }
) {
    val context = LocalContext.current
    val shizukuState by viewModel.shizukuState.collectAsState()
    val wifiStatus by viewModel.wifiStatus.collectAsState()
    val radios by viewModel.radios.collectAsState()
    val isOperating by viewModel.isOperating.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isScanQueued by viewModel.isScanQueued.collectAsState()
    val watchdogActive by viewModel.watchdogActive.collectAsState()
    val batteryOptimizationExempt by viewModel.batteryOptimizationExempt.collectAsState()
    val showBatteryOptimizationPrompt by viewModel.showBatteryOptimizationPrompt.collectAsState()
    val isTileAdded by viewModel.isTileAdded.collectAsState()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var isExportingDiagnostics by remember { mutableStateOf(false) }
    var showMacPolicyDialog by remember { mutableStateOf(false) }
    var targetSsidForMacPolicy by remember { mutableStateOf("") }
    var pendingLockAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var targetRadioForPassword by remember { mutableStateOf<AccessPointRadio?>(null) }
    var pendingBindRadio by remember { mutableStateOf<AccessPointRadio?>(null) }
    var pendingBindSource by remember { mutableStateOf("") }
    var pendingPreferRadio by remember { mutableStateOf<AccessPointRadio?>(null) }
    var pendingPreferSource by remember { mutableStateOf("") }
    var preferRadioForPassword by remember { mutableStateOf<AccessPointRadio?>(null) }
    var isPreparingPrefer by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun openBatteryOptimizationRequest() {
        viewModel.dismissBatteryOptimizationExplanation()
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        val settings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val appDetails = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        val opened = listOf(request, settings, appDetails).any { intent ->
            runCatching { context.startActivity(intent) }.isSuccess
        }
        if (!opened) {
            scope.launch { snackbarHostState.showSnackbar("Could not open Android battery settings.") }
        }
    }

    fun requestRadioBind(radio: AccessPointRadio, source: String) {
        pendingBindRadio = radio
        pendingBindSource = source
    }

    fun requestPrefer5Ghz(source: String) {
        if (isPreparingPrefer) return
        isPreparingPrefer = true
        scope.launch {
            try {
                val radio = viewModel.findManualPreferCandidate()
                if (radio == null) {
                    snackbarHostState.showSnackbar("No fresh compatible 5 GHz radio found for the active network.")
                } else {
                    pendingPreferRadio = radio
                    pendingPreferSource = source
                }
            } finally {
                isPreparingPrefer = false
            }
        }
    }

    fun executeWithMacPolicyCheck(ssid: String, action: () -> Unit) {
        if (ssid.isEmpty() || viewModel.getMacPolicy(ssid) != null) {
            action()
        } else {
            targetSsidForMacPolicy = ssid
            pendingLockAction = action
            showMacPolicyDialog = true
        }
    }

    fun confirmRadioBind() {
        val requested = pendingBindRadio ?: return
        val source = pendingBindSource
        pendingBindRadio = null
        val radio = radios.firstOrNull { it.bssid.equals(requested.bssid, ignoreCase = true) &&
            it.ssid == requested.ssid && it.flags == requested.flags }
        if (radio == null) {
            scope.launch { snackbarHostState.showSnackbar("AP data changed. Rescan before binding.") }
            return
        }
        val targetSsid = radio.ssid.ifEmpty { wifiStatus.ssid }
        val advertised = WifiSecurityPolicy.fromFlags(radio.flags)
        val correlationId = DiagnosticLogger.newCorrelationId()
        DiagnosticLogger.log("USER_ACTION", "id=$correlationId source=$source action=bind targetSsid='$targetSsid' targetBssid=${radio.bssid} security=${WifiSecurityPolicy.securityLabel(radio.flags)}")
        if (advertised.isOpen || advertised.isOwe) {
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

    fun confirmPrefer5Ghz() {
        val radio = pendingPreferRadio ?: return
        val source = pendingPreferSource
        pendingPreferRadio = null
        val ssid = radio.ssid
        val saved = viewModel.getSavedPassword(ssid)
        if (saved.isNotEmpty()) {
            val correlationId = DiagnosticLogger.newCorrelationId()
            executeWithMacPolicyCheck(ssid) {
                viewModel.forceLock5Ghz(saved, approvedBssid = radio.bssid,
                    requestSource = source, correlationId = correlationId)
            }
        } else {
            preferRadioForPassword = radio
            targetRadioForPassword = null
            passwordInput = ""
            showPasswordDialog = true
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
    val graphScrollState = rememberScrollState()

    // RF Telemetry Graph State from ViewModel

    val filteredRadios = remember(radios, selectedFilter) {
        when (selectedFilter) {
            RadioFilter.ALL -> radios
            RadioFilter.BAND_5G -> radios.filter { it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ }
            RadioFilter.BAND_24G -> radios.filter { it.band == BandType.BAND_2_4_GHZ }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val configuration = LocalConfiguration.current
        val isTablet = configuration.smallestScreenWidthDp >= 600
        val isWideScreen = isTablet && maxWidth >= 760.dp && maxHeight >= 480.dp
        val horizontalEdgePadding = if (isWideScreen) 20.dp else 8.dp

        LaunchedEffect(isWideScreen, selectedRightPane, selectedPhoneTab) {
            viewModel.setScannerActive(isWideScreen || selectedPhoneTab != PhoneTab.CONTROLS)
            viewModel.setTelemetryActive(
                if (isWideScreen) selectedRightPane == RightPaneView.GRAPH else selectedPhoneTab == PhoneTab.GRAPH
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
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
                    .padding(top = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalEdgePadding, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Integrated Logo Wordmark ([Q]uintz)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_qs_tile),
                            contentDescription = "Quintz Logo",
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "uintz",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            letterSpacing = (-0.5).sp,
                            color = CliTextPrimary
                        )
                    }

                    // Right: Tactile Shizuku Status Badge, Theme Toggle & Optional Debug Diagnostics
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val shizukuBadgeColor = if (shizukuState.isPermissionGranted) CliAccentGreen else CliAccent24GHz
                        val shizukuBadgeBg = if (shizukuState.isPermissionGranted) CliAccentGreenBg else CliAccent24GHzBg
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, shizukuBadgeColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                .clickable { ShizukuManager.launchOrInstall(context) },
                            shape = RoundedCornerShape(6.dp),
                            color = shizukuBadgeBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
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
                                Spacer(modifier = Modifier.width(5.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open Shizuku",
                                    tint = shizukuBadgeColor,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }

                        // Industrial Contrast Theme Toggle (Light / Dark)
                        val haptic = LocalHapticFeedback.current
                        val contrastRotation by animateFloatAsState(
                            targetValue = if (isDarkTheme) 0f else 180f,
                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            label = "theme_contrast_rotation"
                        )
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, CliBorder, RoundedCornerShape(6.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onToggleTheme()
                                },
                            shape = RoundedCornerShape(6.dp),
                            color = CliSurfaceElevated
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Contrast,
                                    contentDescription = if (isDarkTheme) "Switch to light mode" else "Switch to dark mode",
                                    tint = if (isDarkTheme) CliAccent24GHz else CliTextPrimary,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .rotate(contrastRotation)
                                )
                            }
                        }

                        if (BuildConfig.DEBUG) {
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, CliAccent24GHz.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                    .clickable { showDiagnosticsDialog = true },
                                shape = RoundedCornerShape(6.dp),
                                color = CliAccent24GHzBg
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
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
                }
                CliDivider()
            }
        }
    ) { paddingValues ->
        if (isWideScreen) {
            // ── Two-Pane Mission Control (Tablets & Landscape) ──
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = horizontalEdgePadding, vertical = 14.dp),
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
                            isPreparingPrefer = isPreparingPrefer,
                            recoveryThresholdRssi = viewModel.prefs.recoveryThresholdRssi,
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
                                    requestPrefer5Ghz(source)
                                }
                            },
                            onOpenGraph = { selectedRightPane = RightPaneView.GRAPH },
                            isWatchdogActive = watchdogActive,
                            batteryOptimizationExempt = batteryOptimizationExempt,
                            onBatterySettings = { viewModel.showBatteryOptimizationExplanation() }
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
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                                        isQueued = isScanQueued,
                                        enabled = !isOperating && !isScanning && !isScanQueued && shizukuState.isPermissionGranted,
                                        onRefresh = { viewModel.refreshAll() },
                                        showLabel = false
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Crossfade(
                            targetState = selectedRightPane,
                            modifier = Modifier.weight(1f),
                            label = "rightPaneCrossfade"
                        ) { pane ->
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
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            CliPanel(
                                                modifier = Modifier.fillMaxWidth(),
                                                containerColor = CliSurface,
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    filteredRadios.forEachIndexed { index, radio ->
                                                        if (index > 0) {
                                                            CliDivider(color = CliBorderSubtle)
                                                        }
                                                        val isCurrent = wifiStatus.isConnected && radio.bssid.equals(wifiStatus.bssid, ignoreCase = true)
                                                        CliRadioRow(
                                                            radio = radio,
                                                            isCurrent = isCurrent,
                                                            isPinned = wifiStatus.isLockedToBssid && wifiStatus.lockedBssid?.equals(radio.bssid, ignoreCase = true) == true,
                                                            onLockClick = {
                                                                requestRadioBind(radio, "main_screen_wide_radio_lock")
                                                            },
                                                            onUnpinClick = { viewModel.unlockToAuto("main_screen_wide_radio_unpin") },

                                                            isOperating = isOperating,
                                                            asCard = false
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(16.dp))
                                        }
                                    }
                                }

                                RightPaneView.GRAPH -> {
                                    GraphPane(
                                        viewModel = viewModel,
                                        scrollState = graphScrollState,
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
                        .padding(paddingValues)
                        .padding(start = horizontalEdgePadding, end = horizontalEdgePadding, bottom = 12.dp)
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

                    Crossfade(
                        targetState = selectedPhoneTab,
                        modifier = Modifier.weight(1f),
                        label = "tabCrossfade"
                    ) { tab ->
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
                                        isPreparingPrefer = isPreparingPrefer,
                                        recoveryThresholdRssi = viewModel.prefs.recoveryThresholdRssi,
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
                                                requestPrefer5Ghz(source)
                                            }
                                        },
                                        onOpenGraph = { selectedPhoneTab = PhoneTab.GRAPH },
                                        isWatchdogActive = watchdogActive,
                                        batteryOptimizationExempt = batteryOptimizationExempt,
                                        onBatterySettings = { viewModel.showBatteryOptimizationExplanation() }
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
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    CliPanel(
                                        modifier = Modifier.fillMaxWidth(),
                                        containerColor = CliSurface,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "DETECTED RADIOS (${radios.size})",
                                                style = CliTypography.TelemetryLabel,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )

                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                                                    isQueued = isScanQueued,
                                                    enabled = !isOperating && !isScanning && !isScanQueued && shizukuState.isPermissionGranted,
                                                    onRefresh = { viewModel.refreshAll() },
                                                    showLabel = false
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        CliDivider(color = CliBorderSubtle)

                                        if (filteredRadios.isEmpty()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 24.dp),
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
                                        } else {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                filteredRadios.forEachIndexed { index, radio ->
                                                    if (index > 0) {
                                                        CliDivider(color = CliBorderSubtle)
                                                    }
                                                    val isCurrent = wifiStatus.isConnected && radio.bssid.equals(wifiStatus.bssid, ignoreCase = true)
                                                    CliRadioRow(
                                                        radio = radio,
                                                        isCurrent = isCurrent,
                                                        isPinned = wifiStatus.isLockedToBssid && wifiStatus.lockedBssid?.equals(radio.bssid, ignoreCase = true) == true,
                                                        onLockClick = {
                                                            requestRadioBind(radio, "main_screen_controls_radio_lock")
                                                        },
                                                        onUnpinClick = { viewModel.unlockToAuto("main_screen_controls_radio_unpin") },

                                                        isOperating = isOperating,
                                                        asCard = false
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }

                            PhoneTab.GRAPH -> {
                                GraphPane(
                                    viewModel = viewModel,
                                    scrollState = graphScrollState,
                                    isConnected = wifiStatus.isConnected
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingPreferRadio?.let { radio ->
        Dialog(
            onDismissRequest = { pendingPreferRadio = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { pendingPreferRadio = null },
                contentAlignment = Alignment.Center
            ) {
                CliPanel(
                    borderColor = CliBorderActive,
                    containerColor = CliSurface,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(20.dp),
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                ) {
                    Text("CONFIRM 5 GHz RADIO", style = CliTypography.TelemetryLabel, color = CliAccent5GHz)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("SSID: ${radio.ssid}", style = CliTypography.CodeMono, color = CliTextPrimary)
                    Text("BSSID: ${radio.bssid}", style = CliTypography.CodeMono, color = CliTextPrimary)
                    Text("Security: ${WifiSecurityPolicy.securityLabel(radio.flags)}", style = CliTypography.CodeMono, color = CliTextPrimary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Updates the saved profile and may interrupt the connection. Roaming remains enabled.",
                        style = Typography.bodyMedium, color = CliTextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CliButton(text = "CANCEL", variant = CliButtonVariant.Ghost,
                            onClick = { pendingPreferRadio = null }, modifier = Modifier.weight(1f))
                        CliButton(text = "PREFER 5 GHZ", variant = CliButtonVariant.Primary,
                            onClick = { confirmPrefer5Ghz() }, modifier = Modifier.weight(1.5f))
                    }
                }
            }
        }
    }

    pendingBindRadio?.let { radio ->
        Dialog(
            onDismissRequest = { pendingBindRadio = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { pendingBindRadio = null },
                contentAlignment = Alignment.Center
            ) {
                CliPanel(
                    borderColor = CliBorderActive,
                    containerColor = CliSurface,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(20.dp),
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .clickable(enabled = false) {}
                ) {
                    Text("CONFIRM BSSID BIND", style = CliTypography.TelemetryLabel, color = CliAccent5GHz)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("SSID: ${radio.ssid.ifEmpty { wifiStatus.ssid }}", style = CliTypography.CodeMono, color = CliTextPrimary)
                    Text("BSSID: ${radio.bssid}", style = CliTypography.CodeMono, color = CliTextPrimary)
                    Text("Security: ${WifiSecurityPolicy.securityLabel(radio.flags)}", style = CliTypography.CodeMono, color = CliTextPrimary)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Updates the saved network profile and may interrupt the current connection.", style = Typography.bodyMedium, color = CliTextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CliButton(text = "CANCEL", variant = CliButtonVariant.Ghost,
                            onClick = { pendingBindRadio = null }, modifier = Modifier.weight(1f))
                        CliButton(text = "BIND", variant = CliButtonVariant.Primary,
                            onClick = { confirmRadioBind() }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // Industrial Passphrase Prompt Dialog
    if (showPasswordDialog) {
        val isPreferFlow = preferRadioForPassword != null
        val promptTitle = if (isPreferFlow) "AUTHENTICATE 5 GHz PREFERENCE" else "AUTHENTICATE BSSID BIND"
        val isTargetOpen = targetRadioForPassword?.let {
            val advertised = WifiSecurityPolicy.fromFlags(it.flags)
            advertised.isOpen || advertised.isOwe
        } ?: (wifiStatus.securityType == "0" || wifiStatus.securityType == "6" || wifiStatus.securityType == "open")

        val promptSsid = (preferRadioForPassword ?: targetRadioForPassword)?.ssid?.ifEmpty { wifiStatus.ssid } ?: wifiStatus.ssid

        val promptDescription = if (isTargetOpen) {
            "This network is Open / Unsecured. No passphrase required."
        } else if (isPreferFlow) {
            "Android requires credentials to prioritize 5 GHz bands while allowing roaming. Stored securely on-device."
        } else {
            "Android requires credentials to enforce specific BSSID binding. Stored securely on-device."
        }
        val promptButtonText = if (isPreferFlow) "PREFER 5 GHZ" else "BIND & LOCK"

        fun submitPassphrase() {
            if (isTargetOpen || passwordInput.isNotBlank()) {
                val source = if (preferRadioForPassword != null) "main_screen_password_dialog_prefer_5ghz"
                    else "main_screen_password_dialog_bind_lock"
                val correlationId = DiagnosticLogger.newCorrelationId()
                showPasswordDialog = false
                val target = targetRadioForPassword
                val preferTarget = preferRadioForPassword
                preferRadioForPassword = null
                val pass = if (isTargetOpen) "" else passwordInput
                val targetSsid = (preferTarget ?: target)?.ssid?.ifEmpty { wifiStatus.ssid } ?: wifiStatus.ssid
                DiagnosticLogger.log(
                    "USER_ACTION",
                    "id=$correlationId source=$source callback=${if (preferTarget != null) "prefer_5ghz" else "bind_and_lock"} targetSsid='$targetSsid' targetBssid=${(preferTarget ?: target)?.bssid ?: "auto_5ghz"} connected=${wifiStatus.isConnected} currentBssid=${wifiStatus.bssid} band=${wifiStatus.band.displayName} rssi=${wifiStatus.rssi}"
                )
                executeWithMacPolicyCheck(targetSsid) {
                    if (preferTarget != null) {
                        viewModel.forceLock5Ghz(pass, approvedBssid = preferTarget.bssid,
                            requestSource = source, correlationId = correlationId)
                    } else if (target != null) {
                        viewModel.lockToSpecificRadio(target, pass, requestSource = source, correlationId = correlationId)
                    } else {
                        viewModel.forceLock5Ghz(pass, requestSource = source, correlationId = correlationId)
                    }
                }
            }
        }

        Dialog(
            onDismissRequest = {
                showPasswordDialog = false
                isPasswordVisible = false
                preferRadioForPassword = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showPasswordDialog = false
                        isPasswordVisible = false
                        preferRadioForPassword = null
                    },
                contentAlignment = Alignment.Center
            ) {
                CliPanel(
                    borderColor = CliBorderActive,
                    containerColor = CliSurface,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(20.dp),
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .clickable(enabled = false) {}
                ) {
                    Text(
                        text = promptTitle,
                        style = CliTypography.TelemetryLabel,
                        color = CliAccent5GHz
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (promptSsid.isNotEmpty()) "Network: \"$promptSsid\"" else "Network Credentials",
                        style = Typography.titleMedium,
                        color = CliTextPrimary
                    )
                    preferRadioForPassword?.let { radio ->
                        Text("Target BSSID: ${radio.bssid}", style = CliTypography.CodeMono, color = CliTextSecondary)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = promptDescription,
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
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { submitPassphrase() }
                            ),
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
                            onClick = {
                                showPasswordDialog = false
                                preferRadioForPassword = null
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CliButton(
                            text = promptButtonText,
                            variant = CliButtonVariant.Primary,
                            onClick = { submitPassphrase() },
                            modifier = Modifier.weight(1.5f)
                        )
                    }
                }
            }
        }
    }

    if (showMacPolicyDialog) {
        MacPolicyDialog(
            targetSsid = targetSsidForMacPolicy,
            onSelect = { policy ->
                viewModel.setMacPolicy(targetSsidForMacPolicy, policy)
                showMacPolicyDialog = false
                pendingLockAction?.invoke()
                pendingLockAction = null
            },
            onCancel = {
                showMacPolicyDialog = false
                pendingLockAction = null
            }
        )
    }

    if (showBatteryOptimizationPrompt) {
        Dialog(
            onDismissRequest = { viewModel.dismissBatteryOptimizationExplanation() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { viewModel.dismissBatteryOptimizationExplanation() },
                contentAlignment = Alignment.Center
            ) {
                CliPanel(
                    borderColor = CliAccent24GHz,
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
                        Text(
                            text = "BATTERY SETTING FOR WATCHDOG",
                            style = CliTypography.TelemetryLabel,
                            color = CliAccent24GHz
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Android may delay 5 GHz recovery while this device sleeps. Allow unrestricted battery use for more reliable monitoring. This may increase battery use.",
                            style = Typography.bodyMedium,
                            color = CliTextPrimary
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        CliButton(
                            text = "REQUEST UNRESTRICTED",
                            variant = CliButtonVariant.Primary,
                            onClick = { openBatteryOptimizationRequest() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        CliButton(
                            text = "LATER",
                            variant = CliButtonVariant.Ghost,
                            onClick = { viewModel.dismissBatteryOptimizationExplanation() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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
                            text = if (isExportingDiagnostics) "EXPORTING" else "EXPORT",
                            variant = CliButtonVariant.Primary,
                            onClick = {
                                if (!isExportingDiagnostics) {
                                    isExportingDiagnostics = true
                                    scope.launch {
                                        try {
                                            DiagnosticLogger.shareFlightRecorder(context, statusSummary)
                                        } finally {
                                            isExportingDiagnostics = false
                                        }
                                    }
                                }
                            },
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
private fun GraphPane(
    viewModel: MainViewModel,
    scrollState: ScrollState,
    isConnected: Boolean
) {
    // Keep frequent telemetry updates inside this pane's own recomposition scope.
    val telemetryState by viewModel.telemetryState.collectAsState()
    WifiGraphView(
        state = telemetryState,
        ageClock = viewModel.telemetryClock,
        scrollState = scrollState,
        onTogglePause = { viewModel.togglePauseTelemetry() },
        onClearHistory = { viewModel.clearTelemetryHistory() },
        onSelectCandidate = { viewModel.selectCandidateBssid(it) },
        isConnected = if (telemetryState.isPaused) telemetryState.isConnected else isConnected
    )
}
