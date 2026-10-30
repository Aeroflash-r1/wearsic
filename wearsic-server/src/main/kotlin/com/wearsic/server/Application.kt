package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/** Boot-time messages (before/around the HTTP server going live). */
private val bootLog = LoggerFactory.getLogger("boot")

// Cache-Control values for the API read path (see route("/api")). These feed
// the watch app's OkHttp disk cache: over Tailscale Funnel a relayed round
// trip costs 50-150ms, so a cache hit (zero network) is the difference
// between "instant" and "laggy" screen opens.
// - Content: YouTube-derived lists that change slowly; 5 minutes is safe.
// - User state: favorites/playlists the app itself mutates; kept at 5s and
//   paired with the app's post-mutation `Cache-Control: no-cache` burst
//   (6s > 5s), so a user can never be served their own pre-mutation list.
private const val CONTENT_CACHE_CONTROL = "private, max-age=300"
private const val USER_STATE_CACHE_CONTROL = "private, max-age=5"

/** Size of the deterministic front-door Range probe body (64 KiB). */
private const val FRONT_DOOR_PROBE_BYTES = 64 * 1024

/**
 * Strict single-range parser for the front-door probe (`bytes=N-M`).
 * Returns null for anything unsupported; the caller distinguishes "no Range
 * header" (200 + full body) from "bad Range" (416).
 */
private fun parseProbeRange(header: String?, size: Int): Pair<Int, Int>? {
    if (header.isNullOrBlank()) return null
    val m = Regex("^bytes=(\\d+)-(\\d*)$").find(header.trim()) ?: return null
    val start = m.groupValues[1].toLongOrNull()?.takeIf { it < size }?.toInt() ?: return null
    val rawEnd = m.groupValues[2]
    val end = if (rawEnd.isEmpty()) {
        size - 1
    } else {
        rawEnd.toLongOrNull()?.coerceAtMost(size - 1L)?.toInt() ?: return null
    }
    if (end < start) return null
    return start to end
}

