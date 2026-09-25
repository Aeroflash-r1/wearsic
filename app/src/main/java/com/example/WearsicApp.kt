package com.example

import android.app.Application
import com.example.cache.WearsicCache
import com.example.cache.WearsicResponseCache
import com.example.di.AppContainer

class WearsicApp : Application() {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // Initialize caches early to prevent IllegalStateException
        WearsicResponseCache.init()
        WearsicCache.init(applicationContext)
        // Startup journal + crash hook + recovery-mode detection (must run
        // before any ViewModel is constructed).
        StartupDiagnostics.onApplicationCreate(this)
    }
}
