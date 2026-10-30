package com.wearsic.server

import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny bounded TTL cache for recommendation results.
 *
 * Keyed by the signature of the request (sorted seed ids + artists). A
 * recommendation refresh every <30 minutes is served from memory — the
 * expensive YouTube extractions happen at most twice an hour per user, which
 * is what keeps this feature essentially free on a phone-hosted server.
 */
class RecommendationCache(
    private val ttlMillis: Long = 30L * 60 * 1000,
    private val maxEntries: Int = 32,
) {
    private data class Entry(val tracks: List<TrackDto>, val expiresAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(key: String): List<TrackDto>? {
        val entry = entries[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAtMillis) {
            entries.remove(key)
            return null
        }
        return entry.tracks
    }

    fun put(key: String, tracks: List<TrackDto>) {
        if (entries.size >= maxEntries) {
            // Drop the oldest entry to keep the cache bounded.
            entries.entries.minByOrNull { it.value.expiresAtMillis }?.let {
                entries.remove(it.key)
            }
        }
        entries[key] = Entry(tracks, System.currentTimeMillis() + ttlMillis)
    }

    fun signature(seedTrackIds: List<String>, artists: Set<String>): String = buildString {
        seedTrackIds.sorted().forEach { append(it).append('|') }
        artists.sorted().forEach { append(it).append('|') }
    }
}
