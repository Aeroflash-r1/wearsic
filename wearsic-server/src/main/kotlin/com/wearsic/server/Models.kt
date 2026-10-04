package com.wearsic.server

import kotlinx.serialization.Serializable

/**
 * Matches API_CONTRACT.md exactly. The client parses these defensively with
 * kotlinx.serialization (unknown keys ignored, missing fields defaulted), so
 * new fields can be added freely later, but `videoId` must never be renamed
 * or removed within this client generation.
 */
@Serializable
data class TrackDto(
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationMs: Long = 0L,
    val thumbnailUrl: String? = null,
)

@Serializable
data class SearchResponse(val results: List<TrackDto>)

@Serializable
data class SuggestionsResponse(val suggestions: List<String>)

@Serializable
data class RelatedResponse(val results: List<TrackDto>)

@Serializable
data class AlbumDto(
    val id: String, // full playlist URL, not a bare id — per contract
    val name: String,
    val uploader: String,
    val thumbnailUrl: String? = null,
)

@Serializable
data class PlaylistTracksResponse(
    val id: String,
    val name: String,
    val tracks: List<TrackDto>,
)

@Serializable
data class PlaylistSummaryDto(
    val id: String,
    val name: String,
    val trackCount: Int,
    val thumbnailUrl: String? = null,
)

@Serializable
data class CreatePlaylistRequest(val name: String)

@Serializable
data class ExtractionHealthDto(
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val failureRatePercent: Int = 0,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    // Failure-classification context: WHEN each outcome last happened, so
    // "YouTube broke the engine" (recent failures, long-run successes) can be
    // told apart from "never extracted anything" and from transient outages.
    val lastSuccessAtMillis: Long = 0,
    val lastFailureAtMillis: Long = 0,
)

@Serializable
data class UpdateStatusDto(
    // "idle" | "checking" | "staged" — "staged" means a new engine is
    // downloaded and waiting for the supervisor to apply on restart.
    val status: String = "idle",
    val latestKnownVersion: String? = null,
    val lastCheckAtMillis: Long = 0,
    val lastError: String? = null,
    val stagedVersion: String? = null,
    // Update/rollback loop visibility: how many times the current target has
    // been staged, and why the supervisor last rolled back (if it ever did).
    val updateAttempt: Int = 0,
    val rollbackReason: String? = null,
)

/**
 * Readiness ("can Wearsic currently serve music right now?") — the deeper
 * counterpart to /health ("is the process alive?"). Deliberately cheap:
 * no network, no extraction, no secrets.
 */
@Serializable
data class ReadyResponse(
    val ready: Boolean,
    val database: Boolean,
    val extractor: Boolean,
    val transcoder: Boolean,
    val engineVersion: String = ServerVersion.VERSION,
)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    // ServerVersion.VERSION is the single source of truth (build.gradle.kts
    // parses it so the JAR name and /health always agree).
    val version: String = ServerVersion.VERSION,
    val serverName: String = "Wearsic Engine",
    // True when ffmpeg was found, so server-side AAC conversion works for
    // songs YouTube only offers in Opus/WebM. Purely informational — the app
    // parses health leniently and ignores unknown fields.
    val transcoderAvailable: Boolean = false,
    // Self-healing observability: is the extraction engine succeeding, is the
    // canary (probe of a permanent video) passing, and is an engine update
    // staged? Optional + nullable so old monitoring scripts stay compatible.
    val extraction: ExtractionHealthDto? = null,
    val canaryHealthy: Boolean? = null,
    val update: UpdateStatusDto? = null,
)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class YoutubeCookieRequest(val cookie: String)

@Serializable
data class YoutubeCookieStatus(val hasCookie: Boolean)
