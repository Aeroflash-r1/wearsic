package com.wearsic.server

import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * Plain JDBC over sqlite-jdbc (no ORM in the dependency set). A single
 * connection is reused and all access is synchronized — SQLite doesn't
 * handle concurrent writers well, and this is a personal-use, low-QPS
 * server, so a simple lock is the right tradeoff over connection pooling.
 *
 * The DDL below mirrors the schema of the original compiled server exactly
 * (verified against its wearsic.db), so an existing database with favorites/
 * playlists keeps working without any migration — CREATE TABLE IF NOT EXISTS
 * is a no-op on those tables.
 */
class Database(dbPath: String) : StreamTargetPersistence {

    /** Persisted surrogate matches older than this are re-matched (self-healing). */
    private val MATCH_STALE_MS: Long = 30L * 24 * 60 * 60 * 1000 // 30 days

    /** Hard cap on persisted matches; the oldest are evicted beyond this. */
    private val MAX_MATCH_ROWS = 2000

    /**
     * Anti-abuse backstops for an open (keyless) server: anyone on the LAN
     * can add favorites/playlists. Caps are generous for a human (a personal
     * library never approaches them) and enforced by evicting the oldest —
     * no new error paths, storage simply stops growing.
     */
    private val MAX_FAVORITES = 5000

    /** Bound on persisted stream targets (a few hundred songs' worth). */
    private val MAX_STREAM_TARGETS = 4000

    /**
     * Stream pruning runs on write, but not on *every* write: it is three
     * DELETEs, and a resolve completes on the tap path. Once a minute keeps
     * the tables tidy without putting that work in front of playback.
     */
    private val STREAM_PRUNE_INTERVAL_MS = 60L * 1000
    private var nextStreamPruneAt = 0L
    private val MAX_PLAYLISTS = 200
    private val MAX_TRACKS_PER_PLAYLIST = 2000

