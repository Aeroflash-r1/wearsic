package com.wearsic.app.network

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Process-wide shared OkHttpClient. Every consumer derives from this base via
 * [OkHttpClient.newBuilder], so connection pools, dispatchers, sockets — and,
 * since 1.9.1, the response disk cache — are reused across the API client,
 * downloader, media-stream playback and warm-up requests instead of each
 * holding a private pool (wasteful on a watch).
 *
 * The optional [apiKey] is injected here — centrally — so EVERY request that
 * leaves the app carries `X-Wearsic-Key` when one is configured. This must
 * include ExoPlayer's media-stream requests and background downloads: servers
 * with WEARSIC_API_KEY set reject unauthenticated /api/stream calls with 401,
 * which previously killed playback while search still worked.
 *
 * ## Response cache (remote-link speed)
 * Over Tailscale Funnel every request pays a relayed round trip (~50-150 ms)
 * that LAN use never sees. The server marks read endpoints with a short
 * `Cache-Control` max-age; the disk cache installed below lets repeated
 * screens (search → album → back → search) paint without any network call.
 * [noteMutation] closes the one correctness gap: after the app changes
 * server state (favorites/playlists), reads must not re-serve the
 * pre-mutation list.
 */
object WearsicHttp {

    @Volatile
    var apiKey: String = ""
        private set

    /**
     * Applies a new API key process-wide. Changing the key invalidates the
     * response disk cache: OkHttp keys cached responses by URL only (the key
     * rides in a header), so without eviction a rotated key could be served
     * another profile's cached favorites/playlists for up to a max-age.
     */
    fun setApiKey(key: String) {
        if (key == apiKey) return
        apiKey = key
        runCatching { client.cache?.evictAll() }
    }

    /**
     * Disk budget for cached API responses. Deliberately small for a watch:
     * list payloads are a few KB each, and the cache must stay well under
     * the OS's background storage pressure. Artwork has its own (larger,
     * image-only) Coil cache.
     */
    private const val CACHE_SIZE_BYTES = 16L * 1024L * 1024L

    /**
     * Slightly longer than the server's shortest `max-age` (5s, used for the
     * user-mutable favorites/playlists lists), so a pre-mutation cache entry
     * can never still count as fresh once the burst ends — see [noteMutation].
     */
    private const val MUTATION_FRESHNESS_WINDOW_MS = 6_000L

    @Volatile
    private var mutationFreshUntilMs: Long = 0L

    /**
     * Called by the API client after any successful server-side mutation
     * (favorite/playlist edits). For the next few seconds every request
     * carries `Cache-Control: no-cache`, forcing OkHttp past the disk cache —
     * the screen refresh that follows a mutation must never be answered with
     * the list the user just changed.
     */
    fun noteMutation() {
        mutationFreshUntilMs = System.currentTimeMillis() + MUTATION_FRESHNESS_WINDOW_MS
    }

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

    private val mutationFreshnessInterceptor = Interceptor { chain ->
        val request = if (System.currentTimeMillis() < mutationFreshUntilMs) {
            chain.request().newBuilder()
                .header("Cache-Control", "no-cache")
                .build()
        } else {
            chain.request()
        }
        chain.proceed(request)
    }

    /** Statuses the server itself asks us to retry (see HttpError.isRetryable). */
    private val RETRYABLE_STATUS = setOf(429, 503)

    /** Extra attempts after the first one. Two keeps a blip from costing 3x. */
    private const val MAX_RETRIES = 2

    /**
     * A refused/reset connection gets ONE extra try, not two: the common cause
     * is "the server is not running", where every extra attempt is pure delay
     * (300ms) before the same error. A relay hiccup is still covered.
     */
    private const val MAX_CONNECTION_RETRIES = 1

    /** 300ms, then 600ms — short enough to be invisible on a tap. */
    private const val RETRY_BASE_DELAY_MS = 300L

    /**
     * Retries IDEMPOTENT requests only.
     *
     * The error model has always classified timeouts, refused connections and
     * HTTP 429/503 as `isRetryable`, but nothing ever acted on it: a single
     * dropped handshake over Tailscale Funnel reached the user as an error
     * screen, and a 503 ("extraction busy") failed the tap even though the
     * very next request would have succeeded. Doing it here — one place, below
     * auth and cache — means every GET benefits: search, lists, health, the
     * API-cache refill, and ExoPlayer's own ranged stream requests.
     *
     * POST/PUT/DELETE are never replayed: the server may have applied them
     * before the reply was lost, and a duplicate playlist/track is a worse
     * outcome than a readable error.
     */
    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.method != "GET" && request.method != "HEAD") {
            return@Interceptor chain.proceed(request)
        }
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(request)
                if (response.code in RETRYABLE_STATUS && attempt < MAX_RETRIES) {
                    response.close()
                    attempt++
                    retryPause(chain, attempt)
                    continue
                }
                return@Interceptor response
            } catch (e: IOException) {
                // A read timeout is not retried: callTimeout (45s) already
                // bounds the whole call, so a second 30s wait could not
                // finish anyway and would only delay the error.
                if (attempt >= MAX_CONNECTION_RETRIES || !isConnectionBlip(e) || chain.call().isCanceled()) throw e
                attempt++
                retryPause(chain, attempt)
            }
        }
        // Unreachable: every iteration either returns a response or throws.
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Retry loop exhausted")
    }

    private fun isConnectionBlip(e: IOException): Boolean =
        e is ConnectException || e is SocketException

    private fun retryPause(chain: Interceptor.Chain, attempt: Int) {
        if (chain.call().isCanceled()) throw IOException("Canceled")
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * attempt)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while retrying", interrupted)
        }
    }

    // The builder is kept mutable so [installCache] can attach the cache from
    // WearsicApp.onCreate; [client] is created lazily on first use, which is
    // always after that call in a real process (the warm-up below runs after).
    private val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 30s read (was 60s): a stalled socket held the WiFi radio + CPU
        // awake a full minute per request. Small JSON fails fast; media
        // streams override with their own longer timeout where needed.
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(authInterceptor)
        .addInterceptor(mutationFreshnessInterceptor)
        .addInterceptor(retryInterceptor)

    @Volatile
    private var cacheInstalled = false

    /**
     * Installs the API response disk cache. Must run before any request can
     * be issued; [com.wearsic.app.WearsicApp.onCreate] calls this first.
     */
    fun installCache(context: Context) {
        synchronized(builder) {
            if (cacheInstalled) return
            builder.cache(Cache(File(context.cacheDir, "http_cache"), CACHE_SIZE_BYTES))
            cacheInstalled = true
        }
    }

    val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { builder.build() }
}
