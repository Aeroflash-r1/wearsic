package com.wearsic.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.di.AppContainer
import com.wearsic.app.model.LyricLine
import com.wearsic.app.model.LyricsMode
import com.wearsic.app.model.Track
import com.wearsic.app.model.displayMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lyrics state for the player's scroll-up lyrics view.
 *
 * Deliberately lazy: nothing is fetched until the user actually reveals the
 * lyrics (on-demand, once per track). A track change resets the state; the
 * same track never re-fetches (the server caches too).
 */
class LyricsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as? com.wearsic.app.WearsicApp)?.container
        ?: AppContainer(application)
    private val musicRepository = container.musicRepository
    private val lyricsStore = container.lyricsStore
    private val preferencesRepository = container.preferencesRepository

    data class LyricsUiState(
        val trackId: String = "",
        val mode: LyricsMode = LyricsMode.Unavailable,
        val source: String? = null,
        val lines: List<LyricLine> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    ) {
        val showRetry: Boolean
            get() = errorMessage != null ||
                (trackId.isNotBlank() && !isLoading && mode == LyricsMode.Unavailable)
    }

    private val _state = MutableStateFlow(LyricsUiState())
    val state: StateFlow<LyricsUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Fetch lyrics for [track] once. Safe to call on every reveal. */
    fun ensureLyrics(track: Track) {
        val id = track.id
        if (id.isBlank()) return
        val current = _state.value
        // Already loading, or already resolved (including "unavailable") —
        // do not re-fetch. An error state re-fetches on the next reveal.
        if (current.trackId == id && (current.isLoading || current.errorMessage == null)) return
        job?.cancel()
        _state.value = LyricsUiState(trackId = id, isLoading = true)
        job = viewModelScope.launch {
            // OFFLINE-FIRST: a saved lyrics row answers instantly with no
            // network — this is what makes downloaded songs show lyrics when
            // the phone server is out of reach. Dead-end results are never
            // cached (an empty cache here always means "not saved yet").
            val cached = lyricsStore.get(track.id)
            if (cached != null) {
                _state.value = LyricsUiState(
                    trackId = id,
                    mode = cached.mode,
                    source = cached.source,
                    lines = cached.lines,
                )
                return@launch
            }
            musicRepository.fetchLyrics(track)
                .onSuccess { lyrics ->
                    _state.value = LyricsUiState(
                        trackId = id,
                        mode = lyrics.mode,
                        source = lyrics.source,
                        lines = lyrics.lines,
                    )
                    // "Everywhere" mode additionally caches on open, so a
                    // song the user streams (and once views the lyrics for)
                    // keeps them available offline afterwards.
                    val saveMode = preferencesRepository.lyricsDownloadModeFlow.first()
                    if (saveMode >= com.wearsic.app.data.WearsicPreferencesRepository.LYRICS_MODE_ALWAYS) {
                        lyricsStore.save(track, lyrics)
                    }
                }
                .onFailure { err ->
                    _state.value = LyricsUiState(
                        trackId = id,
                        errorMessage = err.displayMessage("Could not load lyrics"),
                    )
                }
        }
    }

    /** Retry after an error (or an "unavailable" dead-end). */
    fun retry(track: Track) {
        job?.cancel()
        _state.value = LyricsUiState()
        ensureLyrics(track)
    }

    /** Drop stale results when the track changes (called by the player). */
    fun invalidateIfStale(trackId: String) {
        if (_state.value.trackId != trackId) {
            _state.value = LyricsUiState()
        }
    }
}
