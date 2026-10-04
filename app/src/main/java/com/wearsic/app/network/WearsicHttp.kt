package com.wearsic.app.network

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Process-wide shared OkHttpClient. Every consumer derives from this base via
 * [OkHttpClient.newBuilder], so connection pools, dispatchers and sockets are
 * reused across the API client, downloader, media-stream playback and
 * warm-up requests instead of each holding a private pool (wasteful on a
 * watch).
 *
 * The optional [apiKey] is injected here — centrally — so EVERY request that
 * leaves the app carries `X-Wearsic-Key` when one is configured. This must
 * include ExoPlayer's media-stream requests and background downloads: servers
 * with WEARSIC_API_KEY set reject unauthenticated /api/stream calls with 401,
 * which previously killed playback while search still worked.
 */
object WearsicHttp {

    @Volatile
    var apiKey: String = ""

    private val authInterceptor = Interceptor { chain ->
        val request = if (apiKey.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("X-Wearsic-Key", apiKey)
                .build()
        }
        chain.proceed(request)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 30s read (was 60s): a stalled socket held the WiFi radio + CPU
        // awake a full minute per request. Small JSON fails fast; media
        // streams override with their own longer timeout where needed.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .build()
}
