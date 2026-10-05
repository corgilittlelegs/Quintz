package com.quintz.wifi.core

import com.quintz.wifi.model.BandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiParserTest {

    @Test
    fun testParseLockedBssid_validBssid() {
        val line = "* ID: 17 SSID: \"Archer\" PROVIDER-NAME: null BSSID: 30:de:4b:31:55:30 FQDN: null"
        val (locked, bssid) = WifiParser.parseLockedBssid(line)
        assertTrue(locked)
        assertEquals("30:de:4b:31:55:30", bssid)
    }

    @Test
    fun testParseLockedBssid_nullOrAny() {
        val lineNull = "* ID: 17 SSID: \"Archer\" PROVIDER-NAME: null BSSID: null FQDN: null"
        val (locked1, bssid1) = WifiParser.parseLockedBssid(lineNull)
        assertFalse(locked1)
        assertNull(bssid1)

        val lineAny = "* ID: 17 SSID: \"Archer\" PROVIDER-NAME: null BSSID: any FQDN: null"
        val (locked2, bssid2) = WifiParser.parseLockedBssid(lineAny)
        assertFalse(locked2)
        assertNull(bssid2)

        val lineZero = "* ID: 17 SSID: \"Archer\" PROVIDER-NAME: null BSSID: 00:00:00:00:00:00 FQDN: null"
        val (locked3, bssid3) = WifiParser.parseLockedBssid(lineZero)
        assertFalse(locked3)
        assertNull(bssid3)
    }

    @Test
    fun testParseScanResults_ssidWithSpecialCharacters() {
        val scanOutput = """
            BSSID              Frequency      RSSI           Age(sec)     SSID              Flags
            30:de:4b:31:55:30  5745           -68            1            My.Home+5G (Test) [WPA2-PSK-CCMP]
            30:de:4b:31:55:2e  2417           -64            1            My.Home+5G (Test) [WPA2-PSK-CCMP]
            54:46:17:11:22:33  5180           -50            2            Neighbor_5G       [WPA2-PSK-CCMP]
        """.trimIndent()

        val results = WifiParser.parseScanResults(scanOutput, "My.Home+5G (Test)", "30:de:4b:31:55:2e")

        assertEquals(3, results.size)
        // Connected radio should be first
        assertEquals("30:de:4b:31:55:2e", results[0].bssid)
        assertTrue(results[0].isCurrent)
        assertEquals("My.Home+5G (Test)", results[0].ssid)
        assertEquals(1L, results[0].ageSeconds)

        // Matching SSID 5GHz radio should be next
        assertEquals("30:de:4b:31:55:30", results[1].bssid)
        assertEquals(BandType.BAND_5_GHZ, results[1].band)
        assertEquals("My.Home+5G (Test)", results[1].ssid)
        assertEquals(1L, results[1].ageSeconds)

        // Foreign radio should be last despite stronger RSSI
        assertEquals("54:46:17:11:22:33", results[2].bssid)
        assertEquals("Neighbor_5G", results[2].ssid)
        assertEquals(2L, results[2].ageSeconds)
    }

    @Test
    fun testParseScanResults_preservesBracketsInSsidAndTrailingFlagGroups() {
        val scanOutput = """
            BSSID              Frequency      RSSI           Age(sec)     SSID              Flags
            30:de:4b:31:55:30  5745           -68            1            Office [5G]       [WPA2-PSK-CCMP][ESS]
            54:46:17:11:22:33  5180           -50            2            [CorpNet] Guest   [WPA2-PSK-CCMP]
        """.trimIndent()

        val results = WifiParser.parseScanResults(scanOutput, "Office [5G]", "30:de:4b:31:55:30")

        assertEquals(2, results.size)
        assertEquals("Office [5G]", results[0].ssid)
        assertEquals("[WPA2-PSK-CCMP][ESS]", results[0].flags)
        assertEquals("[CorpNet] Guest", results[1].ssid)
        assertEquals("[WPA2-PSK-CCMP]", results[1].flags)
    }

    @Test
    fun testParseScanResults_skipsUnnamedRadioInsteadOfDisplayingSecurityFlags() {
        val scanOutput = """
            BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
            32:de:4b:21:55:2e       2457    -50(0:-50)           13.803                                      [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]
            30:de:4b:31:55:2e       2457    -50(0:-50)           13.808    Archer                            [WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][MFPC]
        """.trimIndent()

        val results = WifiParser.parseScanResults(scanOutput, "Archer", "30:de:4b:31:55:2e")

        assertEquals(1, results.size)
        assertEquals("Archer", results.single().ssid)
        assertEquals("30:de:4b:31:55:2e", results.single().bssid)
        assertEquals("[WPA2-PSK-CCMP-128][RSN-PSK+SAE-CCMP-128][ESS][MFPC]", results.single().flags)
    }

    @Test
    fun testParseScanResults_rejectsStaleResults() {
        val scanOutput = """
            BSSID              Frequency      RSSI           Age(sec)     SSID        Flags
            30:de:4b:31:55:30  5745           -68            45           Archer_5G   [WPA2-PSK-CCMP]
            30:de:4b:31:55:2e  2417           -64            2            Archer_2G   [WPA2-PSK-CCMP]
            54:46:17:11:22:33  5180           -50            16           Other_5G    [WPA2-PSK-CCMP]
        """.trimIndent()

        // With default maxAgeSeconds = 15L, only the entry with age 2 should be included
        val results = WifiParser.parseScanResults(scanOutput, "Archer_2G", "30:de:4b:31:55:2e")

        assertEquals(1, results.size)
        assertEquals("30:de:4b:31:55:2e", results[0].bssid)
        assertEquals(2L, results[0].ageSeconds)

        // With maxAgeSeconds = null, all entries are included
        val allResults = WifiParser.parseScanResults(scanOutput, "Archer_2G", "30:de:4b:31:55:2e", maxAgeSeconds = null)
        assertEquals(3, allResults.size)
    }

    @Test
    fun testParseScanResults_floatingPointAndCorruptedAge() {
        val scanOutput = """
            BSSID              Frequency      RSSI           Age(sec)     SSID        Flags
            30:de:4b:31:55:30  5745           -68            4.269        Archer_5G   [WPA2-PSK-CCMP]
            30:de:4b:31:55:2e  2417           -64            INVALID      Archer_2G   [WPA2-PSK-CCMP]
        """.trimIndent()

        // With maxAgeSeconds = 15L, 4.269 must round up to 5L and be accepted.
        // "INVALID" must fail closed (Long.MAX_VALUE) and be rejected as stale.
        val results = WifiParser.parseScanResults(scanOutput, "Archer_5G", "30:de:4b:31:55:30")

        assertEquals(1, results.size)
        assertEquals("30:de:4b:31:55:30", results[0].bssid)
        assertEquals(5L, results[0].ageSeconds)
    }

    @Test
    fun testParseStatus_connected() {
        val statusOutput = """
            Wifi is enabled
            ==== Primary ClientModeManager instance ====
            Wifi is connected to "Archer"
            WifiInfo: SSID: "Archer", BSSID: 30:de:4b:31:55:30, MAC: cc:f9:f0:fc:e5:b1, IP: /192.168.0.102, Security type: 4, Supplicant state: COMPLETED, Wi-Fi standard: 11ac, RSSI: -67, Link speed: 117Mbps, Frequency: 5745MHz, Net ID: 17
        """.trimIndent()

        val lockLine = "* ID: 17 SSID: \"Archer\" PROVIDER-NAME: null BSSID: 30:de:4b:31:55:30"
        val status = WifiParser.parseStatus(statusOutput, lockLine)

        assertTrue(status.isConnected)
        assertEquals("Archer", status.ssid)
        assertEquals("30:de:4b:31:55:30", status.bssid)
        assertEquals(5745, status.frequency)
        assertEquals(BandType.BAND_5_GHZ, status.band)
        assertEquals(-67, status.rssi)
        assertEquals("192.168.0.102", status.ipAddress)
        assertEquals(17, status.networkId)
        assertTrue(status.isLockedToBssid)
        assertEquals("30:de:4b:31:55:30", status.lockedBssid)
    }

    @Test
    fun testParseNetworkId() {
        val listNetworksOutput = """
            Network Id      SSID                         Security type
            0            Anubis                           wpa2-psk
            1            Archer                           wpa2-psk
            3            Archer                           wpa3-sae
            2            My Home Network                  wpa2-psk
            4            Home                             wpa2-psk
            5            Home 5G                          wpa2-psk
            6            home                             wpa2-psk
        """.trimIndent()

        assertEquals(1, WifiParser.parseNetworkId(listNetworksOutput, "Archer", 1, "2"))
        assertEquals(0, WifiParser.parseNetworkId(listNetworksOutput, "Anubis", 0, "2"))
        assertEquals(2, WifiParser.parseNetworkId(listNetworksOutput, "My Home Network", 2, "2"))
        assertEquals(4, WifiParser.parseNetworkId(listNetworksOutput, "Home", 4, "2"))
        assertNull(WifiParser.parseNetworkId(listNetworksOutput, "Home", 5, "2"))
        assertNull(WifiParser.parseNetworkId(listNetworksOutput, "Home", 6, "2"))
        assertNull(WifiParser.parseNetworkId(listNetworksOutput, "Archer", 3, "2"))
        assertNull(WifiParser.parseNetworkId(listNetworksOutput, "Archer", null, "2"))
        assertNull(WifiParser.parseNetworkId(listNetworksOutput, "UnknownNetwork", 1, "2"))
        assertEquals(9, WifiParser.parseNetworkId("9\tTab network\twpa2-psk", "Tab network", 9, "2"))
        assertNull(WifiParser.parseNetworkId("9\tTab network\twpa2-psk", "Tab network", 9, "4"))
    }

    @Test fun savedNetworkIdentityRejectsCaseAndPrefixCollisions() {
        val networks = """
            Network Id      SSID                         Security type
            10              Home                         wpa2-psk
            11              Home 5G                      wpa2-psk
            12              home                         wpa2-psk
            13              Home                         wpa3-sae
        """.trimIndent()
        assertEquals(10, WifiParser.parseNetworkId(networks, "Home", 10, "2"))
        assertNull(WifiParser.parseNetworkId(networks, "Home", 11, "2"))
        assertNull(WifiParser.parseNetworkId(networks, "Home", 12, "2"))
        assertNull(WifiParser.parseNetworkId(networks, "Home", 13, "2"))
    }
    @Test fun invalidOrFutureAgesAreRejectedAndFractionalBoundaryIsConservative() {
        val input = """
            aa:bb:cc:dd:ee:01 5180 -50 -1 Net [WPA2-PSK][ESS]
            aa:bb:cc:dd:ee:02 5180 -50 NaN Net [WPA2-PSK][ESS]
            aa:bb:cc:dd:ee:03 5180 -50 Infinity Net [WPA2-PSK][ESS]
            aa:bb:cc:dd:ee:04 5180 -50 8.001 Net [WPA2-PSK][ESS]
            aa:bb:cc:dd:ee:05 5180 -50 8.000 Net [WPA2-PSK][ESS]
        """.trimIndent()
        val radios = WifiParser.parseScanResults(input, "Net", "", maxAgeSeconds = 8)
        assertEquals(listOf("aa:bb:cc:dd:ee:05"), radios.map { it.bssid })
    }
    @Test fun duplicateRadioUsesNewestObservationBeforeSignalStrength() {
        val input = "aa:bb:cc:dd:ee:01 5180 -40 7 Net [ESS]\naa:bb:cc:dd:ee:01 5180 -70 1 Net [ESS]"
        assertEquals(-70, WifiParser.parseScanResults(input, "", "").single().rssi)
    }
}
