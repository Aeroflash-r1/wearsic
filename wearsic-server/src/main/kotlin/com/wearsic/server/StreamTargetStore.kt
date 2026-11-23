package com.wearsic.server

/**
 * Durable home for resolved stream targets and wall-throttle marks.
 *
 * WHY: [CachedStreamTarget] used to live only in a 64-entry in-memory map, so
 * every restart — an engine update, a reboot, an lmkd kill, all routine on the
 * phone this runs on — threw away every resolution. The first tap on every
 * song then paid a fresh lean resolve (or worse, a full extraction), which is
 * exactly the "the first song after a restart is slow" symptom.
 *
 * Everything stored here is a CACHE with its own expiry: a stale row is never
 * authoritative (the proxy validates the URL by using it, and self-heals on
 * 403/404/410). So a corrupt or truncated store can only cost a re-resolve,
 * never correctness.
 *
 * Implemented by [Database]; null in tests that don't care.
 */
interface StreamTargetPersistence {

    /** Cached resolution for [videoId], or null. Expiry is the caller's job. */
    fun getStreamTarget(videoId: String): CachedStreamTarget?

    fun putStreamTarget(videoId: String, entry: CachedStreamTarget)

    fun removeStreamTarget(videoId: String)

    /** Epoch millis until which [videoId] is marked wall-throttled (0 = no mark). */
    fun getWallThrottledUntil(videoId: String): Long

    fun putWallThrottled(videoId: String, untilMillis: Long)

    /**
     * Drops expired rows so the tables stay bounded across weeks of uptime.
     * Cheap (two indexed DELETEs); called opportunistically on write.
     */
    fun pruneStreamState(nowMillis: Long = System.currentTimeMillis())
}
