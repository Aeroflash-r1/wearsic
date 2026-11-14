package com.wearsic.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicArtistCollageAvatar
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSearchShimmerRow
import com.wearsic.app.ui.components.WearsicCompactShimmer
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicSectionTitle
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.components.WearsicSongRowActionButton
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicDisplayFont
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicSurfaceActive
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicTabularStyle
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.wearsicAccentContainer
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.WearsicTheme
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
    onToggleFavorite: (Track) -> Unit = {},
    favoriteTrackIds: Set<String> = emptySet(),
    onLoadMore: () -> Unit = {},
    playlists: List<com.wearsic.app.model.Playlist> = emptyList(),
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    // (Tri-mode results, v1.9.49) Albums tab hits the
    // existing /api/search/albums endpoint; Artists tab groups the song
    // results client-side. Voice search reuses the system recognizer.
    albumResults: List<com.wearsic.app.model.Album> = emptyList(),
    onOpenAlbum: (com.wearsic.app.model.Album) -> Unit = {},
    onSearchAlbums: (String) -> Unit = {},
    onPlayArtistTracks: (List<Track>, Int) -> Unit = { tracks, _ -> tracks.firstOrNull()?.let(onTrackSelected) },
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    // Single source of truth: the ViewModel state. The field writes straight
    // back through onSearchTextChanged, so no local remember-mirror is needed.
    val typedQuery = searchState.query
    var actionTrack by remember { mutableStateOf<Track?>(null) }

    // TRI-MODE results (v1.9.49 redesign): Songs (server /api/search),
    // Albums (server /api/search/albums), Artists (client-side grouping of
    // the song results). rememberSaveable so the mode survives tab hops.
    var mode by rememberSaveable { mutableIntStateOf(0) }

    // Voice search through the platform recognizer (Wear OS supports the
    // same RecognizerIntent as phones). Hidden entirely on watches without
    // a recognizer instead of crashing on launch.
    val context = LocalContext.current
    val voiceIntent = remember {
        android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
        }
    }
    val voiceAvailable = remember {
        try {
            context.packageManager.resolveActivity(voiceIntent, 0) != null
        } catch (_: Exception) {
            false
        }
    }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            keyboardController?.hide()
            onSearchTextChanged(spoken)
            onQuerySelected(spoken)
        }
    }

    // Albums mode hits /api/search/albums (5-min server cache) whenever a
    // query lands in Albums mode. Runs in composable scope, not inside the
    // ScalingLazyColumn builder (DSL lambdas are not composable).
    LaunchedEffect(mode, searchState.query) {
        if (mode == 1 && searchState.query.isNotBlank()) {
            onSearchAlbums(searchState.query.trim())
        }
    }

    // Dedupe once per results change instead of on every recomposition
    // (scroll frames, press states), so list building stays cheap.
    val distinctResults by remember(searchState.results) {
        derivedStateOf { searchState.results.distinctBy { it.id } }
    }

    // Artist grouping for the Artists tab — one row per artist found within
    // the current song results, sorted by result count. Computed once per
    // results change (body scope), read inside DSL items.
    val artistRows: List<Pair<Track, Int>> = remember(distinctResults) {
        distinctResults.groupBy { it.artist.trim().lowercase() }
            .map { pair -> pair.value.first() to pair.value.size }
            .sortedByDescending { it.second }
    }

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
            // No auto-centering: the framework otherwise anchors item 1 to the
            // viewport centre, pushing the header (and the clock) off the top
            // of the round face. We pin the list with an explicit content
            // padding instead.
            autoCentering = null,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .testTag("search_lazy_column")
                .wearsicRotaryScroll(listState),
            contentPadding = rememberWearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            item {
                WearsicScreenHeader(
                    title = "Search",
                    subtitle = "Find your next favorite"
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
                            if (isFocused) LocalWearsicAccent.current else WearsicSurfaceBorderSubtle,
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
                            tint = if (isFocused) LocalWearsicAccent.current else WearsicTextMuted,
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
                                    fontFamily = WearsicBodyFont,
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
                                    fontFamily = WearsicBodyFont,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(LocalWearsicAccent.current),
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
                                tint = wearsicSecondaryText()
                            )
                        }

                        if (voiceAvailable) {
                            WearsicSongRowActionButton(
                                icon = Icons.Rounded.Mic,
                                contentDescription = "Voice search",
                                onClick = { voiceLauncher.launch(voiceIntent) },
                                testTag = "search_voice_button",
                                tint = LocalWearsicAccent.current
                            )
                        }
                    }
                }
            }

            // MODE CHIPS — Songs / Albums / Artists. Compact glass pills so
            // they never fight with the round chord below the search bar.
            item(key = "search_modes") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    val modes = listOf("Songs", "Albums", "Artists")
                    modes.forEachIndexed { index, label ->
                        val selected = mode == index
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (selected) LocalWearsicAccent.current.copy(alpha = 0.26f)
                                    else WearsicGlassFill
                                )
                                .border(
                                    1.dp,
                                    if (selected) LocalWearsicAccent.current.copy(alpha = 0.5f)
                                    else WearsicGlassBorder,
                                    CircleShape
                                )
                                .wearsicClickable { mode = index }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("search_mode_$label"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (selected) WearsicTextPrimary else WearsicTextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Live Suggestions (while typing) — hidden while a search is in
            // flight so the shimmer placeholders stay visible.
            if (mode == 0 && searchState.suggestions.isNotEmpty() && typedQuery.isNotBlank() && !searchState.isSearching) {
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
                                tint = LocalWearsicAccent.current,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = suggestion,
                                color = WearsicTextPrimary,
                                fontFamily = WearsicBodyFont,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Loading State — shimmer placeholders, not a spinner. The song
            // list stays in place; while a query is in flight the result
            // area fills with pulsing shimmer rows that the real results
            // then fade in over. If previous results are still shown
            // (re-search), a single compact shimmer strip sits above them.
            if (mode == 0 && searchState.isSearching) {
                if (searchState.results.isEmpty()) {
                    repeat(3) { shimmerIndex ->
                        item(key = "search_shimmer_$shimmerIndex") {
                            WearsicSearchShimmerRow(modifier = Modifier)
                        }
                    }
                } else {
                    item(key = "search_refresh_shimmer") {
                        WearsicCompactShimmer(
                            modifier = Modifier.testTag("search_refresh_shimmer")
                        )
                    }
                }
            }

            // Empty State — surface the real error when the request failed
            // instead of hiding it behind a misleading "No results found".
            if (mode == 0 && searchState.hasSearched && searchState.results.isEmpty() && !searchState.isSearching) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchState.errorMessage != null) {
                                "Search failed: ${searchState.errorMessage}"
                            } else {
                                "No tracks found for \"${searchState.query}\" — try a shorter or simpler title"
                            },
                            color = if (searchState.errorMessage != null) com.wearsic.app.ui.theme.WearsicError else WearsicTextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        if (searchState.errorMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(WearsicGlassFill)
                                    .border(1.dp, WearsicGlassBorder, CircleShape)
                                    .wearsicClickable { submitSearch() }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                                    .testTag("search_retry"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = LocalWearsicAccent.current,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Retry",
                                        color = WearsicTextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (mode == 0 && !searchState.hasSearched && !searchState.isSearching && searchState.suggestions.isEmpty()) {
                item {
                    WearsicEmptyState(
                        title = "A world of music",
                        message = "Search a song, artist, or album to start listening.",
                        icon = Icons.Rounded.Search
                    )
                }
            }

            // "Songs" is only one of the three tri-mode tabs (Songs / Albums /
            // Artists); the other two render their own section headers, so the
            // title must be gated on the songs mode.
            if (mode == 0 && distinctResults.isNotEmpty()) {
                item {
                    WearsicSectionTitle(
                        label = "Songs",
                        trailing = {
                            Text(
                                text = "${distinctResults.size}",
                                color = WearsicTextMuted,
                                style = WearsicTabularStyle.merge(TextStyle(fontSize = 10.sp))
                            )
                        }
                    )
                }
            }
            // Defend against duplicate IDs returned by older servers.
            if (mode == 0) items(distinctResults, key = { it.id }) { track ->
                // Smooth reveal: each result fades in over the shimmer
                // placeholders once the search completes.
                var revealed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { revealed = true }
                AnimatedVisibility(
                    visible = revealed,
                    enter = fadeIn(animationSpec = tween(320))
                ) {
                    SearchTrackItem(
                        track = track,
                        onClick = { onTrackSelected(track) },
                        onLongPress = { actionTrack = track },
                        onMore = { actionTrack = track },
                        onDownload = { onDownloadTrack(track) },
                        onAddToQueue = { onAddToQueue(track) }
                    )
                }
            }

            // Show more: expanded page loads behind the visible list.
            if (mode == 1) {
                if (searchState.query.isBlank()) {
                    item(key = "albums_prompt") {
                        Text(
                            text = "Search to see albums",
                            color = WearsicTextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else if (albumResults.isEmpty()) {
                    item(key = "albums_empty") {
                        Text(
                            text = if (searchState.hasSearched) "No albums found for \"${searchState.query}\"" else "Searching…",
                            color = WearsicTextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    item(key = "albums_title") {
                        WearsicSectionTitle(
                            label = "Albums",
                            trailing = {
                                Text(
                                    "${albumResults.size}",
                                    color = WearsicTextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        )
                    }
                    items(albumResults.size, key = { idx -> "album_$idx" }) { idx ->
                        val album = albumResults[idx]
                        AlbumResultRow(
                            album = album,
                            onClick = { onOpenAlbum(album) }
                        )
                    }
                }
            }

            if (mode == 2) {
                // Artists mode: client-side grouping of the song results —
                // zero extra network cost, one row per artist found.
                if (artistRows.isEmpty()) {
                    item(key = "artists_empty") {
                        Text(
                            text = "Run a Songs search first — artists group from its results",
                            color = WearsicTextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    item(key = "artists_title") {
                        WearsicSectionTitle(
                            label = "Artists",
                            trailing = {
                                Text(
                                    "${artistRows.size}",
                                    color = WearsicTextMuted,
                                    fontSize = 10.sp
                                )
                            }
                        )
                    }
                    items(artistRows.size, key = { idx -> "artist_$idx" }) { idx ->
                        val (seed, count) = artistRows[idx]
                        val artistSongs = distinctResults.filter {
                            it.artist.trim().equals(seed.artist.trim(), ignoreCase = true)
                        }
                        ArtistResultRow(
                            track = seed,
                            songs = artistSongs,
                            songCount = count,
                            onClick = {
                                onPlayArtistTracks(
                                    artistSongs,
                                    artistSongs.indexOf(seed).coerceAtLeast(0)
                                )
                            }
                        )
                    }
                }
            }

            // Show more: expanded page loads behind the visible list.
            if (searchState.isLoadingMore) {
                item {
                    WearsicCompactShimmer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = WearsicDimens.ScreenPaddingH)
                            .testTag("search_loading_more")
                    )
                }
            } else if (mode == 0 && searchState.canLoadMore && searchState.results.isNotEmpty() && !searchState.isSearching) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .wearsicClickable(onClick = onLoadMore)
                            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
                            .testTag("search_show_more"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Show more",
                            color = LocalWearsicAccent.current,
                            fontFamily = WearsicDisplayFont,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
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
                onCreatePlaylistAndAdd = { name -> onCreatePlaylistAndAdd(name, t) },
                onToggleFavorite = { onToggleFavorite(t) },
                isFavorite = t.id in favoriteTrackIds
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

/** One album hit in the Albums tab: square art, name, uploader. */
@Composable
private fun AlbumResultRow(
    album: com.wearsic.app.model.Album,
    onClick: () -> Unit
) {
    val artShape = RoundedCornerShape(WearsicDimens.ArtRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, CircleShape)
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!album.thumbnailUrl.isNullOrBlank()) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val request = remember(album.thumbnailUrl) {
                coil.request.ImageRequest.Builder(ctx)
                    .data(album.thumbnailUrl)
                    .size(96)
                    .build()
            }
            coil.compose.AsyncImage(
                model = request,
                contentDescription = album.name,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(watchScaled(38.dp))
                    .clip(artShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(watchScaled(38.dp))
                    .clip(artShape)
                    .background(wearsicAccentContainer()),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Album,
                    contentDescription = null,
                    tint = WearsicTextPrimaryDark,
                    modifier = Modifier.size(watchScaled(16.dp))
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.name,
                color = WearsicTextPrimary,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.uploader,
                color = WearsicTextMuted,
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One artist hit in the Artists tab: circular art, name, result count. */
@Composable
private fun ArtistResultRow(
    track: Track,
    songs: List<Track>,
    songCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, CircleShape)
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WearsicArtistCollageAvatar(
            songs = songs,
            size = watchScaled(34.dp),
            fallbackTint = WearsicTextPrimaryDark,
            fallbackTile = wearsicAccentContainer()
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.artist,
                color = WearsicTextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$songCount song${if (songCount == 1) "" else "s"}",
                color = WearsicTextMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
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
