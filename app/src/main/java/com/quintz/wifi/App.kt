package com.quintz.wifi

import android.app.Application
import com.quintz.wifi.core.DiagnosticLogger
import com.quintz.wifi.shizuku.ShizukuManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)
        ShizukuManager.initialize(this)
    }
}
