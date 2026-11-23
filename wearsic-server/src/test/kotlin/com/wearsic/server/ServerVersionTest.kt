package com.wearsic.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the server-side JSON wire format against API_CONTRACT.md. The Wear
 * OS client parses these exact field names; any rename breaks every client
 * generation in the field.
 */
class ServerJsonContractTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `TrackDto serializes with contract field names`() {
        val track = TrackDto(
            videoId = "NZ3Ck43m_ZY",
            title = "Weather With You",
            uploader = "Crowded House",
            durationMs = 225_000L,
            thumbnailUrl = "https://example.com/thumb.jpg",
        )
        val encoded = json.encodeToString(track)

        assertTrue(encoded.contains("\"videoId\":\"NZ3Ck43m_ZY\""), encoded)
        assertTrue(encoded.contains("\"title\":\"Weather With You\""), encoded)
        assertTrue(encoded.contains("\"uploader\":\"Crowded House\""), encoded)
        assertTrue(encoded.contains("\"durationMs\":225000"), encoded)
        assertTrue(encoded.contains("\"thumbnailUrl\":"), encoded)
        assertFalse(encoded.contains("artist"), "client-facing artist field is named uploader")
        assertFalse(encoded.contains("id\":"), "client-facing id field is named videoId")
    }

    @Test
    fun `SearchResponse wraps results`() {
        val encoded = json.encodeToString(SearchResponse(listOf(TrackDto("v1", "t", "u"))))
        assertTrue(encoded.contains("\"results\":["))
    }

    @Test
    fun `Surrogate id round-trips through TrackDto`() {
        val encoded = json.encodeToString(TrackDto(videoId = "it:12345", title = "t", uploader = "u"))
        val decoded = json.decodeFromString<TrackDto>(encoded)
        assertEquals("it:12345", decoded.videoId)
    }

    @Test
    fun `ErrorResponse shape is stable`() {
        val encoded = json.encodeToString(ErrorResponse("bad_request"))
        assertEquals("""{"error":"bad_request"}""", encoded)
    }

    @Test
    fun `HealthResponse reports the single-source version`() {
        val health = HealthResponse(transcoderAvailable = true)
        assertEquals(ServerVersion.VERSION, health.version)
        assertEquals("ok", health.status)
        assertEquals("Wearsic Engine", health.serverName)
        val encoded = json.encodeToString(health)
        assertTrue(encoded.contains("\"version\":\"${ServerVersion.VERSION}\""), encoded)
    }

    @Test
    fun `Cookie endpoints never expose the cookie value`() {
        // The status endpoint only ever reports whether a cookie exists.
        val encoded = json.encodeToString(YoutubeCookieStatus(hasCookie = true))
        assertEquals("""{"hasCookie":true}""", encoded)
        assertFalse(encoded.lowercase().contains("cookie="))
        assertTrue(!encoded.contains("SID"), encoded)
    }

    @Test
    fun `PlaylistTracksResponse keeps id-name-tracks shape`() {
        val encoded = json.encodeToString(
            PlaylistTracksResponse(id = "pl-1", name = "Mix", tracks = listOf(TrackDto("v", "t", "u")))
        )
        assertTrue(encoded.contains("\"id\":\"pl-1\""), encoded)
        assertTrue(encoded.contains("\"name\":\"Mix\""), encoded)
        assertTrue(encoded.contains("\"tracks\":["))
    }
}
