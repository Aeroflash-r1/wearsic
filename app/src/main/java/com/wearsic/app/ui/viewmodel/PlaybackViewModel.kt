package com.wearsic.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wearsic.app.media.WearsicPlaybackController
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel responsible for playback state and control.
 * Handles all player operations: play, pause, skip, seek, shuffle, repeat, volume.
 */
class PlaybackViewModel(application: Application) : AndroidViewModel(application) {

    private val playbackController = WearsicPlaybackController(
        application.applicationContext,
        eagerConnect = true
    )

    val uiState: StateFlow<PlaybackUiState> = playbackController.uiState

    // ============================================================================
    // Playback Control
    // ============================================================================

    fun playTrack(track: Track) {
        playbackController.playTrack(track)
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        playbackController.playTracks(tracks, startIndex)
    }

    fun addToQueue(track: Track) {
        playbackController.addToQueue(listOf(track))
    }

    fun addToQueue(tracks: List<Track>) {
        playbackController.addToQueue(tracks)
    }

    fun removeFromQueue(index: Int) {
        playbackController.removeFromQueue(index)
    }

    fun seekToQueueItem(index: Int) {
        playbackController.seekToQueueItem(index)
    }

    fun clearQueue() {
        playbackController.clearQueue()
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun pause() {
        playbackController.pause()
    }

    fun skipToNext() {
        playbackController.skipToNext()
    }

    fun skipToPrevious() {
        playbackController.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun seekForward() {
        playbackController.seekForward()
    }

    fun seekBack() {
        playbackController.seekBack()
    }

    // ============================================================================
    // Playback Settings
    // ============================================================================

    fun toggleShuffle() {
        playbackController.toggleShuffle()
    }

    fun cycleRepeatMode() {
        playbackController.cycleRepeatMode()
    }

    fun setVolumeScale(volume: Float) {
        playbackController.setVolumeScale(volume.coerceIn(0f, 1f))
    }

    // ============================================================================
    // Track Metadata
    // ============================================================================

    fun toggleFavorite() {
        playbackController.toggleFavorite()
    }

    fun setCurrentTrackFavorite(isFavorite: Boolean) {
        playbackController.setCurrentTrackFavorite(isFavorite)
    }

    // ============================================================================
    // Output Device
    // ============================================================================

    fun refreshOutputDevice() {
        playbackController.refreshOutputDevice()
    }

    // ============================================================================
    // Tile Actions
    // ============================================================================

    fun handleTileAction(action: String) {
        when (action) {
            "prev" -> skipToPrevious()
            "next" -> skipToNext()
            "toggle" -> togglePlayPause()
        }
    }

    // ============================================================================
    // Lifecycle
    // ============================================================================

    override fun onCleared() {
        playbackController.release()
        super.onCleared()
    }
}
