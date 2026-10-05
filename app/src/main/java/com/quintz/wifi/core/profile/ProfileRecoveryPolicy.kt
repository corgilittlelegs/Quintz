package com.quintz.wifi.core.profile

enum class RecoveryFailure(val message: String) {
    ACCESS_UNAVAILABLE("Profile recovery needs Shizuku access. Start Shizuku and grant Quintz permission."),
    BACKUP_INVALID("The saved profile backup could not be verified. It has been kept; further changes are blocked."),
    PLATFORM_CHANGED("Android's Wi-Fi framework has changed since the backup. The backup has been kept; recovery is blocked."),
    PROFILE_CHANGED("The saved network has different protected settings. The backup has been kept to avoid overwriting them."),
    AMBIGUOUS_PROFILE("More than one saved network matches the backup. Recovery is blocked to avoid changing the wrong profile."),
    OWNERSHIP_UNKNOWN("The interrupted network creation could not be verified. Its backup has been kept."),
    RESTORE_FAILED("The original saved network could not be restored and verified. Its backup has been kept; try again."),
    SETTINGS_FAILED("The network was restored, but app settings could not be restored securely. Its backup has been kept."),
    TIMEOUT("Profile recovery did not finish in time. Its backup has been kept; try again.")
}
class ProfileRecoveryException(val reason: RecoveryFailure) : IllegalStateException(reason.name)

data class ProfileIdentity(val id: Int, val ssid: String?, val security: String?, val platform: String?, val version: Int)
object ProfileRecoveryPolicy {
    fun same(a: ProfileIdentity, b: ProfileIdentity, fingerprintMatches: Boolean, allowReassignedId: Boolean): Boolean =
        fingerprintMatches && a.ssid != null && a.ssid == b.ssid && a.security != null && a.security == b.security &&
            a.platform != null && a.platform == b.platform && a.version == b.version &&
            (a.id < 0 || b.id < 0 || a.id == b.id || allowReassignedId && a.version >= 2)
}
