package com.quintz.wifi.core.profile

import android.os.Bundle
internal object ProfileBackupCompatibility {
    fun valid(snapshot: Bundle): Boolean = snapshot.getByteArray("payload")?.isNotEmpty() == true &&
        snapshot.getByteArray("fingerprint")?.size == 32 && !snapshot.getString("platform").isNullOrEmpty()
}
