package com.wearsic.app.ui.screens
import com.wearsic.app.ui.theme.LocalFlagshipColors
import com.wearsic.app.ui.theme.FlagshipColors
import com.wearsic.app.ui.theme.FlagshipDisplayFont
import com.wearsic.app.ui.theme.FlagshipBodyFont

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.wearsic.app.model.Playlist
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.components.WearsicDialogButton
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicLoadingState
import com.wearsic.app.ui.components.WearsicSecondaryPillButton
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.viewmodel.LibraryViewModel.PlaylistsUiState

import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll

@Composable
fun PlaylistsScreen(
    playlistsState: PlaylistsUiState,
    onRefresh: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit = {},
    onRemovePlaylist: (String) -> Unit = {},
    aiCreateState: com.wearsic.app.ui.viewmodel.LibraryViewModel.AiCreateUiState =
        com.wearsic.app.ui.viewmodel.LibraryViewModel.AiCreateUiState(),
    onCreateAiPlaylist: (String, String?) -> Unit = { _, _ -> },
    onClearAiCreate: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    var hideCandidate by remember { mutableStateOf<Playlist?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showAiDialog by remember { mutableStateOf(false) }
    var aiName by remember { mutableStateOf("") }
    var aiGenre by remember { mutableStateOf<String?>(null) }

    // Close the AI dialog when creation completes.
    LaunchedEffect(aiCreateState.createdName) {
        if (aiCreateState.createdName != null) {
            showAiDialog = false
            aiName = ""
            aiGenre = null
            onClearAiCreate()
        }
    }

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
            // No auto-centering: the framework otherwise anchors item 1 to the
            // viewport centre, pushing the header (and the clock) off the top
            // of the round face. We pin the list with an explicit content
            // padding instead.
            autoCentering = null,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .wearsicRotaryScroll(listState),
            contentPadding = rememberWearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Playlists",
                    subtitle = "Your Library"
                )
            }

            // AI Playlist entry — taste-built by the server (15 songs)
            item {
                WearsicSecondaryPillButton(
                    label = "AI Playlist",
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = { showAiDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "ai_playlist_button",
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
            items(playlistsState.playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onClick = { onOpenPlaylist(playlist) },
                    onLongPress = { hideCandidate = playlist }
                )
            }

            item {
                Spacer(modifier = Modifier.height(56.dp))
            }
        }

        // Create Playlist dialog
        if (showCreateDialog) {
            com.wearsic.app.ui.components.WearsicDialogPanel(
                onDismiss = { showCreateDialog = false; newPlaylistName = "" },
                title = "New Playlist",
                footer = {
                    WearsicDialogButton(
                        label = "Create",
                        filled = true,
                        enabled = newPlaylistName.isNotBlank(),
                        onClick = {
                            val name = newPlaylistName.trim()
                            if (name.isNotBlank()) {
                                onCreatePlaylist(name)
                                newPlaylistName = ""
                                showCreateDialog = false
                            }
                        },
                    )
                },
            ) {
                Text(
                    text = "Give it a name",
                    color = wearsicSecondaryText(),
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, LocalWearsicAccent.current.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = LocalFlagshipColors.current.textPrimary,
                            fontFamily = FlagshipBodyFont,
                            fontSize = 12.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalWearsicAccent.current),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (newPlaylistName.isEmpty()) {
                                Text(
                                    "Playlist name...",
                                    color = LocalFlagshipColors.current.textMuted,
                                    fontFamily = FlagshipBodyFont,
                                    fontSize = 12.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
        }

        // AI Playlist creation dialog (full-screen page).
        if (showAiDialog) {
            com.wearsic.app.ui.components.WearsicDialogPanel(
                onDismiss = { showAiDialog = false; onClearAiCreate() },
                title = "AI Playlist",
                footer = {
                    WearsicDialogButton(
                        label = if (aiCreateState.isLoading) "Creating…" else "Create AI Playlist",
                        filled = true,
                        enabled = !aiCreateState.isLoading && aiName.isNotBlank(),
                        onClick = {
                            val n = aiName.trim()
                            if (n.isNotEmpty()) onCreateAiPlaylist(n, aiGenre)
                        },
                    )
                },
            ) {
                Text(
                    text = "15 songs from your taste",
                    color = LocalFlagshipColors.current.textPrimary,
                    fontFamily = FlagshipDisplayFont,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (aiCreateState.aiUsed) {
                        "Personalized with your AI provider."
                    } else {
                        "AI optional — taste-based without a key."
                    },
                    color = wearsicSecondaryText(),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, LocalWearsicAccent.current.copy(alpha = 0.4f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = aiName,
                        onValueChange = { aiName = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = LocalFlagshipColors.current.textPrimary,
                            fontFamily = FlagshipBodyFont,
                            fontSize = 12.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalWearsicAccent.current),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (aiName.isEmpty()) {
                                Text(
                                    "Playlist name...",
                                    color = LocalFlagshipColors.current.textMuted,
                                    fontFamily = FlagshipBodyFont,
                                    fontSize = 12.sp,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "VIBE",
                    color = LocalFlagshipColors.current.textMuted,
                    fontFamily = FlagshipDisplayFont,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                // Short labels keep three chips per row legible inside the
                // round-safe column; the full vibe is still sent to the server.
                val vibes = listOf(
                    "Chill" to "chill",
                    "Focus" to "focus",
                    "Gym" to "workout",
                    "Love" to "love",
                    "Party" to "party",
                    "Retro" to "throwback",
                    "Sad" to "sad",
                    "Happy" to "happy",
                    "Jazz" to "jazz",
                )
                vibes.chunked(3).forEach { rowVibes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowVibes.forEach { (label, vibe) ->
                            val selected = aiGenre == vibe
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 30.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) LocalWearsicAccent.current
                                        else LocalFlagshipColors.current.surface
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) Color.Transparent
                                        else LocalFlagshipColors.current.hairline,
                                        CircleShape,
                                    )
                                    .wearsicClickable(pressedScale = 0.94f) {
                                        aiGenre = if (selected) null else vibe
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) {
                                        LocalFlagshipColors.current.textOnAccent
                                    } else {
                                        LocalFlagshipColors.current.textPrimary
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
                if (aiCreateState.errorMessage != null) {
                    Text(
                        text = aiCreateState.errorMessage!!,
                        color = WearsicError,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Hide confirmation overlay
        hideCandidate?.let { pl ->
            com.wearsic.app.ui.components.WearsicDialogPanel(
                onDismiss = { hideCandidate = null },
                title = "Remove playlist",
                footer = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WearsicDialogButton(
                            label = "Cancel",
                            onClick = { hideCandidate = null },
                            modifier = Modifier.weight(1f),
                        )
                        WearsicDialogButton(
                            label = "Remove",
                            destructive = true,
                            onClick = {
                                onRemovePlaylist(pl.id)
                                hideCandidate = null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                },
            ) {
                Text(
                    text = "Remove \"${pl.name}\" from server?",
                    color = LocalFlagshipColors.current.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
        // The row already provides press physics + haptics via its own
        // clickable; do NOT wrap it in a second clickable (the previous
        // combinedClickable competed with the row's internal one).
        modifier = modifier,
        onLongClick = onLongPress,
        testTag = "playlist_${playlist.id}",
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = LocalFlagshipColors.current.textMuted,
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
                    com.wearsic.app.model.Track(id = "1", title = "Weather with You", artist = "Crowded House")
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