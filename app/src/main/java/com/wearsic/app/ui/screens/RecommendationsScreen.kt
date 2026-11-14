package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.WearsicEmptyState
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.components.WearsicSearchShimmerRow
import com.wearsic.app.ui.components.WearsicSongRow
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicBodyFont
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import com.wearsic.app.ui.viewmodel.LibraryViewModel

/**
 * "Made for you" — recommendations built from the user's local taste
 * (favorites/recent plays) and scored on the server. Fully user-initiated:
 * one request per visit, results cached server-side for 30 minutes.
 */
@Composable
fun RecommendationsScreen(
    recommendationsState: LibraryViewModel.RecommendationsUiState,
    onRefresh: () -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onQueue: (Track) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    val distinctTracks = remember(recommendationsState.tracks) {
        recommendationsState.tracks.distinctBy { it.id }
    }
    LaunchedEffect(Unit) { onRefresh() }

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
                .wearsicRotaryScroll(listState)
                .testTag("recommendations_lazy_column"),
            contentPadding = wearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Made for you",
                    subtitle = "From your taste"
                )
            }

            when {
                recommendationsState.isLoading && recommendationsState.tracks.isEmpty() -> {
                    // Same shimmer language as search — pulsing song-row
                    // placeholders, not a spinner.
                    repeat(3) { shimmerIndex ->
                        item(key = "rec_shimmer_$shimmerIndex") {
                            WearsicSearchShimmerRow(modifier = Modifier)
                        }
                    }
                }
                recommendationsState.errorMessage != null && distinctTracks.isEmpty() -> {
                    item {
                        Text(
                            text = recommendationsState.errorMessage,
                            color = WearsicTextMuted,
                            fontFamily = WearsicBodyFont,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp)
                        )
                    }
                }
                distinctTracks.isEmpty() -> {
                    item {
                        WearsicEmptyState(
                            title = "Nothing to recommend yet",
                            message = "Favorite a few songs first to build your personal mix.",
                            icon = Icons.Rounded.Star
                        )
                    }
                }
                else -> {
                    itemsIndexed(distinctTracks, key = { _, track -> track.id }) { index, track ->
                        WearsicSongRow(
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            onClick = { onPlayTrack(distinctTracks, index) },
                            testTag = "recommendation_${track.id}"
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
