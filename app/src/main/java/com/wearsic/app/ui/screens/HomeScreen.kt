package com.wearsic.app.ui.screens
import com.wearsic.app.ui.theme.wearsicHeroCardBrush
import com.wearsic.app.ui.theme.LocalFlagshipColors
import com.wearsic.app.ui.theme.FlagshipColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.NowPlayingMiniCard
import com.wearsic.app.ui.components.WearsicActionTile
import com.wearsic.app.ui.components.WearsicMoodChip
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSectionTitle
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.components.WearsicSongRowArtwork
import com.wearsic.app.ui.components.WearsicTrackShelf
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicAccentAmber
import com.wearsic.app.ui.theme.WearsicAccentMint
import com.wearsic.app.ui.theme.WearsicAccentPeach
import com.wearsic.app.ui.theme.WearsicAccentRose
import com.wearsic.app.ui.theme.WearsicAccentSky
import com.wearsic.app.ui.theme.WearsicAccentViolet
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.wearsicHeroCardBrush
import com.wearsic.app.ui.theme.wearsicAccentBloomBrush
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RecommendationsUiState
import java.util.Calendar

/**
 * HOME — the premium entry tab for the redesigned Wear experience.
 *
 * Personal discovery, designed (not shrunk) for the 44 mm round
 * display: a personal "listen again" hero, a "New for you" shelf (the
 * server's taste-ranked recommendations of songs you have not heard),
 * Moods that run real searches, and a recent shelf.
 *
 * Data: everything renders EXISTING state — recentTracks, recommendations
 * (one POST, 30-minute server cache), favorites (Room). The only network
 * call happens when recommendations are empty, so cold app opens on the
 * wrist stay at one request max.
 */
