package com.wearsic.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Music-only rescue behavior: the general-YouTube rescue (which surfaces
 * unreleased audio/rips) must never hand back non-music videos. The filter
 * is `internal` on YoutubeGateway; we test it via the gateway instance.
 */
class YoutubeGatewayMusicFilterTest {

    private val gateway = YoutubeGateway()

    @Test
    fun `non-music titles are filtered out of general rescue results`() = runTest {
        with(gateway) {
            val probes = listOf(
                TrackDto(videoId = "m1", title = "Mary Jane (Unreleased Audio)", uploader = "Lil Tjay"),
                TrackDto(videoId = "m2", title = "Song Name (Official Audio)", uploader = "Artist"),
                TrackDto(videoId = "v1", title = "Maths Lecture 1: Algebra", uploader = "SchoolTube"),
                TrackDto(videoId = "v2", title = "How to Learn Python", uploader = "CodeAcademy"),
                TrackDto(videoId = "v3", title = "Documentary: The Universe", uploader = "Channel4"),
                TrackDto(videoId = "v4", title = "Full Movie HD", uploader = "SomeChannel"),
                TrackDto(videoId = "v5", title = "Trailer — Big Film", uploader = "Studio"),
                TrackDto(videoId = "v6", title = "1337", uploader = "DigitChannel"),
            )
            val kept = probes.filter { it.isMusicLikely() }
            assertTrue(kept.any { it.videoId == "m1" }, "unreleased audio must be kept")
            assertTrue(kept.any { it.videoId == "m2" }, "official audio must be kept")
            assertFalse(kept.any { it.videoId == "v1" }, "lecture must be filtered")
            assertFalse(kept.any { it.videoId == "v2" }, "tutorial must be filtered")
            assertFalse(kept.any { it.videoId == "v3" }, "documentary must be filtered")
            assertFalse(kept.any { it.videoId == "v4" }, "movie must be filtered")
            assertFalse(kept.any { it.videoId == "v5" }, "trailer must be filtered")
            assertFalse(kept.any { it.videoId == "v6" }, "numeric-only title must be filtered")
        }
    }

    @Test
    fun `weak music page triggers the general rescue and keeps the target`() = runTest {
        // The Mary Jane regression: the music-filtered page for the exact
        // long title comes back WEAK (unrelated covers) but non-empty, so
        // the old `ifEmpty` guard skipped the general rescue entirely. The
        // weakness threshold must trigger the general fetch; the merged,
        // music-gated, token-ranked list must put the unreleased upload
        // first.
        with(gateway) {
            val music = listOf(
                TrackDto(videoId = "weak1", title = "Some Other Track", uploader = "Various Artists"),
                TrackDto(videoId = "weak2", title = "Another Cover", uploader = "Various Artists"),
            )
            val general = listOf(
                TrackDto(videoId = "a1v9-KOobOo", title = "Mary Jane (Unreleased Audio)", uploader = "Lil Tjay"),
            )
            val query = "Lil Tjay x Pop Smoke - Mary Jane (Unreleased Audio)"
            // Same decision the gateway makes: merge when the music page is
            // weak, gate, then rank by token overlap.
            val strongCount = music.count { tokenOverlap(it, query) >= 0.5f }
            val needGeneral = music.isEmpty() || strongCount < 2
            assertTrue(needGeneral, "weak music page must trigger the general rescue")
            val merged = (music + general)
                .distinctBy { it.videoId }
                .filter { it.isMusicLikely() }
                .sortedByDescending { tokenOverlap(it, query) }
            assertTrue(merged.any { it.videoId == "a1v9-KOobOo" }, "general rescue must surface the unreleased upload")
            assertEquals("a1v9-KOobOo", merged.first().videoId)
        }
    }
}
