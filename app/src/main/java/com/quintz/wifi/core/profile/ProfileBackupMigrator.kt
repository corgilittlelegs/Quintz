package com.quintz.wifi.core.profile

import android.content.Context
import android.os.Bundle
import com.quintz.wifi.shizuku.WifiProfileFingerprint

/** Authenticate the legacy comparison before upgrading it; never replace its original parcel. */
class ProfileBackupMigrator(private val context: Context) {
    suspend fun upgrade(backup: ProfileBackupStore): Bundle {
        val record = try { backup.read() ?: throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID) }
            catch (failure: ProfileRecoveryException) { throw failure }
            catch (_: Exception) { throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID) }
        var changed = false
        suspend fun migrate(snapshot: Bundle): Bundle {
            if (!ProfileBackupCompatibility.valid(snapshot)) throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
            val version = snapshot.getInt("fingerprintVersion", 1)
            val validated = ProfileAccess.callChecked(context, if (version == WifiProfileFingerprint.CURRENT_VERSION) "validate" else "upgrade", snapshot)
            if (validated.getByteArray("payload")?.contentEquals(snapshot.getByteArray("payload")!!) != true ||
                validated.getString("platform") != snapshot.getString("platform")) throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
            if (version == WifiProfileFingerprint.CURRENT_VERSION) return snapshot
            changed = true
            return Bundle(snapshot).apply { putAll(validated) }
        }
        val original = record.getBundle("original") ?: throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
        val expected = record.getBundle("expected") ?: throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID)
        val upgradedOriginal = migrate(original); val upgradedExpected = migrate(expected)
        @Suppress("DEPRECATION") val owned = record.getParcelableArrayList<Bundle>("owned").orEmpty()
        val upgradedOwned = arrayListOf<Bundle>()
        for (snapshot in owned) upgradedOwned += migrate(snapshot)
        // All snapshots validate before any durable write. The original encrypted record survives
        // a failed upgrade or write, and app settings/selection state stay attached to it.
        if (changed) {
            record.putBundle("original", upgradedOriginal); record.putBundle("expected", upgradedExpected)
            if (record.containsKey("owned")) record.putParcelableArrayList("owned", upgradedOwned)
            try { backup.write(record) } catch (_: Exception) { throw ProfileRecoveryException(RecoveryFailure.BACKUP_INVALID) }
        }
        return record
    }
}
