package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.core.picture.LivePicturePort
import com.nuvio.tv.core.picture.PictureChoice
import com.nuvio.tv.core.picture.VideoZoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F28: the live picture follows the channel on screen — each channel opens with its own remembered
 * aspect/zoom (lane F's model, per channel) and a change is kept for THAT channel only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePictureFollowerTest {

    private class FakePort : LivePicturePort {
        val stored = mutableMapOf<String, PictureChoice>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        override suspend fun initial(channelId: String): PictureChoice {
            gates[channelId]?.await()
            return stored[channelId] ?: LivePicturePort.DEFAULT_CHOICE
        }
        override suspend fun save(channelId: String, choice: PictureChoice) { stored[channelId] = choice }
    }

    private val crop = PictureChoice(AspectMode.FULL_SCREEN, VideoZoom.IDENTITY)

    @Test
    fun `a channel opens with its remembered picture and applies it`() = runTest {
        val port = FakePort().apply { stored["bbc"] = crop }
        val applied = mutableListOf<PictureChoice>()
        val follower = LivePictureFollower(port, this)
        follower.follow("bbc") { applied += it }
        advanceUntilIdle()
        assertEquals("state", crop, follower.picture.value)
        assertEquals("applied to the surface", listOf(crop), applied)
    }

    @Test
    fun `a slow load for a channel already left is dropped`() = runTest {
        val port = FakePort().apply { stored["bbc"] = crop; gates["bbc"] = CompletableDeferred() }
        val applied = mutableListOf<PictureChoice>()
        val follower = LivePictureFollower(port, this)
        follower.follow("bbc") { applied += it }
        follower.follow("itv") { applied += it }
        advanceUntilIdle()
        port.gates["bbc"]!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("itv's default wins", LivePicturePort.DEFAULT_CHOICE, follower.picture.value)
        assertEquals("bbc's crop never reached itv", listOf(LivePicturePort.DEFAULT_CHOICE), applied)
    }

    @Test
    fun `a change applies at once and is kept for the channel on screen`() = runTest {
        val port = FakePort()
        val applied = mutableListOf<PictureChoice>()
        val follower = LivePictureFollower(port, this)
        follower.follow("bbc") { applied += it }
        advanceUntilIdle()
        follower.change({ it.copy(aspectMode = AspectMode.FULL_SCREEN) }) { applied += it }
        advanceUntilIdle()
        assertEquals("state", crop, follower.picture.value)
        assertEquals("applied", crop, applied.last())
        assertEquals("saved for bbc", crop, port.stored["bbc"])
    }

    @Test
    fun `nothing changes before a channel is on screen`() = runTest {
        val port = FakePort()
        val follower = LivePictureFollower(port, this)
        follower.change({ it.copy(aspectMode = AspectMode.STRETCH) }) { error("must not apply") }
        advanceUntilIdle()
        assertEquals("untouched", LivePicturePort.DEFAULT_CHOICE, follower.picture.value)
        assertEquals("nothing saved", emptyMap<String, PictureChoice>(), port.stored)
    }
}
