package com.wearsic.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Offline lyrics cache — one row per song whose lyrics have been saved.
 *
 * Writes happen only when the user's "Lyrics Downloads" setting allows it:
 * a download completion (any mode ≥ WITH_DOWNLOADS) or a lyrics-screen open
 * in ALWAYS mode. Reads happen in [com.wearsic.app.ui.viewmodel.LyricsViewModel]
 * BEFORE any network fetch, so a downloaded song shows its lyrics even when
 * the phone server is out of reach. Lines are stored as JSON to decouple the
 * persisted shape from the domain model (see [com.wearsic.app.data.lyrics.LyricsCacheCodec]).
 */
@Entity(tableName = "lyrics_cache")
data class WearsicLyricsEntity(
    @PrimaryKey val trackId: String,
    val title: String,
    val artist: String,
    /** [com.wearsic.app.model.LyricsMode] name — Synced or Plain only. */
    val mode: String,
    /** Provider provenance (informational), e.g. "LRCLIB". */
    val source: String?,
    /** JSON array of the lyric lines. */
    val linesJson: String,
    val durationMs: Long,
    val fetchedAt: Long = System.currentTimeMillis(),
)
