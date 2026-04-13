package com.spp.spotify

import android.app.Application

class SPPApplication : Application() {
    companion object {
        lateinit var instance: SPPApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
