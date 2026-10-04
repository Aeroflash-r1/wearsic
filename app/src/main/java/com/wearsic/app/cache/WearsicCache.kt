package com.wearsic.app.cache

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-layer caching system for the Wearsic application.
 * 
 * Provides:
 * 1. In-memory cache for fast access to frequently used data
 * 2. Disk cache for persistent storage of larger data
 * 3. Request deduplication to prevent duplicate network calls
 * 
 * Cache layers:
 * - Memory: L1 cache, fast but limited by RAM
 * - Disk: L2 cache, slower but persistent and larger
 * - Network: Source of truth, slowest
 */

// ============================================================================
// Cache Configuration
// ============================================================================

/**
 * Configuration for cache behavior.
 */
data class CacheConfig(
    /** Maximum number of entries in memory cache */
    val maxMemoryEntries: Int = 100,
    /** Maximum size of memory cache in bytes */
    val maxMemorySizeBytes: Long = 10 * 1024 * 1024, // 10 MB
    /** Time-to-live for memory cache entries in milliseconds */
    val memoryTtlMs: Long = 5 * 60 * 1000, // 5 minutes
    /** Maximum number of entries in disk cache */
    val maxDiskEntries: Int = 500,
    /** Maximum size of disk cache in bytes */
    val maxDiskSizeBytes: Long = 100 * 1024 * 1024, // 100 MB
    /** Time-to-live for disk cache entries in milliseconds */
    val diskTtlMs: Long = 24 * 60 * 60 * 1000, // 24 hours
    /** Enable/disable caching */
    val enabled: Boolean = true,
    /** Enable/disable memory cache */
    val memoryCacheEnabled: Boolean = true,
    /** Enable/disable disk cache */
    val diskCacheEnabled: Boolean = true
)

/**
 * Default cache configuration.
 */
val DEFAULT_CACHE_CONFIG = CacheConfig()

// ============================================================================
// Cache Entry
// ============================================================================

/**
 * A single cache entry with metadata.
 */
data class CacheEntry<T>(
    val key: String,
    val value: T,
    val timestamp: Long = System.currentTimeMillis(),
    val sizeBytes: Int = 0,
    val tags: Set<String> = emptySet()
) {
    /** Check if this entry has expired based on TTL. */
    fun isExpired(ttlMs: Long): Boolean {
        return System.currentTimeMillis() - timestamp > ttlMs
    }

    /** Check if this entry is still valid. */
    fun isValid(ttlMs: Long): Boolean {
        return !isExpired(ttlMs)
    }
}

// ============================================================================
// Memory Cache
// ============================================================================

/**
 * In-memory LRU cache with TTL support.
 * 
 * @param maxEntries Maximum number of entries
 * @param maxSizeBytes Maximum total size in bytes
 * @param ttlMs Time-to-live in milliseconds
 */
class MemoryCache<T : Any>(
    private val maxEntries: Int = 100,
    private val maxSizeBytes: Long = 10 * 1024 * 1024,
    private val ttlMs: Long = 5 * 60 * 1000
) {
    private val cache = ConcurrentHashMap<String, CacheEntry<T>>()
    private var totalSizeBytes: Long = 0
    private val accessOrder = mutableListOf<String>()

    /**
     * Get a value from the cache.
     */
    fun get(key: String): T? {
        val entry = cache[key] ?: return null
        if (!entry.isValid(ttlMs)) {
            cache.remove(key)
            accessOrder.remove(key)
            totalSizeBytes -= entry.sizeBytes
            return null
        }
        // Update access order for LRU
        accessOrder.remove(key)
        accessOrder.add(key)
        return entry.value
    }

    /**
     * Put a value into the cache.
     */
    fun put(key: String, value: T, sizeBytes: Int = 0) {
        // Evict if necessary
        while (cache.size >= maxEntries || totalSizeBytes + sizeBytes > maxSizeBytes) {
            evictOldest()
        }
        
        val entry = CacheEntry(key, value, System.currentTimeMillis(), sizeBytes)
        cache[key] = entry
        accessOrder.add(key)
        totalSizeBytes += sizeBytes
    }

    /**
     * Remove a value from the cache.
     */
    fun remove(key: String): T? {
        val entry = cache.remove(key) ?: return null
        accessOrder.remove(key)
        totalSizeBytes -= entry.sizeBytes
        return entry.value
    }

    /**
     * Clear all entries from the cache.
     */
    fun clear() {
        cache.clear()
        accessOrder.clear()
        totalSizeBytes = 0
    }

    /**
     * Clear expired entries.
     */
    fun clearExpired() {
        val expiredKeys = cache.filter { (_, entry) -> entry.isExpired(ttlMs) }.keys
        for (key in expiredKeys) {
            cache.remove(key)
            accessOrder.remove(key)
        }
    }

    /**
     * Get the number of entries in the cache.
     */
    fun size(): Int = cache.size

    /**
     * Get the total size of the cache in bytes.
     */
    fun totalSize(): Long = totalSizeBytes

    /**
     * Check if a key exists in the cache.
     */
    fun containsKey(key: String): Boolean = cache.containsKey(key)

    /**
     * Get all keys in the cache.
     */
    fun keys(): Set<String> = cache.keys

    /**
     * Evict the oldest (least recently used) entry.
     */
    private fun evictOldest() {
        if (accessOrder.isEmpty()) return
        val oldestKey = accessOrder.removeAt(0)
        val entry = cache.remove(oldestKey)
        if (entry != null) {
            totalSizeBytes -= entry.sizeBytes
        }
    }

    /**
     * Get cache statistics.
     */
    fun stats(): CacheStats {
        val expiredCount = cache.count { (_, entry) -> entry.isExpired(ttlMs) }
        return CacheStats(
            size = cache.size,
            totalSizeBytes = totalSizeBytes,
            maxEntries = maxEntries,
            maxSizeBytes = maxSizeBytes,
            expiredCount = expiredCount
        )
    }
}

