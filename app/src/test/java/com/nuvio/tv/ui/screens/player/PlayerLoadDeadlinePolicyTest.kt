package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerLoadDeadlinePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerLoadDeadlinePolicyTest {
    private fun decide(
        elapsedMs: Long,
        online: Boolean = true,
        firstFrame: Boolean = false,
        isLive: Boolean = false,
        isTorrent: Boolean = false,
        suspended: Boolean = false,
        hasError: Boolean = false,
        failedOffline: Boolean = false,
    ) = PlayerLoadDeadlinePolicy.decide(
        elapsedMs = elapsedMs,
        online = online,
        firstFrameRendered = firstFrame,
        isLive = isLive,
        isTorrent = isTorrent,
        suspended = suspended,
        hasError = hasError,
        failedOffline = failedOffline,
    )

    @Test
    fun `B217 offline start fails fast instead of spinning`() {
        assertEquals("young", Action.Wait, decide(PlayerLoadDeadlinePolicy.OFFLINE_DEADLINE_MS - 1, online = false))
        assertEquals("offline deadline", Action.FailOffline, decide(PlayerLoadDeadlinePolicy.OFFLINE_DEADLINE_MS, online = false))
    }

    @Test
    fun `B214 online load with no first frame ends in timeout failure`() {
        assertEquals(Action.Wait, decide(PlayerLoadDeadlinePolicy.HARD_DEADLINE_MS - 1))
        assertEquals(Action.FailTimeout, decide(PlayerLoadDeadlinePolicy.HARD_DEADLINE_MS))
        assertEquals(Action.FailTimeout, decide(10 * 60_000L))
    }

    @Test
    fun `network return re-arms a load that failed offline`() {
        assertEquals(Action.RetryNetworkBack, decide(0L, online = true, failedOffline = true, hasError = true))
        assertEquals("still offline", Action.Wait, decide(0L, online = false, failedOffline = true, hasError = true))
    }

    @Test
    fun `started live torrent suspended or errored loads are never judged`() {
        val late = 10 * 60_000L
        assertEquals(Action.Wait, decide(late, firstFrame = true))
        assertEquals(Action.Wait, decide(late, isLive = true))
        assertEquals(Action.Wait, decide(late, isTorrent = true))
        assertEquals(Action.Wait, decide(late, suspended = true))
        assertEquals(Action.Wait, decide(late, hasError = true))
        assertEquals(Action.Wait, decide(late, online = false, suspended = true))
    }
}
