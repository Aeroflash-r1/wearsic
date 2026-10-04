package com.wearsic.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.tooling.preview.devices.WearDevices
import com.android.tools.screenshot.PreviewTest
import com.wearsic.app.data.db.DownloadState
import com.wearsic.app.data.db.WearsicDownloadEntity
import com.wearsic.app.model.Album
import com.wearsic.app.model.ArtistGroup
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Playlist
import com.wearsic.app.model.Track
import com.wearsic.app.network.model.ConnectionTestState
import com.wearsic.app.ui.components.WearsicTrackActionSheet
import com.wearsic.app.ui.screens.AlbumsScreen
import com.wearsic.app.ui.screens.ArtistsScreen
import com.wearsic.app.ui.screens.DownloadsScreen
import com.wearsic.app.ui.screens.FavoritesScreen
import com.wearsic.app.ui.screens.LibraryScreen
import com.wearsic.app.ui.screens.PlayerScreen
import com.wearsic.app.ui.screens.PlaylistDetailScreen
import com.wearsic.app.ui.screens.PlaylistsScreen
import com.wearsic.app.ui.screens.QueueScreen
import com.wearsic.app.ui.screens.SearchScreen
import com.wearsic.app.ui.screens.SettingsScreen
import com.wearsic.app.ui.screens.StorageStatsContent
import com.wearsic.app.ui.screens.VolumeScreen
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.viewmodel.LibraryViewModel.AlbumsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.ArtistsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.FavoritesUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistDetailUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.SearchUiState

/**
 * High-fidelity screen gallery rendered by layoutlib (the same Skia + font
 * pipeline Android Studio uses for previews), at the true Galaxy Watch7 44mm
 * round device.
 *
 * This is deliberately higher fidelity than the Robolectric/Roborazzi gallery:
 * real text shaping, real draw order, real Wear Compose rendering.
 *
 * Regenerate reference images with:
 *   ./gradlew updateDebugScreenshotTest
 * and validate future changes with:
 *   ./gradlew validateDebugScreenshotTest
 */
private val previewTracks = listOf(
    Track(id = "t1", title = "Kesariya", artist = "Arijit Singh", durationMs = 250_000),
    Track(id = "t2", title = "Tum Hi Ho", artist = "Arijit Singh", durationMs = 261_000),
    Track(id = "t3", title = "A Sky Full of Stars", artist = "Coldplay", durationMs = 267_000),
    Track(id = "t4", title = "Photograph", artist = "Ed Sheeran", durationMs = 258_000),
    Track(id = "t5", title = "Perfect", artist = "Ed Sheeran", durationMs = 263_000),
    Track(id = "t6", title = "Calm Down", artist = "Rema", durationMs = 239_000)
)

private val previewPlayback = PlaybackUiState(
    currentTrack = previewTracks[2],
    isPlaying = true,
    currentPositionMs = 84_000L,
    durationMs = 267_000L,
    hasNext = true,
    hasPrevious = true,
    playlist = previewTracks,
    currentTrackIndex = 2
)

private val previewPlaylists = listOf(
    Playlist(id = "p1", name = "Road Trip", trackCount = 24),
    Playlist(id = "p2", name = "Focus", trackCount = 12),
    Playlist(id = "p3", name = "Late Night", trackCount = 31)
)

/** Every preview is drawn on the app's true OLED-black backdrop. */
@Composable
private fun GallerySurface(content: @Composable () -> Unit) {
    WearsicTheme {
        Box(Modifier.fillMaxSize().background(WearsicAppBackground)) { content() }
    }
}

