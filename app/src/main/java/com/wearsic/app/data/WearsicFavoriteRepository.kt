package com.wearsic.app.data

import android.content.Context
import com.wearsic.app.data.db.FavoriteDao
import com.wearsic.app.data.db.WearsicDatabase
import com.wearsic.app.data.db.WearsicFavoriteTrackEntity
import com.wearsic.app.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * LOCAL-FIRST favorites store.
 *
 * The watch is the source of truth: hearts write to Room immediately (UI
 * responds in the same frame, works fully offline). The server copy is only
 * an optional backup sink — a best-effort, fire-and-forget push of the same
 * change, never blocking, never required. On connect, [importFromServer]
 * merges any server-side favorites the watch doesn't have yet, so a first
 * install after server-era favorites still sees them.
 */
class WearsicFavoriteRepository(
    context: Context,
    private val musicRepository: WearsicMusicRepository,
    private val favoriteDao: FavoriteDao = WearsicDatabase.getInstance(context).favoriteDao(),
) {

    private val backupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live local favorites, newest first. This is what the UI renders. */
    val favoriteTracksFlow: Flow<List<Track>> = favoriteDao.observeAll()
        .map { entities -> entities.map { it.toDomainTrack() } }

    /** Live set of favorited ids (for hearts everywhere). */
    val favoriteIdsFlow: Flow<Set<String>> = favoriteDao.observeAll()
        .map { entities -> entities.map { it.trackId }.toSet() }

    suspend fun isFavorite(trackId: String): Boolean = favoriteDao.getIds().contains(trackId)

    /** Local write + background backup push. Instant for the UI. */
    suspend fun addFavorite(track: Track) {
        if (track.id.isBlank()) return
        favoriteDao.upsert(
            WearsicFavoriteTrackEntity(
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                durationMs = track.durationMs,
                mediaUri = track.mediaUri,
            )
        )
        backupScope.launch {
            runCatching { musicRepository.addFavorite(track) }
        }
    }

    /** Local delete + background backup delete. Instant for the UI. */
    suspend fun removeFavorite(trackId: String) {
        if (trackId.isBlank()) return
        favoriteDao.deleteById(trackId)
        backupScope.launch {
            runCatching { musicRepository.removeFavorite(trackId) }
        }
    }

    /**
     * Merges the server's favorites into the local store (one-way: server ->
     * watch). Called on library refresh/startup and never blocks the UI —
     * it is entirely best-effort. Missing local favorites are added; the
     * local store is never overwritten or deleted by this call.
     */
    suspend fun importFromServer() {
        val url = musicRepository.getServerUrl()
        if (url.isBlank()) return
        // Best-effort: if the server is unreachable this fails fast (the HTTP
        // client's own timeouts apply) and we simply keep the local store.
        val result = musicRepository.getFavorites()
        val serverTracks = result.getOrNull() ?: return
        val localIds = favoriteDao.getIds().toSet()
        for (track in serverTracks) {
            if (track.id.isBlank() || track.id in localIds) continue
            favoriteDao.upsert(
                WearsicFavoriteTrackEntity(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artworkUrl = track.artworkUrl,
                    durationMs = track.durationMs,
                    mediaUri = track.mediaUri,
                )
            )
        }
    }
}
