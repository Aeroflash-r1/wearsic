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

    suspend fun recordPlayed(track: Track) {
        if (track.id.isBlank()) return
        // The stable track id IS the identity (a real YouTube videoId since
        // the 1.5 YTM migration): upserting on the PK keeps one row per
        // recording and bumps it to the top on replay. Two DIFFERENT
        // recordings that happen to share a title/artist keep separate rows —
        // deduping by title+artist here played the wrong recording (a
        // same-named track silently replaced the one the user actually tapped
        // in Recents).
        recentTrackDao.upsert(
            WearsicRecentTrackEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                durationMs = track.durationMs,
                mediaUri = track.mediaUri,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearRecent() {
        recentTrackDao.deleteAll()
    }
}