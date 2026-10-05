package com.quintz.wifi.core.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileRecoveryPolicyTest {
    private val saved = ProfileIdentity(23, "\"network\"", "4", "same-framework", 2)
    @Test fun changedIdIsAcceptedOnlyDuringProtectedRecovery() {
        val current = saved.copy(id = 1)
        assertFalse(ProfileRecoveryPolicy.same(current, saved, true, false))
        assertTrue(ProfileRecoveryPolicy.same(current, saved, true, true))
    }
    @Test fun changedIdCannotBypassAnUnmigratedRecord() {
        assertFalse(ProfileRecoveryPolicy.same(saved.copy(id = 1, version = 1), saved.copy(version = 1), true, true))
    }
    @Test fun changedProtectedSettingsRemainAConflictAfterIdReassignment() {
        assertFalse(ProfileRecoveryPolicy.same(saved.copy(id = 1), saved, false, true))
    }
    @Test fun sameFingerprintCannotCrossSsidSecurityFrameworkOrVersion() {
        for (other in listOf(saved.copy(ssid = "other"), saved.copy(security = "2"), saved.copy(platform = "updated"), saved.copy(version = 1))) {
            assertFalse(ProfileRecoveryPolicy.same(other, saved, true, true))
        }
    }
    @Test fun missingIdentityCannotMatch() {
        assertFalse(ProfileRecoveryPolicy.same(saved.copy(ssid = null), saved, true, true))
        assertFalse(ProfileRecoveryPolicy.same(saved.copy(security = null), saved, true, true))
        assertFalse(ProfileRecoveryPolicy.same(saved.copy(platform = null), saved, true, true))
    }
    @Test fun aNewProfileCanReceiveAnIdDuringForwardApply() {
        assertTrue(ProfileRecoveryPolicy.same(saved.copy(id = 1), saved.copy(id = -1), true, false))
    }
    @Test fun profileConflictMessageDoesNotRecommendRestoringWorkingAccess() {
        assertFalse(RecoveryFailure.PROFILE_CHANGED.message.contains("Shizuku"))
        assertTrue(RecoveryFailure.ACCESS_UNAVAILABLE.message.contains("Shizuku"))
    }
}
