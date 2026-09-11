package com.bandlock.wifi

import android.app.Application
import com.bandlock.wifi.shizuku.ShizukuManager

class BandLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuManager.initialize(this)
    }
}
