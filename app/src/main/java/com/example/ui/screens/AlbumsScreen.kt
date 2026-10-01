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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.example.model.Album
import com.example.ui.components.WearsicSongRow
import com.example.ui.theme.WearsicGlassBorder
import com.example.ui.theme.WearsicGlassFill
import com.example.ui.components.WearsicScreenHeader
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicError
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender
import com.example.ui.viewmodel.LibraryViewModel.AlbumsUiState

import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun AlbumsScreen(
    albumsState: AlbumsUiState,
    onQueryChanged: (String) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    var typed by remember(albumsState.query) { mutableStateOf(albumsState.query) }
    var debouncedQuery by remember { mutableStateOf("") }

    // Debounce: fire search 400ms after the user stops typing.
    LaunchedEffect(debouncedQuery) {
        if (debouncedQuery.isNotBlank()) {
            kotlinx.coroutines.delay(400)
            onQueryChanged(debouncedQuery.trim())
        }
    }

    // Wear keyboards fire different enter actions; handle all and dismiss so
    // results appear immediately.
    fun submitAlbumSearch() {
        val q = typed.trim()
        if (q.isNotBlank()) {
            keyboardController?.hide()
            onQueryChanged(q)
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
            item {
                WearsicScreenHeader(
                    title = "Albums",
                    subtitle = "Find full albums",
                )
            }

            item {
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(CircleShape)
                        .background(if (isFocused) WearsicVibrantLavender.copy(alpha = 0.12f) else WearsicGlassFill)
                        .border(
                            1.dp,
                            if (isFocused) WearsicVibrantLavender else WearsicGlassBorder,
                            CircleShape
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = if (isFocused) WearsicVibrantLavender else WearsicTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        BasicTextField(
                            value = typed,
                            onValueChange = {
                                typed = it
                                debouncedQuery = it
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = WearsicTextPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(WearsicVibrantLavender),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { submitAlbumSearch() },
                                onDone = { submitAlbumSearch() },
                                onGo = { submitAlbumSearch() }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            if (albumsState.isLoading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            if (albumsState.errorMessage != null && !albumsState.isLoading) {
                item {
                    Text(
                        text = albumsState.errorMessage,
                        color = WearsicError,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            items(albumsState.albums, key = { it.id }) { album ->
                AlbumCard(album = album, onClick = { onOpenAlbum(album) })
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    WearsicSongRow(
        title = album.name,
        artist = "${album.trackCount} songs • ${album.uploader}",
        artworkUrl = album.thumbnailUrl,
        onClick = onClick,
        modifier = modifier,
        testTag = "album_${album.id}",
        trailing = {
            Icon(
                imageVector = Icons.Rounded.Album,
                contentDescription = null,
                tint = WearsicVibrantLavender.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun AlbumsScreenPreview() {
    WearsicTheme {
        AlbumsScreen(albumsState = AlbumsUiState(), onQueryChanged = {}, onOpenAlbum = {})
    }
}