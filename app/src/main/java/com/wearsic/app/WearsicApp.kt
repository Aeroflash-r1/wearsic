package com.wearsic.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.wearsic.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WearsicApp : Application(), ImageLoaderFactory {
    val container: AppContainer by lazy { AppContainer(applicationContext) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Coil's phone defaults are wrong for a watch: a 25%-of-heap memory cache
     * and a 250MB disk cache are both far larger than a Wear device should
     * give artwork thumbnails. This caps the memory cache at ~10% of the (much
     * smaller) watch heap and the disk cache at 64MB, enables RGB_565 for
     * opaque album art (halving decoded bitmap memory), and keeps crossfades
     * off by default so fast-scrolling lists stay crisp.
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
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Startup journal + crash hook + recovery-mode detection (must run
        // before any ViewModel is constructed).
        StartupDiagnostics.onApplicationCreate(this)

        // Attach the API-response disk cache before ANY code path can build
        // the shared OkHttpClient (the warm-up coroutine below does exactly
        // that). Over Tailscale Funnel this is what turns a repeat screen
        // visit into a zero-network paint.
        com.wearsic.app.network.WearsicHttp.installCache(this)

        // Warm the dependency graph asynchronously while the system splash is
        // still up, without blocking a dedicated thread with runBlocking.
        //
        // Skipped under Robolectric: there the JVM/DB sandbox is shared with
        // the test's own setup and a concurrent construction races it.
        if (android.os.Build.FINGERPRINT == "robolectric" ||
            android.os.Build.HARDWARE == "robolectric"
        ) {
            return
        }
        appScope.launch {
            runCatching {
                // `wearsic pair` (adb) drops a one-shot pairing file in
                // the app's external files dir; apply it BEFORE the key
                // restore so the client picks up whatever it carried.
                com.wearsic.app.data.PairingImporter.importIfPresent(applicationContext)

                val savedApiKey = container.preferencesRepository.getApiKey()
                container.musicRepository.refreshApiKeyWith(savedApiKey)

                container.preferencesRepository
                container.musicRepository
                container.downloadRepository
                container.recentRepository
                // Builds the process-wide OkHttpClient here rather than on the
                // main thread during the first composition.
                com.wearsic.app.network.WearsicHttp.client
            }
        }
    }
}
