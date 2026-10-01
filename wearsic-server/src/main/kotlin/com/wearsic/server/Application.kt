package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.application.log
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.io.File
import java.security.MessageDigest

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
    val proxyClient = HttpClient(CIO) {
        engine {
            maxConnectionsCount = 20
            endpoint.apply {
                maxConnectionsPerRoute = 10
                keepAliveTime = 30_000
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
    val healthMeter = ExtractionHealthMeter()
    val gateway = YoutubeGateway(
        downloader = NewPipeDownloader(proxyClient),
        healthMeter = healthMeter,
    )

    // Detect ffmpeg so songs without native AAC can be converted server-side.
    // Without it those rare songs answer 503 with install guidance instead.
    val transcoder = Transcoder(proxyClient)
    if (!transcoder.available) {
        System.err.println("WARNING: ffmpeg not found on PATH — songs YouTube only offers in Opus/WebM " +
            "will return 503. Install it in Termux with: pkg install ffmpeg")
    }

    val audioProxy = AudioProxy(gateway, proxyClient, transcoder)
    val apiKey = System.getenv("WEARSIC_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

    // Self-healing: canary watchdog + engine auto-update pipeline.
    val canary = ExtractionCanary(probe = { gateway.canaryProbe() })
    val engineUpdater = EngineUpdater(proxyClient, stateDir)
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
    )

    if (apiKey == null) {
        System.err.println(
            "WARNING: WEARSIC_API_KEY is not set — the server is OPEN to anyone who can reach it. " +
                "Fine for private LAN/Tailscale use; REQUIRED for any public/tunnel deployment."
        )
    }

    embeddedServer(ServerCIO, port = port, host = "0.0.0.0") {
        module(
            gateway, database, audioProxy, apiKey, searchOrchestrator,
            healthMeter = healthMeter,
            canary = canary,
            engineUpdater = engineUpdater,
        )
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
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging) { level = Level.INFO }

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
                UpdateStatusDto(
                    status = if (it.hasStagedUpdate()) "staged" else "idle",
                    latestKnownVersion = it.latestKnownVersion,
                    lastCheckAtMillis = it.lastCheckAtMillis,
                    lastError = it.lastCheckError,
                    stagedVersion = it.stagedState()?.version,
                    updateAttempt = it.currentAttemptCount(),
                    rollbackReason = it.rollbackInfo()?.reason,
                )
            }
            call.respond(
                HealthResponse(
                    transcoderAvailable = transcoder.available,
                    extraction = extraction,
                    canaryHealthy = canary?.lastProbeHealthy,
                    update = update,
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
                    // Never log or echo the credential.
                    val provided = call.request.headers["X-Wearsic-Key"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: call.request.headers["Authorization"]
                            ?.trim()
                            ?.removePrefix("Bearer ")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    val providedBytes = provided?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                    val ok = MessageDigest.isEqual(providedBytes, apiKey.toByteArray(Charsets.UTF_8))
                    if (!ok) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid API key"))
                        finish()
                    }
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

            get("/stream/{videoId}") {
                val requestedId = call.parameters["videoId"]!!
                // Per-client bucket: authenticated callers share the API-key
                // bucket; open servers bucket by real client IP. Must use
                // origin.remoteHost — request.host() is the SERVER's Host
                // header (same for everyone), which collapsed all open-mode
                // callers into one global bucket.
                val clientKey = call.request.headers["X-Wearsic-Key"]
                    ?: call.request.origin.remoteHost
                    ?: "unknown"
                if (!streamLimiter.tryAcquire(clientKey)) {
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Too many stream requests; slow down and retry shortly")
                    )
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
                audioProxy.handle(call, realVideoId)
            }

            get("/search") {
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(SearchResponse(searchOrchestrator.search(q)))
            }

            get("/suggestions") {
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(SuggestionsResponse(gateway.suggestions(q)))
            }

            get("/related/{videoId}") {
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
                searchOrchestrator.prefetchVideoIds(related.take(2).map { it.videoId })
                call.respond(RelatedResponse(related))
            }

            get("/search/albums") {
                val q = call.request.queryParameters["q"].orEmpty()
                call.respond(gateway.searchAlbums(q))
            }

            get("/playlist") {
                val url = call.request.queryParameters["url"]
                if (url.isNullOrBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing 'url' query parameter"))
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
                    searchOrchestrator.prefetchVideoIds(result.tracks.take(2).map { it.videoId })
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
            get("/config/youtube-cookie") {
                call.respond(YoutubeCookieStatus(hasCookie = YoutubeSession.hasCookie()))
            }
            post("/config/youtube-cookie") {
                val req = call.receive<YoutubeCookieRequest>()
                YoutubeSession.cookie = req.cookie
                call.respond(YoutubeCookieStatus(hasCookie = YoutubeSession.hasCookie()))
            }
        }
    }
}

/** Maps request-parsing failures to the (400, message) pair. */
private fun mapRequestError(cause: Throwable?): Pair<HttpStatusCode, String> = when (cause) {
    null -> HttpStatusCode.BadRequest to "Invalid request"
    is SerializationException -> HttpStatusCode.BadRequest to "Malformed JSON body"
    is BadRequestException -> HttpStatusCode.BadRequest to "Invalid request"
    is IllegalArgumentException -> HttpStatusCode.BadRequest to "Invalid request"
    else -> HttpStatusCode.BadRequest to "Invalid request"
}
