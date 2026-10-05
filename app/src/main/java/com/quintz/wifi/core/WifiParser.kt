package com.quintz.wifi.core

import com.quintz.wifi.model.AccessPointRadio
import com.quintz.wifi.model.BandType
import com.quintz.wifi.model.WifiStatus

object WifiParser {

    private val SSID_REGEX = Regex("""SSID:\s*"([^"]+)"""")
    private val BSSID_REGEX = Regex("""BSSID:\s*([0-9a-fA-F:]{17})""")
    private val FREQ_REGEX = Regex("""Frequency:\s*(\d+)MHz""")
    private val RSSI_REGEX = Regex("""RSSI:\s*(-?\d+)""")
    private val LINK_SPEED_REGEX = Regex("""Rx Link speed:\s*(\d+)Mbps|Link speed:\s*(\d+)Mbps""")
    private val STANDARD_REGEX = Regex("""Wi-Fi standard:\s*(\w+)""")
    private val IP_REGEX = Regex("""IP:\s*/?([0-9.]+)""")
    private val SEC_TYPE_REGEX = Regex("""Security type:\s*(\d+)""")
    private val NET_ID_REGEX = Regex("""Net ID:\s*(\d+)""")
    private val SCAN_FLAGS_SUFFIX_REGEX = Regex("""(?:^|\s+)((?:\[[^\]]+\])+)$""")

    /** Reads identity fields from dumpsys output even when it omits connection-state markers. */
    fun parseConnectionIdentity(output: String): Pair<String, String> {
        val rawSsid = SSID_REGEX.find(output)?.groupValues?.get(1).orEmpty()
        val ssid = rawSsid.takeUnless {
            it.equals("<unknown ssid>", ignoreCase = true) || it.equals("<none>", ignoreCase = true)
        }.orEmpty()
        if (rawSsid.contains('\\') || Regex("SSID:\\s*\"[^\"]*\"[^,\\n]*\"").containsMatchIn(output)) return "" to ""
        val bssid = BSSID_REGEX.find(output)?.groupValues?.get(1).orEmpty()
        return ssid to bssid
    }

    fun parseStatus(statusOutput: String, lockDumpLine: String? = null): WifiStatus {
        val hasConnectedString = statusOutput.contains("Wifi is connected to", ignoreCase = true)
        val hasSupplicantCompleted = statusOutput.contains("Supplicant state: COMPLETED", ignoreCase = true) &&
                !statusOutput.contains("SSID: <unknown ssid>", ignoreCase = true) &&
                !statusOutput.contains("SSID: <none>", ignoreCase = true)

        if (!hasConnectedString && !hasSupplicantCompleted) {
            return WifiStatus(isConnected = false)
        }

        var ssid = SSID_REGEX.find(statusOutput)?.groupValues?.get(1).orEmpty()
        if (ssid == "<unknown ssid>" || ssid == "<none>") ssid = ""

        if (ssid.isEmpty()) {
            val connectedToMatch = Regex("""Wifi is connected to\s+"?([^"\n]+)"?""", RegexOption.IGNORE_CASE).find(statusOutput)
            ssid = connectedToMatch?.groupValues?.get(1)?.trim('"')?.trim().orEmpty()
        }

        val bssid = BSSID_REGEX.find(statusOutput)?.groupValues?.get(1).orEmpty()
        val freq = FREQ_REGEX.find(statusOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val rssi = RSSI_REGEX.find(statusOutput)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        val speedMatch = LINK_SPEED_REGEX.find(statusOutput)
        val speed = speedMatch?.groupValues?.get(1)?.ifEmpty { null }?.toIntOrNull()
            ?: speedMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0

        val standard = STANDARD_REGEX.find(statusOutput)?.groupValues?.get(1).orEmpty()
        val ip = IP_REGEX.find(statusOutput)?.groupValues?.get(1).orEmpty()
        val secType = SEC_TYPE_REGEX.find(statusOutput)?.groupValues?.get(1).orEmpty()

        var isLocked = false
        var lockedBssid: String? = null

        if (!lockDumpLine.isNullOrEmpty() && ssid.isNotEmpty()) {
            val (locked, bssidFound) = parseLockedBssid(lockDumpLine)
            isLocked = locked
            lockedBssid = bssidFound
        }

        return WifiStatus(
            isConnected = true,
            ssid = ssid,
            bssid = bssid,
            frequency = freq,
            band = BandType.fromFrequency(freq),
            rssi = rssi,
            linkSpeedMbps = speed,
            standard = standard,
            ipAddress = ip,
            securityType = secType,
            networkId = NET_ID_REGEX.find(statusOutput)?.groupValues?.get(1)?.toIntOrNull(),
            isLockedToBssid = isLocked,
            lockedBssid = lockedBssid
        )
    }

    fun parseLockedBssid(output: String): Pair<Boolean, String?> {
        val match = BSSID_REGEX.find(output) ?: return Pair(false, null)
        val candidate = match.groupValues[1]
        val isExplicitBssid = candidate.lowercase() != "null" &&
                candidate.lowercase() != "any" &&
                candidate.lowercase() != "00:00:00:00:00:00"
        return if (isExplicitBssid) Pair(true, candidate) else Pair(false, null)
    }

    fun parseNetworkId(listNetworksOutput: String, ssid: String, activeNetworkId: Int?, securityType: String): Int? {
        if (activeNetworkId == null || activeNetworkId < 0 || ssid.isEmpty()) return null
        val rows = listNetworksOutput.lineSequence().mapNotNull { line ->
            val columns = line.trim().split(Regex("""\t+| {2,}"""))
            if (columns.size != 3) null else columns[0].toIntOrNull()?.let { id -> Triple(id, columns[1].trim('"'), columns[2].lowercase()) }
        }.filter { it.first == activeNetworkId && it.second == ssid }.toList()
        if (rows.size != 1) return null
        val security = rows.single().third
        val matchesSecurity = when (securityType) {
            "0" -> security.contains("open") || security.contains("none")
            "2" -> security.contains("wpa2") || security.contains("psk")
            "4" -> security.contains("wpa3") || security.contains("sae")
            "6" -> security.contains("owe")
            else -> false
        }
        return activeNetworkId.takeIf { matchesSecurity }
    }

    fun parseScanResults(
        scanOutput: String,
        currentSsid: String,
        currentBssid: String,
        maxAgeSeconds: Long? = 15L
    ): List<AccessPointRadio> {
        val lines = scanOutput.lines()
        val results = mutableListOf<AccessPointRadio>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("BSSID")) continue

            // Line format: BSSID Frequency RSSI Age SSID Flags
            val parts = trimmed.split(Regex("""\s+"""))
            if (parts.size >= 5) {
                val bssid = parts[0]
                if (!bssid.matches(Regex("^[0-9a-fA-F:]{17}$"))) continue

                val freq = parts[1].toIntOrNull() ?: continue
                
                // Parse RSSI which can be formatted as "-64(0:-64)" or "-64"
                val rssiRaw = parts[2].substringBefore('(')
                val rssi = rssiRaw.toIntOrNull() ?: -99
                if (rssi !in -126..-1) continue

                // Parse Age in seconds and enforce scan freshness.
                // Fail closed (Long.MAX_VALUE) if age is unparseable or missing.
                val age = parts[3].toDoubleOrNull() ?: continue
                if (!age.isFinite() || age < 0 || age > Long.MAX_VALUE / 1000.0) continue
                val ageSeconds = kotlin.math.ceil(age).toLong()
                if (maxAgeSeconds != null && ageSeconds > maxAgeSeconds) {
                    continue // Reject stale cached scan results
                }

                // Reconstruct SSID and Flags
                val rest = trimmed.substringAfter(parts[2]).trim()
                val afterAge = if (parts.size > 4) rest.substringAfter(parts[3]).trim() else ""
                // Flags are the final, contiguous bracket groups, including when the SSID
                // column is empty. Earlier brackets can be part of a named SSID.
                val flagsMatch = SCAN_FLAGS_SUFFIX_REGEX.find(afterAge)
                val flags = flagsMatch?.groupValues?.get(1).orEmpty()
                val ssid = if (flagsMatch == null) afterAge else afterAge.substring(0, flagsMatch.range.first).trim()

                if (ssid.isEmpty() || ssid.equals("<unknown ssid>", ignoreCase = true) || ssid == "\"\"") {
                    continue
                }

                val band = BandType.fromFrequency(freq)
                val channel = AccessPointRadio.frequencyToChannel(freq)
                val isCurrentRadio = currentBssid.isNotEmpty() && bssid.equals(currentBssid, ignoreCase = true)

                results.add(
                    AccessPointRadio(
                        bssid = bssid,
                        ssid = ssid,
                        frequency = freq,
                        band = band,
                        channel = channel,
                        rssi = rssi,
                        flags = flags,
                        isCurrent = isCurrentRadio,
                        ageSeconds = ageSeconds
                    )
                )
            }
        }

        // Deduplicate by BSSID, keeping the strongest RSSI entry
        val deduplicated = results.groupBy { it.bssid.lowercase() }
            .map { (_, list) -> list.sortedWith(compareBy<AccessPointRadio> { it.ageSeconds }.thenByDescending { it.rssi }).first() }

        // Sort priority:
        // 1. Currently connected radio first (if connected)
        // 2. Other radios matching the connected SSID next
        // 3. 5 GHz / 6 GHz before 2.4 GHz
        // 4. Highest RSSI (signal strength)
        return deduplicated.sortedWith(
            compareByDescending<AccessPointRadio> { it.isCurrent }
                .thenByDescending { currentSsid.isNotEmpty() && it.ssid == currentSsid }
                .thenByDescending { it.band == BandType.BAND_5_GHZ || it.band == BandType.BAND_6_GHZ }
                .thenByDescending { it.rssi }
        )
    }
}
