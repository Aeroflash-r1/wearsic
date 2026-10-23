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

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        playbackController.moveQueueItem(fromIndex, toIndex)
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

    fun swapQueuedItemToLocalFile(trackId: String, localFilePath: String) {
        playbackController.swapQueuedItemToLocalFile(trackId, localFilePath)
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
        // Delegate: the controller now BUFFERS taps that arrive before the
        // MediaController is live (up to INITIAL_CONNECTION_TIMEOUT_MS) and
        // replays them once connected. The old direct switch was a silent
        // no-op whenever the tap landed during a cold start, so tile presses
        // regularly did nothing.
        playbackController.handleTileAction(action)
    }

    // ============================================================================
    // Lifecycle
    // ============================================================================

    /**
     * Releases the [WearsicPlaybackController] (MediaController + its coroutine
     * scope).
     *
     * Public because this ViewModel is constructed directly by
     * WearsicPlayerViewModel rather than through a ViewModelStore, so the
     * framework never calls [onCleared] on it — the parent has to drive
     * teardown explicitly. Safe to call more than once.
     */
    fun release() {
        playbackController.release()
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }
}
