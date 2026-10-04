package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicLoadingState
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicSectionTitle
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.components.WearsicSongRowActionButton
import com.wearsic.app.ui.components.WearsicSongRowPlayButton
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicBlack
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicLavenderContainer
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceActive
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.WearsicTextSecondary
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.WearsicVibrantLavender
import com.wearsic.app.ui.viewmodel.LibraryViewModel.SearchUiState

import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll

@Composable
fun SearchScreen(
    searchState: SearchUiState,
    onQuerySelected: (String) -> Unit,
    onSearchTextChanged: (String) -> Unit = {},
    onTrackSelected: (Track) -> Unit,
    onDownloadTrack: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    playlists: List<com.wearsic.app.model.Playlist> = emptyList(),
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    // Single source of truth: the ViewModel state. The field writes straight
    // back through onSearchTextChanged, so no local remember-mirror is needed.
    val typedQuery = searchState.query
    var actionTrack by remember { mutableStateOf<Track?>(null) }

    // Wear keyboards are inconsistent: the enter key may fire onSearch, onDone
    // or onGo depending on the active IME. Handle all of them and dismiss the
    // keyboard so the results become visible immediately.
    fun submitSearch() {
        val query = typedQuery.trim()
        if (query.isNotBlank()) {
            keyboardController?.hide()
            // search(query) overwrites state.query, so no separate clear needed.
            onQuerySelected(query)
        }
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
                .testTag("search_lazy_column")
                .wearsicRotaryScroll(listState),
            contentPadding = wearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            item {
                WearsicScreenHeader(
                    title = "Search",
                    subtitle = "Find your next favorite",
                )
            }

            // Interactive Search Bar
            item {
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = WearsicDimens.TouchTarget)
                        .clip(CircleShape)
                        .background(if (isFocused) WearsicSurfaceActive else WearsicGlassFill)
                        .border(
                            1.dp,
                            if (isFocused) WearsicVibrantLavender else WearsicSurfaceBorderSubtle,
                            CircleShape
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search Icon",
                            tint = if (isFocused) WearsicVibrantLavender else WearsicTextMuted,
                            modifier = Modifier.size(16.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (typedQuery.isEmpty()) {
                                Text(
                                    text = "Type artist/song...",
                                    color = WearsicTextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }

                            BasicTextField(
                                value = typedQuery,
                                onValueChange = onSearchTextChanged,
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = WearsicTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(WearsicVibrantLavender),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Search
                                ),
                                keyboardActions = KeyboardActions(
                                    onSearch = { submitSearch() },
                                    onDone = { submitSearch() },
                                    onGo = { submitSearch() }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        isFocused = focusState.isFocused
                                    }
                                    .testTag("search_text_input")
                            )
                        }

                        if (typedQuery.isNotEmpty()) {
                            WearsicSongRowActionButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = "Clear Search",
                                onClick = { onSearchTextChanged("") },
                                testTag = "search_clear_button",
                                tint = WearsicTextSecondary
                            )
                        }
                    }
                }
            }

            // Live Suggestions (while typing)
            if (searchState.suggestions.isNotEmpty() && typedQuery.isNotBlank()) {
                items(searchState.suggestions, key = { it }) { suggestion ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = WearsicDimens.TouchTarget)
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .wearsicClickable {
                                keyboardController?.hide()
                                onQuerySelected(suggestion)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("search_suggestion_${suggestion.take(24)}"),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = WearsicVibrantLavender,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = suggestion,
                                color = WearsicTextPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Loading State
            if (searchState.isSearching) {
                item {
                    WearsicLoadingState(label = "Finding your music…")
                }
            }

            // Empty State — surface the real error when the request failed
            // instead of hiding it behind a misleading "No results found".
            if (searchState.hasSearched && searchState.results.isEmpty() && !searchState.isSearching) {
                item {
                    Text(
                        text = if (searchState.errorMessage != null) {
                            "Search failed: ${searchState.errorMessage}"
                        } else {
                            "No tracks found for \"${searchState.query}\""
                        },
                        color = if (searchState.errorMessage != null) com.wearsic.app.ui.theme.WearsicError else WearsicTextMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            if (!searchState.hasSearched && !searchState.isSearching && searchState.suggestions.isEmpty()) {
                item {
                    WearsicEmptyState(
                        title = "A world of music",
                        message = "Search a song, artist, or album to start listening.",
                        icon = Icons.Rounded.Search
                    )
                }
            }

            if (searchState.results.isNotEmpty()) {
                item { WearsicSectionTitle(label = "Songs", trailing = {
                    Text("${searchState.results.size}", color = WearsicTextMuted, fontSize = 10.sp)
                }) }
            }
            // Defend against duplicate IDs returned by older servers.
            items(searchState.results.distinctBy { it.id }, key = { it.id }) { track ->
                SearchTrackItem(
                    track = track,
                    onClick = { onTrackSelected(track) },
                    onLongPress = { actionTrack = track },
                    onMore = { actionTrack = track },
                    onDownload = { onDownloadTrack(track) },
                    onAddToQueue = { onAddToQueue(track) }
                )
            }

            // Bottom Spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        actionTrack?.let { t ->
            com.wearsic.app.ui.components.WearsicTrackActionSheet(
                track = t,
                playlists = playlists,
                onDismiss = { actionTrack = null },
                onPlay = { onTrackSelected(t) },
                onQueue = { onAddToQueue(t) },
                onDownload = { onDownloadTrack(t) },
                onAddToPlaylist = { pid -> onAddToPlaylist(pid, t) },
                onCreatePlaylistAndAdd = { name -> onCreatePlaylistAndAdd(name, t) }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SearchTrackItem(
    track: Track,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onMore: () -> Unit = {},
    onDownload: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Rows keep just More + Play inline; queue/download/playlist actions are
    // in the long-press action sheet, which frees real width for the title.
    WearsicSongRow(
        title = track.title,
        artist = track.artist,
        artworkUrl = track.artworkUrl,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongPress,
        testTag = "search_track_${track.id}",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WearsicSongRowActionButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = "More actions",
                    onClick = onMore,
                    testTag = "search_more_${track.id}",
                    tint = WearsicTextMuted
                )

            }
        }
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun SearchScreenPreview() {
    WearsicTheme {
        SearchScreen(
            searchState = SearchUiState(
                query = "Crowded House",
                results = listOf(
                    Track(id = "1", title = "Weather with You", artist = "Crowded House"),
                    Track(id = "2", title = "Don't Dream It's Over", artist = "Crowded House")
                )
            ),
            onQuerySelected = {},
            onTrackSelected = {}
        )
    }
}