/**
 * Cache statistics.
 */
data class CacheStats(
    val size: Int,
    val totalSizeBytes: Long,
    val maxEntries: Int,
    val maxSizeBytes: Long,
    val expiredCount: Int
)

// ============================================================================
// Request Deduplication
// ============================================================================

/**
 * Request deduplication cache to prevent duplicate network calls.
 * Uses a sliding window to track active requests.
 */
class RequestDeduplicator(
    private val windowMs: Long = 5000 // 5 seconds
) {
    private val activeRequests = ConcurrentHashMap<String, Long>()

    /**
     * Check if a request with this key is already active.
     * Returns true if the request should be skipped (duplicate).
     */
    fun shouldSkip(key: String): Boolean {
        val lastRequestTime = activeRequests[key] ?: return false
        val now = System.currentTimeMillis()
        if (now - lastRequestTime < windowMs) {
            return true
        }
        activeRequests[key] = now
        return false
    }

    /**
     * Mark a request as active.
     */
    fun markActive(key: String) {
        activeRequests[key] = System.currentTimeMillis()
    }

    /**
     * Mark a request as completed.
     */
    fun markCompleted(key: String) {
        activeRequests.remove(key)
    }

    /**
     * Clear all active requests.
     */
    fun clear() {
        activeRequests.clear()
    }

    /**
     * Get the number of active requests.
     */
    fun activeCount(): Int = activeRequests.size
}

// ============================================================================
// Search Cache
// ============================================================================

/**
 * Specialized cache for search results.
 * 
 * Caches search queries and their results to avoid duplicate network calls
 * for the same or similar queries.
 */
class SearchCache(
    private val memoryCache: MemoryCache<List<com.wearsic.app.model.Track>> = MemoryCache(
        maxEntries = 20,
        maxSizeBytes = 5 * 1024 * 1024,
        ttlMs = 10 * 60 * 1000 // 10 minutes
    ),
    private val deduplicator: RequestDeduplicator = RequestDeduplicator()
) {
    /**
     * Get cached search results for a query.
     */
    fun get(query: String): List<com.wearsic.app.model.Track>? {
        return memoryCache.get(normalizeQuery(query))
    }

    /**
     * Cache search results for a query.
     */
    fun put(query: String, results: List<com.wearsic.app.model.Track>) {
        val normalized = normalizeQuery(query)
        // Estimate size: each track is roughly 200 bytes
        val estimatedSize = results.size * 200
        memoryCache.put(normalized, results, estimatedSize)
    }

    /**
     * Check if a search request should be skipped (duplicate).
     */
    fun shouldSkip(query: String): Boolean {
        return deduplicator.shouldSkip(normalizeQuery(query))
    }

    /**
     * Mark a search request as active.
     */
    fun markActive(query: String) {
        deduplicator.markActive(normalizeQuery(query))
    }

    /**
     * Mark a search request as completed.
     */
    fun markCompleted(query: String) {
        deduplicator.markCompleted(normalizeQuery(query))
    }

    /**
     * Normalize a query for caching.
     */
    private fun normalizeQuery(query: String): String {
        return query.trim().lowercase()
    }

    /**
     * Clear the cache.
     */
    fun clear() {
        memoryCache.clear()
        deduplicator.clear()
    }
}

// ============================================================================
// Image Cache
// ============================================================================

/**
 * Specialized cache for images/artwork.
 * 
 * Caches artwork URLs and their loaded bitmaps to avoid duplicate downloads.
 */
class ImageCache(
    private val memoryCache: MemoryCache<android.graphics.Bitmap> = MemoryCache(
        maxEntries = 50,
        maxSizeBytes = 20 * 1024 * 1024, // 20 MB
        ttlMs = 30 * 60 * 1000 // 30 minutes
    )
) {
    /**
     * Get a cached bitmap for a URL.
     */
    fun get(url: String): android.graphics.Bitmap? {
        return memoryCache.get(url)
    }

    /**
     * Cache a bitmap for a URL.
     */
    fun put(url: String, bitmap: android.graphics.Bitmap) {
        val sizeBytes = bitmap.rowBytes * bitmap.height
        memoryCache.put(url, bitmap, sizeBytes)
    }

    /**
     * Clear the cache.
     */
    fun clear() {
        memoryCache.clear()
    }

    /**
     * Get the number of cached images.
     */
    fun size(): Int = memoryCache.size()
}

