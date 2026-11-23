package com.wearsic.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Artist-aware radio ranking (queue/radio accuracy): same-artist candidates
 * lead, and once enough strong matches exist off-artist junk is dropped so a
 * searched song never queues random other-artist results.
 */
class RelatedRankingTest {

    private fun track(id: String, uploader: String, title: String = "Song") = TrackDto(
        videoId = id,
        title = title,
        uploader = uploader,
        durationMs = 240_000L,
    )

    // Real gateway instance (default downloader, no network touched for pure
    // ranking); rankRelated/orderByDominantArtist are class members.
    private val gateway = YoutubeGateway()

    @Test
    fun `without hints order is preserved for unique artists`() {
        val other = track("a2", "Daft Punk")
        val same = track("a1", "The Weeknd")
        // No seed artist: no reordering should happen (stability).
        val ranked = gateway.rankRelated(listOf(other, same), null, null)
        assertEquals(listOf(other.videoId, same.videoId), ranked.map { it.videoId })
    }

    @Test
    fun `seed artist promotes same-artist first and drops off-artist when strong`() {
        val same1 = track("b1", "KR\$NA")
        val same2 = track("b2", "KR\$NA")
        val same3 = track("b3", "KR\$NA")
        val same4 = track("b4", "KR\$NA")
        val same5 = track("b5", "KR\$NA")
        val off = track("b6", "Someone Else")
        val ranked = gateway.rankRelated(listOf(off, same1, same2, same3, same4, same5), "KR\$NA", "NGL")
        assertEquals(5, ranked.size)
        assertTrue(ranked.none { it.videoId == off.videoId })
        assertTrue(ranked.all { it.uploader == "KR\$NA" })
    }

    @Test
    fun `thin related rail keeps everyone but same-artist still leads`() {
        val same = track("c1", "Arijit Singh")
        val off1 = track("c2", "Publisher X")
        val off2 = track("c3", "Publisher Y")
        val ranked = gateway.rankRelated(listOf(off1, same, off2), "Arijit Singh", "Kesariya")
        assertEquals(3, ranked.size)
        assertEquals(same.videoId, ranked.first().videoId)
    }

    @Test
    fun `shared surname does not count as full artist match`() {
        val real = track("d1", "Arijit Singh")
        val surnameOnly = track("d2", "Suraaj Singh")
        val ranked = gateway.rankRelated(listOf(surnameOnly, real), "Arijit Singh", "Kesariya")
        assertEquals(real.videoId, ranked.first().videoId)
        // No strong set -> surnameOnly stays but after the real artist.
        assertEquals(2, ranked.size)
    }
}
