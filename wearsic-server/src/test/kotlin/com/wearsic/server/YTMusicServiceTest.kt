package com.wearsic.server

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline tests for the YouTube Music metadata source: duration parsing,
 * DTO mapping and InnerTube response parsing all run against canned JSON.
 */
class YTMusicServiceTest {

    private val service = YTMusicService()

    @Test
    fun `duration parsing handles minutes and hours`() {
        assertEquals(225_000L, YTMusicService.parseDurationToMs("3:45"))
        assertEquals(30_000L, YTMusicService.parseDurationToMs("0:30"))
        assertEquals(3_723_000L, YTMusicService.parseDurationToMs("1:02:03"))
    }

    @Test
    fun `duration parsing rejects garbage`() {
        assertEquals(0L, YTMusicService.parseDurationToMs(""))
        assertEquals(0L, YTMusicService.parseDurationToMs("abc"))
        assertEquals(0L, YTMusicService.parseDurationToMs("5"))
    }

    @Test
    fun `client version matches WEB_REMIX date format`() {
        val v = YTMusicService.clientVersion()
        assertTrue(Regex("""1\.\d{8}\.01\.00""").matches(v), "got: $v")
    }

    @Test
    fun `toTrackDto maps fields and defaults`() {
        val dto = service.toTrackDto(
            YtmTrack(
                videoId = "abc123", title = "Song", artist = "Artist",
                durationMs = 200_000L, thumbnailUrl = "https://x/img.jpg",
            )
        )
        assertEquals("abc123", dto.videoId)
        assertEquals("Song", dto.title)
        assertEquals("Artist", dto.uploader)
        assertEquals(200_000L, dto.durationMs)
        val bare = service.toTrackDto(YtmTrack(videoId = "x"))
        assertEquals("Unknown", bare.title)
        assertEquals(0L, bare.durationMs)
        assertNull(bare.thumbnailUrl)
    }

    @Test
    fun `parseSearchResponse extracts songs and skips rows without videoId`() {
        val tracks = service.parseSearchResponse(SEARCH_FIXTURE, 10)
        assertEquals(2, tracks.size)
        val first = tracks[0]
        assertEquals("NZ3Ck43m_ZY", first.videoId)
        assertEquals("Weather With You", first.title)
        assertEquals("Crowded House", first.artist)
        assertEquals(225_000L, first.durationMs)
        assertEquals("https://example.com/w120-h120.jpg", first.thumbnailUrl)
        assertEquals("Dont Dream Its Over", tracks[1].title)
        assertEquals("H7UMRkp7m80", tracks[1].videoId)
        assertEquals(237_000L, tracks[1].durationMs)
    }


    @Test
    fun `parseSearchResponse drops album card rows that would play the wrong track`() {
        // "Rumours" is an ALBUM card row: its title links to a browse page and
        // its overlay play button points at the album's first video
        // (ALBUMFIRST1). Neither that row nor its leaked videoId may surface
        // as a playable song — tapping it used to play the WRONG track.
        val tracks = service.parseSearchResponse(CARD_AND_DUPLICATE_FIXTURE, 10)
        assertEquals(listOf("NZ3Ck43m_ZY", "H7UMRkp7m80"), tracks.map { it.videoId })
        assertTrue(tracks.none { it.title == "Rumours" })
        assertTrue(tracks.none { it.videoId == "ALBUMFIRST1" })
    }

    @Test
    fun `parseSearchResponse dedupes the same song across shelves`() {
        // The fixture repeats "Weather With You" (same videoId) in a second
        // shelf; it must appear exactly once and must not consume a second
        // slot of the limited result page.
        val tracks = service.parseSearchResponse(CARD_AND_DUPLICATE_FIXTURE, 10)
        assertEquals(2, tracks.size)
        assertEquals(1, service.parseSearchResponse(CARD_AND_DUPLICATE_FIXTURE, 2).size)
    }

    @Test
    fun `parseSearchResponse respects limit and tolerates garbage`() {
        assertEquals(1, service.parseSearchResponse(SEARCH_FIXTURE, 1).size)
        assertEquals(0, service.parseSearchResponse("not json", 10).size)
        assertEquals(0, service.parseSearchResponse("{}", 10).size)
        assertEquals(0, service.parseSearchResponse(SEARCH_FIXTURE, 0).size)
    }