@Composable
fun HomeScreen(
    recentTracks: List<Track>,
    recommendationsState: RecommendationsUiState,
    favorites: List<Track>,
    isServerReachable: Boolean,
    offlineReason: String? = null,
    onPlay: (List<Track>, Int) -> Unit,
    onOpenMood: (WearsicMood) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMix: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    /** The id of the track currently playing — lets the hero open the player
     *  instead of RESTARTING an already-playing song on tap. */
    currentTrackId: String? = null,
    /** Live now-playing track — takes the hero slot whenever a song is playing. */
    currentTrack: Track = Track(),
    isPlaying: Boolean = false,
    onRefreshRecommendations: () -> Unit,
    /** Opens the selectable song picker; the chosen list is shuffled + played. */
    onShuffleSelected: (List<Track>) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    // Library functions merged here (the standalone Library page was removed).
    onOpenDownloads: () -> Unit = {},
    onOpenPlaylists: () -> Unit = {},
    onOpenAlbums: () -> Unit = {},
    onOpenArtists: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    offlineDownloads: List<Track> = emptyList(),
    modifier: Modifier = Modifier
) {
    var showShufflePicker by remember { mutableStateOf(false) }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    // "New for you" must never show something the user already knows —
    // recents + favorites ids, derived from state the screen already holds
    // (no extra work per recomposition, no extra network).
    val knownIds = remember(recentTracks, favorites) {
        (recentTracks + favorites).map { it.id }.toSet()
    }
    // Derived once per state change; the shelf below only renders it.
    val freshPicks = remember(recommendationsState.tracks, knownIds) {
        filterNewToYou(recommendationsState.tracks, knownIds)
    }
    // Recommendations: fetch ONCE when the tab is first shown with an empty
    // list (the server holds a 30-min cache, so this is one cheap POST).
    // A previous FAILED fetch pins errorMessage — the guard keeps that state
    // pinned so a flaky server can never trap this effect in a retry loop
    // (every state change restarts LaunchedEffect); the user can always
    // re-run it from the Mix tab.
    LaunchedEffect(
        isServerReachable,
        recommendationsState.tracks.isEmpty(),
        recommendationsState.isLoading,
        recommendationsState.errorMessage
    ) {
        if (isServerReachable &&
            recommendationsState.tracks.isEmpty() &&
            recommendationsState.errorMessage == null &&
            !recommendationsState.isLoading
        ) {
            onRefreshRecommendations()
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
                .testTag("home_lazy_column")
                .wearsicRotaryScroll(listState),
            contentPadding = rememberWearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Personal header (no WEARSIC eyebrow on Home) ----
            item {
                val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                WearsicScreenHeader(
                    title = greetingForHour(hour),
                    subtitle = "Your music on your wrist",
                    showEyebrow = false
                )
            }

            // ---- Offline chip: only when the app truly cannot reach the
            //      server (no network, or the server is unreachable). Never
            //      blinks — the monitor keeps it stable. Deliberately calm:
            //      glass + amber, the same language as the Library offline
            //      banner. One word, no host names, no "unreachable" jargon. ----
            item {
                if (offlineReason != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(LocalFlagshipColors.current.glassFill)
                            .border(1.dp, LocalFlagshipColors.current.glassBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("home_offline_chip"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = LocalFlagshipColors.current.amber,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Offline",
                            color = LocalFlagshipColors.current.textPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // ---- Hero slot: once a track is loaded it STAYS as the Now
            //      Playing card (even when paused) so it is always tappable.
            //      The quiet "Made for this moment" card shows only before
            //      anything has been played. Both are clickable. ----
            item {
                if (currentTrack.id.isNotBlank()) {
                    NowPlayingMiniCard(
                        title = currentTrack.title,
                        artist = currentTrack.artist,
                        artworkUrl = currentTrack.artworkUrl,
                        isPlaying = isPlaying,
                        onClick = onOpenPlayer,
                        modifier = Modifier
                            .padding(horizontal = watchScaled(4.dp))
                            .testTag("home_now_playing_hero")
                    )
                } else {
                    com.wearsic.app.ui.components.FlagshipHeroCard(
                        title = "Made for this moment",
                        caption = "Your personal soundtrack",
                        onClick = onOpenMix,
                        modifier = Modifier.padding(horizontal = watchScaled(4.dp)),
                        testTag = "home_hero_card",
                    )
                }
            }

            // ---- While a song plays the hero is the Now Playing card, so
            //      keep a compact "Made for this moment" pill here to stay
            //      accessible. When idle the hero card already does this,
            //      so the pill is hidden to avoid showing both at once. ----
            if (currentTrack.id.isNotBlank()) {
                item {
                    WearsicActionCard(
                        label = "Made for this moment",
                        icon = Icons.Rounded.AutoAwesome,
                        onClick = onOpenMix,
                        testTag = "home_made_for_you"
                    )
                }
            }

            // ---- Browse: four clean, full-width M3 list pills (no grid,
            //      no solid icon discs) stacked vertically. ----
            item {
                WearsicSectionTitle(
                    label = "Browse",
                    icon = Icons.Rounded.Apps,
                    iconContentDescription = "Browse your music"
                )
            }
            item {
                WearsicActionCard(
                    label = "Downloads",
                    icon = Icons.Rounded.Download,
                    onClick = onOpenDownloads,
                    testTag = "home_browse_downloads_tile"
                )
            }
            item {
                WearsicActionCard(
                    label = "Playlists",
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    onClick = onOpenPlaylists,
                    testTag = "home_browse_playlists_tile"
                )
            }
            item {
                WearsicActionCard(
                    label = "Albums",
                    icon = Icons.Rounded.Album,
                    onClick = onOpenAlbums,
                    testTag = "home_browse_albums_tile"
                )
            }
            item {
                WearsicActionCard(
                    label = "Artists",
                    icon = Icons.Rounded.Person,
                    onClick = onOpenArtists,
                    testTag = "home_browse_artists_tile"
                )
            }
            // Offline saved-shelf: when the server is unreachable, saved music
            // stays one tap away (carried over from the removed Library page).
            if (!isServerReachable && offlineDownloads.isNotEmpty()) {
                item {
                    WearsicSectionTitle(
                        label = "Saved offline",
                        icon = Icons.Rounded.Download,
                        trailing = {
                            Text(
                                text = "${offlineDownloads.size} songs",
                                color = wearsicSecondaryText(),
                                fontFamily = com.wearsic.app.ui.theme.WearsicMonoFont,
                                fontSize = 10.sp
                            )
                        }
                    )
                }
                itemsIndexed(offlineDownloads.take(8), key = { _, t -> "home_offline_${t.id}" }) { _, track ->
                    WearsicSongRow(
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        onClick = { onPlay(listOf(track), 0) },
                        testTag = "home_offline_${track.id}"
                    )
                }
            }

            if (!isServerReachable) {
                item {
                    WearsicOfflineNote()
                }
            }

            // ---- New for you: the server's taste-ranked picks — songs
            //      related to your artists, genres and history that you
            //      have NOT heard yet. Renders the same cached state the
            //      Mix tab refreshes (one POST, 30-min server cache); this
            //      shelf itself triggers no network work. ----
            if (freshPicks.isNotEmpty()) {
                item {
                    WearsicSectionTitle(
                        label = "New for you",
                        icon = Icons.Rounded.AutoAwesome,
                        iconContentDescription = "New songs picked for you"
                    )
                }
                item {
                    WearsicTrackShelf(
                        tracks = freshPicks,
                        onPlayTrack = onPlay,
                        placeholderIcon = Icons.Rounded.AutoAwesome
                    )
                }
            }

            // ---- Keep listening (rest of recents) ----
            if (recentTracks.size > 1) {
                item {
                    WearsicSectionTitle(
                        label = "Keep listening",
                        icon = Icons.Rounded.History,
                        iconContentDescription = "Recently played"
                    )
                }
                item {
                    WearsicTrackShelf(
                        tracks = recentTracks.drop(1).take(10),
                        onPlayTrack = onPlay,
                        placeholderIcon = Icons.Rounded.History
                    )
                }
            }

            // ---- Shuffle favorites (opens a selectable song picker) ----
            if (favorites.isNotEmpty()) {
                item {
                    WearsicActionCard(
                        label = "Shuffle favorites",
                        icon = Icons.Rounded.Shuffle,
                        onClick = { showShufflePicker = true },
                        testTag = "home_play_favorites"
                    )
                }
            }

            // ---- Settings (bottom edge affordance) ----
            item {
                WearsicActionCard(
                    label = "Settings",
                    icon = Icons.Rounded.Settings,
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(horizontal = watchScaled(10.dp)),
                    testTag = "home_settings_button"
                )
            }

            // Extra tail room so the LAST item can always be scrolled into the
            // readable centre of the round face (the bezel otherwise hides it).
            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    if (showShufflePicker) {
        WearsicSongPickerSheet(
            tracks = favorites,
            onDismiss = { showShufflePicker = false },
            onConfirm = { selected ->
                showShufflePicker = false
                if (selected.isNotEmpty()) onShuffleSelected(selected)
            }
        )
    }
}

/**
 * "Listen again" hero: a wide charcoal card with the accent bloom under the
 * artwork, kicker + title + artist, and a compact accent play disc.
 */
@Composable
private fun WearsicHeroCard(
    kicker: String,
    title: String,
    subtitle: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val accent = LocalWearsicAccent.current
    val radius = watchScaled(WearsicDimens.CardRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(wearsicHeroCardBrush())
            .border(1.dp, LocalFlagshipColors.current.glassBorder, RoundedCornerShape(radius))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = watchScaled(10.dp),
                vertical = watchScaled(8.dp)
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WearsicSongRowArtwork(
            artworkUrl = artworkUrl,
            contentDescription = title,
            size = watchScaled(46.dp)
        )
        Spacer(Modifier.width(watchScaled(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                text = kicker,
                color = accent,
                fontSize = 8.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                color = LocalFlagshipColors.current.textPrimary,
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = wearsicSecondaryText(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(watchScaled(6.dp)))
        Box(
            modifier = Modifier
                .size(watchScaled(30.dp))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = LocalFlagshipColors.current.textPrimary,
                modifier = Modifier.size(watchScaled(16.dp))
            )
        }
    }
}

/**
 * A full-width glassy action card — favorites CTA, radio cards on Mix, etc.
 */
@Composable
private fun WearsicActionCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val accent = LocalWearsicAccent.current
    val radius = watchScaled(WearsicDimens.CardRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(LocalFlagshipColors.current.canvas) // OLED black — battery friendly
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(radius))
            .wearsicClickable(pressedScale = 0.97f, onClick = onClick)
            .padding(horizontal = watchScaled(14.dp), vertical = watchScaled(12.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Plain accent glyph — no solid circle disc.
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(watchScaled(20.dp))
        )
        Spacer(Modifier.width(watchScaled(12.dp)))
        Text(
            text = label,
            color = LocalFlagshipColors.current.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = true)
                .padding(horizontal = 4.dp)
        )
    }
}

/** Ammolite "offline" note styled like the Library offline banner. */
@Composable
private fun WearsicOfflineNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(com.wearsic.app.ui.theme.LocalFlagshipColors.current.glassFill)
            .border(1.dp, LocalFlagshipColors.current.glassBorder, CircleShape)
            .padding(horizontal = watchScaled(10.dp), vertical = watchScaled(5.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = com.wearsic.app.ui.theme.LocalFlagshipColors.current.amber,
            modifier = Modifier.size(watchScaled(13.dp))
        )
        Spacer(Modifier.width(watchScaled(6.dp)))
        Text(
            text = "Offline — shelf shows saved music",
            color = LocalFlagshipColors.current.textPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Full-screen song picker for "Shuffle favorites": every song is a selectable
 * row; confirm shuffles only the chosen tracks.
 */
@Composable
private fun WearsicSongPickerSheet(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    onConfirm: (List<Track>) -> Unit
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val pickerState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val selected = tracks.filter { it.id in selectedIds }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalFlagshipColors.current.canvas)
            .testTag("shuffle_picker")
    ) {
        ScreenScaffold(scrollState = pickerState) { padding ->
            ScalingLazyColumn(
                state = pickerState,
                autoCentering = null,
                modifier = Modifier
                    .fillMaxSize()
                    .wearsicRotaryScroll(pickerState),
                contentPadding = rememberWearsicListContentPadding(padding),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Select songs",
                            color = LocalFlagshipColors.current.textPrimary,
                            fontFamily = com.wearsic.app.ui.theme.FlagshipDisplayFont,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${selected.size} selected",
                            color = LocalWearsicAccent.current,
                            fontFamily = com.wearsic.app.ui.theme.FlagshipBodyFont,
                            fontSize = 11.sp
                        )
                    }
                }
                itemsIndexed(tracks, key = { _, t -> "pick_${t.id}" }) { _, track ->
                    val isSelected = track.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (isSelected) LocalWearsicAccent.current.copy(alpha = 0.22f)
                                else LocalFlagshipColors.current.surface
                            )
                            .border(
                                1.dp,
                                if (isSelected) LocalWearsicAccent.current else LocalFlagshipColors.current.hairline,
                                RoundedCornerShape(18.dp)
                            )
                            .wearsicClickable {
                                selectedIds = if (isSelected) selectedIds - track.id else selectedIds + track.id
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WearsicSongRowArtwork(
                            artworkUrl = track.artworkUrl,
                            contentDescription = track.title,
                            size = watchScaled(34.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = LocalFlagshipColors.current.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = LocalFlagshipColors.current.textMuted,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                tint = LocalWearsicAccent.current,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                item {
                    WearsicActionCard(
                        label = "Shuffle ${selected.size}",
                        icon = Icons.Rounded.Shuffle,
                        onClick = { if (selected.isNotEmpty()) onConfirm(selected) },
                        testTag = "picker_shuffle"
                    )
                }
                item {
                    WearsicActionCard(
                        label = "Cancel",
                        icon = Icons.Rounded.Close,
                        onClick = onDismiss
                    )
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
        }
    }
}

/** Compact round pill used for Home section shortcuts. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.HomeShortcutPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    containerColor: Color = com.wearsic.app.ui.theme.LocalFlagshipColors.current.surface,
    iconTint: Color = LocalWearsicAccent.current,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .weight(1f)
            .clip(CircleShape)
            .background(containerColor)
            .border(1.dp, iconTint.copy(alpha = 0.35f), CircleShape)
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = LocalFlagshipColors.current.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
