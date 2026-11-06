package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.mainSource
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertFalse
import org.junit.Test

/** `runCatching` around a suspending read also catches CancellationException: the caller leaving must unwind. */
class CancellationNotSwallowedWiringTest {
    private fun memberBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val next = Regex("\n    (private |internal |override )?(suspend )?fun ").find(text, start + signature.length)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    @Test
    fun `suspending guide and replay reads never swallow cancellation`() {
        assertFalse("IptvLivePlayback.programmeAt", mainSource("ui/screens/iptv/player/IptvLivePlayback.kt").contains("runCatching {"))
        val publish = memberBody(mainSource("ui/screens/iptv/XtreamLiveGuideViewModel.kt"), "    private suspend fun publishWindows(")
        assertFalse("XtreamLiveGuideViewModel.publishWindows", publish.contains("runCatching {"))
        val follow = functionBody(source("PlayerRuntimeControllerCatchUp.kt"), "internal fun PlayerRuntimeController.followCatchUpPlayhead(")
        assertFalse("followCatchUpPlayhead's programme lookup", follow.contains("runCatching {"))
    }
}
