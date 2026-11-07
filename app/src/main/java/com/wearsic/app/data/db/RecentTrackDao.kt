package com.wearsic.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentTrackDao {

    // Display-level grouping: one row per SONG NAME (title+artist), keeping
    // the most recently played recording of that song. A REPLACE on replay
    // re-inserts the row with a fresh rowid, so MAX(rowid) per group is the
    // latest play. Rows themselves stay per-recording-id in the DB (two
    // different recordings that share a name keep separate rows, and tapping
    // the visible row plays that exact row's recording id — never a title
    // re-search). rowid DESC also breaks same-millisecond playedAt ties.
    @Query(
        "SELECT * FROM recent_tracks WHERE rowid IN (" +
            "SELECT MAX(rowid) FROM recent_tracks " +
            "GROUP BY UPPER(title), UPPER(artist)" +
            ") ORDER BY playedAt DESC, rowid DESC LIMIT 10"
    )
    fun getRecentTracksFlow(): Flow<List<WearsicRecentTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WearsicRecentTrackEntity)

    /**
     * Bumps an EXISTING row: play count +1, recency + refreshed metadata.
     * Returns the affected row count (0 = brand-new track; the repository
     * then falls back to [upsert] so a brand-new row starts at count 1).
     * An unconditional UPDATE keeps the count — a REPLACE insert would wipe
     * it back to the default on every replay.
     */
    @Query(
        "UPDATE recent_tracks SET playCount = playCount + 1, " +
            "playedAt = :playedAt, title = :title, artist = :artist, album = :album, " +
            "artworkUrl = :artworkUrl, durationMs = :durationMs, mediaUri = :mediaUri " +
            "WHERE trackId = :trackId"
    )
    suspend fun bumpPlayCount(
        trackId: String,
        title: String,
        artist: String,
        album: String?,
        artworkUrl: String?,
        durationMs: Long,
        mediaUri: String,
        playedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * "Most played": one row per SONG (same display-level grouping as
     * recents), ranked by cumulative play count; latest play breaks ties.
     */
    @Query(
        "SELECT * FROM recent_tracks WHERE rowid IN (" +
            "SELECT MAX(rowid) FROM recent_tracks " +
            "GROUP BY UPPER(title), UPPER(artist)" +
            ") ORDER BY playCount DESC, playedAt DESC LIMIT 10"
    )
    fun getMostPlayedFlow(): Flow<List<WearsicRecentTrackEntity>>

    @Query("DELETE FROM recent_tracks WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    /** Total stored rows (per-recording). Used by tests to verify identity is
     *  preserved under the display-level grouping. */
    @Query("SELECT COUNT(*) FROM recent_tracks")
    suspend fun countRows(): Int

    @Query("DELETE FROM recent_tracks")
    suspend fun deleteAll()
}