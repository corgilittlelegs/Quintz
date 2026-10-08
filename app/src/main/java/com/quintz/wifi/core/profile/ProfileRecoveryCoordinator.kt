package com.quintz.wifi.core.profile

import android.content.Context
import com.quintz.wifi.core.WifiController
import com.quintz.wifi.core.WifiOperationCoordinator
import com.quintz.wifi.shizuku.ShizukuManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

object ProfileRecoveryCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: RecoveryCoordinator
    val state get() = coordinator.state

    @Synchronized fun initialize(context: Context) {
        if (::coordinator.isInitialized) return
        val app = context.applicationContext
        val backup = ProfileBackupStore(app)
        val controller = WifiController(app)
        coordinator = RecoveryCoordinator(backup::exists, ShizukuManager::isReady) {
            withTimeout(40_000L) {
                WifiOperationCoordinator.isOperating.first { !it }
                controller.recoverPendingProfileResult(backup)
            }
        }
        scope.launch {
            ShizukuManager.state.collectLatest { access ->
                coordinator.ensureReady(retry = access.isRunning && access.isPermissionGranted)
            }
        }
    }

    suspend fun ensureReady(context: Context, retry: Boolean = false): Boolean {
        initialize(context)
        return coordinator.ensureReady(retry)
    }
}
