package com.wearsic.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OPTIONAL "AI" re-ranker for recommendations ("Made for this moment").
 *
 * Works with ANY OpenAI-compatible chat-completions provider — OpenAI, Groq,
 * Together, local Ollama/LM Studio, etc. Configure via env vars:
 *
 *   WEARSIC_AI_API_KEY     required key (Groq/OpenAI/…)
 *   WEARSIC_AI_BASE_URL    default https://api.openai.com/v1
 *                          (Groq: https://api.groq.com/openai/v1 — note the
 *                          trailing /v1 is required by OpenAI-compatible
 *                          servers; we append /chat/completions to it)
 *   WEARSIC_AI_MODEL       default gpt-4o-mini
 *                          (Groq: llama-3.3-70b-versatile, etc.)
 *
 * AI spend is deliberately COMPACT:
 *  - the model is only invoked on explicit refresh (`force = true`), never
 *    on automatic app-open loads or "Show more" — those use genre scoring;
 *  - a global throttle allows at most ONE provider call every 5 minutes;
 *  - concurrent identical requests share one in-flight call (single-flight);
 *  - the prompt ships only ~12 candidates (id + title — artist), and
 *    `max_tokens` is capped at 96;
 *  - results are cached 30 minutes per candidate signature.
 *
 * It is still an ENHANCEMENT, never the primary path: any failure, timeout
 * or unusable reply falls back to the engine's own ranking, and the AI can
 * never inject tracks outside the candidate pool.
 */
class AiReRanker(
    private val apiKey: String?,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini",
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

    companion object {
        /** Minimum interval between provider calls, globally. */
        private const val THROTTLE_MS = 5 * 60 * 1000L
        /** TTL of a re-rank result in the cache. */
        private const val CACHE_TTL_MS = 30 * 60 * 1000L
        private const val MIN_POOL = 6
        private const val PROMPT_CANDIDATES = 12
    }

    /** Signature -> (cachedAt, ordered ids). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val singleFlight = SingleFlight<String, List<String>>()

    @Volatile
    private var nextAllowedAtMs = 0L

    val isConfigured: Boolean get() = !apiKey.isNullOrBlank()

    /**
     * Re-order [tracks] by preference. [force] gates the provider call: only
     * an explicit user refresh sets it true; automatic loads skip AI and use
     * the classic ranking immediately. Returns the same pool, reordered.
     */
    suspend fun rerank(
        tracks: List<TrackDto>,
        seedIds: List<String>,
        topArtists: Set<String>,
        genres: List<String> = emptyList(),
        force: Boolean = false,
    ): List<TrackDto> {
        if (tracks.size < MIN_POOL || !isConfigured || !force) return tracks

        // Signature uses ONLY pool ids + taste (not titles), so a re-rank is
        // reused even when titles are annotated differently later.
        val signature = buildString {
            append(seedIds.sorted().joinToString("|"))
            append("::")
            append(topArtists.sorted().joinToString("|"))
            append("::")
            append(genres.sorted().joinToString("|"))
            append("::")
            append(tracks.take(40).joinToString("|") { it.videoId })
        }

        val now = System.currentTimeMillis()
        cache[signature]?.let { (at, ordered) ->
            if (now - at < CACHE_TTL_MS) {
                return ordered.mapNotNull { id -> tracks.firstOrNull { it.videoId == id } }.ifEmpty { tracks }
            }
        }

        // Global throttle: never hammer the provider more than once per
        // interval no matter how many requests arrive.
        if (now < nextAllowedAtMs) return tracks

        val ranked = try {
            singleFlight.run("rerank:$signature") {
                if (System.currentTimeMillis() < nextAllowedAtMs) {
                    tracks.map { it.videoId }
                } else {
                    withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                            val result = callModel(tracks)
                            nextAllowedAtMs = System.currentTimeMillis() + THROTTLE_MS
                            result
                        } ?: tracks.map { it.videoId }
                    }
                }
            }
        } catch (e: Exception) {
            logger.info("AI rerank skipped: {}", e.message)
            tracks.map { it.videoId }
        }

        if (System.currentTimeMillis() - now < CACHE_TTL_MS) {
            cache[signature] = System.currentTimeMillis() to ranked
        }
        val byId = tracks.associateBy { it.videoId }
        val result = ranked.mapNotNull { byId[it] } + tracks.filter { it.videoId !in ranked }
        return result.ifEmpty { tracks }
    }

    private suspend fun callModel(tracks: List<TrackDto>): List<String> {
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
        val http = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(4))
            .build()
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val chatUrl = "$normalizedBase/chat/completions"
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(chatUrl))
            .timeout(java.time.Duration.ofSeconds(4))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.encodeToString(req)))
            .build()
        val resp = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) {
            logger.info("AI rerank HTTP {}: {}", resp.statusCode(), resp.body().take(160))
            return tracks.map { it.videoId }
        }
        val parsed = json.decodeFromString<ChatResponse>(resp.body())
        val sentences = parsed.choices.firstOrNull()?.message?.content ?: ""
        val ids = Regex("""[A-Za-z0-9_-]{6,}""").findAll(sentences).map { it.value }.toList()
        val known = tracks.map { it.videoId }.toSet()
        return ids.filter { it in known }
    }
}
