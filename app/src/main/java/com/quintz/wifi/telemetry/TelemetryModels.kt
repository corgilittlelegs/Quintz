package com.quintz.wifi.telemetry

import com.quintz.wifi.model.BandType

data class CandidateSample(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val channel: Int,
    val band: BandType
)

data class RoamEvent(
    val timestamp: Long,
    val fromBssid: String,
    val toBssid: String,
    val fromBand: BandType,
    val toBand: BandType
)

data class TelemetrySample(
    val timestamp: Long,
    val activeBssid: String,
    val activeRssi: Int,
    val activeLinkSpeedMbps: Int,
    val activeBand: BandType,
    val candidates: Map<String, CandidateSample> = emptyMap(),
    val roamEvent: RoamEvent? = null
)

data class CandidateMeta(
    val bssid: String,
    val ssid: String,
    val channel: Int,
    val band: BandType,
    val latestRssi: Int,
    val colorIndex: Int
)

data class TelemetryGraphState(
    val samples: List<TelemetrySample> = emptyList(),
    val activeBssid: String = "",
    val activeSsid: String = "",
    val activeRssi: Int = 0,
    val activeLinkSpeedMbps: Int = 0,
    val activeBand: BandType = BandType.UNKNOWN,
    val minRssi: Int = 0,
    val maxRssi: Int = 0,
    val candidates: List<CandidateMeta> = emptyList(),
    val bestCandidateBssid: String? = null,
    val roamAdvantageDbm: Int = 0,
    val isPaused: Boolean = false,
    val selectedCandidateBssid: String? = null
)
