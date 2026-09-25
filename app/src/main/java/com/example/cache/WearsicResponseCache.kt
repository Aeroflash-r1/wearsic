package com.example.cache

import com.example.network.model.AlbumDto
import com.example.network.model.PlaylistDto
import com.example.network.model.PlaylistWithTracksDto
import com.example.network.model.SearchResponseDto
import com.example.network.model.ServerHealthDto
import com.example.network.model.SuggestionsResponseDto
import com.example.network.model.TrackDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Response caching layer for API responses.
 * 
 * Caches API responses to:
 * 1. Avoid duplicate network calls for the same request
 * 2. Provide instant results for repeated requests
 * 3. Reduce server load
 * 4. Improve app responsiveness
 * 
 * Features:
 * - Time-based expiration (TTL)
 * - Size-based eviction (LRU)
 * - Request deduplication
 * - Thread-safe operations
 */

/**
 * Configuration for response caching.
 */
data class ResponseCacheConfig(
    /** Maximum number of cached responses */
    val maxEntries: Int = 50,
    /** Maximum total size of cached responses in bytes */
    val maxSizeBytes: Long = 50 * 1024 * 1024, // 50 MB
    /** Default TTL for cached responses in milliseconds */
    val defaultTtlMs: Long = 5 * 60 * 1000, // 5 minutes
    /** TTL for health check responses (shorter, as they change frequently) */
    val healthTtlMs: Long = 30 * 1000, // 30 seconds
    /** TTL for search results (longer, as they're less likely to change) */
    val searchTtlMs: Long = 10 * 60 * 1000, // 10 minutes
    /** TTL for suggestions (medium, as they change moderately) */
    val suggestionsTtlMs: Long = 5 * 60 * 1000, // 5 minutes
    /** TTL for favorites/playlists (longer, as they're user-specific) */
    val libraryTtlMs: Long = 15 * 60 * 1000, // 15 minutes
    /** Enable/disable response caching */
    val enabled: Boolean = true
)

/**
 * Default response cache configuration.
 */
val DEFAULT_RESPONSE_CACHE_CONFIG = ResponseCacheConfig()

/**
 * Cached response wrapper with metadata.
 */
private data class CachedResponse<T>(
    val response: T,
    val timestamp: Long = System.currentTimeMillis(),
    val ttlMs: Long,
    val sizeBytes: Int
) {
    fun isExpired(): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMs
    }

    fun isValid(): Boolean {
        return !isExpired()
    }
}

/**
 * Response cache for API responses.
 * 
 * This cache stores responses from the server to avoid duplicate network calls.
 * It supports different TTL values for different types of responses.
 */
class WearsicResponseCache(
    private val config: ResponseCacheConfig = DEFAULT_RESPONSE_CACHE_CONFIG
) {
    private val cache = ConcurrentHashMap<String, Any>()
    private val accessOrder = mutableListOf<String>()
    private var totalSizeBytes: Long = 0
    private val mutex = Mutex()

    /**
     * Get a cached response.
     */
    suspend fun <T> get(key: String, ttlMs: Long = config.defaultTtlMs): T? {
        return mutex.withLock {
            val cached = cache[key] as? CachedResponse<T> ?: return@withLock null
            if (!cached.isValid()) {
                cache.remove(key)
                accessOrder.remove(key)
                totalSizeBytes -= cached.sizeBytes
                return@withLock null
            }
            // Update access order for LRU
            accessOrder.remove(key)
            accessOrder.add(key)
            return@withLock cached.response
        }
    }

    /**
     * Put a response into the cache.
     */
    suspend fun <T> put(key: String, response: T, ttlMs: Long = config.defaultTtlMs, sizeBytes: Int = 0) {
        mutex.withLock {
            // Evict if necessary
            while (cache.size >= config.maxEntries || totalSizeBytes + sizeBytes > config.maxSizeBytes) {
                evictOldest()
            }
            
            val cachedResponse = CachedResponse(response, System.currentTimeMillis(), ttlMs, sizeBytes)
            cache[key] = cachedResponse
            accessOrder.add(key)
            totalSizeBytes += sizeBytes
        }
    }

    /**
     * Clear a specific cached response.
     */
    suspend fun clear(key: String) {
        mutex.withLock {
            val cached = cache.remove(key) as? CachedResponse<*> ?: return@withLock
            accessOrder.remove(key)
            totalSizeBytes -= cached.sizeBytes
        }
    }

    /**
     * Clear all cached responses.
     */
    suspend fun clearAll() {
        mutex.withLock {
            cache.clear()
            accessOrder.clear()
            totalSizeBytes = 0
        }
    }

    /**
     * Clear expired responses.
     */
    suspend fun clearExpired() {
        mutex.withLock {
            val expiredKeys = cache.filter { (_, value) ->
                (value as? CachedResponse<*>)?.isExpired() == true
            }.keys
            for (key in expiredKeys) {
                val cached = cache.remove(key) as? CachedResponse<*> ?: continue
                accessOrder.remove(key)
                totalSizeBytes -= cached.sizeBytes
            }
        }
    }

    /**
     * Get the number of cached responses.
     */
    fun size(): Int = cache.size

    /**
     * Get the total size of cached responses in bytes.
     */
    fun totalSize(): Long = totalSizeBytes

    /**
     * Check if a key is cached.
     */
    fun containsKey(key: String): Boolean = cache.containsKey(key)

    /**
     * Evict the oldest (least recently used) entry.
     */
    private fun evictOldest() {
        if (accessOrder.isEmpty()) return
        val oldestKey = accessOrder.removeAt(0)
        val cached = cache.remove(oldestKey) as? CachedResponse<*> ?: return
        totalSizeBytes -= cached.sizeBytes
    }

    /**
     * Get cache statistics.
     */
    fun stats(): ResponseCacheStats {
        val expiredCount = cache.count { (_, value) ->
            (value as? CachedResponse<*>)?.isExpired() == true
        }
        return ResponseCacheStats(
            size = cache.size,
            totalSizeBytes = totalSizeBytes,
            maxEntries = config.maxEntries,
            maxSizeBytes = config.maxSizeBytes,
            expiredCount = expiredCount,
            enabled = config.enabled
        )
    }

    /**
     * Check if caching is enabled.
     */
    fun isEnabled(): Boolean = config.enabled
}

