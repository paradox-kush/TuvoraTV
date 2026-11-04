package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertTrue
import org.junit.Test

/** B4: a provider-WAF status (HTTP 456) gets the actionable hint, not a bare code. */
class HttpStatusHintWiringTest {
    @Test
    fun `the stream error text maps statuses through StreamHttpStatusPolicy`() {
        val body = functionBody(source("PlayerRuntimeControllerErrorRecovery.kt"), "fun httpStatusExplanation(")
        assertTrue(body.contains("StreamHttpStatusPolicy.hint(code)"))
        assertTrue(body.contains("player_error_stream_provider_firewall"))
    }
}
