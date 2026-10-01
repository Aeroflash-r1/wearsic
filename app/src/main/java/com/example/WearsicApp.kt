package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.cache.WearsicCache
import com.example.cache.WearsicResponseCache
import com.example.di.AppContainer

class WearsicApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(applicationContext) }

    /**
     * Coil's phone defaults are wrong for a watch: a 25%-of-heap memory cache
     * and a 250MB disk cache are both far larger than a Wear device should
     * give artwork thumbnails. This caps the memory cache at ~10% of the (much
     * smaller) watch heap and the disk cache at 64MB — enough to keep list
     * scrolling and album art instant, without the app fighting the OS for
     * memory in the background.
     */
    override fun newImageLoader(): ImageLoader {
        val memoryCache = MemoryCache.Builder(this)
            .maxSizePercent(0.10)
            .build()
        val diskCache = DiskCache.Builder()
            .directory(cacheDir.resolve("image_cache"))
            .maxSizeBytes(64L * 1024L * 1024L)
            .build()
        return ImageLoader.Builder(this)
            .memoryCache(memoryCache)
            .diskCache(diskCache)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize caches early to prevent IllegalStateException
        WearsicResponseCache.init()
        WearsicCache.init(applicationContext)
        // Startup journal + crash hook + recovery-mode detection (must run
        // before any ViewModel is constructed).
        StartupDiagnostics.onApplicationCreate(this)

        // Warm the dependency graph on a background thread while the system
        // splash is still up. Constructing DataStore, Room and the shared
        // OkHttp client takes real milliseconds on a watch SoC; doing it here
        // means the first composition finds everything already built instead
        // of paying for it on the main thread.
        //
        // AppContainer and each repository are thread-safe `by lazy`
        // singletons, so a racing main-thread access simply waits for this
        // thread rather than constructing a second instance.
        //
        // Skipped under Robolectric: there the JVM/DB sandbox is shared with
        // the test's own setup and a concurrent construction races it.
        if (android.os.Build.FINGERPRINT == "robolectric" ||
            android.os.Build.HARDWARE == "robolectric"
        ) {
            return
        }
        Thread({
            runCatching {
                // Restore the persisted API key into the process-wide HTTP
                // interceptor before any background request can be issued.
                // Wear OS may recreate the process without recreating the
                // Settings screen, so DataStore is the source of truth here.
                val savedApiKey = container.preferencesRepository.getApiKey()
                container.musicRepository.refreshApiKeyWith(savedApiKey)

                container.preferencesRepository
                container.musicRepository
                container.downloadRepository
                container.recentRepository
                // Builds the process-wide OkHttpClient here rather than on the
                // main thread during the first composition.
                com.example.network.WearsicHttp.client
            }
        }, "Wearsic-Warmup").apply { isDaemon = true }.start()
    }
}
