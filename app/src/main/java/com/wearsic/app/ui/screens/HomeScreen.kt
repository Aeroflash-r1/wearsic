package com.wearsic.app.ui.screens

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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material.icons.rounded.Radio
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicMoodChip
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSectionTitle
import com.wearsic.app.ui.components.WearsicSongRowArtwork
import com.wearsic.app.ui.components.WearsicTrackShelf
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicAccentAmber
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicHeroCardBrush
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
 * display: a personal "listen again" hero, a Fresh picks carousel (the
 * server's taste-ranked recommendations), Moods that run real searches,
 * most-played and recent shelves.
 *
 * Data: everything renders EXISTING state — recentTracks, most played
 * (Room), recommendations (one POST, 30-minute server cache), favorites
 * (Room). The only network call happens when recommendations are empty,
 * so cold app opens on the wrist stay at one request max.
 */
@Composable
fun HomeScreen(
    recentTracks: List<Track>,
    mostPlayedTracks: List<Track>,
    recommendationsState: RecommendationsUiState,
    favorites: List<Track>,
    isServerReachable: Boolean,
    onPlay: (List<Track>, Int) -> Unit,
    onOpenSearchForMood: (WearsicMood) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMix: () -> Unit,
    onOpenLibrary: () -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    /** The id of the track currently playing — lets the hero open the player
     *  instead of RESTARTING an already-playing song on tap. */
    currentTrackId: String? = null,
    onRefreshRecommendations: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
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
            // ---- Personal header ----
            item {
                val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
                WearsicScreenHeader(
                    title = greetingForHour(hour),
                    subtitle = "Your music on your wrist"
                )
            }

            // ---- Search bar: one tap jumps to the Hub's Search tab ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .wearsicClickable(onClick = onOpenSearch)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("home_search_bar"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = LocalWearsicAccent.current,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Search songs, artists, albums…",
                        color = WearsicTextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ---- Library + Mix shortcuts (the old floating rail is gone) ----
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HomeShortcutPill(
                        label = "Library",
                        icon = Icons.Rounded.LibraryMusic,
                        onClick = onOpenLibrary,
                        testTag = "home_library_shortcut"
                    )
                    HomeShortcutPill(
                        label = "Mix",
                        icon = Icons.Rounded.GraphicEq,
                        onClick = onOpenMix,
                        testTag = "home_mix_shortcut"
                    )
                }
            }

            if (!isServerReachable) {
                item {
                    WearsicOfflineNote()
                }
            }

            // ---- Listen again (last played hero) ----
            if (recentTracks.isNotEmpty()) {
                val last = recentTracks.first()
                item {
                    WearsicHeroCard(
                        kicker = "LISTEN AGAIN",
                        title = last.title,
                        subtitle = last.artist,
                        artworkUrl = last.artworkUrl,
                        onClick = {
                            if (last.id == currentTrackId) onOpenPlayer() else onPlay(recentTracks, 0)
                        },
                        testTag = "home_hero_last_played"
                    )
                }
            }

            // ---- Fresh picks (taste-ranked recommendations) ----
            item {
                WearsicSectionTitle(
                    label = "Fresh for you",
                    icon = Icons.Rounded.AutoAwesome,
                    iconContentDescription = "Fresh picks",
                )
            }
            if (recommendationsState.tracks.isEmpty() && !recommendationsState.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Open Mix to discover something new",
                            color = LocalWearsicAccent.current,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onOpenMix
                                )
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            } else {
                item {
                    WearsicTrackShelf(
                        tracks = recommendationsState.tracks.take(12),
                        onPlayTrack = onPlay,
                        placeholderIcon = Icons.Rounded.Whatshot
                    )
                }
            }

            // ---- Moods & vibes ----
            item {
                WearsicSectionTitle(
                    label = "Moods",
                    icon = Icons.Rounded.SelfImprovement,
                    iconContentDescription = "Mood chips"
                )
            }
            item {
                val moods = homeMoods()
                val icons = listOf(
                    Icons.Rounded.Whatshot,
                    Icons.Rounded.Headphones,
                    Icons.Rounded.FitnessCenter,
                    Icons.Rounded.LocalCafe,
                    Icons.Rounded.SelfImprovement,
                    Icons.Rounded.History,
                    Icons.Rounded.Celebration
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = watchScaled(WearsicDimens.ScreenPaddingH)),
                    horizontalArrangement = Arrangement.spacedBy(watchScaled(6.dp))
                ) {
                    items(moods.size) { index ->
                        val mood = moods[index]
                        WearsicMoodChip(
                            label = mood.label,
                            icon = icons[index % icons.size],
                            onClick = { onOpenSearchForMood(mood) }
                        )
                    }
                }
            }

            // ---- Most played (≥3 ranked rows = a real taste signal) ----
            if (mostPlayedTracks.size >= 3) {
                item {
                    WearsicSectionTitle(
                        label = "Most played",
                        icon = Icons.Rounded.Whatshot,
                        iconContentDescription = "Most played"
                    )
                }
                item {
                    WearsicTrackShelf(
                        tracks = mostPlayedTracks.take(10),
                        onPlayTrack = onPlay,
                        placeholderIcon = Icons.Rounded.Radio
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

            // ---- Favorites CTA ----
            if (favorites.isNotEmpty()) {
                item {
                    WearsicActionCard(
                        label = "Play your favorites (${favorites.size})",
                        icon = Icons.Rounded.Favorite,
                        onClick = { onPlay(favorites, 0) },
                        testTag = "home_play_favorites"
                    )
                }
            } else {
                item {
                    WearsicActionCard(
                        label = "Find your first favorite",
                        icon = Icons.Rounded.Search,
                        onClick = onOpenSearch,
                        testTag = "home_open_search"
                    )
                }
            }
        }
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
            .background(WearsicHeroCardBrush)
            .border(1.dp, WearsicGlassBorder, RoundedCornerShape(radius))
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
                color = WearsicTextPrimary,
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
                tint = WearsicTextPrimary,
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
            .background(WearsicHeroCardBrush)
            .border(1.dp, WearsicGlassBorder, RoundedCornerShape(radius))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = watchScaled(10.dp), vertical = watchScaled(9.dp))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(watchScaled(28.dp))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(watchScaled(16.dp))
            )
        }
        Spacer(Modifier.width(watchScaled(8.dp)))
        Text(
            text = label,
            color = WearsicTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true)
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
            .background(com.wearsic.app.ui.theme.WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, CircleShape)
            .padding(horizontal = watchScaled(10.dp), vertical = watchScaled(5.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = WearsicAccentAmber,
            modifier = Modifier.size(watchScaled(13.dp))
        )
        Spacer(Modifier.width(watchScaled(6.dp)))
        Text(
            text = "Offline — shelf shows saved music",
            color = WearsicTextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Compact round pill used for Home section shortcuts. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.HomeShortcutPill(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val accent = LocalWearsicAccent.current
    Row(
        modifier = modifier
            .weight(1f)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = WearsicTextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
