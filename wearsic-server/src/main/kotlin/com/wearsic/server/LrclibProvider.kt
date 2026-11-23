package com.wearsic.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * LRCLIB (https://lrclib.net) provider — a free, community-maintained lyrics
 * API that serves both synced LRC and plain lyrics from one GET. Primary
 * source for the server's lyrics service.
 *
 * The request is a single GET keyed by artist/title/duration (which is also
 * how LRCLIB disambiguates versions of the same song), so the cost is one
 * round trip per cache miss.
 */
class LrclibProvider(
    private val client: HttpClient,
    private val timeoutMs: Long = 8_000L,
) : LyricsProvider {

    override suspend fun fetch(query: LyricsQuery): LyricsFetchResult {
        if (query.title.isBlank() && query.artist.isBlank()) return LyricsFetchResult.NotAvailable
        val url = buildString {
            append("https://lrclib.net/api/get?")
            append("artist_name=")
            append(URLEncoder.encode(query.artist, "UTF-8"))
            append("&track_name=")
            append(URLEncoder.encode(query.title, "UTF-8"))
            append("&duration=")
            // Whole seconds: LRCLIB matches on seconds, and the watch's
            // duration is ms. Rounding down avoids a one-second miss.
            append((query.durationMs / 1000L).toString())
        }
        val resp = try {
            // Hard deadline: a stalled provider must never hold the route
            // past its own timeout (the shared client's socket timeout is
            // far longer than a lyrics lookup should ever take).
            withTimeoutOrNull(timeoutMs) { client.get(url) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return LyricsFetchResult.NotAvailable
        } ?: return LyricsFetchResult.NotAvailable
        if (resp.status != HttpStatusCode.OK) return LyricsFetchResult.NotAvailable
        val body = try {
            resp.bodyAsText()
        } catch (_: Exception) {
            return LyricsFetchResult.NotAvailable
        }
        val json = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return LyricsFetchResult.NotAvailable
        }
        // Instrumentals have no singable lyrics.
        if (json["instrumental"]?.jsonPrimitive?.booleanOrNull == true) {
            return LyricsFetchResult.NotAvailable
        }
        val synced = json["syncedLyrics"]?.jsonPrimitive?.contentOrNull
        if (!synced.isNullOrBlank()) {
            // Prefer synced; if the LRC fails to parse (provider returned a
            // nonstandard blob), fall through to the plain text.
            if (LrcParser.parse(synced).complete) {
                return LyricsFetchResult.Synced(synced, "lrclib")
            }
        }
        val plain = json["plainLyrics"]?.jsonPrimitive?.contentOrNull
        if (!plain.isNullOrBlank()) {
            return LyricsFetchResult.Plain(plain, "lrclib")
        }
        return LyricsFetchResult.NotAvailable
    }
}
