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
class Database(dbPath: String) {

    /** Persisted surrogate matches older than this are re-matched (self-healing). */
    private val MATCH_STALE_MS: Long = 30L * 24 * 60 * 60 * 1000 // 30 days

    /** Hard cap on persisted matches; the oldest are evicted beyond this. */
    private val MAX_MATCH_ROWS = 2000

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
            }
        }
    }

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

    fun listFavorites(): List<TrackDto> = synchronized(lock) {
        conn.prepareStatement("SELECT * FROM favorites ORDER BY rowid DESC").use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(rs.toTrackDto())
                }
            }
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
    }

    fun removeFavorite(videoId: String) = synchronized(lock) {
        conn.prepareStatement("DELETE FROM favorites WHERE video_id = ?").use { ps ->
            ps.setString(1, videoId)
            ps.executeUpdate()
        }
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

    private fun java.sql.ResultSet.toTrackDto() = TrackDto(
        videoId = getString("video_id"),
        title = getString("title"),
        uploader = getString("uploader"),
        durationMs = getLong("duration_ms"),
        thumbnailUrl = getString("thumbnail_url"),
    )
}
