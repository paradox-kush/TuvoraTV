package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertTrue
import org.junit.Test

/** C5: a large audio delay on ExoPlayer must not hang Starting stream (AudioDelayRangePolicy). */
class AudioDelayClampWiringTest {
    @Test
    fun `the applied delay is clamped per engine`() {
        val body = functionBody(source("PlayerRuntimeControllerPlaybackEvents.kt"), "fun PlayerRuntimeController.applyEffectiveAudioDelay(")
        assertTrue(body.contains("AudioDelayRangePolicy.effectiveDelayMs(audioDelayParts.totalMs, usingMpv = isUsingMpvEngine())"))
    }
}
