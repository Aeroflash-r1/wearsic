package com.wearsic.server

import kotlinx.serialization.Serializable

/**
 * One lyric line. [timeMs] is the timestamp in the track for synced mode;
 * null for plain mode (lines are display paragraphs, not timed).
 */
@Serializable
data class LyricsLineDto(
    val timeMs: Long? = null,
    val text: String = "",
)

/**
 * `/api/lyrics` response.
 *
 * `mode`:
 *  - `synced` — [lines] carry [LyricsLineDto.timeMs]; the client highlights
 *    the line matching the playback position.
 *  - `plain`  — timed lyrics are not available; [lines] are untimed display
 *    paragraphs and [plainText] holds the full text.
 *  - `unavailable` — nothing could be fetched (missing, instrumental, or a
 *    provider failure); [lines] is empty.
 */
@Serializable
data class LyricsResponse(
    val mode: String = "unavailable",
    val source: String? = null,
    val lines: List<LyricsLineDto> = emptyList(),
    val plainText: String? = null,
) {
    companion object {
        val UNAVAILABLE = LyricsResponse()
    }
}

/** What the watch already knows about the track, sent to the provider. */
data class LyricsQuery(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

/** Result of a provider lookup, before shaping into [LyricsResponse]. */
sealed interface LyricsFetchResult {
    data class Synced(val lrc: String, val source: String) : LyricsFetchResult
    data class Plain(val text: String, val source: String) : LyricsFetchResult
    data object NotAvailable : LyricsFetchResult
}

/**
 * A lyrics backend. Implementations are stateless; [LyricsService] owns
 * caching and response shaping.
 */
interface LyricsProvider {
    suspend fun fetch(query: LyricsQuery): LyricsFetchResult
}
