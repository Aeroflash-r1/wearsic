package com.wearsic.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.wearsic.app.model.PlaybackUiState
import com.wearsic.app.model.Track
import com.wearsic.app.ui.screens.QueueScreen
import com.wearsic.app.ui.theme.WearsicTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class QueueDragTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleTracks = listOf(
        Track(id = "t0", title = "Zero Track", artist = "Artist 0"),
        Track(id = "t1", title = "First Up Next", artist = "Artist 1"),
        Track(id = "t2", title = "Second Up Next", artist = "Artist 2"),
        Track(id = "t3", title = "Third Up Next", artist = "Artist 3"),
        Track(id = "t4", title = "Fourth Up Next", artist = "Artist 4"),
    )

    private fun startQueue(
        initialPlaylist: List<Track> = sampleTracks,
        currentIndex: Int = 0,
        onPlay: (Int) -> Unit = {},
        onMove: (Int, Int) -> Unit = { _, _ -> },
    ): (List<Track>) -> Unit {
        var playlist by mutableStateOf(initialPlaylist)
        composeTestRule.setContent {
            WearsicTheme {
                QueueScreen(
                    playbackState = PlaybackUiState(
                        currentTrack = playlist.getOrElse(currentIndex) { Track() },
                        isPlaying = true,
                        currentTrackIndex = currentIndex,
                        playlist = playlist,
                    ),
                    onPlayItem = onPlay,
                    onRemoveItem = {},
                    onMoveItem = { from, to ->
                        val next = playlist.toMutableList()
                        val item = next.removeAt(from)
                        next.add(to, item)
                        playlist = next
                        onMove(from, to)
                    },
                    onClearQueue = {},
                )
            }
        }
        composeTestRule.waitForIdle()
        // Scroll header + toggles slightly up so upcoming cards sit cleanly in viewport.
        composeTestRule.onRoot().performTouchInput {
            swipeUp(startY = centerY + 60f, endY = centerY - 120f, durationMillis = 150L)
        }
        composeTestRule.waitForIdle()
        return { updated -> playlist = updated }
    }

    @Test
    fun longPressingUpperHalfOfSongCard_liftsThatExactCard_notPreviousCard() {
        startQueue()

        val card2Bounds = composeTestRule.onNodeWithTag("queue_track_t2")
            .fetchSemanticsNode().boundsInRoot
        // Deliberately hold the upper third of Card #2 (which previously
        // mis-hit Card #1 due to center-relative ScalingLazyListItemInfo.offset).
        val holdPoint = Offset(
            x = card2Bounds.center.x,
            y = card2Bounds.top + card2Bounds.height * 0.25f,
        )

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(holdPoint) }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        val floating = composeTestRule.onNodeWithTag("queue_drag_floating_card")
        floating.assertIsDisplayed()
        composeTestRule.onNode(
            androidx.compose.ui.test.hasTestTag("queue_drag_floating_card") and
                hasAnyDescendant(hasText("Second Up Next"))
        ).assertExists()

        // Adjacent cards remain displayed (not occluded/blacked out).
        composeTestRule.onNodeWithTag("queue_track_t1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("queue_track_t3").assertIsDisplayed()

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(400L)
        composeTestRule.waitForIdle()
    }

    @Test
    fun draggingSongCardDown_shiftsNeighborCleanly_andReordersOnRelease() {
        val moves = mutableListOf<Pair<Int, Int>>()
        startQueue(onMove = { from, to -> moves += (from to to) })

        val card1Bounds = composeTestRule.onNodeWithTag("queue_track_t1")
            .fetchSemanticsNode().boundsInRoot
        val card2Bounds = composeTestRule.onNodeWithTag("queue_track_t2")
            .fetchSemanticsNode().boundsInRoot

        val start = card1Bounds.center
        val dest = card2Bounds.center

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(start) }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("queue_drag_floating_card").assertIsDisplayed()

        // Drag card t1 down onto card t2's center.
        composeTestRule.onRoot().performTouchInput { moveTo(dest) }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()

        // Floating card tracks finger center; neighbor t2 shifted upward cleanly into t1's slot.
        val floatingAfterDrag = composeTestRule.onNodeWithTag("queue_drag_floating_card")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Floating card center (${floatingAfterDrag.center.y}) must follow finger (${dest.y})",
            kotlin.math.abs(floatingAfterDrag.center.y - dest.y) <= 12f
        )

        val shiftedT2 = composeTestRule.onNodeWithTag("queue_track_t2")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Neighbor t2 must animate upward toward t1's slot (was ${card2Bounds.center.y}, now ${shiftedT2.center.y})",
            shiftedT2.center.y < card2Bounds.center.y - card1Bounds.height * 0.4f
        )

        // Release and let settle animation complete.
        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        assertEquals(listOf(1 to 2), moves)
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("queue_drag_floating_card").fetchSemanticsNodes().size
        )

        // Verify final non-overlapping order: t2 is now above t1.
        val finalT2 = composeTestRule.onNodeWithTag("queue_track_t2")
            .fetchSemanticsNode().boundsInRoot
        val finalT1 = composeTestRule.onNodeWithTag("queue_track_t1")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "After drop, t2 (${finalT2.bottom}) must sit cleanly above t1 (${finalT1.top})",
            finalT2.bottom <= finalT1.top
        )
    }

    @Test
    fun draggingSongCardUp_reordersUpward() {
        val moves = mutableListOf<Pair<Int, Int>>()
        startQueue(onMove = { from, to -> moves += (from to to) })

        val card1Bounds = composeTestRule.onNodeWithTag("queue_track_t1")
            .fetchSemanticsNode().boundsInRoot
        val card3Bounds = composeTestRule.onNodeWithTag("queue_track_t3")
            .fetchSemanticsNode().boundsInRoot

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(card3Bounds.center) }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput { moveTo(card1Bounds.center) }
        composeTestRule.mainClock.advanceTimeBy(300L)
        composeTestRule.waitForIdle()

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        assertEquals(listOf(3 to 1), moves)
    }

    @Test
    fun holdingNowPlayingCard_doesNotLiftOrMove() {
        val moves = mutableListOf<Pair<Int, Int>>()
        startQueue(onMove = { from, to -> moves += (from to to) })

        val currentBounds = composeTestRule.onNodeWithTag("queue_current_track")
            .fetchSemanticsNode().boundsInRoot

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(currentBounds.center) }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("queue_drag_floating_card").fetchSemanticsNodes().size
        )

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.waitForIdle()
        assertTrue(moves.isEmpty())
    }

    @Test
    fun quickTapOnSongCard_playsWithoutStartingDrag() {
        val played = mutableListOf<Int>()
        startQueue(onPlay = { played += it })

        composeTestRule.onNodeWithTag("queue_track_t2").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(2), played)
    }

    @Config(qualifiers = RobolectricDeviceQualifiers.WearOSSmallRound, sdk = [36])
    @Test
    fun compactRoundFace_longPressHitsExactCard() {
        startQueue()

        val card2Bounds = composeTestRule.onNodeWithTag("queue_track_t2")
            .fetchSemanticsNode().boundsInRoot
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onRoot().performTouchInput { down(card2Bounds.center) }
        composeTestRule.mainClock.advanceTimeBy(450L)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("queue_drag_floating_card").assertIsDisplayed()
        composeTestRule.onNode(
            androidx.compose.ui.test.hasTestTag("queue_drag_floating_card") and
                hasAnyDescendant(hasText("Second Up Next"))
        ).assertExists()

        composeTestRule.onRoot().performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(400L)
        composeTestRule.waitForIdle()
    }
}