/** First site-local IPv4 address, for the startup banner. null when offline. */
private fun lanAddress(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()

/**
 * One compact startup box instead of five scattered lines. Plain text (no
 * ANSI): it lands in wearsic-server.log and is read via `wearsic logs`.
 */
private fun startupBanner(port: Int, dbPath: String, apiKeySet: Boolean, transcoderReady: Boolean): String {
    val title = "wearsic engine ${ServerVersion.VERSION}"
    val rows = buildList {
        add("address    http://0.0.0.0:$port")
        lanAddress()?.let { add("LAN        http://$it:$port") }
        add("database   ${File(dbPath).name}")
        add("transcoder ${if (transcoderReady) "ready (ffmpeg)" else "unavailable - pkg install ffmpeg"}")
        add("auth       ${if (apiKeySet) "API key required" else "open - no API key (LAN use)"}")
    }
    val w = maxOf(title.length, rows.maxOf { it.length })
    val sb = StringBuilder()
    sb.append("╭─ ").append(title).append(' ').append("─".repeat(w - title.length)).append('\n')
    for (r in rows) {
        sb.append("│ ").append(r).append(" ".repeat(w - r.length)).append(" │").append('\n')
    }
    sb.append('╰').append("─".repeat(w + 3))
    return sb.toString()
}

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dbPath = System.getenv("WEARSIC_DB_PATH") ?: "wearsic.db"
    // Self-healing state (staged engine updates + update.json handoff).
    // WEARSIC_STATE_DIR lets deployments keep it next to the DB by default.
    val stateDir = java.io.File(
        System.getenv("WEARSIC_STATE_DIR")
            ?: File(dbPath).absoluteFile.parentFile?.let { File(it, "wearsic-state").path }
            ?: "wearsic-state"
    )
    val autoUpdate = System.getenv("WEARSIC_AUTO_UPDATE") != "0"

    val database = Database(dbPath)
    YoutubeSession.init(database)
    // Crash capture FIRST so even startup failures are recorded. Files land
    // in <state>/crashes (cap 20); /health reports the count + newest time.
    val crashReporter = CrashReporter(java.io.File(stateDir, "crashes"))
    crashReporter.install()
    // v1.4.4: wipe matches persisted by older, version-blind matcher builds
    // so every song re-matches with the fixed logic once.
    database.clearStaleMatchesOnce("matcher_version_wipe", "1.4.4")
    // One tuned client for EVERYTHING that talks to the internet
    // (CDN audio proxy, ffmpeg source, engine updater, and the
    // NewPipe downloader): identical tuning (12s/20s timeouts, 20
    // connections), so sharing one pool halves the engine's thread
    // and connection footprint and gives the whole process a single
    // bounded concurrency budget. The default CIO client has NO
    // timeouts — one stalled googlevideo.com connection used to park
    // a server worker forever while the watch spun buffering.
    // keepAliveTime is 60s (was 30s): a watch that skips back and forth
    // inside one song re-requests byte ranges of the SAME CDN URL, so
    // reusing the live connection saves a fresh TLS handshake and a radio
    // wake-up on every seek.
    val proxyClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 20
            endpoint.apply {
                maxConnectionsPerRoute = 10
                keepAliveTime = 60_000
                connectAttempts = 2
            }
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            connectTimeoutMillis = 12_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = false
    }
    /**
     * DEDICATED CDN STREAMING client (audio proxy + transcoder source
     * feeds) — separate from [proxyClient] because the timeout semantics
     * of streaming are OPPOSITE to everything else:
     *   - requestTimeout INFINITE: a song streamed through one request
     *     runs 30s+; a whole-call cap (the old 20s one) killed the upstream
     *     mid-body, the watch's buffer drained, and playback stopped
     *     silently at the halfway mark of the song.
     *   - The only bound is the socket INACTIVITY watchdog (60s): a
     *     connection that stops delivering bytes entirely is noticed
     *     within a minute; slow-but-alive links never time out.
     *   - connectTimeout 10s: dead hosts fail fast, before buffering.
     * The engine pool mirrors proxyClient so the two clients share the
     * same physical-connection budget rather than doubling it.
     */
    val streamClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 20
            endpoint.apply {
                maxConnectionsPerRoute = 10
                keepAliveTime = 60_000
                connectAttempts = 2
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }
    val healthMeter = ExtractionHealthMeter()
    val gateway = YoutubeGateway(
        downloader = NewPipeDownloader(proxyClient),
        healthMeter = healthMeter,
    )

    // Detect ffmpeg so songs without native AAC can be converted server-side.
    // Without it those rare songs answer 503 with install guidance instead.
    // Completed transcodes are cached under the state dir (bounded LRU) so
    // repeat plays and ExoPlayer's range requests share one ffmpeg run.
    // Both the proxy and the transcoder pump use streamClient: whole-life
    // song streaming, inactivity-watchdog only.
    val transcoder = Transcoder(streamClient, cacheDir = java.io.File(stateDir, "transcode-cache"))
    if (!transcoder.available) {
        bootLog.warn("ffmpeg not found on PATH — songs YouTube only offers in Opus/WebM " +
            "will return 503. Install it in Termux with: pkg install ffmpeg")
    }

    val audioProxy = AudioProxy(gateway, streamClient, transcoder)
    val apiKey = System.getenv("WEARSIC_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

    // Self-healing: canary watchdog + engine auto-update pipeline.
    val canary = ExtractionCanary(probe = { gateway.canaryProbe() })
    // The updater gets streamClient, NOT proxyClient: update packages are
    // ~30 MB zip downloads over the slow phone/hotspot/funnel links the
    // self-heal exists for, and proxyClient's whole-call 20s request timeout
    // aborted every real download mid-transfer (the same trap that
    // previously killed song streams). streamClient has NO whole-request
    // budget — its 60s socket-inactivity watchdog still fails fast on truly
    // dead links while slow-but-alive transfers complete.
    val engineUpdater = EngineUpdater(streamClient, stateDir)
    val selfHeal = SelfHealOrchestrator(
        healthMeter = healthMeter,
        canary = canary,
        updater = engineUpdater,
        stateDir = stateDir,
        autoUpdate = autoUpdate,
    )
    selfHeal.start()

    // /api/search is YouTube Music-first: official titles/artists/durations
    // with REAL videoIds (no surrogate matching step). The orchestrator
    // pre-resolves streams in the background so taps are instant.
    val ytmusic = YTMusicService()
    // Persist legacy surrogate -> videoId matches so favorites saved by
    // pre-1.5 builds (old iTunes `it:<id>` ids) still replay after upgrade.
    val searchOrchestrator = MetadataSearchOrchestrator(
        ytmusic, gateway,
        persistentMatches = object : MetadataSearchOrchestrator.MatchPersistence {
            override fun getMatchedVideoId(surrogateId: String): String? =
                database.getMatchedVideoId(surrogateId)
        },
        warmUpstream = audioProxy::warmUpstream,
    )

    if (apiKey == null) {
        // Hard opt-in for public exposure: WEARSIC_REQUIRE_KEY=1 refuses to
        // boot an open server instead of merely warning — a forgotten key
        // must never silently expose the API (stream proxy, mutable state).
        val requireKey = System.getenv("WEARSIC_REQUIRE_KEY")
            ?.equals("1", ignoreCase = true) == true
        if (requireKey) {
            bootLog.error(
                "WEARSIC_REQUIRE_KEY=1 is set but WEARSIC_API_KEY is missing — " +
                    "refusing to start an open server."
            )
            kotlin.system.exitProcess(1)
        }
        bootLog.warn(
            "WEARSIC_API_KEY is not set — the server is OPEN to anyone who can reach it. " +
                "Fine for private LAN/Tailscale use; REQUIRED for any public/tunnel deployment."
        )
    }

    // Boot warm-up: favorites are the most-tapped songs on the watch. Their
    // streams resolve here, 30s after boot (never competing with startup),
    // so the first tap on a favorite hits a hot cache. Lean path, no mutex,
    // and foreground playback preempts this work automatically.
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        delay(30_000)
        runCatching {
            searchOrchestrator.prefetchVideoIds(
                database.listFavorites().take(10).map { it.videoId }
            )
        }
    }

    embeddedServer(ServerCIO, port = port, host = "0.0.0.0") {
        module(
            gateway, database, audioProxy, apiKey, searchOrchestrator,
            healthMeter = healthMeter,
            canary = canary,
            engineUpdater = engineUpdater,
            crashReporter = crashReporter,
        )
        // Startup banner once the app is actually live (connectors bound),
        // so the log opens with one readable box instead of scattered lines.
        environment.monitor.subscribe(ApplicationStarted) {
            bootLog.info(startupBanner(port, dbPath, apiKey != null, transcoder.available))
        }
    }.apply {
        // Graceful shutdown: drain in-flight streams, then release every
        // owned resource. Previously SIGTERM cut streams mid-byte and leaked
        // the SQLite connection, HTTP pools, and watchdog scopes.
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching { stop(5_000, 10_000) }
            runCatching { selfHeal.stop() }
            runCatching { searchOrchestrator.shutdown() }
            runCatching { ytmusic.close() }
            runCatching { proxyClient.close() }
            runCatching { streamClient.close() }
            runCatching { database.close() }
        }, "wearsic-shutdown"))
    }.start(wait = true)
}

