package com.wearsic.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.wearsic.app.model.Track

/**
 * A favorited track stored LOCALLY on the watch. This is the source of truth
 * for the Favorites screen — offline-first, instant, zero network dependency.
 * The server copy (when reachable) is only an optional backup sink / seed.
 */
@Entity(tableName = "favorite_tracks")
data class WearsicFavoriteTrackEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long,
    /** The playable URI as known at favorite time (server stream URL). */
    val mediaUri: String,
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toDomainTrack(): Track = Track(
        id = trackId,
        title = title,
        artist = artist,
        album = album ?: "Unknown Album",
        durationMs = durationMs,
        mediaUri = mediaUri,
        artworkUrl = artworkUrl,
        isFavorite = true,
    )
}
