package com.wearsic.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedCacheTest {

    @Test
    fun `evicts least-recently-used beyond max size`() {
        val cache = BoundedCache<String, Int>(maxSize = 3)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.put("c", 3)

        // Touch "a" so "b" becomes the LRU entry.
        cache.get("a")
        cache.put("d", 4)

        assertEquals(3, cache.size, "cache must stay bounded")
        assertNull(cache.get("b"), "least recently used entry must be evicted")
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
        assertEquals(4, cache.get("d"))
    }

    @Test
    fun `access order refreshes recency`() {
        val cache = BoundedCache<String, Int>(maxSize = 2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a") // refresh a
        cache.put("c", 3) // evicts b

        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
    }

    @Test
    fun `put overwrites existing key without growing`() {
        val cache = BoundedCache<String, String>(maxSize = 2)
        cache.put("k", "v1")
        cache.put("k", "v2")
        assertEquals(1, cache.size)
        assertEquals("v2", cache.get("k"))
    }

    @Test
    fun `remove and clear work`() {
        val cache = BoundedCache<String, Int>(maxSize = 2)
        cache.put("a", 1)
        cache.remove("a")
        assertNull(cache.get("a"))

        cache.put("b", 2)
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `getOrPut computes only on miss`() {
        val cache = BoundedCache<String, Int>(maxSize = 2)
        var computed = 0
        val v1 = cache.getOrPut("k") { computed++; 7 }
        val v2 = cache.getOrPut("k") { computed++; 7 }
        assertEquals(7, v1)
        assertEquals(7, v2)
        assertEquals(1, computed)
    }

    @Test
    fun `size-1 cache keeps exactly the newest entry`() {
        val cache = BoundedCache<Int, Int>(maxSize = 1)
        cache.put(1, 1)
        cache.put(2, 2)
        assertTrue(cache.size <= 1)
        assertEquals(2, cache.get(2))
    }
}
