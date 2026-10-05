package com.quintz.wifi.core

import com.quintz.wifi.model.WifiOperationKind
import org.junit.Assert.assertEquals
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

    @Test
    fun tracksSpecificOperationKindAndResetsToIdle() {
        assertEquals(WifiOperationKind.IDLE, WifiOperationCoordinator.currentOperation.value)
        assertTrue(WifiOperationCoordinator.tryBegin(WifiOperationKind.CHANGE_MAC_POLICY))
        try {
            assertTrue(WifiOperationCoordinator.isOperating.value)
            assertEquals(WifiOperationKind.CHANGE_MAC_POLICY, WifiOperationCoordinator.currentOperation.value)
        } finally {
            WifiOperationCoordinator.end()
        }
        assertEquals(WifiOperationKind.IDLE, WifiOperationCoordinator.currentOperation.value)
        assertFalse(WifiOperationCoordinator.isOperating.value)
    }
}