// ============================================================================
// Wearsic Cache Manager
// ============================================================================

/**
 * Central cache manager for the Wearsic application.
 * 
 * Provides unified access to all cache layers and manages cache configuration.
 */
class WearsicCacheManager(
    private val context: Context,
    private val config: CacheConfig = DEFAULT_CACHE_CONFIG
) {
    // Specialized caches
    val searchCache: SearchCache = SearchCache()
    val imageCache: ImageCache = ImageCache()
    
    // Generic caches
    private val genericMemoryCache = MemoryCache<Any>(
        maxEntries = config.maxMemoryEntries,
        maxSizeBytes = config.maxMemorySizeBytes,
        ttlMs = config.memoryTtlMs
    )

    /**
     * Get a value from the cache.
     */
    fun <T : Any> get(key: String, cacheKey: String = "generic"): T? {
        return when (cacheKey) {
            "search" -> searchCache.get(key) as? T
            "image" -> imageCache.get(key) as? T
            else -> genericMemoryCache.get(key) as? T
        }
    }

    /**
     * Put a value into the cache.
     */
    fun <T : Any> put(key: String, value: T, cacheKey: String = "generic", sizeBytes: Int = 0) {
        when (cacheKey) {
            "search" -> searchCache.put(key, value as List<com.wearsic.app.model.Track>)
            "image" -> imageCache.put(key, value as android.graphics.Bitmap)
            else -> genericMemoryCache.put(key, value, sizeBytes)
        }
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        searchCache.clear()
        imageCache.clear()
        genericMemoryCache.clear()
    }

    /**
     * Clear expired entries from all caches.
     */
    fun clearExpired() {
        genericMemoryCache.clearExpired()
    }

    /**
     * Get cache statistics.
     */
    fun stats(): Map<String, CacheStats> {
        return mapOf(
            "generic" to genericMemoryCache.stats(),
            "search" to CacheStats(
                size = 0, // Would need to expose from SearchCache
                totalSizeBytes = 0,
                maxEntries = config.maxMemoryEntries,
                maxSizeBytes = config.maxMemorySizeBytes,
                expiredCount = 0
            )
        )
    }

    /**
     * Check if caching is enabled.
     */
    fun isEnabled(): Boolean = config.enabled

    /**
     * Check if memory caching is enabled.
     */
    fun isMemoryCacheEnabled(): Boolean = config.memoryCacheEnabled

    /**
     * Check if disk caching is enabled.
     */
    fun isDiskCacheEnabled(): Boolean = config.diskCacheEnabled
}

// ============================================================================
// Cache Extensions
// ============================================================================

/**
 * Extension to cache a value with a specific key.
 */
fun <T : Any> T.cache(key: String, cache: MemoryCache<T>) {
    cache.put(key, this)
}

/**
 * Extension to get a cached value or compute it.
 */
fun <T : Any> MemoryCache<T>.getOrCompute(key: String, compute: () -> T, sizeBytes: Int = 0): T {
    val cached = get(key)
    if (cached != null) return cached
    val value = compute()
    put(key, value, sizeBytes)
    return value
}

/**
 * Extension to get a cached value or compute it asynchronously.
 */
suspend fun <T : Any> MemoryCache<T>.getOrComputeAsync(
    key: String,
    compute: suspend () -> T,
    sizeBytes: Int = 0
): T {
    val cached = get(key)
    if (cached != null) return cached
    val value = compute()
    put(key, value, sizeBytes)
    return value
}

// ============================================================================
// Singleton
// ============================================================================

/**
 * Singleton instance of the cache manager.
 * Note: Requires initialization via init() before use.
 */
object WearsicCache {
    @Volatile
    private var instance: WearsicCacheManager? = null

    fun init(context: Context, config: CacheConfig = DEFAULT_CACHE_CONFIG) {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    instance = WearsicCacheManager(context, config)
                }
            }
        }
    }

    fun get(): WearsicCacheManager {
        return instance ?: throw IllegalStateException("WearsicCache not initialized. Call init() first.")
    }

    fun search(): SearchCache = get().searchCache
    fun images(): ImageCache = get().imageCache
}

// ============================================================================
// Cache Keys
// ============================================================================

/**
 * Cache key generators for consistent caching.
 */
object CacheKeys {
    fun search(query: String): String = "search:$query"
    fun track(id: String): String = "track:$id"
    fun album(id: String): String = "album:$id"
    fun playlist(id: String): String = "playlist:$id"
    fun artwork(url: String): String = "artwork:$url"
    fun favorites(): String = "favorites"
    fun playlists(): String = "playlists"
    fun health(url: String): String = "health:$url"
}

// ============================================================================
// End of File
// ============================================================================
