package com.cosmos.audiorhythmwallpaper

import android.app.Application
import android.util.Log

/** Initializes the context needed by the shared WebView asset-loader factory. */
class CosmosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val holderClass = Class.forName("com.cosmos.audiorhythmwallpaper.CosmosApplicationContextHolder")
            val holder = holderClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            holderClass.getDeclaredField("context").apply { isAccessible = true }
                .set(holder, applicationContext)
        } catch (error: ReflectiveOperationException) {
            Log.e("CosmosApplication", "Could not initialize WebView asset context", error)
        }
    }
}
