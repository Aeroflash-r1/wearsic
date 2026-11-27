package com.wearsic.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiReRankerTest {
    private val tracks = (1..6).map { i ->
        TrackDto(
            videoId = "video%07d".format(i),
            title = "Song $i",
            uploader = "Artist $i",
        )
    }

    private fun response(vararg ids: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"${ids.joinToString("\\n")}"}}]}"""

    @Test
    fun `explicit AI action calls provider and reports actual use`() = runTest {
        var calls = 0
        val reranker = AiReRanker(
            apiKey = "secret",
            baseUrl = "http://test/v1",
            postChat = { _, _, key ->
                calls++
                assertEquals("secret", key)
                200 to response(tracks[1].videoId, tracks[0].videoId)
            },
        )

        val result = reranker.rerankDetailed(tracks, emptyList(), emptySet(), force = true)

        assertTrue(result.aiApplied)
        assertEquals(1, calls)
        assertEquals(tracks.take(2).reversed().map { it.videoId }, result.tracks.take(2).map { it.videoId })
        assertEquals(6, result.tracks.size, "unranked remainder must never be dropped")
    }

    @Test
    fun `cache preserves unranked tracks and avoids another provider call`() = runTest {
        var calls = 0
        val reranker = AiReRanker(
            apiKey = "secret",
            postChat = { _, _, _ ->
                calls++
                200 to response(tracks[2].videoId)
            },
        )

        reranker.rerankDetailed(tracks, emptyList(), emptySet(), force = true)
        val cached = reranker.rerankDetailed(tracks, emptyList(), emptySet(), force = true)

        assertEquals(1, calls)
        assertTrue(cached.aiApplied)
        assertEquals(6, cached.tracks.size)
        assertEquals(tracks[2].videoId, cached.tracks.first().videoId)
    }

    @Test
    fun `failure is not cached and aiUsed stays false`() = runTest {
        var calls = 0
        val reranker = AiReRanker(
            apiKey = "secret",
            throttleMs = 0,
            postChat = { _, _, _ ->
                calls++
                if (calls == 1) 500 to "unavailable" else 200 to response(tracks[0].videoId)
            },
        )

        val failed = reranker.rerankDetailed(tracks, emptyList(), emptySet(), force = true)
        val recovered = reranker.rerankDetailed(tracks, emptyList(), emptySet(), force = true)

        assertFalse(failed.aiApplied)
        assertEquals(tracks, failed.tracks)
        assertTrue(recovered.aiApplied)
        assertEquals(2, calls)
    }

    @Test
    fun `different concurrent prompts share the global throttle slot`() = runTest {
        var calls = 0
        val reranker = AiReRanker(
            apiKey = "secret",
            throttleMs = 60_000,
            postChat = { _, _, _ ->
                synchronized(this) { calls++ }
                delay(30)
                200 to response(tracks[0].videoId)
            },
        )

        val first = async {
            reranker.rerankDetailed(tracks, listOf("seed-a"), emptySet(), force = true)
        }
        val second = async {
            reranker.rerankDetailed(tracks, listOf("seed-b"), emptySet(), force = true)
        }
        val results = listOf(first, second).awaitAll()

        assertEquals(1, calls)
        assertEquals(1, results.count { it.aiApplied })
    }

    @Test
    fun `automatic loads never spend provider tokens`() = runTest {
        var calls = 0
        val reranker = AiReRanker(
            apiKey = "secret",
            postChat = { _, _, _ -> calls++; 200 to response(tracks[0].videoId) },
        )

        val result = reranker.rerankDetailed(tracks, emptyList(), emptySet(), force = false)

        assertFalse(result.aiApplied)
        assertEquals(0, calls)
        assertEquals(tracks, result.tracks)
    }
}