fun Application.module(
    gateway: YoutubeMetadataClient,
    database: Database,
    audioProxy: AudioProxy,
    apiKey: String?,
    searchOrchestrator: MetadataSearchOrchestrator,
    transcoder: Transcoder = audioProxy.transcoder,
    healthMeter: ExtractionHealthMeter? = null,
    canary: ExtractionCanary? = null,
    engineUpdater: EngineUpdater? = null,
    crashReporter: CrashReporter? = null,
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging) { level = Level.INFO }

    // Range requests for FILE responses: without this plugin, respondFile
    // (used for cached transcodes) 200s the ENTIRE file and ignores the
    // watch's Range header — every seek/re-read would re-transfer the
    // whole song. With it, 206 + Content-Range come out byte-exact.
    install(io.ktor.server.plugins.partialcontent.PartialContent) {
        maxRangeCount = 32
    }

    // JSON is what crosses Tailscale Funnel most (search results, album and
    // playlist listings, favorites), and a relayed link is latency-bound AND
    // bandwidth-capped: gzip shrinks list payloads ~3-5x. The explicit
    // content-type allow-list keeps audio out of the compressor (already
    // compressed, and Range responses must stay byte-exact); the size floor
    // skips tiny bodies where encoding overhead is not worth it.
    install(Compression) {
        gzip { priority = 1.0 }
        matchContentType(ContentType.Application.Json)
        minimumSize(512)
    }

    // Consistent JSON error surface for every failure mode — malformed JSON
    // bodies, unknown routes, and unexpected exceptions all respond with the
    // project's ErrorResponse DTO instead of an empty 500.
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // Let the auth interceptor's responses pass through untouched.
            if (call.response.status() == HttpStatusCode.Unauthorized) {
                return@exception
            }
            val (status, message) = when (cause) {
                // Thrown by call.receive<>() on malformed/missing bodies or
                // undecodable JSON (cause chain carries the details; the
                // client only ever sees the generic message).
                is BadRequestException, is SerializationException,
                is IllegalArgumentException,
                -> mapRequestError(cause)
                else -> {
                    call.application.log.error("Unhandled exception on ${call.request.uri}", cause)
                    HttpStatusCode.InternalServerError to "Internal server error"
                }
            }
            call.respond(status, ErrorResponse(message))
        }
        // The catch-all exception handler above already turns unhandled
        // NotFound responses into JSON where possible; this status handler
        // covers framework-level 404s. If ContentNegotiation cannot express
        // the response the raw body is an acceptable, non-sensitive fallback.
        status(HttpStatusCode.NotFound) { call, _ ->
            if (call.response.status() == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("No such endpoint: ${call.request.uri}"))
            }
        }
    }

    routing {
        // /health = LIVENESS ("is the process alive?"). Public, cheap,
        // secret-free. /ready answers "can it serve music right now?".
        get("/health") {
            val extraction = healthMeter?.let {
                ExtractionHealthDto(
                    successCount = it.successCount,
                    failureCount = it.failureCount,
                    failureRatePercent = it.failureRatePercent(),
                    consecutiveFailures = it.consecutiveFailures(),
                    lastError = it.lastError,
                    lastSuccessAtMillis = it.lastSuccessAtMillis,
                    lastFailureAtMillis = it.lastFailureAtMillis,
                )
            }
            val update = engineUpdater?.let {
                val snap = it.updateSnapshot()
                UpdateStatusDto(
                    status = if (snap.staged) "staged" else "idle",
                    latestKnownVersion = it.latestKnownVersion,
                    lastCheckAtMillis = it.lastCheckAtMillis,
                    lastError = it.lastCheckError,
                    stagedVersion = snap.stagedVersion,
                    updateAttempt = snap.attemptCount,
                    rollbackReason = snap.rollbackReason,
                )
            }
            call.respond(
                HealthResponse(
                    transcoderAvailable = transcoder.available,
                    extraction = extraction,
                    canaryHealthy = canary?.lastProbeHealthy,
                    update = update,
                    crashCount = crashReporter?.crashCount() ?: 0,
                    lastCrashAtMillis = crashReporter?.lastCrash()?.atMillis ?: 0,
                    upstreamRefusals = UpstreamStats.rangeRefusals.get(),
                )
            )
        }

        // /ready = READINESS ("can Wearsic serve music right now?"). The
        // deeper counterpart to /health, used by `wearsic doctor` and
        // monitoring. Deliberately cheap (one indexed DB probe, in-memory
        // counters — no network, no extraction) and exposes NO secrets.
        get("/ready") {
            val databaseOk = database.ping()
            // Same threshold as the self-heal watchdog: a sustained
            // consecutive-failure streak means the extraction engine is
            // broken and an update is (or should be) in flight.
            val extractorOk = healthMeter == null ||
                healthMeter.consecutiveFailures() < SelfHealOrchestrator.FAILURE_TRIGGER
            val transcoderOk = transcoder.available
            call.respond(
                ReadyResponse(
                    ready = databaseOk && extractorOk,
                    database = databaseOk,
                    extractor = extractorOk,
                    transcoder = transcoderOk,
                )
            )
        }

        route("/api") {
            // Shared-secret auth for everything under /api/* — /health stays public.
            // MessageDigest.isEqual is a constant-time comparison so response
            // timing cannot leak the key byte-by-byte.
            intercept(ApplicationCallPipeline.Plugins) {
                if (apiKey != null) {
                    // Primary contract is X-Wearsic-Key. Also accept a
                    // standard Bearer token so generic HTTP clients and media
                    // stacks can authenticate without custom-header support.
                    // RFC 7235 makes the auth-scheme case-insensitive
                    // ("bearer" and "Bearer" both valid); strip it
                    // case-insensitively — a strict removePrefix rejected
                    // legal lowercase schemes with 401.
                    // Never log or echo the credential.
                    val provided = call.request.headers["X-Wearsic-Key"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: call.request.headers["Authorization"]
                            ?.trim()
                            ?.removePrefixIfBearer()
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    val providedBytes = provided?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    val ok = MessageDigest.isEqual(providedBytes, apiKey.toByteArray(Charsets.UTF_8))
                    if (!ok) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid API key"))
                        finish()
                    }
                }

                // Coarse read-path Cache-Control for the watch app's OkHttp
                // disk cache (app >= 1.9.1). Only GETs are considered, and
                // only the paths below — streams, config, health and errors
                // keep the previous no-header behavior, which OkHttp treats
                // as "do not cache".
                if (call.request.httpMethod == HttpMethod.Get) {
                    val path = call.request.path()
                    val cacheControl = when {
                        path == "/api/search" ||
                            path.startsWith("/api/search/") ||
                            path == "/api/suggestions" ||
                            path.startsWith("/api/related/") ||
                            path == "/api/playlist" -> CONTENT_CACHE_CONTROL

                        path == "/api/favorites" ||
                            path == "/api/playlists" ||
                            path.startsWith("/api/playlists/") -> USER_STATE_CACHE_CONTROL

                        else -> null
                    }
                    if (cacheControl != null) {
                        // Attach in the send pipeline, gated on a 2xx status:
                        // a transient 404 (e.g. a failed playlist resolve)
                        // must never reach the watch as "cache this failure
                        // for the next N minutes".
                        call.response.pipeline.intercept(ApplicationSendPipeline.Before) {
                            val status = call.response.status()
                            if (status == null || status.value in 200..299) {
                                call.response.header(HttpHeaders.CacheControl, cacheControl)
                            }
                        }
                    }
                }
            }

            // Front-door self-test. `wearsic cloudflare verify` fetches this
            // THROUGH the permanent workers.dev address, so one request
            // proves the pieces that decide whether playback survives the
            // proxy: the API key reaches the engine, Range is forwarded,
            // the engine answers 206 + Content-Range, and the body arrives
            // intact. It is deterministic and needs no YouTube extraction.
            // 64 KiB is enough to be a real stream and small enough to be
            // instant. No user data, no secrets.
            get("/frontdoor/probe") {
                val size = FRONT_DOOR_PROBE_BYTES
                call.response.header(HttpHeaders.CacheControl, "no-store")
                val payload = ByteArray(size) { ((it * 31 + 7) and 0xFF).toByte() }
                val range = call.request.header(HttpHeaders.Range)
                val parsed = parseProbeRange(range, size)
                if (range != null && parsed == null) {
                    call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                    call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                    return@get
                }
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                if (parsed == null) {
                    call.respondBytes(payload, ContentType.Application.OctetStream)
                } else {
                    val (start, end) = parsed
                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$size")
                    call.respondBytes(
                        payload.copyOfRange(start, end + 1),
                        ContentType.Application.OctetStream,
                        HttpStatusCode.PartialContent,
                    )
                }
            }

            // Expensive endpoints (extraction + CDN traffic + possible ffmpeg
            // CPU) get a lightweight per-client rate limit. This mainly
            // protects the OPEN-server configuration (no API key) from
            // becoming a free YouTube proxy; with auth enabled the shared
            // secret is the primary control and this is defense in depth.
            // Limits are documented in README.md ("Rate limiting").
            val streamLimiter = RateLimiter(
                permitsPerMinute = 30.0,
                burstCapacity = 12,
            )
            // Warm-ups have their OWN bucket: they are cheap for the watch
            // (a tiny JSON body) but still trigger an extraction, and they
            // must never consume the playback request's stream permits — a
            // shared bucket was exactly what throttled real playback when the
            // old `bytes=0-0` probe existed.
            val prewarmLimiter = RateLimiter(
                permitsPerMinute = 90.0,
                burstCapacity = 30,
            )
            // Extraction-triggering metadata endpoints (search, related, albums,
            // playlist) also hit YouTube and fire CDN prefetches; on an open
            // server they must not be unlimited work. Separate bucket so a
            // search burst never starves playback or warm-ups.
            val metaLimiter = RateLimiter(
                permitsPerMinute = 60.0,
                burstCapacity = 20,
            )
            // "Made for you" (Tier A+B): multi-seed related fetch, taste-profile
            // scoring, 30-minute TTL cache. Cheap for the server because only
            // the cache miss ever touches YouTube.
            val recommendationEngine = RecommendationEngine(gateway)
            val recommendationCache = RecommendationCache()

            get("/stream/{videoId}") {
                val requestedId = call.parameters["videoId"]!!
                if (!call.checkRateLimited(streamLimiter, apiKey, "Too many stream requests; slow down and retry shortly")) {
                    return@get
                }
                // YTM search ids are already real YouTube videoIds -
                // usually stream-prefetched at search time. Legacy `it:<id>`
                // ids from pre-1.5 builds resolve via the persisted match
                // store. Real YouTube ids (related/playlist) pass through.
                val realVideoId = searchOrchestrator.resolveStreamVideoId(requestedId)
                if (realVideoId == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Could not match '$requestedId' to a playable source")
                    )
                    return@get
                }
                searchOrchestrator.withForegroundPlayback {
                    audioProxy.handle(call, realVideoId)
                }
            }

            // Resolve a track ahead of playback WITHOUT streaming audio. The
            // watch calls this on tap and for the next queue item (replacing
            // the old `Range: bytes=0-0` stream probe, which relayed a whole
            // song over Tailscale Funnel before noticing the client left).
            get("/prewarm/{videoId}") {
                val requestedId = call.parameters["videoId"]!!
                if (!call.checkRateLimited(prewarmLimiter, apiKey, "Too many warm-up requests; slow down and retry shortly")) {
                    return@get
                }
                if (searchOrchestrator.prewarm(requestedId)) {
                    call.respond(PrewarmResponse(ready = true))
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse("Could not match '$requestedId' to a playable source")
                    )
                }
            }

            get("/search") {
                if (!call.checkRateLimited(metaLimiter, apiKey, "Too many search requests; slow down and retry shortly")) {
                    return@get
                }
                val q = call.request.queryParameters["q"].orEmpty()
                // "Show more" paging: first page 15, each later page fetches
                // the next window via offset. Clamped server-side — a huge
                // limit would serialize dozens of slow metadata fetches
                // behind one request.
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 25) ?: 15
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val tracks = searchOrchestrator.search(q, limit, offset)
                // Rapid-retap insurance: a user who taps result 1 then
                // immediately jumps to result 2/3 should hit a HOT stream
                // cache, not a cold extraction. Warms top-3 in the background
                // (lean path, no mutex — cheap).
                searchOrchestrator.prefetchVideoIds(tracks.take(3).map { it.videoId })
                call.respond(SearchResponse(tracks))
            }

            get("/suggestions") {
                if (!call.checkRateLimited(metaLimiter, apiKey, "Too many suggestion requests; slow down and retry shortly")) {
                    return@get
                }
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(SuggestionsResponse(gateway.suggestions(q)))
            }

            get("/related/{videoId}") {
                if (!call.checkRateLimited(metaLimiter, apiKey, "Too many related requests; slow down and retry shortly")) {
                    return@get
                }
                val requestedId = call.parameters["videoId"]!!
                // RADIO: search ids are already real videoIds; legacy `it:`
                // ids resolve via the match store first.
                val realVideoId = searchOrchestrator.resolveStreamVideoId(requestedId)
                if (realVideoId == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Could not match '$requestedId' to a playable source"))
                    return@get
                }
                val related = gateway.related(realVideoId)
                // Radio plays its top results back-to-back: warm the first few
                // streams so the hand-off between songs is instant.
                searchOrchestrator.prefetchVideoIds(related.take(3).map { it.videoId })
                call.respond(RelatedResponse(related))
            }

            get("/search/albums") {
                if (!call.checkRateLimited(metaLimiter, apiKey, "Too many album-search requests; slow down and retry shortly")) {
                    return@get
                }
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(gateway.searchAlbums(q))
            }

            post("/recommendations") {
                if (!call.checkRateLimited(metaLimiter, apiKey, "Too many recommendation requests; slow down and retry shortly")) {
                    return@post
                }
                val req = call.receive<RecommendationsRequest>()
                val seeds = req.seedTrackIds.filter { it.isNotBlank() }.distinct().take(RecommendationEngine.MAX_SEEDS)
                if (seeds.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("No seed tracks provided"))
                    return@post
                }
                val artists = req.topArtists.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                // Never recommend something the user already has (server-side
                // favorites + the watch's own local favorites).
                val excluded = req.excludeTrackIds.filter { it.isNotBlank() }.toSet() +
                    database.listFavorites().map { it.videoId }

                val key = recommendationCache.signature(seeds, artists)
                val tracks = recommendationCache.get(key) ?: run {
                    val built = recommendationEngine.build(
                        RecommendationEngine.Seeds(seeds, artists, excluded)
                    )
                    recommendationCache.put(key, built)
                    built
                }

                // Warm the top picks so tapping one is instant.
                searchOrchestrator.prefetchVideoIds(tracks.take(3).map { it.videoId })
                call.respond(RecommendationsResponse(tracks))
            }

            get("/playlist") {
                if (!call.checkRateLimited(metaLimiter, apiKey, "Too many playlist requests; slow down and retry shortly")) {
                    return@get
                }
                val url = call.request.queryParameters["url"]
                if (url.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'url' query parameter"))
                    return@get
                }
                // SSRF guard: attacker input becomes a fetch target, so only
                // YouTube origins are ever resolved. Everything else is 400.
                if (!isYouTubeUrl(url)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Only YouTube playlist URLs are accepted"))
                    return@get
                }
                val result = gateway.playlistByUrl(url)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Could not resolve playlist"))
                } else {
                    // Albums/playlists are played top-down: warm the first
                    // tracks' streams while the watch renders the list, so
                    // the tap on track 1 hits a hot cache instead of queueing
                    // a cold extraction (the single biggest play-latency win).
                    searchOrchestrator.prefetchVideoIds(result.tracks.take(3).map { it.videoId })
                    call.respond(result)
                }
            }

            // ---- Favorites ----
            get("/favorites") {
                call.respond(database.listFavorites())
            }
            post("/favorites") {
                val track = call.receive<TrackDto>()
                database.addFavorite(track)
                call.respond(HttpStatusCode.Created, track)
            }
            delete("/favorites/{videoId}") {
                database.removeFavorite(call.parameters["videoId"]!!)
                call.respond(HttpStatusCode.NoContent)
            }

            // ---- Playlists ----
            get("/playlists") {
                call.respond(database.listPlaylists())
            }
            post("/playlists") {
                val req = call.receive<CreatePlaylistRequest>()
                call.respond(HttpStatusCode.Created, database.createPlaylist(req.name))
            }
            get("/playlists/{id}") {
                val result = database.getPlaylistTracks(call.parameters["id"]!!)
                if (result == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Playlist not found"))
                } else {
                    call.respond(result)
                }
            }
            post("/playlists/{id}/tracks") {
                val track = call.receive<TrackDto>()
                database.addTrackToPlaylist(call.parameters["id"]!!, track)
                call.respond(HttpStatusCode.Created, track)
            }
            delete("/playlists/{id}/tracks/{videoId}") {
                database.deletePlaylistTrack(call.parameters["id"]!!, call.parameters["videoId"]!!)
                call.respond(HttpStatusCode.NoContent)
            }

            // ---- YouTube cookie runtime config (bot-challenge workaround) ----
            // SECURITY: the YouTube cookie is an account credential. It must
            // never be writable (or discoverable) on an OPEN server — with no
            // WEARSIC_API_KEY the auth interceptor below does nothing, so
            // without this gate any device on the LAN (or the internet, via a
            // Cloudflare-front-door proxy) could point the engine at an
            // arbitrary YouTube session. Favorites/playlists stay open in
            // open mode BY DESIGN (user choice, personal data only); this
            // endpoint is always key-gated.
            get("/config/youtube-cookie") {
                if (apiKey == null) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(
                            "YouTube-cookie configuration requires WEARSIC_API_KEY to be set — " +
                                "refusing open-mode access"
                        )
                    )
                    return@get
                }
                call.respond(YoutubeCookieStatus(hasCookie = YoutubeSession.hasCookie()))
            }
            post("/config/youtube-cookie") {
                if (apiKey == null) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse(
                            "YouTube-cookie configuration requires WEARSIC_API_KEY to be set — " +
                                "refusing open-mode access"
                        )
                    )
                    return@post
                }
                val req = call.receive<YoutubeCookieRequest>()
                YoutubeSession.cookie = req.cookie
                call.respond(YoutubeCookieStatus(hasCookie = YoutubeSession.hasCookie()))
            }
        }
    }
}

