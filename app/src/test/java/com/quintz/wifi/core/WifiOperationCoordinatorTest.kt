package com.quintz.wifi.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOperationCoordinatorTest {
    @Test
    fun rejectsOverlappingProfileChangesAndReleasesAfterCompletion() {
        assertTrue(WifiOperationCoordinator.tryBegin())
        try {
            assertFalse(WifiOperationCoordinator.tryBegin())
        } finally {
            WifiOperationCoordinator.end()
        }
        assertTrue(WifiOperationCoordinator.tryBegin())
        WifiOperationCoordinator.end()
    }
}
