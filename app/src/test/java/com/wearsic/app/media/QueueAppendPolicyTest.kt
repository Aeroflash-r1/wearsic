package com.wearsic.app.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The queue-append policy: adding tracks (queue radio, "Queue" action) must
 * NEVER change the song that is playing. The bug this pins: tapping radio
 * during a session reconnect (controller null / empty timeline while a song
 * was current) fell into playTracks() and hijacked the current track.
 * Pure JVM — runs on every dev machine, including ARM.
 */
class QueueAppendPolicyTest {

    @Test
    fun liveTimeline_appends() {
        assertEquals(
            QueueAppendAction.APPEND,
            queueAppendAction(controllerConnected = true, controllerItemCount = 5, hasCurrentTrack = true)
        )
    }

    @Test
    fun reconnecting_withCurrentSong_buffersInsteadOfHijacking() {
        assertEquals(
            QueueAppendAction.BUFFER_FOR_RECONNECT,
            queueAppendAction(controllerConnected = false, controllerItemCount = 0, hasCurrentTrack = true)
        )
    }

    @Test
    fun emptyTimeline_withCurrentSong_rebuildsKeepingCurrent() {
        assertEquals(
            QueueAppendAction.REBUILD_KEEPING_CURRENT,
            queueAppendAction(controllerConnected = true, controllerItemCount = 0, hasCurrentTrack = true)
        )
    }

    @Test
    fun genuinelyIdle_disconnected_startsPlayback() {
        assertEquals(
            QueueAppendAction.START_PLAYBACK,
            queueAppendAction(controllerConnected = false, controllerItemCount = 0, hasCurrentTrack = false)
        )
    }

    @Test
    fun genuinelyIdle_emptyTimeline_startsPlayback() {
        assertEquals(
            QueueAppendAction.START_PLAYBACK,
            queueAppendAction(controllerConnected = true, controllerItemCount = 0, hasCurrentTrack = false)
        )
    }

    @Test
    fun staleItemCount_whileDisconnected_stillBuffersForCurrentSong() {
        // itemCount is meaningless while disconnected — the current-song
        // guard must win regardless of what a stale count says.
        assertEquals(
            QueueAppendAction.BUFFER_FOR_RECONNECT,
            queueAppendAction(controllerConnected = false, controllerItemCount = 9, hasCurrentTrack = true)
        )
    }

    @Test
    fun noCurrentSong_butLiveItems_appends() {
        // Rare but legal: timeline exists while the UI has not settled a
        // current track yet — still a plain append, never a restart.
        assertEquals(
            QueueAppendAction.APPEND,
            queueAppendAction(controllerConnected = true, controllerItemCount = 3, hasCurrentTrack = false)
        )
    }
}
