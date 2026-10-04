package com.wearsic.app.media

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.wearsic.app.StartupDiagnostics
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class WearsicPlaybackController(
    private val context: Context,
    /**
     * Connect to the media session at startup. Startup-recovery mode (the
     * previous process died during cold start) defers this until the first
     * playback action so a wedged/broken session can never again hold the UI
     * hostage on the opening screen; playback commands reconnect on demand.
     */
    private val eagerConnect: Boolean = true
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var positionUpdateJob: Job? = null
    private var pendingPlay: Pair<List<Track>, Int>? = null
    private var reconnectAttempts = 0
    private var playbackRetryAttempts = 0
    private var lastPreviousTapTimeMs = 0L
    private var pendingPreviousTap = false
    private var lastTrackIndex = -1

    /**
     * Queue order before shuffle was enabled — retained so toggling shuffle
     * off can restore the original order instead of leaving the shuffled one.
     */
    private var originalQueueOrder: List<Track> = emptyList()

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val MAX_PLAYBACK_RETRIES = 3
        private const val PREVIOUS_DOUBLE_TAP_WINDOW_MS = 1500L

        /**
         * Bound on how long storage code waits for the session connection
         * outcome. Media3's buildAsync future is documented to occasionally
         * never complete (released session mid-connect, binder stall) —
         * waiting forever on it hung the app on the splash screen.
         */
        private const val INITIAL_CONNECTION_TIMEOUT_MS = 15_000L
    }

    /**
     * Resolved when the controller has either connected to the media session
     * (true — its current track, if any, is then reflected in [uiState]) or
     * definitively failed to connect / was released (false). Storage code uses
     * this to know when playback state is trustworthy before touching files.
     */
    private val initialConnectionKnown = CompletableDeferred<Boolean>()
    private var connectionWatchdogJob: Job? = null

    /**
     * Suspends until the session connection outcome is known (see
     * [initialConnectionKnown]). On true, [uiState] reflects the real player;
     * on false nothing is (or will be) playing through this controller.
     */
    suspend fun awaitInitialConnection(): Boolean = initialConnectionKnown.await()

    /**
     * Like [awaitInitialConnection] but bounded: resolves within
     * [timeoutMs] or reports "unknown" (false) so callers can never be stuck
     * forever by a wedged session connection.
     */
    suspend fun awaitInitialConnectionOrTimeout(
        timeoutMs: Long = INITIAL_CONNECTION_TIMEOUT_MS
    ): Boolean = try {
        withTimeoutOrNull(timeoutMs) { initialConnectionKnown.await() } ?: false
    } catch (_: Exception) {
        false
    }

    private val _uiState = MutableStateFlow(
        PlaybackUiState(
            playlist = emptyList()
        )
    )
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateStateFromPlayer(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startPositionTracker()
            } else {
                stopPositionTracker()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isTransient = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

            if (isTransient && playbackRetryAttempts < MAX_PLAYBACK_RETRIES) {
                // Tunnel connections drop mid-stream: retry and let ExoPlayer
                // resume from the bytes already held in its memory buffer.
                playbackRetryAttempts++
                val attempt = playbackRetryAttempts
                _uiState.update { it.copy(isBuffering = true) }
                scope.launch {
                    delay(1500L * attempt)
                    val controller = mediaController
                    if (controller != null && controller.mediaItemCount > 0) {
                        controller.prepare()
                        controller.play()
                    }
                }
                return
            }
            playbackRetryAttempts = 0

            // Walk the cause chain to the root for a useful diagnostic.
            var deepest: Throwable? = error.cause
            while (deepest?.cause != null) deepest = deepest.cause
            val detail = deepest?.message
                ?: error.cause?.message
                ?: error.message
            _uiState.update { current ->
                current.copy(
                    isPlaying = false,
                    isBuffering = false,
                    playbackError = if (!detail.isNullOrBlank()) {
                        "Playback error (${error.errorCodeName}): ${detail.substringBefore('\n')}"
                    } else {
                        "Playback error: ${error.errorCodeName}"
                    }
                )
            }
        }
    }

    init {
        StartupDiagnostics.log(context, "controller-init (eager=$eagerConnect)")
        if (eagerConnect) {
            initializeController()
        } else {
            // Nothing is (or can be) playing through this controller yet —
            // the old process died with its player. Treat the outcome as
            // known so startup reconciliation can proceed immediately.
            initialConnectionKnown.complete(false)
        }
    }

    fun initializeController() {
        connect()
    }

    private fun connect() {
        if (mediaController != null || controllerFuture != null) return

        if (android.os.Build.FINGERPRINT == "robolectric" || android.os.Build.HARDWARE == "robolectric") {
            // In Robolectric test environments, Media3 SessionService connection lacks a live OS binder
            initialConnectionKnown.complete(false)
            return
        }

        try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, WearsicMediaService::class.java)
            )

            StartupDiagnostics.log(context, "controller-connecting")
            val future = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture = future

            // Watchdog: if this future never completes (a real Media3
            // failure mode after a session teardown/stale binder), release it
            // and schedule reconnects instead of leaving the app permanently
            // unconnected with a pending play request. releaseFuture is
            // non-blocking (cancel + getDone paths only) so this is safe on
            // the main thread, and the outcome is journaled for diagnostics.
            connectionWatchdogJob?.cancel()
            connectionWatchdogJob = scope.launch {
                delay(INITIAL_CONNECTION_TIMEOUT_MS)
                if (controllerFuture === future && mediaController == null) {
                    StartupDiagnostics.log(context, "controller-connect-watchdog-timeout")
                    runCatching { MediaController.releaseFuture(future) }
                    if (controllerFuture === future) controllerFuture = null
                    // Mark the initial outcome known (false) only the first
                    // time; after a session teardown the deferred is already
                    // complete and reconnects just restore the controller.
                    initialConnectionKnown.complete(false)
                    scheduleReconnect()
                }
            }

            future.addListener(
                {
                    controllerFuture = null
                    try {
                        val controller = future.get()
                        mediaController = controller
                        reconnectAttempts = 0
                        controller.addListener(playerListener)
                        updateStateFromPlayer(controller)
                        refreshOutputDevice()
                        StartupDiagnostics.log(context, "controller-connected")
                        // Flush any play request that arrived while connecting
                        pendingPlay?.let { (tracks, startIndex) ->
                            pendingPlay = null
                            playTracks(tracks, startIndex)
                        }
                        initialConnectionKnown.complete(true)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(playbackError = "Could not connect to media service") }
                        StartupDiagnostics.log(context, "controller-connect-failed")
                        scheduleReconnect()
                    }
                },
                MoreExecutors.directExecutor()
            )
        } catch (e: Exception) {
            _uiState.update { it.copy(playbackError = "Media service unavailable in test environment") }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            // No more attempts: the connection outcome is final (nothing is or
            // will be playing through this controller). Playback commands
            // still reconnect on demand via connect().
            StartupDiagnostics.log(context, "controller-gave-up-after-$reconnectAttempts")
            initialConnectionKnown.complete(false)
            return
        }
        reconnectAttempts++
        scope.launch {
            delay(1000L * reconnectAttempts)
            connect()
        }
    }

    private fun updateStateFromPlayer(player: Player) {
        val currentMediaItem = player.currentMediaItem
        val currentIdx = player.currentMediaItemIndex
        if (currentIdx != lastTrackIndex) {
            lastTrackIndex = currentIdx
            pendingPreviousTap = false
        }
        val isPlaying = player.isPlaying
        val isBuffering = player.playbackState == Player.STATE_BUFFERING
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        // Propagate C.TIME_UNSET for unknown durations instead of flattening
        // it to 0 — the UI renders unknown as "--:--" rather than a fake 0:00.
        val duration = player.duration

        val activePlaylist = _uiState.value.playlist
        val currentTrack = if (currentIdx in activePlaylist.indices) {
            val base = activePlaylist[currentIdx]
            val meta = currentMediaItem?.mediaMetadata
            base.copy(
                title = meta?.title?.toString() ?: base.title,
                artist = meta?.artist?.toString() ?: base.artist,
                artworkUrl = meta?.artworkUri?.toString() ?: base.artworkUrl,
                durationMs = if (duration > 0) duration else base.durationMs,
                isFavorite = _uiState.value.currentTrack.isFavorite.takeIf { it && _uiState.value.currentTrackIndex == currentIdx } ?: base.isFavorite
            )
        } else {
            val meta = currentMediaItem?.mediaMetadata
            if (meta != null && !meta.title.isNullOrBlank()) {
                Track(
                    id = currentMediaItem.mediaId.ifBlank { "current" },
                    title = meta.title.toString(),
                    artist = meta.artist?.toString() ?: "Unknown Artist",
                    album = meta.albumTitle?.toString() ?: "",
                    artworkUrl = meta.artworkUri?.toString(),
                    durationMs = duration
                )
            } else {
                activePlaylist.firstOrNull() ?: Track()
            }
        }

        _uiState.update { current ->
            val next = current.copy(
                currentTrack = currentTrack,
                currentTrackIndex = currentIdx,
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                currentPositionMs = currentPos,
                durationMs = duration,
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem()
            )
            // Skip redundant emissions: the player fires many events that do
            // not change observable state. Re-emitting identical state makes
            // every collector recompose, which burns battery on the watch.
            if (next != current) next else current
        }
    }

    private fun startPositionTracker() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            var idleTicks = 0
            var lastEmittedSecond = -1L
            while (isActive) {
                val controller = mediaController
                if (controller != null && controller.isPlaying) {
                    idleTicks = 0
                    val pos = controller.currentPosition.coerceAtLeast(0L)
                    val dur = if (controller.duration > 0) controller.duration else _uiState.value.durationMs
                    // 2s cadence + same-second dedupe: halves recompositions
                    // (3600→1800/hr) and Canvas redraws. 2s steps are invisible
                    // on a 1.2" round screen but let the SoC sleep twice as long.
                    val second = pos / 2000L
                    if (second != lastEmittedSecond) {
                        lastEmittedSecond = second
                        _uiState.update { it.copy(currentPositionMs = pos, durationMs = dur) }
                    }
                } else {
                    // Not playing (paused/ended): stop ticking entirely to save
                    // battery instead of looping forever.
                    idleTicks++
                    if (idleTicks >= 2) break
                }
                delay(2000L) // 2s cadence: smooth-enough progress, minimal wakeups
            }
        }
    }

    private fun stopPositionTracker() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    fun playTrack(track: Track) {
        val tracks = listOf(track)
        playTracks(tracks, 0)
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return

        val controller = mediaController
        if (controller == null) {
            // Controller is still connecting — queue the play request instead of
            // silently dropping it. It is flushed in connect() once ready.
            pendingPlay = tracks to startIndex
            connect()
            return
        }
        pendingPlay = null
        playbackRetryAttempts = 0
        pendingPreviousTap = false

        // A fresh queue is not shuffled — drop any retained pre-shuffle order.
        originalQueueOrder = emptyList()

        val items = WearsicMediaItemFactory.buildMediaItems(tracks)
        controller.stop()
        controller.clearMediaItems()
        controller.setMediaItems(items, startIndex, 0L)
        controller.repeatMode = Player.REPEAT_MODE_OFF
        controller.prepare()
        controller.play()
        val currentTrack = tracks.getOrNull(startIndex) ?: tracks.first()
        _uiState.update { current ->
            current.copy(
                currentTrack = currentTrack,
                playlist = tracks,
                currentTrackIndex = startIndex,
                isPlaying = true,
                currentPositionMs = 0L,
                // C.TIME_UNSET is Media3's canonical "unknown duration". A fake
                // value used to leak into progress/seek math and the session.
                durationMs = if (currentTrack.durationMs > 0) currentTrack.durationMs else androidx.media3.common.C.TIME_UNSET,
                playbackError = null
            )
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val controller = mediaController
        if (controller == null) {
            playTracks(tracks, 0)
            return
        }
        if (controller.mediaItemCount == 0) {
            playTracks(tracks, 0)
            return
        }

        val items = WearsicMediaItemFactory.buildMediaItems(tracks)
        controller.addMediaItems(items)
        _uiState.update { current ->
            current.copy(
                playlist = current.playlist + tracks,
                playbackError = null
            )
        }
    }

    fun removeFromQueue(index: Int) {
        val controller = mediaController ?: return
        if (index !in 0 until controller.mediaItemCount) return

        controller.removeMediaItem(index)
        _uiState.update { current ->
            current.copy(
                playlist = current.playlist.filterIndexed { i, _ -> i != index }
            )
        }
    }

    fun seekToQueueItem(index: Int) {
        val controller = mediaController ?: return
        if (index !in 0 until controller.mediaItemCount) return

        pendingPreviousTap = false
        controller.seekTo(index, 0L)
        if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
            controller.prepare()
        }
        controller.play()
        val queuedTrack = _uiState.value.playlist.getOrNull(index)
        _uiState.update { current ->
            current.copy(
                currentTrack = queuedTrack ?: current.currentTrack,
                currentTrackIndex = index,
                isPlaying = true,
                currentPositionMs = 0L,
                playbackError = null
            )
        }
    }

    fun clearQueue() {
        val controller = mediaController ?: return
        controller.stop()
        controller.clearMediaItems()
        _uiState.update {
            it.copy(
                currentTrack = Track(),
                playlist = emptyList(),
                currentTrackIndex = 0,
                isPlaying = false,
                isBuffering = false,
                currentPositionMs = 0L,
                durationMs = 0L,
                hasNext = false,
                hasPrevious = false,
                playbackError = null
            )
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_IDLE || controller.playbackState == Player.STATE_ENDED) {
                controller.prepare()
            }
            controller.play()
        }
    }

    fun skipToNext() {
        mediaController?.let { controller ->
            if (controller.hasNextMediaItem()) {
                controller.seekToNextMediaItem()
            } else {
                // End of queue. With repeat-all the player itself wraps
                // around, so this branch only runs with repeat OFF/ONE and
                // the correct behavior is to STOP — not to silently restart
                // the queue from index 0.
                controller.pause()
                controller.seekTo(controller.currentMediaItemIndex, 0L)
                _uiState.update { it.copy(isPlaying = false, currentPositionMs = 0L) }
            }
        }
    }

    fun skipToPrevious() {
        mediaController?.let { controller ->
            val now = SystemClock.uptimeMillis()

            // Second tap within the window: go to the actual previous track.
            if (pendingPreviousTap && now - lastPreviousTapTimeMs < PREVIOUS_DOUBLE_TAP_WINDOW_MS) {
                pendingPreviousTap = false
                if (controller.hasPreviousMediaItem()) {
                    controller.seekToPreviousMediaItem()
                } else {
                    controller.seekTo(0L)
                }
                return
            }

            // First tap: always restart the current song from the beginning and
            // arm the double-tap so a second tap goes to the previous track.
            pendingPreviousTap = true
            lastPreviousTapTimeMs = now
            controller.seekTo(0L)
            _uiState.update { it.copy(currentPositionMs = 0L) }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.let { controller ->
            // C.TIME_UNSET (unknown duration) must not clamp the target: only
            // clamp against a real, positive duration.
            val maxPos = controller.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            val clamped = positionMs.coerceIn(0L, maxPos)
            controller.seekTo(clamped)
            _uiState.update { it.copy(currentPositionMs = clamped) }
        }
    }

    fun seekForward(ms: Long = 5000L) {
        mediaController?.let { controller ->
            val maxPos = controller.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            val newPos = (controller.currentPosition + ms).coerceAtMost(maxPos)
            controller.seekTo(newPos)
            _uiState.update { it.copy(currentPositionMs = newPos) }
        }
    }

    fun seekBack(ms: Long = 5000L) {
        mediaController?.let { controller ->
            val newPos = (controller.currentPosition - ms).coerceAtLeast(0L)
            controller.seekTo(newPos)
            _uiState.update { it.copy(currentPositionMs = newPos) }
        }
    }

    /**
     * SHUFFLE: reorders the upcoming portion of the queue (current song stays
     * first and keeps playing). Toggling off restores the pre-shuffle order.
     */
    fun toggleShuffle() {
        val controller = mediaController ?: return
        val current = _uiState.value
        if (current.playlist.size <= 1) return

        val enabled = !current.shuffleEnabled
        val idx = current.currentTrackIndex.coerceIn(0, current.playlist.lastIndex)
        val currentItem = current.playlist[idx]
        val position = controller.currentPosition
        val wasPlaying = controller.isPlaying

        val newList: List<Track>
        var newIndex: Int
        if (enabled) {
            // Remember the original order so shuffle-off can restore it, and
            // start the shuffled portion AFTER the current track (the current
            // song does not jump around while it is playing).
            originalQueueOrder = current.playlist
            newList = listOf(currentItem) + current.playlist
                .filterIndexed { i, _ -> i != idx }
                .shuffled()
            newIndex = 0
        } else {
            // Restore the retained pre-shuffle order, keeping the current
            // song at its position in playback terms (it becomes the index of
            // its original position, or 0 if the queue changed underneath).
            val restored = originalQueueOrder.ifEmpty { current.playlist }
            newList = restored
            newIndex = restored.indexOfFirst { it.id == currentItem.id }.takeIf { it >= 0 } ?: 0
        }

        val items = WearsicMediaItemFactory.buildMediaItems(newList)
        controller.stop()
        controller.clearMediaItems()
        controller.setMediaItems(items, newIndex, position)
        controller.repeatMode = current.repeatMode
        controller.prepare()
        if (wasPlaying) controller.play()

        _uiState.update {
            it.copy(
                playlist = newList,
                currentTrackIndex = newIndex,
                currentPositionMs = position,
                shuffleEnabled = enabled
            )
        }
    }

    /** REPEAT: cycles OFF -> ALL -> ONE using ExoPlayer modes. */
    fun cycleRepeatMode() {
        val controller = mediaController ?: return
        val next = when (_uiState.value.repeatMode) {
            androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ALL
            androidx.media3.common.Player.REPEAT_MODE_ALL -> androidx.media3.common.Player.REPEAT_MODE_ONE
            else -> androidx.media3.common.Player.REPEAT_MODE_OFF
        }
        controller.repeatMode = next
        _uiState.update { it.copy(repeatMode = next) }
    }

    fun setVolumeScale(volume: Float) {
        mediaController?.volume = volume.coerceIn(0f, 1f)
    }

    fun pause() {
        mediaController?.pause()
    }

    fun toggleFavorite() {
        _uiState.update { current ->
            val updatedFavorite = !current.currentTrack.isFavorite
            val updatedTrack = current.currentTrack.copy(isFavorite = updatedFavorite)
            current.copy(currentTrack = updatedTrack)
        }
    }

    fun setCurrentTrackFavorite(isFavorite: Boolean) {
        _uiState.update { current ->
            current.copy(currentTrack = current.currentTrack.copy(isFavorite = isFavorite))
        }
    }

    fun refreshOutputDevice() {
        val output = AudioOutputHelper.getCurrentOutputInfo(context)
        _uiState.update {
            it.copy(
                outputDeviceName = output.name,
                isBluetoothConnected = output.isBluetooth
            )
        }
    }

    fun release() {
        initialConnectionKnown.complete(false)
        stopPositionTracker()
        try {
            scope.cancel()
        } catch (_: Exception) {}
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}
