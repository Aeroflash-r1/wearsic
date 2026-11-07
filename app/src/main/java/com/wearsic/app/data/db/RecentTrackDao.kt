package com.wearsic.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentTrackDao {

    // Display-level grouping: one row per SONG NAME (title+artist), keeping
    // the most recently played recording of that song. Replays BUMP the
    // existing row (same rowid, newer playedAt), so the group winner must be
    // the LATEST playedAt per name — MAX(rowid) alone would keep an older
    // replay pinned when a newer replay of another recording arrives (a
    // regression the 1.9.49 playCount bump introduced and the identity tests
    // caught). rowid DESC breaks same-millisecond playedAt ties. Rows stay
    // per-recording-id in the DB, so tapping the visible row plays that
    // exact row's recording id — never a title re-search.
    @Query(
        "SELECT * FROM recent_tracks " +
            "WHERE rowid = (" +
            "  SELECT r2.rowid FROM recent_tracks r2 " +
            "  WHERE UPPER(r2.title) = UPPER(recent_tracks.title) " +
            "    AND UPPER(r2.artist) = UPPER(recent_tracks.artist) " +
            "  ORDER BY r2.playedAt DESC, r2.rowid DESC LIMIT 1" +
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
        "SELECT * FROM recent_tracks " +
            "WHERE rowid = (" +
            "  SELECT r2.rowid FROM recent_tracks r2 " +
            "  WHERE UPPER(r2.title) = UPPER(recent_tracks.title) " +
            "    AND UPPER(r2.artist) = UPPER(recent_tracks.artist) " +
            "  ORDER BY r2.playedAt DESC, r2.rowid DESC LIMIT 1" +
            ") ORDER BY playCount DESC, playedAt DESC LIMIT 10"
    )
    fun getMostPlayedFlow(): Flow<List<WearsicRecentTrackEntity>>

    @Query("DELETE FROM recent_tracks WHERE trackId = :trackId")
    suspend fun deleteByTrackId(trackId: String)

    /** Total stored rows (per-recording). Used by tests to verify identity is
     *  preserved under the display-level grouping. */
    @Query("SELECT COUNT(*) FROM recent_tracks")
    suspend fun countRows(): Int

    /**
     * Highest playedAt currently stored. The repository uses this to make the
     * next timestamp strictly increasing even when the wall clock did not
     * tick (same-millisecond replays on coarse clocks — Robolectric). Without
     * it, replayed rows tie on playedAt and the rowid tie-break can surface a
     * stale recording.
     */
    @Query("SELECT COALESCE(MAX(playedAt), 0) FROM recent_tracks")
    suspend fun maxPlayedAt(): Long

    @Query("DELETE FROM recent_tracks")
    suspend fun deleteAll()
}