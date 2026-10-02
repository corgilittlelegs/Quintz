package com.quintz.wifi.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTileSpecTest {
    private val service = "com.quintz.wifi.service.TileService"

    @Test fun matchesPhoneDebugSpec() {
        assertTrue(QuickTileSpec.matches("custom(com.quintz.wifi.debug/$service)", "com.quintz.wifi.debug", service))
    }

    @Test fun matchesBothReleaseClassFormats() {
        assertTrue(QuickTileSpec.matches(" custom(com.quintz.wifi/.service.TileService) ", "com.quintz.wifi", service))
        assertTrue(QuickTileSpec.matches("custom(com.quintz.wifi/$service)", "com.quintz.wifi", service))
    }

    @Test fun rejectsOtherBuildsAndSimilarComponents() {
        assertFalse(QuickTileSpec.matches("custom(com.quintz.wifi/$service)", "com.quintz.wifi.debug", service))
        assertFalse(QuickTileSpec.matches("custom(com.quintz.wifi.debug/${service}Other)", "com.quintz.wifi.debug", service))
        assertFalse(QuickTileSpec.matches("null", "com.quintz.wifi.debug", service))
        assertFalse(QuickTileSpec.matches("custom(com.quintz.wifi.debug/.service.TileService)", "com.quintz.wifi.debug", service))
    }
}
