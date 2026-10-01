package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.ui.components.WearsicEmptyState
import com.example.ui.components.WearsicLibraryTrackRow
import com.example.ui.components.WearsicLoadingState
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll
import com.example.ui.viewmodel.ArtistGroup
import com.example.ui.viewmodel.ArtistsUiState

@Composable
fun ArtistsScreen(
    artistsState: ArtistsUiState,
    onRefresh: () -> Unit,
    onPlayArtistSongs: (ArtistGroup, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    var selected by remember { mutableStateOf<ArtistGroup?>(null) }

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
                .wearsicRotaryScroll(listState),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                if (selected == null) {
                    WearsicScreenHeader(
                        title = "Artists",
                        subtitle = "From your saved songs",
                    )
                } else {
                    WearsicScreenHeader(
                        title = selected!!.name,
                        subtitle = "${selected!!.songs.size} songs • tap Clear to go back",
                    )
                    // Clear button — exits the artist detail back to the full list
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .wearsicClickable { selected = null }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("artist_clear"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear artist",
                            tint = WearsicTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Clear", color = WearsicTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            if (artistsState.isLoading && artistsState.artists.isEmpty()) {
                item {
                    WearsicLoadingState(label = "Loading artists…")
                }
            }

            val group = selected
            if (group != null) {
                itemsIndexed(group.songs, key = { _, song -> song.id }) { index, song ->
                    WearsicLibraryTrackRow(
                        track = song,
                        onPlay = { onPlayArtistSongs(group, index) }
                    )
                }
            } else if (artistsState.artists.isEmpty() && !artistsState.isLoading) {
                item {
                    WearsicEmptyState(
                        title = "No artists yet",
                        message = "Save favorites or download songs to see artists here.",
                        icon = Icons.Rounded.Person,
                        iconContentDescription = null
                    )
                }
            } else {
                items(artistsState.artists, key = { it.name }) { artist ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicGlassFill)
                            .border(1.dp, WearsicGlassBorder, CircleShape)
                            .wearsicClickable { selected = artist }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("artist_${artist.name}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(WearsicVibrantLavender.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = WearsicVibrantLavender,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = artist.name,
                                    color = WearsicTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${artist.songs.size} saved songs",
                                    color = WearsicTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun ArtistsScreenPreview() {
    WearsicTheme {
        ArtistsScreen(artistsState = ArtistsUiState(), onRefresh = {}, onPlayArtistSongs = { _, _ -> })
    }
}
