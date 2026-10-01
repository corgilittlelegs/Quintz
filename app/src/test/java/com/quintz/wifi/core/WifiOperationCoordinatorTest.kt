package com.quintz.wifi.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiOperationCoordinatorTest {
    @Test
    fun rejectsOverlappingProfileChangesAndReleasesAfterCompletion() {
        assertFalse(WifiOperationCoordinator.isOperating.value)
        assertTrue(WifiOperationCoordinator.tryBegin())
        try {
            assertTrue(WifiOperationCoordinator.isOperating.value)
            assertFalse(WifiOperationCoordinator.tryBegin())
            assertTrue(WifiOperationCoordinator.isOperating.value)
        } finally {
            WifiOperationCoordinator.end()
        }
        assertFalse(WifiOperationCoordinator.isOperating.value)
        assertTrue(WifiOperationCoordinator.tryBegin())
        WifiOperationCoordinator.end()
        assertFalse(WifiOperationCoordinator.isOperating.value)
    }
}