    @Test
    fun `findContinuationToken reads the shelf token`() {
        val body = TWO_PAGE_FIXTURE_P1
        assertEquals("TOKEN12345678901234567890", service.findContinuationToken(body))
        assertEquals(null, service.findContinuationToken("{}"))
        assertEquals(null, service.findContinuationToken("not json"))
    }

    @Test
    fun `searchSongs follows continuations past the first page`() = runBlocking {
        var posts = 0
        val svc = YTMusicService(postJson = { _, sent ->
            posts++
            if ("TOKEN12345678901234567890" in sent) TWO_PAGE_FIXTURE_P2
            else TWO_PAGE_FIXTURE_P1
        })
        val tracks = svc.searchSongs("anything", 25)
        assertEquals(2, posts, "expected page 1 + one continuation fetch")
        assertEquals(listOf("AAA111AAAAA", "BBB222BBBBB"), tracks.map { it.videoId })
        svc.close()
    }

    companion object {
        private fun songRow(id: String, title: String): String =
            """{"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title", "navigationEndpoint": {"watchEndpoint": {"videoId": "$id"}}}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Artist"}, {"text": " • "}, {"text": "3:33"}]}}}]}}"""

        private val TWO_PAGE_FIXTURE_P1 = """{"contents": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": [${songRow("AAA111AAAAA", "Song One")}], "continuations": [{"nextContinuationData": {"continuation": "TOKEN12345678901234567890", "clickTrackingParams": "abc"}}]}}]}}}"""

        private val TWO_PAGE_FIXTURE_P2 = """{"continuationContents": {"musicShelfContinuation": {"contents": [${songRow("BBB222BBBBB", "Song Two")}]}}}"""
        private const val SONG_ROW_1 = """{"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Weather With You", "navigationEndpoint": {"watchEndpoint": {"videoId": "NZ3Ck43m_ZY"}}}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Crowded House"}, {"text": " \u2022 "}, {"text": "3:45"}]}}}], "playlistItemData": {"videoId": "NZ3Ck43m_ZY"}}}"""
        private const val SONG_ROW_2 = """{"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Dont Dream Its Over", "navigationEndpoint": {"watchEndpoint": {"videoId": "H7UMRkp7m80"}}}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Crowded House"}, {"text": " \u2022 "}, {"text": "3:57"}]}}}]}}"""
        private const val ALBUM_CARD_ROW = """{"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Rumours", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_album"}}}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Fleetwood Mac"}, {"text": " \u2022 "}, {"text": "1977"}]}}}], "overlay": {"musicItemThumbnailOverlayRenderer": {"content": {"musicPlayButtonRenderer": {"playNavigationEndpoint": {"watchEndpoint": {"videoId": "ALBUMFIRST1"}}}}}}, "playlistItemData": {"videoId": "ALBUMFIRST1"}}}"""

        private val CARD_AND_DUPLICATE_FIXTURE =
            """{"contents": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": [$ALBUM_CARD_ROW, $SONG_ROW_1]}}, {"musicShelfRenderer": {"contents": [$SONG_ROW_1, $SONG_ROW_2]}}]}}}"""

        private const val SEARCH_FIXTURE = """
{"contents": {"tabbedSearchResultsRenderer": {"tabs": [{"tabRenderer": {"content": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"title": {"runs": [{"text": "Songs"}]}, "contents": [{"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Weather With You", "navigationEndpoint": {"watchEndpoint": {"videoId": "NZ3Ck43m_ZY"}}}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Crowded House"}, {"text": " • "}, {"text": "Woodface", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREb_x", "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "MUSIC_PAGE_TYPE_ALBUM"}}}}}, {"text": " • "}, {"text": "3:45"}]}}}], "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [{"url": "https://example.com/w60-h60.jpg", "width": 60}, {"url": "https://example.com/w120-h120.jpg", "width": 120}]}}}, "playlistItemData": {"videoId": "NZ3Ck43m_ZY"}}}, {"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "NoId Song"}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Nobody"}, {"text": " • "}, {"text": "Single"}]}}}]}}, {"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Dont Dream Its Over", "navigationEndpoint": {"watchEndpoint": {"videoId": "H7UMRkp7m80"}}}]}}}, {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Crowded House"}, {"text": " • "}, {"text": "3:57"}]}}}]}}]}}]}}}}]}}}
"""
    }
}


