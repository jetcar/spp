package com.spp.spotify

import android.app.Application

/** Application entry point – installs the crash-recovery handler early in the process lifetime. */
class SpotifyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecoveryHandler.install(this)
    }
}

