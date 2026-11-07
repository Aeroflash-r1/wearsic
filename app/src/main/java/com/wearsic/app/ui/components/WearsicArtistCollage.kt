package com.wearsic.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.Icon
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wearsic.app.model.Track
import com.wearsic.app.ui.theme.WearsicAccentPeach

/**
 * Distinct artwork URLs (first-encounter order, no repeats) for an artist
 * collage, capped at [max]. Pure so JVM tests can pin it without Coil or
 * an Android context.
 */
internal fun wearsicArtistCollageArtworks(
    songs: List<Track>,
    max: Int = 4,
): List<String> {
    if (max <= 0) return emptyList()
    val seen = HashSet<String>()
    val picked = ArrayList<String>(max)
    for (song in songs) {
        val url = song.artworkUrl?.trim().orEmpty()
        if (url.isBlank() || !seen.add(url)) continue
        picked.add(url)
        if (picked.size >= max) break
    }
    return picked
}

/**
 * The artist "portrait": a small circular collage of the artist's SAVED
 * song artwork — 1 song = one cover, 2 = a vertical split, 3+ = a 2×2
 * mosaic (Music-app convention). No real artist photo is served upstream,
 * so this is the nearest photo-like tile the existing pipes allow, and it
 * never costs a network round-trip beyond the covers already loaded.
 *
 * Fallback when the artist has no artwork at all: the old person-icon
 * circle, caller-coloured to stay in place visually.
 */
@Composable
fun WearsicArtistCollageAvatar(
    songs: List<Track>,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Rounded.Person,
    fallbackTint: Color = WearsicAccentPeach,
    fallbackTile: Color = WearsicAccentPeach.copy(alpha = 0.18f),
) {
    val context = LocalContext.current
    val artworks = remember(songs) { wearsicArtistCollageArtworks(songs) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fallbackTile)
    ) {
        when {
            artworks.isEmpty() -> Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = fallbackTint,
                modifier = Modifier.size(size * 0.5f)
            )
            artworks.size == 1 -> CollageArtworkCell(context, artworks[0], Modifier.fillMaxSize())
            artworks.size == 2 -> Row(Modifier.fillMaxSize()) {
                CollageArtworkCell(context, artworks[0], Modifier.weight(1f).fillMaxHeight())
                CollageArtworkCell(context, artworks[1], Modifier.weight(1f).fillMaxHeight())
            }
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    CollageArtworkCell(context, artworks[0], Modifier.weight(1f).fillMaxHeight())
                    CollageArtworkCell(context, artworks[1], Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    CollageArtworkCell(
                        context, artworks.getOrNull(2), Modifier.weight(1f).fillMaxHeight()
                    )
                    CollageArtworkCell(
                        context, artworks.getOrNull(3), Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

/** One mosaic pane: the cover centre-cropped, or the quiet tile tone. */
@Composable
private fun CollageArtworkCell(
    context: Context,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    tileColor: Color = WearsicAccentPeach.copy(alpha = 0.10f),
) {
    if (artworkUrl.isNullOrBlank()) {
        Box(modifier = modifier.background(tileColor))
        return
    }
    AsyncImage(
        model = remember(context, artworkUrl) {
            ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(72)
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}
