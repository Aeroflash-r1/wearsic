package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicPrimaryPillButton
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSectionTitle
import com.wearsic.app.ui.components.WearsicSongRowArtwork
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicHeroCardBrush
import com.wearsic.app.ui.theme.rememberWearsicListContentPadding
import com.wearsic.app.ui.theme.wearsicSecondaryText
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.watchScaled
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicRotaryScroll
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RecommendationsUiState
import com.wearsic.app.ui.viewmodel.LibraryViewModel.RadioState

/**
 * MIX — personalized radio & discovery.
 *
 * Built entirely on EXISTING pipes: taste-ranked recommendations (one POST
 * /api/recommendations, 30-min server cache), the auto radio (related-
 * tracks flow via /api/related with the radioState the queue screen already
 * consumes), and a favorites shuffle. Nothing new hits the network.
 */
@Composable
fun MixScreen(
    recommendationsState: RecommendationsUiState,
    favorites: List<Track>,
    recentTracks: List<Track>,
    radioState: RadioState,
    onPlay: (List<Track>, Int) -> Unit,
    onStartRadio: () -> Unit,
    onPlayRadio: (List<Track>) -> Unit = { onPlay(it, 0) },
    onDismissRadio: () -> Unit = {},
    onNavigateToSearch: () -> Unit = {},
    onShuffleFavorites: () -> Unit = {},
    onRefreshRecommendations: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)

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
                .testTag("mix_lazy_column")
                .wearsicRotaryScroll(listState),
            contentPadding = rememberWearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Mix",
                    subtitle = "Discovery, tuned to you"
                )
            }

            // ---- Search pill — parity with Home/Library ----
            item {
                WearsicPrimaryPillButton(
                    label = "Search Music",
                    icon = Icons.Rounded.Search,
                    onClick = onNavigateToSearch,
                    backgroundColor = LocalWearsicAccent.current,
                    contentColor = WearsicTextPrimaryDark,
                    testTag = "mix_search_button"
                )
            }

            // ---- Auto radio card ----
            item {
                WearsicRadioCard(
                    radioState = radioState,
                    hasHistory = recentTracks.isNotEmpty() || favorites.isNotEmpty(),
                    onStartRadio = onStartRadio
                )
            }

            // The generated radio list — show what is about to play BEFORE
            // anything starts; the user taps the row to play it.
            if (radioState is RadioState.Ready) {
                item {
                    WearsicSectionTitle(
                        label = "Radio mix",
                        icon = Icons.Rounded.GraphicEq,
                        iconContentDescription = "Radio mix",
                        trailing = {
                            Text(
                                text = "${radioState.tracks.size} tracks",
                                color = wearsicSecondaryText(),
                                fontSize = 10.sp
                            )
                        }
                    )
                }
                itemsIndexed(
                    radioState.tracks,
                    key = { i, t -> "radio_${t.id}_$i" }
                ) { index, track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, RoundedCornerShape(WearsicDimens.CardRadius))
                            .wearsicClickable(onClick = { onPlayRadio(radioState.tracks) })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            color = wearsicSecondaryText(),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = WearsicTextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = wearsicSecondaryText(),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                item {
                    Text(
                        text = "Tap any track to play the whole mix",
                        color = wearsicSecondaryText(),
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }

            // ---- Made for you (taste-ranked) ----
            item {
                WearsicSectionTitle(
                    label = "Made for you",
                    icon = Icons.Rounded.AutoAwesome,
                    iconContentDescription = "Made for you",
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(WearsicGlassFill)
                                .border(1.dp, WearsicGlassBorder, CircleShape)
                                .wearsicClickable(onClick = onRefreshRecommendations)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("mix_refresh_recommendations")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh recommendations",
                                tint = LocalWearsicAccent.current,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Refresh",
                                color = WearsicTextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                )
            }
            when {
                recommendationsState.isLoading -> item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                recommendationsState.tracks.isEmpty() -> item {
                    WearsicEmptyState(
                        title = "No mix yet",
                        message = "Play a few songs and this fills up",
                        icon = Icons.Rounded.MusicNote
                    )
                }
                else -> {
                    val mixTracks = recommendationsState.tracks.distinctBy { it.id }.take(15)
                    itemsIndexed(
                        mixTracks,
                        key = { _, track -> "mix_${track.id}" }
                    ) { index, track ->
                        MixTrackRow(
                            track = track,
                            source = mixTracks,
                            index = index,
                            onClick = onPlay
                        )
                    }
                }
            }
        }
    }
}

/** Auto-radio launcher card with live RadioState translation. */
@Composable
private fun WearsicRadioCard(
    radioState: RadioState,
    hasHistory: Boolean,
    onStartRadio: () -> Unit
) {
    val accent = LocalWearsicAccent.current
    val radius = watchScaled(WearsicDimens.CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(WearsicHeroCardBrush)
            .border(1.dp, WearsicGlassBorder, RoundedCornerShape(radius))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onStartRadio
            )
            .padding(horizontal = watchScaled(10.dp), vertical = watchScaled(10.dp))
            .testTag("mix_start_radio"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(watchScaled(34.dp))
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            when (radioState) {
                is RadioState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(watchScaled(16.dp))
                )
                else -> Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(watchScaled(17.dp))
                )
            }
        }
        Spacer(Modifier.width(watchScaled(9.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Auto radio",
                color = WearsicTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (radioState) {
                    is RadioState.Loading -> "Building your radio…"
                    is RadioState.Error -> radioState.message
                    else -> if (hasHistory) "From your plays & favorites" else "Needs one song first"
                },
                color = if (radioState is RadioState.Error) WearsicError else wearsicSecondaryText(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Generic mix action card (shuffle favorites). */
@Composable
private fun WearsicMixActionCard(
    label: String,
    icon: ImageVector,
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
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** One recommendation row on Mix — art, two lines of type, tap = play. */
@Composable
private fun MixTrackRow(
    track: Track,
    source: List<Track>,
    index: Int,
    onClick: (List<Track>, Int) -> Unit
) {
    val accent = LocalWearsicAccent.current
    val radius = watchScaled(WearsicDimens.CardRadius)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(WearsicHeroCardBrush)
            .border(1.dp, WearsicGlassBorder.copy(alpha = 0.6f), RoundedCornerShape(radius))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(source, index) }
            )
            .padding(horizontal = watchScaled(8.dp), vertical = watchScaled(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WearsicSongRowArtwork(
            artworkUrl = track.artworkUrl,
            contentDescription = track.title,
            size = watchScaled(38.dp)
        )
        Spacer(Modifier.width(watchScaled(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = WearsicTextPrimary,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = wearsicSecondaryText(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = accent.copy(alpha = 0.75f),
            modifier = Modifier.size(watchScaled(12.dp))
        )
    }
}
