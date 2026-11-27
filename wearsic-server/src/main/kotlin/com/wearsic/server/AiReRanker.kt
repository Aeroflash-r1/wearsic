package com.wearsic.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * OPTIONAL "AI" re-ranker for recommendations and AI playlists.
 *
 * Works with ANY OpenAI-compatible chat-completions provider. AI is an
 * enhancement, never the primary path: failures fall back to the classic
 * ranking and the model can never inject tracks outside the candidate pool.
 */
class AiReRanker(
    private val apiKey: String?,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini",
    /** Test/operational knobs; production defaults preserve the spend caps. */
    private val throttleMs: Long = THROTTLE_MS,
    private val requestTimeoutMs: Long = 4_000L,
    private val postChat: (suspend (url: String, body: String, apiKey: String) -> Pair<Int, String>)? = null,
) {
    private val logger = LoggerFactory.getLogger(AiReRanker::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val temperature: Double = 0.0,
        val max_tokens: Int = 96,
        val messages: List<ChatMessage>,
    )

    @Serializable
    private data class Choice(val message: ChatMessage)

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    /** Unlike the legacy boolean, this reports whether the model really ran. */
    data class RerankResult(
        val tracks: List<TrackDto>,
        val aiApplied: Boolean,
    )

    companion object {
        private const val THROTTLE_MS = 5 * 60 * 1000L
        private const val CACHE_TTL_MS = 30 * 60 * 1000L
        private const val MIN_POOL = 6
        private const val PROMPT_CANDIDATES = 12
    }

    /** Signature -> (cachedAt, ordered ids). Only successful model ranks enter. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val singleFlight = SingleFlight<String, List<String>?>()

    private val throttleLock = Any()
    private var nextAllowedAtMs = 0L

    /** Atomically reserve the one global provider slot across different prompts. */
    private fun reserveProviderSlot(now: Long = System.currentTimeMillis()): Boolean =
        synchronized(throttleLock) {
            if (now < nextAllowedAtMs) return@synchronized false
            nextAllowedAtMs = now + throttleMs
            true
        }

    private val http: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofMillis(requestTimeoutMs))
            .build()
    }

    val isConfigured: Boolean get() = !apiKey.isNullOrBlank()

    /** Re-order [tracks], preserving the original List-based API. */
    suspend fun rerank(
        tracks: List<TrackDto>,
        seedIds: List<String>,
        topArtists: Set<String>,
        genres: List<String> = emptyList(),
        force: Boolean = false,
    ): List<TrackDto> = rerankDetailed(tracks, seedIds, topArtists, genres, force).tracks

    /**
     * Re-order [tracks] and report whether the provider actually produced the
     * order. [force] is required for provider spend. Failed/throttled calls
     * are NOT cached, so a temporary outage cannot disable AI for 30 minutes.
     */
    suspend fun rerankDetailed(
        tracks: List<TrackDto>,
        seedIds: List<String>,
        topArtists: Set<String>,
        genres: List<String> = emptyList(),
        force: Boolean = false,
    ): RerankResult {
        val fallback = RerankResult(tracks, aiApplied = false)
        if (tracks.size < MIN_POOL || !isConfigured || !force) return fallback

        val signature = buildString {
            append(seedIds.sorted().joinToString("|"))
            append("::")
            append(topArtists.sorted().joinToString("|"))
            append("::")
            append(genres.sorted().joinToString("|"))
            append("::")
            append(tracks.take(40).joinToString("|") { it.videoId })
        }

        fun cachedOrder(): List<String>? {
            val (at, ordered) = cache[signature] ?: return null
            return ordered.takeIf { System.currentTimeMillis() - at < CACHE_TTL_MS }
        }

        cachedOrder()?.let { ordered ->
            return RerankResult(applyOrder(tracks, ordered), aiApplied = true)
        }
        val ranked = try {
            singleFlight.run("rerank:$signature") {
                cachedOrder() ?: if (reserveProviderSlot()) {
                    // Reserve before the request. A timeout must not let every
                    // waiting caller immediately hammer the provider.
                    withTimeoutOrNull(requestTimeoutMs) { callModel(tracks) }
                } else {
                    null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.info("AI rerank skipped: {}", e.message)
            null
        } ?: return fallback

        cache[signature] = System.currentTimeMillis() to ranked
        val result = applyOrder(tracks, ranked)
        return if (result.isEmpty()) fallback else RerankResult(result, aiApplied = true)
    }

    private fun applyOrder(tracks: List<TrackDto>, orderedIds: List<String>): List<TrackDto> {
        val byId = tracks.associateBy { it.videoId }
        return orderedIds.mapNotNull { byId[it] } + tracks.filter { it.videoId !in orderedIds }
    }

    /** Null means the provider failed or returned no usable known ids. */
    private suspend fun callModel(tracks: List<TrackDto>): List<String>? {
        val pool = tracks.take(PROMPT_CANDIDATES)
        val body = buildString {
            append("You are a music taste assistant. Rank these songs by how well they fit a personal radio for someone whose favorites/artists produced this pool. ")
            append("Return ONLY the video ids, one per line, best first, no commentary, no duplicates, no extra text:\n")
            pool.forEach { t ->
                append(t.videoId).append("|").append(t.title).append(" — ").append(t.uploader).append('\n')
            }
        }
        val req = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage("system", "You rank songs. Reply with video ids only, one per line, in preference order."),
                ChatMessage("user", body),
            ),
        )
        val requestBody = json.encodeToString(req)
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val chatUrl = "$normalizedBase/chat/completions"
        val (status, responseBody) = if (postChat != null) {
            postChat.invoke(chatUrl, requestBody, apiKey.orEmpty())
        } else {
            val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(chatUrl))
                .timeout(java.time.Duration.ofMillis(requestTimeoutMs))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer ${apiKey.orEmpty()}")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
            // Async keeps the caller's coroutine free: withTimeout can abandon
            // the wait immediately instead of pinning a blocking IO worker
            // until the socket's own (bounded) timeout expires.
            val resp = http.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofString()).await()
            resp.statusCode() to resp.body()
        }
        if (status != 200) {
            logger.info("AI rerank HTTP {}: {}", status, responseBody.take(160))
            return null
        }
        val parsed = json.decodeFromString<ChatResponse>(responseBody)
        val sentences = parsed.choices.firstOrNull()?.message?.content ?: return null
        val ids = Regex("""[A-Za-z0-9_-]{6,}""").findAll(sentences).map { it.value }.toList()
        val known = tracks.map { it.videoId }.toSet()
        return ids.filter { it in known }.distinct().takeIf { it.isNotEmpty() }
    }
}
