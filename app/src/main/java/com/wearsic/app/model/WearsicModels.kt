package com.wearsic.app.model

data class Track(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val mediaUri: String = "",
    val isFavorite: Boolean = false
)

data class Album(
    val id: String = "",
    val name: String = "",
    val uploader: String = "",
    val thumbnailUrl: String? = null
)

data class Playlist(
    val id: String = "",
    val name: String = "",
    val trackCount: Int = 0,
    val thumbnailUrl: String? = null
)

data class PlaybackUiState(
    val currentTrack: Track = Track(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackError: String? = null,
    val outputDeviceName: String = "Watch Speaker",
    val isBluetoothConnected: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val playlist: List<Track> = emptyList(),
    val currentTrackIndex: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = 0
)
