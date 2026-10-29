package com.wearsic.server

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
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
}
