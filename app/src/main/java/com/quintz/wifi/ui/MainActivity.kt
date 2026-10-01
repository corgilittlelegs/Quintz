package com.quintz.wifi.ui

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.quintz.wifi.shizuku.ShizukuManager
import com.quintz.wifi.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val userDarkMode by viewModel.isDarkMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = userDarkMode ?: systemDark

            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            AppTheme(darkTheme = darkTheme) {
                MainScreen(
                    viewModel = viewModel,
                    isDarkTheme = darkTheme,
                    onToggleTheme = { viewModel.toggleTheme(darkTheme) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBatteryOptimizationStatus()
        ShizukuManager.updateState(this)
        viewModel.refreshAll()
        viewModel.startForegroundPolling()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopForegroundPolling()
    }
}
