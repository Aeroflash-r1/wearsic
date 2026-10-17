package com.wearsic.app.media

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.wearsic.app.MainActivity
import com.wearsic.app.StartupDiagnostics
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.media.crossfade.WearsicCrossfadeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class WearsicMediaService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * The two decoders the crossfade feature can move the session between. One
     * of them is bound to [mediaSession] at any moment — the engine decides
     * which, and demotes the other to a silent standby at the end of a fade.
     */
    private var sessionPlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null

    private var crossfadeEngine: WearsicCrossfadeEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // One-time migration from the old persistent stream cache: no playback
        // path writes to it anymore, so wipe any leftover directory (pure dead
        // storage duplicating completed downloads). Runs on a background
        // thread — a large legacy cache must never block the process's main
        // thread during service start (main-thread disk IO at cold start is
        // exactly the kind of stall that shows up as a frozen opening screen).
        Thread {
            WearsicStreamDataSource.deleteLegacyStreamCache(this@WearsicMediaService)
        }.start()
        StartupDiagnostics.log(this, "media-service-created")

        // Media3's default media notification uses a system-managed playback
        // channel; no manual channel needed (and a manual one would leave
        // dead config behind once the custom provider is gone).

        // 1-5. Build the player, the session and the crossfade engine —
        //      shared with recreateSession().
        buildSession()
    }

    /**
     * Builds the session player and the [WearsicCrossfadeEngine] that may hand
     * the session over to a standby player mid-transition.
     *
     * Session Activity Intent note: Wear OS creates the media ongoing activity
     * (status ring) automatically from media notifications, and Media3
     * publishes the notification itself — so there is deliberately NO manual
     * OngoingActivity here. Applying one manually on top of Media3's
     * notification made the watch show TWO notifications (the media card + an
     * orphaned ongoing-activity card), because every metadata update re-posts
     * the notification without the manual ongoing-activity extras.
     */
    private fun buildSession() {
        val attributes = audioAttributes()
        val exoPlayer = buildPlayer(attributes, handleAudioFocus = true)
        this.sessionPlayer = exoPlayer

        val session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(playerSessionIntent())
            .build()
        mediaSession = session

        // The engine is rebuilt with the session: it reads the current player
        // through the session, never through a cached reference, so a rebuilt
        // session can never leave it ramping a released player's volume.
        crossfadeEngine?.release()
        crossfadeEngine = WearsicCrossfadeEngine(
            context = this,
            scope = serviceScope,
            session = session,
            audioAttributes = attributes,
            standbyFactory = ::ensureStandbyPlayer,
            crossfadeMsFlow = WearsicPreferencesRepository(this).crossfadeMsFlow
        ).also { it.start() }
    }

    /**
     * The crossfade standby decoder, created on first use — a watch that never
     * turns the feature on never pays for a second player's memory. It is
     * built WITHOUT audio focus handling on purpose: during a fade both
     * decoders are audible at once, and a second focus request from this same
     * process would make Media3 pause the outgoing one at the fade's first
     * frame. The engine transfers focus to it at the moment it takes over as
     * the session player.
     */
    @OptIn(UnstableApi::class)
    private fun ensureStandbyPlayer(): ExoPlayer =
        standbyPlayer ?: buildPlayer(audioAttributes(), handleAudioFocus = false)
            .also { standbyPlayer = it }

    /**
     * Tapping the media notification opens the PLAYER, not the library home
     * screen: the extra is read by MainActivity and routed by the nav host.
     * SINGLE_TOP so an already-running app receives it via onNewIntent instead
     * of stacking a second activity on top of the player.
     */
    private fun playerSessionIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Self-healing: if the session was torn down by a previous lifecycle
        // event (or lost by a process/surface hiccup) while the service is
        // still alive, rebuild it instead of returning null forever. Returning
        // null wedges every future MediaController.connect() — the relaunch
        // then hangs on the splash screen with no way to recover.
        if (mediaSession == null) {
            runCatching { recreateSession() }
        }
        return mediaSession
    }

    /**
     * User preference: swiping the app away from the recents list STOPS the
     * music. The player is stopped and its queue cleared BEFORE the default
     * Media3 handling runs, so the media notification disappears and the
     * service has nothing left to stay foregrounded for — Media3 then stops
     * the service itself, the session is released and the next launch starts
     * clean (the self-healing onGetSession + connect watchdog below make a
     * half-released session harmless anyway).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            // A fade in flight would otherwise keep the outgoing track (and
            // the standby decoder) audible for up to its remaining window.
            crossfadeEngine?.cancel()
            mediaSession?.player?.let { player ->
                player.stop()
                player.clearMediaItems()
            }
        } catch (_: Exception) {
            // Best-effort stop; the default handling still applies.
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        crossfadeEngine?.release()
        crossfadeEngine = null
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        // Both decoders are released: after a hand-off the session is bound to
        // the standby, so releasing only the session's player would leak the
        // other one for the life of the process.
        sessionPlayer?.release()
        sessionPlayer = null
        standbyPlayer?.release()
        standbyPlayer = null
        super.onDestroy()
    }

    /** Rebuilds players + session after a teardown, preserving nothing (the
     *  queue is gone with the old players; users re-select music). */
    private fun recreateSession() {
        crossfadeEngine?.release()
        crossfadeEngine = null
        sessionPlayer?.release()
        sessionPlayer = null
        standbyPlayer?.release()
        standbyPlayer = null
        buildSession()
    }

    /** 1. Audio attributes for battery-conscious music playback. */
    @OptIn(UnstableApi::class)
    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    /**
     * @param handleAudioFocus the session player owns audio focus; the
     *   crossfade standby is built without it (see [ensureStandbyPlayer]).
     */
    @OptIn(UnstableApi::class)
    private fun buildPlayer(audioAttributes: AudioAttributes, handleAudioFocus: Boolean): ExoPlayer {
        // 2. Media source factory. NO persistent disk cache: playback buffers
        //    in memory only, and the sole permanent local copy of a song is
        //    the single Auto/Manual download file.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            WearsicStreamDataSource.createDataSourceFactory(this)
        )

        // 3. Buffer policy: an in-memory window only (nothing is written to
        //    disk). These four numbers were the main reason playback felt
        //    sluggish on a watch:
        //
        //    - bufferForPlaybackMs 1500 -> 800: cold-start latency. The old
        //      value made every tap wait 1.5s of audio before the first
        //      sample, which reads as the app being slow even when the
        //      network is fast.
        //    - bufferForPlaybackAfterRebufferMs 5000 -> 2500: THIS was the
        //      real culprit for mid-song stalls. After any interruption the
        //      player sat silent for a full 5 seconds waiting to accumulate
        //      buffer. On a phone that reads as cautious; on a watch it
        //      reads as broken. 2.5s still guards against thrashing between
        //      stalling and resuming, so a flaky link isn't made worse.
        //    - maxBufferMs 30s -> 50s -> 90s: a deeper runway against WiFi
        //      jitter. A 4-minute AAC track at 128kbps is ~3.8MB, so 90s
        //      costs ~1.4MB of heap — affordable, and it means a track that
        //      starts buffering usually finishes before the buffer is gone.
        //    - targetBufferBytes 3MB as a hard backstop so a pathological
        //      bitrate can't grow the buffer without bound on a small heap,
        //      with time thresholds prioritised over that byte target.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15000,
                /* maxBufferMs = */ 90000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 2500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(3 * 1024 * 1024)
            .build()
        // 4. Player
        return ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            // WAKE_MODE_NETWORK (not LOCAL): streams arrive over WiFi while
            // the screen is off. LOCAL holds only the CPU wake lock — Wear
            // OS still dozes the radio between packets, the socket sits
            // silent, the buffer drains, and playback "stalls halfway".
            // NETWORK additionally holds the WifiLock for the whole
            // playback session; audio keeps flowing in the dark.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}