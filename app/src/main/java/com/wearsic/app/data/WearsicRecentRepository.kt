package com.wearsic.app.data

import android.content.Context
import com.wearsic.app.data.db.RecentTrackDao
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.data.db.WearsicRecentTrackEntity
import com.wearsic.app.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WearsicRecentRepository(
    context: Context,
    // Injectable for tests (in-memory Room); production uses the singleton.
    private val recentTrackDao: RecentTrackDao = WearsicDatabase.getInstance(context).recentTrackDao(),
) {

    val recentTracksFlow: Flow<List<Track>> = recentTrackDao.getRecentTracksFlow()
        .map { entities -> entities.map { it.toDomainTrack() } }

    /** "Most played": same rows ranked by cumulative play count. */
    val mostPlayedFlow: Flow<List<Track>> = recentTrackDao.getMostPlayedFlow()
        .map { entities -> entities.map { it.toDomainTrack() } }

    suspend fun recordPlayed(track: Track) {
        if (track.id.isBlank()) return
        // The stable track id IS the identity (a real YouTube videoId since
        // the 1.5 YTM migration). Replays BUMP the existing row (count +1)
        // and only fall back to insert for brand-new tracks — a REPLACE
        // would reset the cumulative play count on every replay and break
        // the "Most played" shelf.
        val playedAt = nextPlayedAt()
        val bumped = recentTrackDao.bumpPlayCount(
            trackId = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            durationMs = track.durationMs,
            mediaUri = track.mediaUri,
            playedAt = playedAt
        )
        if (bumped == 0) {
            recentTrackDao.upsert(
                WearsicRecentTrackEntity(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = track.artworkUrl,
                    durationMs = track.durationMs,
                    mediaUri = track.mediaUri,
                    playedAt = playedAt
                )
            )
        }
    }

    /**
     * Strictly increasing play timestamp: the wall clock when it has advanced
     * past the newest row, otherwise MAX+1. Keeps replay order unambiguous on
     * coarse clocks (same-millisecond plays) — the display grouping orders
     * by playedAt, so a tie would fall back to rowid and could surface the
     * wrong recording of a song (see RecentPlaybackIdentityTest).
     */
    private suspend fun nextPlayedAt(): Long {
        val now = System.currentTimeMillis()
        val latest = recentTrackDao.maxPlayedAt()
        return if (now > latest) now else latest + 1
    }

    suspend fun clearRecent() {
        recentTrackDao.deleteAll()
    }
}