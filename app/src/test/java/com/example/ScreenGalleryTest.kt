package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.data.db.DownloadState
import com.example.data.db.WearsicDownloadEntity
import com.example.model.Album
import com.example.model.ArtistGroup
import com.example.model.PlaybackUiState
import com.example.model.Playlist
import com.example.model.Track
import com.example.network.model.ConnectionTestState
import com.example.ui.components.WearsicTrackActionSheet
import com.example.ui.screens.AlbumsScreen
import com.example.ui.screens.ArtistsScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.PlaylistDetailScreen
import com.example.ui.screens.PlaylistsScreen
import com.example.ui.screens.QueueScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StorageStatsContent
import com.example.ui.screens.VolumeScreen
import com.example.ui.theme.WearsicTheme
import com.example.ui.viewmodel.LibraryViewModel.AlbumsUiState
import com.example.ui.viewmodel.LibraryViewModel.ArtistsUiState
import com.example.ui.viewmodel.LibraryViewModel.FavoritesUiState
import com.example.ui.viewmodel.LibraryViewModel.PlaylistDetailUiState
import com.example.ui.viewmodel.LibraryViewModel.PlaylistsUiState
import com.example.ui.viewmodel.LibraryViewModel.SearchUiState
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders EVERY reachable watch screen at true Galaxy Watch7 44mm round
 * dimensions and writes a PNG per screen, so the shipped UI can be reviewed
 * as images instead of inferred from code.
 *
 * Artwork URLs are intentionally omitted: Robolectric performs no network I/O,
 * so an image-backed screen would render an empty box. Every screen therefore
 * exercises its real "no artwork" placeholder path, which is what many songs
 * show in production anyway.
 *
 * Output: app/src/test/screenshots/<screen>.png (450x450 @ the round qualifiers)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class ScreenGalleryTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        composeTestRule.setContent { WearsicTheme { Box(Modifier.fillMaxSize()) { content() } } }
        composeTestRule.waitForIdle()
        composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/$name.png")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun track(
        id: String,
        title: String,
        artist: String,
        durationMs: Long = 240_000L,
        favorite: Boolean = false
    ) = Track(
        id = id, title = title, artist = artist,
        durationMs = durationMs, artworkUrl = null, isFavorite = favorite
    )

    private val tracks = listOf(
        track("t1", "Kesariya", "Arijit Singh", 250_000),
        track("t2", "Tum Hi Ho", "Arijit Singh", 261_000),
        track("t3", "A Sky Full of Stars", "Coldplay", 267_000),
        track("t4", "Photograph", "Ed Sheeran", 258_000),
        track("t5", "Perfect", "Ed Sheeran", 263_000),
        track("t6", "Calm Down", "Rema", 239_000)
    )

    private val playback = PlaybackUiState(
        currentTrack = tracks[2],
        isPlaying = true,
        isBuffering = false,
        currentPositionMs = 84_000L,
        durationMs = 267_000L,
        hasNext = true,
        hasPrevious = true,
        playlist = tracks,
        currentTrackIndex = 2
    )

    private val playlists = listOf(
        Playlist(id = "p1", name = "Road Trip", trackCount = 24),
        Playlist(id = "p2", name = "Focus", trackCount = 12),
        Playlist(id = "p3", name = "Late Night", trackCount = 31)
    )

    // ── Home ────────────────────────────────────────────────────────────────

    @Test
    fun library_playing() = shoot("01-library") {
        LibraryScreen(
            onNavigateToSearch = {}, onNavigateToDownloads = {}, onNavigateToPlaylists = {},
            onNavigateToAlbums = {}, onNavigateToArtists = {}, onNavigateToSettings = {},
            onNavigateToPlayer = {}, onNavigateToFavorites = {},
            playbackState = playback,
            recentTracks = tracks.take(3),
            onPlayRecentTrack = { _, _ -> }
        )
    }

    @Test
    fun library_idle() = shoot("02-library-idle") {
        LibraryScreen(
            onNavigateToSearch = {}, onNavigateToDownloads = {}, onNavigateToPlaylists = {},
            onNavigateToAlbums = {}, onNavigateToArtists = {}, onNavigateToSettings = {},
            onNavigateToPlayer = {}, onNavigateToFavorites = {},
            playbackState = PlaybackUiState(), recentTracks = emptyList(), onPlayRecentTrack = { _, _ -> }
        )
    }

    // ── Player ──────────────────────────────────────────────────────────────

    @Test
    fun player_nowPlaying() = shoot("03-player-now-playing") {
        PlayerScreen(
            playbackState = playback,
            onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
            onToggleFavorite = {}, onNavigateToVolume = {}
        )
    }

    @Test
    fun player_empty() = shoot("04-player-empty") {
        PlayerScreen(
            playbackState = PlaybackUiState(),
            onTogglePlayPause = {}, onSkipNext = {}, onSkipPrevious = {},
            onToggleFavorite = {}, onNavigateToVolume = {}
        )
    }

    // ── Discovery ───────────────────────────────────────────────────────────

    @Test
    fun search_results() = shoot("05-search-results") {
        SearchScreen(
            searchState = SearchUiState(query = "arjit singh", results = tracks, hasSearched = true),
            onQuerySelected = {}, onTrackSelected = {}
        )
    }

    @Test
    fun search_empty() = shoot("06-search-idle") {
        SearchScreen(
            searchState = SearchUiState(),
            onQuerySelected = {}, onTrackSelected = {}
        )
    }

    @Test
    fun albums() = shoot("07-albums") {
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

    @Test
    fun artists() = shoot("08-artists") {
        ArtistsScreen(
            artistsState = ArtistsUiState(
                artists = listOf(
                    ArtistGroup("Coldplay", tracks.take(3)),
                    ArtistGroup("Ed Sheeran", tracks.drop(3)),
                    ArtistGroup("Arijit Singh", tracks.take(2))
                )
            ),
            onRefresh = {}, onPlayArtistSongs = { _, _ -> }
        )
    }

    // ── Library collections ─────────────────────────────────────────────────

    @Test
    fun favorites() = shoot("09-favorites") {
        FavoritesScreen(
            favoritesState = FavoritesUiState(tracks = tracks.take(4)),
            onRefresh = {}, onPlayTrack = { _, _ -> },
            onDownloadTrack = {}, onRemoveFavorite = {}
        )
    }

    @Test
    fun playlists() = shoot("10-playlists") {
        PlaylistsScreen(
            playlistsState = PlaylistsUiState(favorites = tracks.take(3), playlists = playlists),
            onRefresh = {}, onNavigateToFavorites = {}, onOpenPlaylist = {}
        )
    }

    @Test
    fun playlistDetail() = shoot("11-playlist-detail") {
        PlaylistDetailScreen(
            playlistId = "p1", playlistName = "Road Trip",
            detailState = PlaylistDetailUiState(playlistId = "p1", tracks = tracks),
            onLoadTracks = {}, onPlayTrack = { _, _ -> },
            onDownloadTrack = {}, onRemoveTrack = { _, _ -> }
        )
    }

    // ── Queue & offline ─────────────────────────────────────────────────────

    @Test
    fun queue() = shoot("12-queue") {
        QueueScreen(
            playbackState = playback,
            onPlayItem = {}, onRemoveItem = {}, onClearQueue = {},
            shuffleEnabled = true, repeatMode = 2,
            onToggleShuffle = {}, onCycleRepeat = {}
        )
    }

    @Test
    fun downloads() = shoot("13-downloads") {
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

    @Test
    fun storage() = shoot("14-storage") {
        StorageStatsContent(
            autoCount = 34, autoMb = 118.4, manualCount = 6, manualMb = 24.9,
            onClearAutoCached = {}
        )
    }

    // ── Settings & output ───────────────────────────────────────────────────

    @Test
    fun settings() = shoot("15-settings") {
        SettingsScreen(
            serverUrl = "https://phone.tailnet.ts.net",
            connectionTestState = ConnectionTestState.Success("1.6.0", "Wearsic Engine"),
            apiKey = "wsk_9f2b7c1a4e8d",
            autoCacheEnabled = true,
            offlineLimitSongs = 15,
            startupHealth = "Ready — engine v1.6.0"
        )
    }

    @Test
    fun settings_error() = shoot("16-settings-error") {
        SettingsScreen(
            serverUrl = "http://192.168.1.44:8080",
            connectionTestState = ConnectionTestState.Error("Host not resolved. Check URL or internet."),
            apiKey = "", autoCacheEnabled = false, offlineLimitSongs = 50
        )
    }

    @Test
    fun audioOutput() = shoot("17-audio-output") {
        VolumeScreen(
            currentOutputDevice = "Pixel Buds Pro",
            sleepRemainingMs = 15 * 60_000L
        )
    }

    // ── Overlay ─────────────────────────────────────────────────────────────

    @Test
    fun trackActionSheet() = shoot("18-track-actions") {
        WearsicTrackActionSheet(
            track = tracks[0], playlists = playlists,
            onDismiss = {}, onPlay = {}, onQueue = {}, onDownload = {},
            onAddToPlaylist = {}, onCreatePlaylistAndAdd = {}
        )
    }
}