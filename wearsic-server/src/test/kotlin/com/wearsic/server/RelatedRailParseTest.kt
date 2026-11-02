package com.wearsic.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the lean related-rail parser against the shape YouTube actually serves.
 *
 * WHY THIS TEST EXISTS: the parser used to look for `compactVideoRenderer`
 * rows at `secondaryResults.results`, but WEB now nests the list at
 * `secondaryResults.secondaryResults.results` and fills it with
 * `lockupViewModel` rows instead. The mismatch was invisible — `leanRelated`
 * returned null, `related()` fell back to a full NewPipe extraction, and
 * "Made for you" quietly took ~10s per seed (6 seeds) instead of ~0.4s.
 *
 * The fixture is a verbatim slice of a live response (two video rows, one
 * playlist lockup that must NOT become a song, plus the continuation item).
 */
class RelatedRailParseTest {

    private fun fixture(): String =
        javaClass.classLoader.getResourceAsStream("related_web_next.json")!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `parses lockup rows from the live WEB next shape`() {
        val tracks = YoutubeGateway().parseRelatedJson(fixture())

        assertNotNull(tracks, "recognised shape must not fall through to extraction")
        assertEquals(2, tracks.size, "playlist lockup and continuation item are dropped")
        assertEquals("129kuDCQtHs", tracks[0].videoId)
        assertEquals("Bruce Springsteen - Dancing In the Dark (Official Video)", tracks[0].title)
        assertEquals("Bruce Springsteen", tracks[0].uploader)
        assertEquals(239_000L, tracks[0].durationMs, "duration comes from the thumbnail badge")
        assertTrue(
            tracks[0].thumbnailUrl!!.contains("129kuDCQtHs"),
            "thumbnail is the lockup's image, got ${tracks[0].thumbnailUrl}"
        )
        assertTrue(tracks.none { it.videoId.startsWith("MPREb_") }, "album/playlist rows are not songs")
    }

    @Test
    fun `still parses the pre-2025 compactVideoRenderer path`() {
        // The older shape: one less nesting level, renderer rows in the form
        // they had before lockups. A shape flip must not silently turn the
        // rail back into "nothing related" (which is what costs 10s/seed).
        val legacy = """
            {"contents":{"twoColumnWatchNextResults":{"secondaryResults":{"results":[
              {"compactVideoRenderer":{
                "videoId":"dQw4w9WgXcQ",
                "title":{"runs":[{"text":"Rick Astley - Never Gonna Give You Up"}]},
                "longBylineText":{"runs":[{"text":"Rick Astley"}]},
                "lengthText":{"simpleText":"3:33"},
                "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"}]}
              }},
              {"compactVideoRenderer":{"videoId":"short000000","title":{"simpleText":"A Short"},
                "lengthText":{"simpleText":"0:12"}}}
            ]}}}}
        """.trimIndent()

        val tracks = assertNotNull(YoutubeGateway().parseRelatedJson(legacy))
        assertEquals(1, tracks.size, "sub-30s shorts are filtered like the extractor path")
        assertEquals("dQw4w9WgXcQ", tracks[0].videoId)
        assertEquals(213_000L, tracks[0].durationMs)
    }

    @Test
    fun `unrecognised body returns null so the caller can extract`() {
        assertEquals(null, YoutubeGateway().parseRelatedJson("{\"contents\":{}}"))
        assertEquals(null, YoutubeGateway().parseRelatedJson("not json"))
    }
}
