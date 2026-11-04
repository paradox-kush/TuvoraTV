package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertTrue
import org.junit.Test

/** C9: forced-subtitles-only holds when the audio has no language tag (ForcedSubtitlePolicy). */
class ForcedSubtitleWiringTest {
    @Test
    fun `auto subtitle selection decides forced-only through the policy`() {
        val tracks = source("PlayerRuntimeControllerTracks.kt")
        assertTrue(tracks.contains("ForcedSubtitlePolicy.evaluate("))
        assertTrue("the old inline decision is gone", !tracks.contains("!useForcedSubtitles -> null"))
    }
}
