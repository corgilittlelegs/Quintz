package com.quintz.wifi.core.profile

/** The same transaction ordering is used by Android and the failure-sequence tests. */
data class RecoveryRecord<S>(val original: S?, val expected: S, val selectProfile: Boolean = true)
interface RecoveryStore<S> {
    fun pending(): RecoveryRecord<S>?
    fun save(record: RecoveryRecord<S>)
    fun clear()
}
interface TransactionBackend<S> {
    fun beginRecovery() {}
    suspend fun current(): S?
    fun same(a: S, b: S): Boolean
    fun owns(snapshot: S): Boolean = false
    suspend fun apply(snapshot: S): S
    suspend fun remove(snapshot: S)
    suspend fun select(snapshot: S)
    suspend fun verifyRecovery(snapshot: S): Boolean = current()?.let { same(it, snapshot) } == true
    fun recoveryConflict() {}
    fun recoveryRestoreFailed() {}
    suspend fun verify(snapshot: S): Boolean
    suspend fun commit(): Boolean
    suspend fun rollbackSettings(): Boolean = true
}
sealed interface TransitionResult {
    data object Verified : TransitionResult
    data object Busy : TransitionResult
    data object Unsupported : TransitionResult
    data object RecoveryPending : TransitionResult
    data object StorageFailed : TransitionResult
    data object Failed : TransitionResult
    data object NetworkChanged : TransitionResult
    data object AccessUnavailable : TransitionResult
    data object NoCandidate : TransitionResult
}
class ProfileTransaction<S>(private val store: RecoveryStore<S>, private val backend: TransactionBackend<S>) {
    suspend fun recover(): Boolean {
        val record = store.pending() ?: return true
        backend.beginRecovery()
        val current = backend.current()
        if (current != null && record.original != null && backend.same(current, record.original)) {
            if (record.selectProfile) backend.select(current)
            if (!backend.verifyRecovery(current)) { backend.recoveryRestoreFailed(); return false }
            if (!backend.rollbackSettings()) return false
            store.clear(); return true
        }
        if (current != null && !backend.same(current, record.expected) && !backend.owns(current)) { backend.recoveryConflict(); return false }
        if (record.original != null) {
            val restored = backend.apply(record.original)
            if (!backend.same(restored, record.original)) { backend.recoveryRestoreFailed(); return false }
            if (record.selectProfile) backend.select(restored)
            if (!backend.verifyRecovery(restored)) { backend.recoveryRestoreFailed(); return false }
        } else if (current != null) backend.remove(current)
        if (!backend.rollbackSettings()) return false
        store.clear()
        return true
    }
    suspend fun execute(target: S, selectProfile: Boolean = true): TransitionResult {
        if (!recover()) return TransitionResult.RecoveryPending
        val original = backend.current()
        store.save(RecoveryRecord(original, target, selectProfile)) // Must be durable and read back before apply.
        val now = backend.current()
        check(if (original == null) now == null else now != null && backend.same(original, now)) { "Profile changed before mutation" }
        val applied = backend.apply(target)
        check(backend.same(applied, target)) { "Profile readback differs" }
        if (selectProfile) backend.select(applied)
        if (!backend.verify(applied)) return TransitionResult.Failed
        if (!backend.commit()) return TransitionResult.StorageFailed
        if (!backend.verify(applied)) return TransitionResult.Failed
        store.clear()
        return TransitionResult.Verified
    }
}
