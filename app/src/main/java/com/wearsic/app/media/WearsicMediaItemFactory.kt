package com.wearsic.app.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.wearsic.app.R
import com.wearsic.app.model.Track

object WearsicMediaItemFactory {

    fun getTestTracks(context: Context): List<Track> {
        val packageName = context.packageName
        val track1Uri = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/${R.raw.test_track_1}"
        val track2Uri = "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/${R.raw.test_track_2}"

        return listOf(
            Track(
                id = "track_1",
                title = "Weather with You",
                artist = "Crowded House",
                album = "Woodface",
                durationMs = 6000L,
                mediaUri = track1Uri,
                isFavorite = true
            ),
            Track(
                id = "track_2",
                title = "Don't Dream It's Over",
                artist = "Crowded House",
                album = "Crowded House",
                durationMs = 6000L,
                mediaUri = track2Uri,
                isFavorite = false
            )
        )
    }

    fun buildMediaItem(track: Track): MediaItem {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)

        track.artworkUrl?.let {
            if (it.isNotBlank()) {
                metadataBuilder.setArtworkUri(Uri.parse(it))
            }
        }

        val mediaUri = if (track.mediaUri.startsWith("/")) {
            Uri.fromFile(java.io.File(track.mediaUri))
        } else {
            Uri.parse(track.mediaUri)
        }

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(mediaUri)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    fun buildMediaItems(tracks: List<Track>): List<MediaItem> {
        return tracks.map { buildMediaItem(it) }
    }
}
