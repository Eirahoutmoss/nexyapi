package com.eirahoutmoss.nexyapi

import android.app.Application
import com.eirahoutmoss.nexyapi.diagnostics.CrashLogger

class NexYapiApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        container = AppContainer(this)
    }
}