// ── Home ─────────────────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryLibraryPlaying() {
    GallerySurface {
        LibraryScreen(
            onNavigateToSearch = {}, onNavigateToDownloads = {}, onNavigateToPlaylists = {},
            onNavigateToAlbums = {}, onNavigateToArtists = {}, onNavigateToSettings = {},
            onNavigateToPlayer = {}, onNavigateToFavorites = {},
            playbackState = previewPlayback,
            recentTracks = previewTracks.take(3),
            onPlayRecentTrack = { _, _ -> }
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryLibraryIdle() {
    GallerySurface {
        LibraryScreen(
            onNavigateToSearch = {}, onNavigateToDownloads = {}, onNavigateToPlaylists = {},
            onNavigateToAlbums = {}, onNavigateToArtists = {}, onNavigateToSettings = {},
            onNavigateToPlayer = {}, onNavigateToFavorites = {},
            playbackState = PlaybackUiState(), recentTracks = emptyList(), onPlayRecentTrack = { _, _ -> }
        )
    }
}

// ── Player ───────────────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryPlayerNowPlaying() {
    GallerySurface {
        PlayerScreen(
            playbackState = previewPlayback,
            onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
            onToggleFavorite = {}, onNavigateToVolume = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryPlayerLongTitle() {
    GallerySurface {
        PlayerScreen(
            playbackState = previewPlayback.copy(
                currentTrack = previewPlayback.currentTrack!!.copy(
                    title = "A Really Quite Long Song Title That Should Slide Instead Of Wrapping"
                )
            ),
            onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
            onToggleFavorite = {}, onNavigateToVolume = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryPlayerEmpty() {
    GallerySurface {
        PlayerScreen(
            playbackState = PlaybackUiState(),
            onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
            onToggleFavorite = {}, onNavigateToVolume = {}
        )
    }
}

// ── Discovery ────────────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GallerySearchResults() {
    GallerySurface {
        SearchScreen(
            searchState = SearchUiState(
                query = "arjit singh", results = previewTracks, hasSearched = true
            ),
            onQuerySelected = {}, onTrackSelected = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryAlbums() {
    GallerySurface {
        AlbumsScreen(
            albumsState = AlbumsUiState(
                query = "coldplay",
                albums = listOf(
                    Album("a1", "Parachutes", "Coldplay", 10),
                    Album("a2", "A Rush of Blood to the Head", "Coldplay", 11),
                    Album("a3", "X&Y", "Coldplay", 13)
                )
            ),
            onQueryChanged = {}, onOpenAlbum = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryArtists() {
    GallerySurface {
        ArtistsScreen(
            artistsState = ArtistsUiState(
                artists = listOf(
                    ArtistGroup("Coldplay", previewTracks.take(3)),
                    ArtistGroup("Ed Sheeran", previewTracks.drop(3)),
                    ArtistGroup("Arijit Singh", previewTracks.take(2))
                )
            ),
            onRefresh = {}, onPlayArtistSongs = { _, _ -> }
        )
    }
}

// ── Collections ──────────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryFavorites() {
    GallerySurface {
        FavoritesScreen(
            favoritesState = FavoritesUiState(tracks = previewTracks.take(4)),
            onRefresh = {}, onPlayTrack = { _, _ -> },
            onDownloadTrack = {}, onRemoveFavorite = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryPlaylists() {
    GallerySurface {
        PlaylistsScreen(
            playlistsState = PlaylistsUiState(favorites = previewTracks.take(3), playlists = previewPlaylists),
            onRefresh = {}, onNavigateToFavorites = {}, onOpenPlaylist = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryPlaylistDetail() {
    GallerySurface {
        PlaylistDetailScreen(
            playlistId = "p1", playlistName = "Road Trip",
            detailState = PlaylistDetailUiState(playlistId = "p1", tracks = previewTracks),
            onLoadTracks = {}, onPlayTrack = { _, _ -> },
            onDownloadTrack = {}, onRemoveTrack = { _, _ -> }
        )
    }
}

// ── Queue & offline ──────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryQueue() {
    GallerySurface {
        QueueScreen(
            playbackState = previewPlayback,
            onPlayItem = {}, onRemoveItem = {}, onClearQueue = {},
            shuffleEnabled = true, repeatMode = 2,
            onToggleShuffle = {}, onCycleRepeat = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryDownloads() {
    GallerySurface {
        DownloadsScreen(
            downloads = listOf(
                WearsicDownloadEntity(
                    trackId = "d1", title = "Kesariya", artist = "Arijit Singh", album = null,
                    artworkUrl = null, durationMs = 250_000, localFilePath = "/data/d1.m4a",
                    originalStreamUrl = "http://x/d1", downloadState = DownloadState.COMPLETED.name,
                    progress = 100, fileSizeBytes = 4_200_000
                ),
                WearsicDownloadEntity(
                    trackId = "d2", title = "Tum Hi Ho", artist = "Arijit Singh", album = null,
                    artworkUrl = null, durationMs = 261_000, localFilePath = "/data/d2.m4a",
                    originalStreamUrl = "http://x/d2", downloadState = DownloadState.DOWNLOADING.name,
                    progress = 64
                ),
                WearsicDownloadEntity(
                    trackId = "d3", title = "Calm Down", artist = "Rema", album = null,
                    artworkUrl = null, durationMs = 239_000, localFilePath = "/data/d3.m4a",
                    originalStreamUrl = "http://x/d3", downloadState = DownloadState.COMPLETED.name,
                    progress = 100, fileSizeBytes = 3_900_000, autoCached = true
                )
            ),
            onPlayTrack = {}, onDeleteDownload = {}, onCancelDownload = {},
            onClearAllDownloads = {}
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryStorage() {
    GallerySurface {
        StorageStatsContent(
            autoCount = 34, autoMb = 118.4, manualCount = 6, manualMb = 24.9,
            onClearAutoCached = {}
        )
    }
}

// ── Settings & output ────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GallerySettings() {
    GallerySurface {
        SettingsScreen(
            serverUrl = "https://phone.tailnet.ts.net",
            connectionTestState = ConnectionTestState.Success("1.6.0", "Wearsic Engine"),
            apiKey = "wsk_9f2b7c1a4e8d",
            autoCacheEnabled = true,
            offlineLimitSongs = 15,
            startupHealth = "Ready — engine v1.6.0"
        )
    }
}

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryAudioOutput() {
    GallerySurface {
        VolumeScreen(currentOutputDevice = "Pixel Buds Pro", sleepRemainingMs = 15 * 60_000L)
    }
}

// ── Overlay ──────────────────────────────────────────────────────────────────

@PreviewTest
@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun GalleryTrackActions() {
    GallerySurface {
        WearsicTrackActionSheet(
            track = previewTracks[0], playlists = previewPlaylists,
            onDismiss = {}, onPlay = {}, onQueue = {}, onDownload = {},
            onAddToPlaylist = {}, onCreatePlaylistAndAdd = {}
        )
    }
}