package com.example.ui.screens

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
import com.example.model.Track
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.components.WearsicSongRow
import com.example.ui.components.WearsicSongRowActionButton
import com.example.ui.components.WearsicSongRowPlayButton
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.theme.WearsicLavenderContainer
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceActive
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.viewmodel.SearchUiState

import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun SearchScreen(
    searchState: SearchUiState,
    onQuerySelected: (String) -> Unit,
    onSearchTextChanged: (String) -> Unit = {},
    onTrackSelected: (Track) -> Unit,
    onDownloadTrack: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    playlists: List<com.example.model.Playlist> = emptyList(),
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
                .wearsicRotaryScroll(listState),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            item {
                WearsicScreenHeader(
                    title = "Search",
                    subtitle = "Stream Catalog",
                )
            }

            // Interactive Search Bar
            item {
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
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
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear Search",
                                tint = WearsicTextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)                                    .wearsicClickable {
                                onSearchTextChanged("")
                            }
                                    .testTag("search_clear_button")
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Searching server...",
                            color = WearsicTextSecondary,
                            fontSize = 11.sp
                        )
                    }
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
                        color = if (searchState.errorMessage != null) com.example.ui.theme.WearsicError else WearsicTextMuted,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            // Result Items
            items(searchState.results, key = { it.id }) { track ->
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
            com.example.ui.components.WearsicTrackActionSheet(
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
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
                Spacer(modifier = Modifier.width(6.dp))
                WearsicSongRowPlayButton(onClick = onClick)
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