/**
 * RFC 7235: the authentication scheme is case-insensitive, so "bearer X",
 * "BearER X" etc. are all valid. Drops the scheme prefix when present.
 */
private fun String.removePrefixIfBearer(): String {
    val idx = indexOf(' ')
    if (idx < 0) return this
    val scheme = substring(0, idx)
    if (!scheme.equals("Bearer", ignoreCase = true)) return this
    return substring(idx + 1).trim()
}

/**
 * Per-client bucket key for rate limiting.
 *
 * Authenticated callers share the verified API-key bucket (the secret is the
 * same for every authorized user, so a per-user layer would need real
 * identity — out of scope here). Everyone else is bucketed by peer address.
 * When a request arrives through the local Cloudflare tunnel (cloudflared
 * connects from loopback), the real client's address is carried in
 * CF-Connecting-IP (preferred) or the leftmost X-Forwarded-For entry; those
 * headers are trusted ONLY from a loopback peer, so a remote client can
 * never forge them to mint a fresh bucket per request and defeat the
 * throttle. Previously every front-door caller shared one global bucket (the
 * tunnel peer address) — one heavy user exhausted playback for everyone.
 */
private fun ApplicationCall.clientBucketKey(apiKey: String?): String {
    val headerKey = request.headers["X-Wearsic-Key"]
    if (apiKey != null && headerKey != null &&
        MessageDigest.isEqual(
            headerKey.toByteArray(Charsets.UTF_8),
            apiKey.toByteArray(Charsets.UTF_8)
        )
    ) return headerKey
    val peerHost = request.origin.remoteHost ?: ""
    val trustedProxy = peerHost == "127.0.0.1" || peerHost == "::1" || peerHost == "localhost"
    if (trustedProxy) {
        request.headers["CF-Connecting-IP"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        request.headers["X-Forwarded-For"]?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return peerHost.ifEmpty { "unknown" }
}

/** Acquires a rate-limit permit or responds 503 and returns false. */
private suspend fun ApplicationCall.checkRateLimited(
    limiter: RateLimiter,
    apiKey: String?,
    message: String,
): Boolean {
    if (limiter.tryAcquire(clientBucketKey(apiKey))) return true
    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(message))
    return false
}

/** Maps request-parsing failures to the (400, message) pair. */
private fun mapRequestError(cause: Throwable?): Pair<HttpStatusCode, String> = when (cause) {
    null -> HttpStatusCode.BadRequest to "Invalid request"
    is SerializationException -> HttpStatusCode.BadRequest to "Malformed JSON body"
    is BadRequestException -> HttpStatusCode.BadRequest to "Invalid request"
    is IllegalArgumentException -> HttpStatusCode.BadRequest to "Invalid request"
    else -> HttpStatusCode.BadRequest to "Invalid request"
}

/**
 * SSRF allowlist for /api/playlist?url=. Only YouTube origins may be
 * resolved: youtu.be, youtube.com, music.youtube.com and their www/nocookie
 * variants. Case-insensitive host match; userinfo/ports do not smuggle
 * another host past java.net.URI parsing.
 */
private val YOUTUBE_HOSTS = setOf(
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
    "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com"
)

private fun isYouTubeUrl(raw: String): Boolean {
    val host = try {
        java.net.URI(raw.trim()).host?.lowercase()
    } catch (_: Exception) {
        null
    } ?: return false
    return host in YOUTUBE_HOSTS || YOUTUBE_HOSTS.any { host.endsWith(".$it") }
}
