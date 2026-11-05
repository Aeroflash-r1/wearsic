package com.wearsic.app.data.lyrics

import android.content.Context
import com.wearsic.app.data.db.LyricsDao
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.data.db.WearsicLyricsEntity
import com.wearsic.app.model.LyricLine
import com.wearsic.app.model.Lyrics
import com.wearsic.app.model.LyricsMode
import com.wearsic.app.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON codec for persisted lyric lines. Deliberately NOT the domain
 * [LyricLine] itself: the cached row shape stays decoupled from the domain
 * model, so a future model change (e.g. an added field) never silently
 * rewrites the schema of rows on disk. Pure — unit-tested without Android.
 */
object LyricsCacheCodec {

    @Serializable
    private data class CachedLine(val t: Long?, val s: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(lines: List<LyricLine>): String =
        json.encodeToString(ListSerializer(CachedLine.serializer()), lines.map { CachedLine(it.timeMs, it.text) })

    /** Null on any malformed row — callers just fall through to a fetch. */
    fun decode(raw: String): List<LyricLine>? = runCatching {
        json.decodeFromString(ListSerializer(CachedLine.serializer()), raw).map { LyricLine(it.t, it.s) }
    }.getOrNull()
}

/**
 * On-watch lyrics storage (Room `lyrics_cache`) behind the small surface the
 * app needs: [save] and [get]. Every write keeps the cache bounded
 * ([MAX_ROWS]) by evicting the oldest rows. All "song has no lyrics" states
 * are NEVER stored — a dead-end row would poison [get] with a permanent
 * negative answer (LRCLIB coverage grows over time).
 */
class LyricsStore(
    private val context: Context,
    private val dao: LyricsDao = WearsicDatabase.getInstance(context).lyricsDao(),
) {

    private companion object {
        /** Small text; a rounding error on watch storage. Cap only for safety. */
        const val MAX_ROWS = 250
    }

    /** Persists [lyrics] for [track]; a no-op for unavailable/dead responses. */
    suspend fun save(track: Track, lyrics: Lyrics) = withContext(Dispatchers.IO) {
        if (lyrics.mode == LyricsMode.Unavailable) return@withContext
        val lines = LyricsCacheCodec.encode(lyrics.lines)
        if (lines.isEmpty()) return@withContext
        runCatching {
            dao.upsert(
                WearsicLyricsEntity(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    mode = lyrics.mode.name,
                    source = lyrics.source,
                    linesJson = lines,
                    durationMs = track.durationMs,
                )
            )
            val overflow = dao.count() - MAX_ROWS
            if (overflow > 0) dao.deleteOldest(overflow)
        }.onFailure { it.printStackTrace() }
    }

    /** Cached lyrics or null. Decode failures are treated as a cache miss. */
    suspend fun get(trackId: String): Lyrics? = withContext(Dispatchers.IO) {
        runCatching { dao.getByTrackId(trackId) }.getOrNull()?.let { entity ->
            LyricsCacheCodec.decode(entity.linesJson)?.let { lines ->
                Lyrics(
                    mode = runCatching { LyricsMode.valueOf(entity.mode) }
                        .getOrDefault(LyricsMode.Plain),
                    source = entity.source,
                    lines = lines,
                    plainText = if (entity.mode == LyricsMode.Plain.name)
                        lines.joinToString("\n") { it.text } else null,
                )
            }
        }
    }

    suspend fun deleteForTrack(trackId: String) = withContext(Dispatchers.IO) {
        runCatching { dao.deleteByTrackId(trackId) }
    }
}
