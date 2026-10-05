package com.quintz.wifi.core.profile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileTransactionTest {
    private class Fixture {
        var profile: String? = "original:credential:static-ip:proxy:mac:pin"
        var durable: RecoveryRecord<String>? = null
        var failWrite = false; var failApply = false; var failSelect = false
        var restoredReadbackWrong = false; var storageOk = true; var verification = true
        var checks = 0; var loseConnectionAfterCommit = false; var settingsRestored = false
        var changeOnRecoverySelect = false; var failRecoveryVerification = false; var rollbackOk = true
        val events = mutableListOf<String>()
        val store = object : RecoveryStore<String> {
            override fun pending() = durable
            override fun save(record: RecoveryRecord<String>) { events += "save"; check(!failWrite); durable = record }
            override fun clear() { events += "clear"; durable = null }
        }
        val backend = object : TransactionBackend<String> {
            override suspend fun current() = profile.also { events += "read" }
            override fun same(a: String, b: String) = a == b
            override suspend fun apply(snapshot: String): String {
                events += "apply:$snapshot"; check(!failApply); profile = snapshot
                return if (restoredReadbackWrong && snapshot.startsWith("original")) "different-ip" else snapshot
            }
            override suspend fun remove(snapshot: String) { events += "remove"; profile = null }
            override suspend fun select(snapshot: String) { events += "select"; check(!failSelect); if (changeOnRecoverySelect) profile = "independent-proxy-change" }
            override suspend fun verifyRecovery(snapshot: String) = !failRecoveryVerification && profile == snapshot
            override suspend fun verify(snapshot: String): Boolean { events += "verify"; checks++; return verification && !(loseConnectionAfterCommit && checks > 1) }
            override suspend fun commit(): Boolean { events += "commit"; return storageOk }
            override suspend fun rollbackSettings(): Boolean { settingsRestored = true; events += "settings-restored"; return rollbackOk }
        }
        fun transaction() = ProfileTransaction(store, backend)
    }
    private val target = "target:new-credential:same-static-ip:same-proxy:new-mac:new-pin"
    @Test fun saveOnlyVerifiesAndCommitsWithoutSelectingOrConnecting() = runBlocking {
        val f = Fixture(); f.failSelect = true
        assertEquals(TransitionResult.Verified, f.transaction().execute(target, selectProfile = false))
        assertEquals(target, f.profile); assertEquals(2, f.checks)
        assertFalse(f.events.contains("select")); assertTrue(f.events.contains("commit")); assertNull(f.durable)
    }
    @Test fun saveOnlyVerificationFailureRetainsOriginalAndDoesNotCommit() = runBlocking {
        val f = Fixture(); val original = f.profile; f.verification = false
        assertEquals(TransitionResult.Failed, f.transaction().execute(target, selectProfile = false))
        assertEquals(original, f.durable!!.original)
        assertFalse(f.events.contains("select")); assertFalse(f.events.contains("commit"))
    }
    @Test fun saveOnlyStorageFailureRetainsBackupWithoutSelecting() = runBlocking {
        val f = Fixture(); f.storageOk = false
        assertEquals(TransitionResult.StorageFailed, f.transaction().execute(target, selectProfile = false))
        assertNotNull(f.durable); assertFalse(f.events.contains("select"))
    }
    @Test fun saveOnlyRecoveryAfterRestartRestoresWithoutSelecting() = runBlocking {
        val f = Fixture(); val original = f.profile; f.verification = false; f.failSelect = true
        assertEquals(TransitionResult.Failed, f.transaction().execute(target, selectProfile = false))
        assertFalse(f.durable!!.selectProfile)
        assertTrue(f.transaction().recover()); assertEquals(original, f.profile)
        assertTrue(f.settingsRestored); assertFalse(f.events.contains("select")); assertNull(f.durable)
    }
    @Test fun saveOnlyRecoveryWithAnAlreadyRestoredProfileDoesNotSelect() = runBlocking {
        val f = Fixture(); val original = f.profile; f.verification = false; f.failSelect = true
        f.transaction().execute(target, selectProfile = false)
        f.profile = original
        assertTrue(f.transaction().recover()); assertFalse(f.events.contains("select")); assertNull(f.durable)
    }
    @Test fun durableBackupPrecedesMutationAndCommitFollowsBothVerifications() = runBlocking {
        val f = Fixture(); assertEquals(TransitionResult.Verified, f.transaction().execute(target))
        assertTrue(f.events.indexOf("save") < f.events.indexOf("apply:$target"))
        assertTrue(f.events.indexOf("verify") < f.events.indexOf("commit"))
        assertEquals(2, f.checks); assertNull(f.durable)
    }
    @Test fun failedBackupMakesNoProfileOrCredentialChange() = runBlocking {
        val f = Fixture(); f.failWrite = true
        runCatching { f.transaction().execute(target) }
        assertTrue(f.profile!!.startsWith("original")); assertFalse(f.events.contains("commit")); assertFalse(f.events.any { it.startsWith("apply:") })
    }
    @Test fun failedAssociationRestoresCompleteOriginalAcrossFreshEngineInstance() = runBlocking {
        val f = Fixture(); val original = f.profile; f.verification = false
        assertEquals(TransitionResult.Failed, f.transaction().execute(target))
        assertTrue(f.transaction().recover()); assertEquals(original, f.profile)
        assertTrue(f.settingsRestored); assertNull(f.durable)
    }
    @Test fun connectionLostAfterCredentialCommitStillRollsBack() = runBlocking {
        val f = Fixture(); val original = f.profile; f.loseConnectionAfterCommit = true
        assertEquals(TransitionResult.Failed, f.transaction().execute(target))
        assertTrue(f.transaction().recover()); assertEquals(original, f.profile); assertTrue(f.settingsRestored)
    }
    @Test fun storageFailureIsDistinctAndRecoverable() = runBlocking {
        val f = Fixture(); f.storageOk = false
        assertEquals(TransitionResult.StorageFailed, f.transaction().execute(target))
        assertTrue(f.transaction().recover()); assertTrue(f.profile!!.startsWith("original"))
    }
    @Test fun lostCapabilityRetainsBackupAndBlocksFurtherMutation() = runBlocking {
        val f = Fixture(); f.verification = false; f.transaction().execute(target); f.failApply = true
        assertTrue(runCatching { f.transaction().recover() }.isFailure); assertNotNull(f.durable)
        f.failApply = false; assertTrue(f.transaction().recover())
    }
    @Test fun independentlyChangedProfileIsNeverOverwritten() = runBlocking {
        val f = Fixture(); f.verification = false; f.transaction().execute(target); f.profile = "user-edited-proxy"
        assertFalse(f.transaction().recover()); assertNotNull(f.durable)
        assertEquals(TransitionResult.RecoveryPending, f.transaction().execute(target)); assertEquals("user-edited-proxy", f.profile)
    }
    @Test fun mismatchingRestoreReadbackRetainsRecoveryRecord() = runBlocking {
        val f = Fixture(); f.verification = false; f.transaction().execute(target); f.restoredReadbackWrong = true
        assertFalse(f.transaction().recover()); assertNotNull(f.durable)
    }
    @Test fun newFailedProfileIsRemovedWithoutTouchingOtherProfiles() = runBlocking {
        val f = Fixture(); f.profile = null; f.verification = false; f.transaction().execute(target)
        assertTrue(f.transaction().recover()); assertNull(f.profile); assertTrue(f.events.contains("remove"))
    }
    @Test fun failureBetweenApplyAndSelectStillHasDurableOriginal() = runBlocking {
        val f = Fixture(); f.failSelect = true; runCatching { f.transaction().execute(target) }
        assertNotNull(f.durable); f.failSelect = false; assertTrue(f.transaction().recover()); assertTrue(f.profile!!.startsWith("original"))
    }
    @Test fun profileChangedDuringRecoverySelectionKeepsOriginalBackup() = runBlocking {
        val f = Fixture(); f.verification = false; f.transaction().execute(target)
        f.changeOnRecoverySelect = true
        assertFalse(f.transaction().recover()); assertNotNull(f.durable)
        assertFalse(f.settingsRestored); assertFalse(f.events.contains("clear"))
    }
    @Test fun failedRecoveryVerificationCanRetryWithoutLosingBackup() = runBlocking {
        val f = Fixture(); f.verification = false; f.transaction().execute(target)
        f.failRecoveryVerification = true
        assertFalse(f.transaction().recover()); assertNotNull(f.durable)
        f.failRecoveryVerification = false
        assertTrue(f.transaction().recover()); assertNull(f.durable)
    }
    @Test fun restoredProfileWithFailedSettingsRollbackRemainsPending() = runBlocking {
        val f = Fixture(); f.verification = false; f.transaction().execute(target); f.rollbackOk = false
        assertFalse(f.transaction().recover()); assertNotNull(f.durable)
        assertTrue(f.profile!!.startsWith("original")); assertFalse(f.events.contains("clear"))
    }
}
