package com.wearsic.server

/** Cache entry with an expiry, used by [LyricsService]. */
data class Timed<T>(val value: T, val expiresAt: Long)

/**
 * The lyrics facade the API exposes: provider lookup, LRC/plain shaping,
 * and a bounded in-memory cache.
 *
 * Caching is keyed by artist|title|duration-bucket (15 s), which is how
 * the same song on a different album/version is told apart without storing
 * per-video entries for every remix. A miss (unavailable) is cached
 * shorter than a hit so a song whose lyrics appear later can resolve.
 */
class LyricsService(
    private val provider: LyricsProvider,
    private val cache: BoundedCache<String, Timed<LyricsResponse>> = BoundedCache(128),
) {
    private val HIT_TTL_MS = 24L * 60 * 60 * 1000
    private val MISS_TTL_MS = 60L * 60 * 1000

    suspend fun lyrics(query: LyricsQuery): LyricsResponse {
        val key = "%s|%s|%d".format(
            normalize(query.artist),
            normalize(query.title),
            query.durationMs.coerceAtLeast(0L) / 15_000L,
        )
        val now = System.currentTimeMillis()
        cache.get(key)?.let { if (it.expiresAt > now) return it.value }

        val response = runCatching { provider.fetch(query) }
            .getOrDefault(LyricsFetchResult.NotAvailable)
            .let { shape(query, it) }

        cache.put(
            key,
            Timed(
                response,
                now + if (response.mode == "unavailable") MISS_TTL_MS else HIT_TTL_MS,
            ),
        )
        return response
    }

    private fun shape(query: LyricsQuery, result: LyricsFetchResult): LyricsResponse =
        when (result) {
            is LyricsFetchResult.Synced -> {
                val parsed = LrcParser.parse(result.lrc)
                if (parsed.complete) {
                    LyricsResponse(
                        mode = "synced",
                        source = result.source,
                        lines = parsed.lines,
                    )
                } else {
                    LyricsResponse.UNAVAILABLE
                }
            }

            is LyricsFetchResult.Plain -> {
                val paragraphs = result.text
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                LyricsResponse(
                    mode = "plain",
                    source = result.source,
                    lines = paragraphs.map { LyricsLineDto(timeMs = null, text = it) },
                    plainText = result.text.trim().takeIf { it.isNotEmpty() },
                )
            }

            LyricsFetchResult.NotAvailable -> LyricsResponse.UNAVAILABLE
        }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}