    private val lock = Any()
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
        // WAL gives crash safety via the -wal/-shm sidecar files; NORMAL
        // syncing is the SQLite-recommended pairing with WAL (durable across
        // application crashes; only power loss may lose the last commits) and
        // avoids an fsync per write on slow phone storage.
        createStatement().use { it.execute("PRAGMA synchronous = NORMAL") }
        // Busy timeout: WAL still momentarily blocks readers during
        // checkpoints; wait briefly instead of failing instantly.
        createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
    }

    init {
        synchronized(lock) {
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS favorites (
                        video_id     TEXT PRIMARY KEY,
                        title        TEXT NOT NULL,
                        uploader     TEXT NOT NULL,
                        duration_ms  INTEGER NOT NULL,
                        thumbnail_url TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playlists (
                        id   TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        thumbnail_url TEXT
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_tracks (
                        playlist_id  TEXT NOT NULL,
                        video_id     TEXT NOT NULL,
                        position     INTEGER NOT NULL,
                        title        TEXT NOT NULL,
                        uploader     TEXT NOT NULL,
                        duration_ms  INTEGER NOT NULL,
                        thumbnail_url TEXT NOT NULL,
                        PRIMARY KEY (playlist_id, video_id),
                        FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS settings (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS surrogate_matches (
                        surrogate_id TEXT PRIMARY KEY,
                        video_id     TEXT NOT NULL,
                        matched_at   INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // Durable stream-target cache (see StreamTargetPersistence).
                // A resolved CDN URL + its byte length is exactly what a tap
                // needs, and it used to live only in a 64-entry in-memory map
                // — so every restart cold-started every song.
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS stream_targets (
                        video_id       TEXT PRIMARY KEY,
                        url            TEXT NOT NULL,
                        mime_type      TEXT NOT NULL,
                        itag           INTEGER NOT NULL,
                        content_length INTEGER NOT NULL,
                        needs_warm     INTEGER NOT NULL,
                        expires_at     INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                // Wall-throttle marks: "this video's lean URLs hit the CDN's
                // per-URL transfer budget on this network". Losing them on a
                // restart sent the next resolve straight back into the URL
                // family that had just refused.
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS stream_marks (
                        video_id   TEXT PRIMARY KEY,
                        wall_until INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.execute(
                    "CREATE INDEX IF NOT EXISTS idx_stream_targets_expiry " +
                        "ON stream_targets(expires_at)"
                )
            }
        }
    }

    /**
     * Prepared-statement cache for the read paths the watch hits on every
     * screen open. sqlite-jdbc compiles the SQL on each prepareStatement()
     * call; on a phone (and worse, on a proot filesystem) that is real work
     * per request. Everything runs under [lock], which is what makes sharing a
     * statement instance safe.
     */
    private val statements = HashMap<String, java.sql.PreparedStatement>()

    private fun stmt(sql: String): java.sql.PreparedStatement =
        statements.getOrPut(sql) { conn.prepareStatement(sql) }

    /**
     * In-memory copy of the favourites list. `listFavorites()` is called on
     * every favourites screen open AND by /api/recommendations on every hit
     * (to build the exclude set), so re-reading and re-mapping up to 5000 rows
     * each time was pure waste. Invalidated by every write.
     */
    @Volatile
    private var favoritesSnapshot: List<TrackDto>? = null

    /**
     * Cheap liveness probe for /ready: a single indexed SELECT against the
     * open connection. True when the database can answer queries at all.
     * (Reads a missing key — never writes, never touches user data.)
     */
    fun ping(): Boolean = try {
        synchronized(lock) {
            conn.prepareStatement("SELECT 1").use { ps ->
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Idempotent close for graceful shutdown (drains WAL via checkpoint). */
    fun close() = try {
        synchronized(lock) {
            statements.values.forEach { runCatching { it.close() } }
            statements.clear()
            conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            conn.close()
        }
    } catch (_: Exception) {
    }

    // ---------------- Settings (persisted YouTube cookie etc.) ----------------

    fun getSetting(key: String): String? = synchronized(lock) {
        conn.prepareStatement("SELECT value FROM settings WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun setSetting(key: String, value: String) = synchronized(lock) {
        conn.prepareStatement(
            """
            INSERT INTO settings (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    // ---------------- Legacy surrogate -> YouTube match persistence ----------------

    /**
     * Legacy read-only lookup for surrogate ("it:12345") -> real YouTube
     * videoId matches persisted by pre-YTM builds. No new rows are written
     * since the iTunes layer was removed — this only serves upgrade replay
     * for favorites/playlists saved before the YouTube Music migration.
     *
     * Entries are treated as stale after [MATCH_STALE_MS] and the table stays
     * bounded via [putMatchedVideoId]'s eviction (kept for tests/backfill).
     */
    fun getMatchedVideoId(surrogateId: String, nowMs: Long = System.currentTimeMillis()): String? =
        synchronized(lock) {
            conn.prepareStatement(
                "SELECT video_id FROM surrogate_matches WHERE surrogate_id = ? AND matched_at > ?"
            ).use { ps ->
                ps.setString(1, surrogateId)
                ps.setLong(2, nowMs - MATCH_STALE_MS)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    fun putMatchedVideoId(surrogateId: String, videoId: String, nowMs: Long = System.currentTimeMillis()): Unit =
        synchronized(lock) {
            conn.prepareStatement(
                """
                INSERT INTO surrogate_matches (surrogate_id, video_id, matched_at) VALUES (?, ?, ?)
                ON CONFLICT(surrogate_id) DO UPDATE SET
                    video_id = excluded.video_id,
                    matched_at = excluded.matched_at
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, surrogateId)
                ps.setString(2, videoId)
                ps.setLong(3, nowMs)
                ps.executeUpdate()
            }
            // Keep the table bounded: drop the oldest rows beyond the cap.
            conn.prepareStatement(
                """
                DELETE FROM surrogate_matches WHERE surrogate_id NOT IN (
                    SELECT surrogate_id FROM surrogate_matches ORDER BY matched_at DESC LIMIT ?
                )
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, MAX_MATCH_ROWS)
                ps.executeUpdate()
            }
        }

    /** Current number of persisted matches (exposed for tests). */
    fun matchCount(): Int = synchronized(lock) {
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM surrogate_matches").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    /**
     * One-time cleanup for the v1.4.4 matcher fix: builds before it scored
     * version words (demo/live/...) on normalized text and could persist
     * several songs onto the same video. Those rows are wiped once so every
     * song re-matches with the fixed matcher; the marker row makes this a
     * no-op on all subsequent boots.
     */
    fun clearStaleMatchesOnce(markerKey: String, markerValue: String) {
        synchronized(lock) {
            if (getSetting(markerKey) == markerValue) return
            conn.createStatement().use { it.executeUpdate("DELETE FROM surrogate_matches") }
            setSetting(markerKey, markerValue)
        }
    }

    // ---------------- Favorites ----------------

    fun listFavorites(): List<TrackDto> {
        synchronized(lock) {
            favoritesSnapshot?.let { return it }
            val list = stmt("SELECT * FROM favorites ORDER BY rowid DESC").executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toTrackDto())
                }
            }
            favoritesSnapshot = list
            return list
        }
    }

    fun addFavorite(track: TrackDto) = synchronized(lock) {
        conn.prepareStatement(
            """
            INSERT INTO favorites (video_id, title, uploader, duration_ms, thumbnail_url)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(video_id) DO UPDATE SET
                title = excluded.title,
                uploader = excluded.uploader,
                duration_ms = excluded.duration_ms,
                thumbnail_url = excluded.thumbnail_url
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, track.videoId)
            ps.setString(2, track.title)
            ps.setString(3, track.uploader)
            ps.setLong(4, track.durationMs)
            ps.setString(5, track.thumbnailUrl ?: "")
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "DELETE FROM favorites WHERE video_id NOT IN " +
                "(SELECT video_id FROM favorites ORDER BY rowid DESC LIMIT $MAX_FAVORITES)"
        ).use { it.executeUpdate() }
        favoritesSnapshot = null
    }

    fun removeFavorite(videoId: String): Unit = synchronized(lock) {
        conn.prepareStatement("DELETE FROM favorites WHERE video_id = ?").use { ps ->
            ps.setString(1, videoId)
            ps.executeUpdate()
        }
        favoritesSnapshot = null
    }

    // ---------------- Playlists ----------------

    fun listPlaylists(): List<PlaylistSummaryDto> = synchronized(lock) {
        conn.prepareStatement(
            """
            SELECT p.id, p.name,
                   (SELECT COUNT(*) FROM playlist_tracks t WHERE t.playlist_id = p.id) AS track_count,
                   (SELECT thumbnail_url FROM playlist_tracks t
                     WHERE t.playlist_id = p.id ORDER BY t.position ASC LIMIT 1) AS thumb
            FROM playlists p
            ORDER BY p.rowid DESC
            """.trimIndent()
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            PlaylistSummaryDto(
                                id = rs.getString("id"),
                                name = rs.getString("name"),
                                trackCount = rs.getInt("track_count"),
                                thumbnailUrl = rs.getString("thumb"),
                            )
                        )
                    }
                }
            }
        }
    }

    fun createPlaylist(name: String): PlaylistSummaryDto = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        conn.prepareStatement("INSERT INTO playlists (id, name) VALUES (?, ?)").use { ps ->
            ps.setString(1, id)
            ps.setString(2, name)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "DELETE FROM playlists WHERE id NOT IN " +
                "(SELECT id FROM playlists ORDER BY rowid DESC LIMIT $MAX_PLAYLISTS)"
        ).use { it.executeUpdate() }
        PlaylistSummaryDto(id = id, name = name, trackCount = 0, thumbnailUrl = null)
    }

    fun getPlaylistTracks(playlistId: String): PlaylistTracksResponse? = synchronized(lock) {
        val name = conn.prepareStatement("SELECT name FROM playlists WHERE id = ?").use { ps ->
            ps.setString(1, playlistId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("name") else null }
        } ?: return null

        val tracks = conn.prepareStatement(
            "SELECT * FROM playlist_tracks WHERE playlist_id = ? ORDER BY position ASC"
        ).use { ps ->
            ps.setString(1, playlistId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toTrackDto())
                }
            }
        }
        PlaylistTracksResponse(id = playlistId, name = name, tracks = tracks)
    }

    fun addTrackToPlaylist(playlistId: String, track: TrackDto) = synchronized(lock) {
        val nextPosition = conn.prepareStatement(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?"
        ).use { ps ->
            ps.setString(1, playlistId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        conn.prepareStatement(
            """
            INSERT INTO playlist_tracks
                (playlist_id, video_id, position, title, uploader, duration_ms, thumbnail_url)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(playlist_id, video_id) DO UPDATE SET
                title = excluded.title,
                uploader = excluded.uploader,
                duration_ms = excluded.duration_ms,
                thumbnail_url = excluded.thumbnail_url
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, playlistId)
            ps.setString(2, track.videoId)
            ps.setInt(3, nextPosition)
            ps.setString(4, track.title)
            ps.setString(5, track.uploader)
            ps.setLong(6, track.durationMs)
            ps.setString(7, track.thumbnailUrl ?: "")
            ps.executeUpdate()
        }
        conn.prepareStatement(
            """
            DELETE FROM playlist_tracks WHERE playlist_id = ? AND video_id NOT IN (
                SELECT video_id FROM playlist_tracks WHERE playlist_id = ?
                ORDER BY position DESC LIMIT $MAX_TRACKS_PER_PLAYLIST
            )
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, playlistId)
            ps.setString(2, playlistId)
            ps.executeUpdate()
        }
    }

    /**
     * Matches the documented special case: videoId == "*" deletes the whole
     * playlist (cascades to its tracks via the FK), rather than deleting a
     * single track. Otherwise deletes just that one track from the playlist.
     */
    fun deletePlaylistTrack(playlistId: String, videoId: String) = synchronized(lock) {
        if (videoId == "*") {
            conn.prepareStatement("DELETE FROM playlists WHERE id = ?").use { ps ->
                ps.setString(1, playlistId)
                ps.executeUpdate()
            }
        } else {
            conn.prepareStatement(
                "DELETE FROM playlist_tracks WHERE playlist_id = ? AND video_id = ?"
            ).use { ps ->
                ps.setString(1, playlistId)
                ps.setString(2, videoId)
                ps.executeUpdate()
            }
        }
    }

    // ---------------- Stream target / throttle-mark persistence ----------------

    override fun getStreamTarget(videoId: String): CachedStreamTarget? = synchronized(lock) {
        // Reused statement: close the ResultSet, never the statement itself.
        // `.use` here would close the cached PreparedStatement, and the next
        // read of the same SQL would then throw "statement is closed".
        val ps = stmt(
            "SELECT url, mime_type, itag, content_length, needs_warm, expires_at " +
                "FROM stream_targets WHERE video_id = ?"
        )
        ps.setString(1, videoId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) return@synchronized null
            CachedStreamTarget(
                StreamTarget(
                    url = rs.getString(1),
                    mimeType = rs.getString(2),
                    itag = rs.getInt(3),
                    contentLength = rs.getLong(4),
                    needsExtractorWarm = rs.getInt(5) != 0,
                ),
                expiresAtMillis = rs.getLong(6),
            )
        }
    }

    override fun putStreamTarget(videoId: String, entry: CachedStreamTarget) = synchronized(lock) {
        conn.prepareStatement(
            """
            INSERT INTO stream_targets
                (video_id, url, mime_type, itag, content_length, needs_warm, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(video_id) DO UPDATE SET
                url = excluded.url,
                mime_type = excluded.mime_type,
                itag = excluded.itag,
                content_length = excluded.content_length,
                needs_warm = excluded.needs_warm,
                expires_at = excluded.expires_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, videoId)
            ps.setString(2, entry.target.url)
            ps.setString(3, entry.target.mimeType)
            ps.setInt(4, entry.target.itag)
            ps.setLong(5, entry.target.contentLength)
            ps.setInt(6, if (entry.target.needsExtractorWarm) 1 else 0)
            ps.setLong(7, entry.expiresAtMillis)
            ps.executeUpdate()
        }
        val now = System.currentTimeMillis()
        if (now >= nextStreamPruneAt) {
            nextStreamPruneAt = now + STREAM_PRUNE_INTERVAL_MS
            pruneStreamState(now)
        }
    }

    override fun removeStreamTarget(videoId: String): Unit = synchronized(lock) {
        conn.prepareStatement("DELETE FROM stream_targets WHERE video_id = ?").use { ps ->
            ps.setString(1, videoId)
            ps.executeUpdate()
        }
    }

    override fun getWallThrottledUntil(videoId: String): Long = synchronized(lock) {
        val ps = stmt("SELECT wall_until FROM stream_marks WHERE video_id = ?")
        ps.setString(1, videoId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
    }

    override fun putWallThrottled(videoId: String, untilMillis: Long): Unit = synchronized(lock) {
        conn.prepareStatement(
            """
            INSERT INTO stream_marks (video_id, wall_until) VALUES (?, ?)
            ON CONFLICT(video_id) DO UPDATE SET wall_until = excluded.wall_until
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, videoId)
            ps.setLong(2, untilMillis)
            ps.executeUpdate()
        }
    }

    /**
     * Bounded on every write: expired targets and expired marks are dropped so
     * weeks of playback cannot grow these tables without limit. The caps are
     * generous (a personal library is a few thousand songs at most).
     */
    override fun pruneStreamState(nowMillis: Long): Unit = synchronized(lock) {
        conn.prepareStatement("DELETE FROM stream_targets WHERE expires_at <= ?").use { ps ->
            ps.setLong(1, nowMillis)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM stream_marks WHERE wall_until <= ?").use { ps ->
            ps.setLong(1, nowMillis)
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "DELETE FROM stream_targets WHERE video_id NOT IN " +
                "(SELECT video_id FROM stream_targets ORDER BY expires_at DESC LIMIT $MAX_STREAM_TARGETS)"
        ).use { it.executeUpdate() }
    }

    private fun java.sql.ResultSet.toTrackDto() = TrackDto(
        videoId = getString("video_id"),
        title = getString("title"),
        uploader = getString("uploader"),
        durationMs = getLong("duration_ms"),
        thumbnailUrl = getString("thumbnail_url"),
    )
}
