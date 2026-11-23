package com.wearsic.app.components

import com.wearsic.app.model.Track
import com.wearsic.app.ui.components.wearsicArtistCollageArtworks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the artist collage picker: the tile must keep
 * first-encounter order, never repeat a cover, skip songs without real
 * artwork, and cap the mosaic at four panes.
 */
class WearsicArtistCollageTest {

    private fun song(id: String, artworkUrl: String?) =
        Track(id = id, title = "Song $id", artworkUrl = artworkUrl)

    @Test
    fun `collage picks first-encounter artwork in order`() {
        val picked = wearsicArtistCollageArtworks(
            listOf(
                song("1", "https://a/1.jpg"),
                song("2", "https://a/2.jpg"),
                song("3", "https://a/3.jpg")
            )
        )
        assertEquals(listOf("https://a/1.jpg", "https://a/2.jpg", "https://a/3.jpg"), picked)
    }

    @Test
    fun `collage never repeats the same cover`() {
        val picked = wearsicArtistCollageArtworks(
            listOf(
                song("1", "shared-art.jpg"),
                song("2", "shared-art.jpg"),
                song("3", "other-art.jpg"),
                song("4", "shared-art.jpg")
            )
        )
        assertEquals(listOf("shared-art.jpg", "other-art.jpg"), picked)
    }

    @Test
    fun `collage skips songs without artwork`() {
        val picked = wearsicArtistCollageArtworks(
            listOf(
                song("1", null),
                song("2", ""),
                song("3", "   "),
                song("4", "real-art.jpg")
            )
        )
        assertEquals(listOf("real-art.jpg"), picked)
    }

    @Test
    fun `collage caps at four panes`() {
        val picked = wearsicArtistCollageArtworks(
            (1..9).map { n -> song("$n", "https://a/$n.jpg") }
        )
        assertEquals(
            listOf("https://a/1.jpg", "https://a/2.jpg", "https://a/3.jpg", "https://a/4.jpg"),
            picked
        )
    }

    @Test
    fun `caller can ask for a smaller collage`() {
        val picked = wearsicArtistCollageArtworks(
            (1..5).map { n -> song("$n", "https://a/$n.jpg") },
            max = 2
        )
        assertEquals(listOf("https://a/1.jpg", "https://a/2.jpg"), picked)
    }

    @Test
    fun `an artist with no artwork ends in an empty collage`() {
        val picked = wearsicArtistCollageArtworks(listOf(song("1", null), song("2", null)))
        assertEquals(emptyList<String>(), picked)
    }

    @Test
    fun `an empty artist has no collage`() {
        assertEquals(emptyList<String>(), wearsicArtistCollageArtworks(emptyList()))
    }
}
