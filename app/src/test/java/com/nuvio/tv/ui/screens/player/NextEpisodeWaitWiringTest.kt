package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertTrue
import org.junit.Test

/** C8: next-episode auto-play waits for a slower matching addon past the delay, and skips a search that cannot pick. */
class NextEpisodeWaitWiringTest {
    @Test
    fun `playNextEpisode uses the coordinator and the handoff policy`() {
        val body = functionBody(source("PlayerRuntimeControllerStreams.kt"), "fun PlayerRuntimeController.playNextEpisode(")
        assertTrue("UX25", body.contains("NextEpisodePickerHandoffPolicy.skipSearch("))
        assertTrue("e30fdd2bb", body.contains("onSelectionDelayElapsed("))
    }
}
