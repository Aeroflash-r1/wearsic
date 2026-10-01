package com.example.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.example.model.Playlist
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.components.WearsicEmptyState
import com.example.ui.components.WearsicLoadingState
import com.example.ui.components.WearsicSecondaryPillButton
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.components.WearsicSongRow
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicError
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.viewmodel.LibraryViewModel.PlaylistsUiState

import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun PlaylistsScreen(
    playlistsState: PlaylistsUiState,
    onRefresh: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit = {},
    onRemovePlaylist: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    var hideCandidate by remember { mutableStateOf<Playlist?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .wearsicRotaryScroll(listState),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Playlists",
                    subtitle = "Your Library",
                )
            }

            // Favorites entry — the heart of the library
            item {
                WearsicSecondaryPillButton(
                    label = "Favorites",
                    icon = Icons.Rounded.Favorite,
                    onClick = onNavigateToFavorites,
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "playlists_favorites_button"
                )
            }

            // Create Playlist action
            item {
                WearsicSecondaryPillButton(
                    label = "Create Playlist",
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "playlists_create_button"
                )
            }

            // Loading state
            if (playlistsState.isLoading && playlistsState.playlists.isEmpty()) {
                item {
                    WearsicLoadingState(label = "Loading playlists...")
                }
            }

            // Empty state
            if (!playlistsState.isLoading && playlistsState.playlists.isEmpty()) {
                item {
                    WearsicEmptyState(
                        title = "No playlists yet",
                        message = "Long-press a playlist to remove it from your server.",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic
                    )
                }
            }

            // Playlist rows
            playlistsState.playlists.forEach { playlist ->
                item(key = playlist.id) {
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist) },
                        onLongPress = { hideCandidate = playlist }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Create Playlist dialog
        if (showCreateDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { showCreateDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(CircleShape)
                        .background(WearsicSurface)
                        .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "New Playlist",
                        color = WearsicTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.4f), CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = newPlaylistName,
                            onValueChange = { newPlaylistName = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = WearsicTextPrimary,
                                fontSize = 12.sp
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(WearsicVibrantLavender),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                if (newPlaylistName.isEmpty()) {
                                    Text("Playlist name...", color = WearsicTextMuted, fontSize = 12.sp)
                                }
                                innerTextField()
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(WearsicVibrantLavender)
                                .wearsicClickable {
                                    val name = newPlaylistName.trim()
                                    if (name.isNotBlank()) {
                                        onCreatePlaylist(name)
                                        newPlaylistName = ""
                                        showCreateDialog = false
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Create", color = WearsicTextPrimaryDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(WearsicGlassFill)
                                .border(1.dp, WearsicGlassBorder, CircleShape)
                                .wearsicClickable { showCreateDialog = false; newPlaylistName = "" }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Cancel", color = WearsicTextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Hide confirmation overlay
        hideCandidate?.let { pl ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { hideCandidate = null },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(CircleShape)
                        .background(WearsicSurface)
                        .border(1.dp, WearsicVibrantLavender.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "Remove \"${pl.name}\" from server?",
                        color = WearsicTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(WearsicError.copy(alpha = 0.25f))
                                .border(1.dp, WearsicError, CircleShape)
                                .wearsicClickable {
                                    onRemovePlaylist(pl.id)
                                    hideCandidate = null
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Remove", color = WearsicError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(WearsicGlassFill)
                                .border(1.dp, WearsicGlassBorder, CircleShape)
                                .wearsicClickable { hideCandidate = null }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Cancel", color = WearsicTextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {}
) {
    WearsicSongRow(
        title = playlist.name,
        artist = "${playlist.trackCount} tracks",
        artworkUrl = playlist.thumbnailUrl,
        onClick = onClick,
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
        testTag = "playlist_${playlist.id}",
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = WearsicTextMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun PlaylistsScreenPreview() {
    WearsicTheme {
        PlaylistsScreen(
            playlistsState = PlaylistsUiState(
                favorites = listOf(
                    com.example.model.Track(id = "1", title = "Weather with You", artist = "Crowded House")
                ),
                playlists = listOf(
                    Playlist(id = "p1", name = "My Mix", trackCount = 3)
                )
            ),
            onRefresh = {},
            onNavigateToFavorites = {},
            onOpenPlaylist = {}
        )
    }
}