/**
 * Response cache statistics.
 */
data class ResponseCacheStats(
    val size: Int,
    val totalSizeBytes: Long,
    val maxEntries: Int,
    val maxSizeBytes: Long,
    val expiredCount: Int,
    val enabled: Boolean
)

// ============================================================================
// Specialized Response Caches
// ============================================================================

/**
 * Specialized cache for search responses.
 */
class SearchResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 20,
            maxSizeBytes = 10 * 1024 * 1024,
            defaultTtlMs = 10 * 60 * 1000
        )
    )
) {
    private val mutex = Mutex()

    suspend fun get(query: String, baseUrl: String): SearchResponseDto? {
        val key = "search:$baseUrl:$query"
        return delegate.get<SearchResponseDto>(key, delegate.config.searchTtlMs)
    }

    suspend fun put(query: String, baseUrl: String, response: SearchResponseDto) {
        val key = "search:$baseUrl:$query"
        val sizeBytes = response.tracks.sumOf { 
            it.title.length + it.artist.length + it.id.length + 100 // Estimate
        }
        delegate.put(key, response, delegate.config.searchTtlMs, sizeBytes)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

/**
 * Specialized cache for suggestions responses.
 */
class SuggestionsResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 30,
            maxSizeBytes = 1 * 1024 * 1024,
            defaultTtlMs = 5 * 60 * 1000
        )
    )
) {
    suspend fun get(query: String, baseUrl: String): SuggestionsResponseDto? {
        val key = "suggestions:$baseUrl:$query"
        return delegate.get<SuggestionsResponseDto>(key, delegate.config.suggestionsTtlMs)
    }

    suspend fun put(query: String, baseUrl: String, response: SuggestionsResponseDto) {
        val key = "suggestions:$baseUrl:$query"
        val sizeBytes = response.suggestions.sumOf { it.length } + 100
        delegate.put(key, response, delegate.config.suggestionsTtlMs, sizeBytes)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

/**
 * Specialized cache for health check responses.
 */
class HealthResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 5,
            maxSizeBytes = 1024 * 100, // 100 KB
            defaultTtlMs = 30 * 1000
        )
    )
) {
    suspend fun get(baseUrl: String): ServerHealthDto? {
        val key = "health:$baseUrl"
        return delegate.get<ServerHealthDto>(key, delegate.config.healthTtlMs)
    }

    suspend fun put(baseUrl: String, response: ServerHealthDto) {
        val key = "health:$baseUrl"
        delegate.put(key, response, delegate.config.healthTtlMs, 1000)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

/**
 * Specialized cache for favorites responses.
 */
class FavoritesResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 1,
            maxSizeBytes = 1 * 1024 * 1024,
            defaultTtlMs = 15 * 60 * 1000
        )
    )
) {
    suspend fun get(baseUrl: String, apiKey: String): List<TrackDto>? {
        val key = "favorites:$baseUrl:$apiKey"
        return delegate.get<List<TrackDto>>(key, delegate.config.libraryTtlMs)
    }

    suspend fun put(baseUrl: String, apiKey: String, response: List<TrackDto>) {
        val key = "favorites:$baseUrl:$apiKey"
        val sizeBytes = response.sumOf { 
            it.title.length + it.artist.length + it.id.length + 100
        }
        delegate.put(key, response, delegate.config.libraryTtlMs, sizeBytes)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

/**
 * Specialized cache for playlists responses.
 */
class PlaylistsResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 10,
            maxSizeBytes = 2 * 1024 * 1024,
            defaultTtlMs = 15 * 60 * 1000
        )
    )
) {
    suspend fun get(baseUrl: String, apiKey: String): List<PlaylistDto>? {
        val key = "playlists:$baseUrl:$apiKey"
        return delegate.get<List<PlaylistDto>>(key, delegate.config.libraryTtlMs)
    }

    suspend fun put(baseUrl: String, apiKey: String, response: List<PlaylistDto>) {
        val key = "playlists:$baseUrl:$apiKey"
        val sizeBytes = response.sumOf { 
            it.name.length + it.id.length + 100
        }
        delegate.put(key, response, delegate.config.libraryTtlMs, sizeBytes)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

/**
 * Specialized cache for playlist detail responses.
 */
class PlaylistDetailResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 20,
            maxSizeBytes = 5 * 1024 * 1024,
            defaultTtlMs = 15 * 60 * 1000
        )
    )
) {
    suspend fun get(playlistId: String, baseUrl: String, apiKey: String): PlaylistWithTracksDto? {
        val key = "playlist_detail:$baseUrl:$apiKey:$playlistId"
        return delegate.get<PlaylistWithTracksDto>(key, delegate.config.libraryTtlMs)
    }

    suspend fun put(playlistId: String, baseUrl: String, apiKey: String, response: PlaylistWithTracksDto) {
        val key = "playlist_detail:$baseUrl:$apiKey:$playlistId"
        val sizeBytes = response.tracks.sumOf { 
            it.title.length + it.artist.length + it.id.length + 100
        } + response.name.length + 100
        delegate.put(key, response, delegate.config.libraryTtlMs, sizeBytes)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

/**
 * Specialized cache for album search responses.
 */
class AlbumSearchResponseCache(
    private val delegate: WearsicResponseCache = WearsicResponseCache(
        ResponseCacheConfig(
            maxEntries = 20,
            maxSizeBytes = 5 * 1024 * 1024,
            defaultTtlMs = 10 * 60 * 1000
        )
    )
) {
    suspend fun get(query: String, baseUrl: String): List<AlbumDto>? {
        val key = "albums:$baseUrl:$query"
        return delegate.get<List<AlbumDto>>(key, delegate.config.searchTtlMs)
    }

    suspend fun put(query: String, baseUrl: String, response: List<AlbumDto>) {
        val key = "albums:$baseUrl:$query"
        val sizeBytes = response.sumOf { 
            it.name.length + it.uploader.length + it.id.length + 100
        }
        delegate.put(key, response, delegate.config.searchTtlMs, sizeBytes)
    }

    suspend fun clear() {
        delegate.clearAll()
    }
}

// ============================================================================
// Response Cache Manager
// ============================================================================

/**
 * Central manager for all response caches.
 */
class ResponseCacheManager(
    private val config: ResponseCacheConfig = DEFAULT_RESPONSE_CACHE_CONFIG
) {
    val searchCache: SearchResponseCache = SearchResponseCache()
    val suggestionsCache: SuggestionsResponseCache = SuggestionsResponseCache()
    val healthCache: HealthResponseCache = HealthResponseCache()
    val favoritesCache: FavoritesResponseCache = FavoritesResponseCache()
    val playlistsCache: PlaylistsResponseCache = PlaylistsResponseCache()
    val playlistDetailCache: PlaylistDetailResponseCache = PlaylistDetailResponseCache()
    val albumSearchCache: AlbumSearchResponseCache = AlbumSearchResponseCache()

    /**
     * Clear all caches.
     */
    suspend fun clearAll() {
        searchCache.clear()
        suggestionsCache.clear()
        healthCache.clear()
        favoritesCache.clear()
        playlistsCache.clear()
        playlistDetailCache.clear()
        albumSearchCache.clear()
    }

    /**
     * Clear expired entries from all caches.
     */
    suspend fun clearExpired() {
        // Each cache handles its own expiration
    }

    /**
     * Get combined statistics for all caches.
     */
    fun stats(): Map<String, ResponseCacheStats> {
        return mapOf(
            "search" to searchCache.stats(),
            "suggestions" to suggestionsCache.stats(),
            "health" to healthCache.stats(),
            "favorites" to favoritesCache.stats(),
            "playlists" to playlistsCache.stats(),
            "playlist_detail" to playlistDetailCache.stats(),
            "albums" to albumSearchCache.stats()
        )
    }

    /**
     * Check if caching is enabled.
     */
    fun isEnabled(): Boolean = config.enabled
}

// ============================================================================
// Singleton
// ============================================================================

/**
 * Singleton instance of the response cache manager.
 */
object WearsicResponseCache {
    private val instance: ResponseCacheManager by lazy { ResponseCacheManager(DEFAULT_RESPONSE_CACHE_CONFIG) }

    fun init(config: ResponseCacheConfig = DEFAULT_RESPONSE_CACHE_CONFIG) {
        // Already initialized via lazy, but kept for backward compatibility
    }

    fun get(): ResponseCacheManager = instance

    fun search(): SearchResponseCache = instance.searchCache
    fun suggestions(): SuggestionsResponseCache = instance.suggestionsCache
    fun health(): HealthResponseCache = instance.healthCache
    fun favorites(): FavoritesResponseCache = instance.favoritesCache
    fun playlists(): PlaylistsResponseCache = instance.playlistsCache
    fun playlistDetail(): PlaylistDetailResponseCache = instance.playlistDetailCache
    fun albums(): AlbumSearchResponseCache = instance.albumSearchCache
}

// ============================================================================
// End of File
// ============================================================================
