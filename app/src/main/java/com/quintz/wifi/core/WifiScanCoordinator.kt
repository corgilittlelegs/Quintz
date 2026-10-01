package com.quintz.wifi.core

import android.os.SystemClock
import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class WifiScanBatch(
    val radios: List<AccessPointRadio>,
    val completedAtMillis: Long,
    val completedAtElapsedMillis: Long,
    val succeeded: Boolean,
    val durationMillis: Long
)

/** Reuses a just-completed scan across the activity and watchdog controllers. */
internal object WifiScanCoordinator {
    private const val COALESCE_WINDOW_MS = 1_500L
    private val mutex = Mutex()
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private var lastSuccessfulScan: WifiScanBatch? = null
    private var totalRequests = 0L
    private var actualScans = 0L
    private var coalescedRequests = 0L

    suspend fun scan(
        freshForSsid: String?,
        freshForBssid: String?,
        onLockAcquired: () -> Unit,
        block: suspend (Map<String, Long>) -> WifiScanBatch
    ): Pair<WifiScanBatch, Boolean> = mutex.withLock {
        onLockAcquired()
        totalRequests++
        val now = SystemClock.elapsedRealtime()
        val cached = lastSuccessfulScan
        if (cached != null) {
            val cacheAgeMs = (now - cached.completedAtElapsedMillis).coerceAtLeast(0L)
            val extraAgeSeconds = (cacheAgeMs + 999L) / 1000L
            val agedRadios = cached.radios.map { radio ->
                radio.copy(ageSeconds = radio.ageSeconds + extraAgeSeconds)
            }
            val reusable = cacheAgeMs <= COALESCE_WINDOW_MS && satisfiesRequest(agedRadios, freshForSsid, freshForBssid)
            if (reusable) {
                coalescedRequests++
                DiagnosticLogger.log("WIFI_SCAN", "coordinator=request_reused totalRequests=$totalRequests actualScans=$actualScans coalescedRequests=$coalescedRequests")
                return@withLock cached.copy(radios = agedRadios) to true
            }
        }

        actualScans++
        val cacheAgeSeconds = cached?.let { ((now - it.completedAtElapsedMillis).coerceAtLeast(0L) + 999L) / 1000L } ?: 0L
        val previousAges = cached?.radios?.associate {
            it.bssid.lowercase() to (it.ageSeconds + cacheAgeSeconds)
        }.orEmpty()
        _isScanning.value = true
        val result = try {
            block(previousAges)
        } finally {
            _isScanning.value = false
        }
        if (result.succeeded) lastSuccessfulScan = result
        DiagnosticLogger.log("WIFI_SCAN", "coordinator=scan_attempt totalRequests=$totalRequests actualScans=$actualScans coalescedRequests=$coalescedRequests succeeded=${result.succeeded}")
        result to false
    }

    private fun satisfiesRequest(
        radios: List<AccessPointRadio>,
        ssid: String?,
        bssid: String?
    ): Boolean {
        if (bssid.isNullOrBlank() && ssid.isNullOrBlank()) return true
        return radios.any { radio ->
            val isTarget = if (!bssid.isNullOrBlank()) {
                radio.bssid.equals(bssid, ignoreCase = true) && (ssid == null || radio.ssid == ssid)
            } else {
                radio.ssid == ssid && (radio.band == BandType.BAND_5_GHZ || radio.band == BandType.BAND_6_GHZ)
            }
            isTarget && radio.ageSeconds <= 8L
        }
    }
}
