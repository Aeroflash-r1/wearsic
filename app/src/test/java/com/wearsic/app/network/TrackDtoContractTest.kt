package com.wearsic.app.network

import com.wearsic.app.network.model.SearchResultsResponseDto
import com.wearsic.app.network.model.TrackDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the DTO contract against the REAL server response shape
 * (API_CONTRACT.md): tracks ship as videoId/uploader/thumbnailUrl and never
 * include a streamUrl. The 1.0.3 kotlinx.serialization rewrite lost these
 * mappings, silently decoding every search/favorites/playlists response to
 * empty — this test would have caught it.
 */
class TrackDtoContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesRealServerSearchResponse() {
        val body = """
            {"results":[
              {"videoId":"NZ3Ck43m_ZY","title":"Weather With You","uploader":"Crowded House","durationMs":225000,"thumbnailUrl":"https://yt3.googleusercontent.com/GTNqgIvQx2szu5yMc1ReBehP8I4oErbexFAQbPPQEC4X9X6PXrMykP1mrTiUP47RBkhUbbTHEYJ3489p=w60-h60-l90-rj"}
            ]}
        """.trimIndent()

        val dto = json.decodeFromString<SearchResultsResponseDto>(body)
        assertEquals(1, dto.results.size)
        val track = dto.results[0]
        assertEquals("NZ3Ck43m_ZY", track.id)
        assertEquals("Weather With You", track.title)
        assertEquals("Crowded House", track.artist)
        assertEquals(225000L, track.durationMs)
        // Raw artworkUrl arrives as received (upscale happens in toDomainTrack).
        assertTrue(track.artworkUrl!!.contains("googleusercontent"))
        // The server never sends streamUrl; the client synthesizes it.
        assertEquals("", track.streamUrl)
        // toDomainTrack() applies the artwork upscale and carries the id.
        // w256-h256: sharp enough for the 96dp header + blurred ambient glow
        // at ~1/4 the bytes of the old w544-h544 (less radio = less heat).
        val domain = track.toDomainTrack()
        assertEquals("NZ3Ck43m_ZY", domain.id)
        assertEquals("Crowded House", domain.artist)
        assertTrue(domain.artworkUrl!!.contains("w256-h256"))
    }

    @Test
    fun serializesPostBodyWithServerFieldNames() {
        val track = TrackDto(
            id = "NZ3Ck43m_ZY",
            title = "Weather With You",
            artist = "Crowded House",
            artworkUrl = null,
            durationMs = 225000L,
            streamUrl = "https://server/api/stream/NZ3Ck43m_ZY"
        )

        val body = Json.encodeToString(TrackDto.serializer(), track)
        assertTrue(body.contains("\"videoId\":\"NZ3Ck43m_ZY\""))
        assertTrue(body.contains("\"uploader\":\"Crowded House\""))
        assertFalse(body.contains("streamUrl")) // transient — never sent to the server
    }
}