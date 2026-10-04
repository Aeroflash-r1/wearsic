package com.wearsic.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.wearsic.app.model.Track

@Entity(tableName = "recent_tracks")
data class WearsicRecentTrackEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long,
    val mediaUri: String,
    val playedAt: Long = System.currentTimeMillis()
) {
    fun toDomainTrack(): Track {
        return Track(
            id = trackId,
            title = title,
            artist = artist,
            album = album ?: "Unknown Album",
            durationMs = durationMs,
            mediaUri = mediaUri,
            artworkUrl = artworkUrl
        )
    }
}