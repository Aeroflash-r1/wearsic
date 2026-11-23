package com.wearsic.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Album search relevance: YouTube's album search is name-fuzzy and returns
 * same-named albums by unrelated artists. rankAlbums keeps the RIGHT album
 * from the RIGHT publisher/artist at the top and trims namesake junk.
 */
class AlbumRankingTest {

    private fun album(name: String, uploader: String) = AlbumDto(
        id = "https://example.com/${name.hashCode()}",
        name = name,
        uploader = uploader,
        thumbnailUrl = null,
    )

    @Test
    fun `full name+artist match drops wrong-artist namesakes`() {
        val right = album("Thriller", "Michael Jackson")
        val namesake = album("Thriller", "Some Random Band")
        val other = album("Bad", "Michael Jackson")

        val ranked = rankAlbums("Thriller Michael Jackson", listOf(namesake, right, other))

        assertEquals(listOf(right), ranked)
    }

    @Test
    fun `partial queries rank exact album-name matches first`() {
        val exact = album("Back in Black", "AC/DC")
        val partial = album("Back in Black Live", "Someone")
        val unrelated = album("Jazz Classics", "Nobody")

        val ranked = rankAlbums("back in black", listOf(partial, unrelated, exact))

        assertEquals(exact, ranked.first())
        // Zero-overlap junk is trimmed once anything matches.
        assertTrue(unrelated !in ranked)
    }

    @Test
    fun `publisher tokens count toward a match`() {
        val byArtist = album("Greatest Hits", "Fleetwood Mac")
        val byOther = album("Greatest Hits", "Cover Masters")

        val ranked = rankAlbums("Greatest Hits Fleetwood", listOf(byOther, byArtist))

        assertEquals(listOf(byArtist), ranked)
    }

    @Test
    fun `queries matching nothing keep the original order`() {
        val a = album("Album A", "Artist A")
        val b = album("Album B", "Artist B")

        // No token overlap at all (e.g. a non-Latin or fuzzy query): never
        // nuke the result page over an imperfect query.
        val ranked = rankAlbums("ツ", listOf(a, b))

        assertEquals(listOf(a, b), ranked)
    }

    @Test
    fun `dominant artist promotes the real album over auto-singles`() {
        // The exact "Kesariya" failure: a bare song-title query returns a
        // page of same-named auto-generated singles by random publishers. The
        // official album is buried below them; the track-search dominant
        // artist must lift it to the top.
        val official = album("Kesariya (From \"Brahmastra\")", "Arijit Singh")
        val single1 = album("Kesariya", "Arjun Ahir")
        val single2 = album("Kesariya", "Apex Music")
        val single3 = album("Kesariya", "Sohil Bloch")

        val ranked = rankAlbums("Kesariya", listOf(single1, single2, official, single3), dominantArtist = "Arijit Singh")

        assertEquals(official, ranked.first())
        assertTrue(single1 in ranked)
        assertTrue(single2 in ranked)
        assertTrue(single3 in ranked)
    }


    @Test
    fun `shared-surname uploader does not win the artist tier`() {
        val official = album("Kesariya (From \"Brahmastra\")", "Arijit Singh")
        val surnameCollision = album("Kesariya", "Suraaj Singh")
        val junk = album("Kesariya", "Arjun Ahir")

        val ranked = rankAlbums(
            "Kesariya",
            listOf(junk, surnameCollision, official),
            dominantArtist = "Arijit Singh",
        )
        assertEquals(official, ranked.first())
    }

    @Test
    fun `empty input passes through`() {
        assertEquals(emptyList(), rankAlbums("anything", emptyList()))
        val a = album("Album A", "Artist A")
        assertEquals(listOf(a), rankAlbums("", listOf(a)))
    }
}
