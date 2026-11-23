package com.wearsic.server

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the YouTube cookie used to avoid the "bot-challenged server IP"
 * problem noted in .env.example.
 *
 * Resolution order: WEARSIC_YOUTUBE_COOKIE env var on boot first, then the
 * value persisted in SQLite (settings table) from a previous runtime update —
 * matching the original server, where POST /api/config/youtube-cookie saved
 * the cookie and it survived restarts.
 */
object YoutubeSession {

    private fun envFallbackCookie(): String? =
        System.getenv("WEARSIC_YOUTUBE_COOKIE")?.trim()?.takeIf { it.isNotEmpty() }

    private val cookieRef = AtomicReference<String?>(null)

    @Volatile
    private var store: Database? = null

    /**
     * Call once at startup, after the [Database] exists: resolves the cookie
     * (env var wins over the persisted value) and backfills the DB with the
     * effective value so a runtime update survives restarts.
     */
    fun init(database: Database) {
        store = database
        val effective = envFallbackCookie()
            ?: database.getSetting("youtube_cookie")?.trim()?.takeIf { it.isNotEmpty() }
        cookieRef.set(effective)
        if (effective != null) database.setSetting("youtube_cookie", effective)
    }

    var cookie: String?
        get() = cookieRef.get()
        set(value) {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
            cookieRef.set(trimmed)
            store?.setSetting("youtube_cookie", trimmed ?: "")
        }

    fun hasCookie(): Boolean = cookie != null
}
