package com.nuvio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring guards for the fork fixes carried onto upstream's VOD player (2026-11-04 convergence, audit §2
 * buckets B/C). The decisions are pure policies with their own tests; these assert upstream's controller
 * still calls them, so the next upstream merge can't silently drop a call site.
 */
class VodConvergeCarriedFixesWiringTest {

    @Test
    fun `B2 in-player source and episode switches mint a stalker-deferred url before the engine sees it`() {
        val streams = source("PlayerRuntimeControllerStreams.kt")
        val helper = streams.substringAfter("fun PlayerRuntimeController.mintDeferredStreamThen(", "")
        assertTrue("mintDeferredStreamThen must exist", helper.isNotEmpty())
        assertTrue(
            "the mint must go through DeferredStreamPolicy (never hand the placeholder on)",
            helper.substringBefore("\n}\n").contains("DeferredStreamPolicy.resolve(")
        )
        for (entry in listOf("switchToSourceStream", "switchToEpisodeStream")) {
            val body = functionBody(streams, "fun PlayerRuntimeController.$entry(")
            val mintAt = body.indexOf("mintDeferredStreamThen(")
            assertTrue("$entry must mint deferred IPTV urls", mintAt >= 0)
            val engineAt = body.indexOf("applySelectedStreamState(")
            assertTrue("$entry must hand the url to the engine via applySelectedStreamState", engineAt >= 0)
            assertTrue("$entry must mint BEFORE the engine handoff", mintAt < engineAt)
        }
    }

    @Test
    fun `B1 an expired IPTV link is re-minted before any of upstream's ExoPlayer recovery rungs`() {
        val init = source("PlayerRuntimeControllerInitialization.kt")
        val onError = init.substringAfter("override fun onPlayerError(error: PlaybackException)", "")
        assertTrue("onPlayerError must exist", onError.isNotEmpty())
        val refreshAt = onError.indexOf("attemptIptvLinkRefresh(")
        assertTrue("onPlayerError must try the IPTV link re-mint", refreshAt >= 0)
        for (rung in listOf(
            "isVc1PlaybackFailure(",
            "isRetryablePlaybackError(error)",
            "handleParsingErrorFallback(",
            "attemptStartupRecovery(",
            "maybeAutoSwitchInternalPlayerOnStartupError(",
        )) {
            val rungAt = onError.indexOf(rung)
            if (rungAt >= 0) assertTrue("the re-mint must run before $rung", refreshAt < rungAt)
        }
    }

    @Test
    fun `B1 an expired IPTV link is re-minted before libmpv's failover and auto-retry`() {
        val events = source("PlayerRuntimeControllerMpvEvents.kt")
        val body = functionBody(events, "fun PlayerRuntimeController.handleMpvPlaybackError(")
        val refreshAt = body.indexOf("attemptIptvLinkRefresh(")
        assertTrue("handleMpvPlaybackError must try the IPTV link re-mint", refreshAt >= 0)
        assertTrue("before engine failover", refreshAt < body.indexOf("maybeAutoSwitchInternalPlayerOnStartupError("))
        assertTrue("before the auto-retry", refreshAt < body.indexOf("attemptMpvAutoRetry("))
    }

    @Test
    fun `C7 the re-mint swap reopens at the requested resume position before the first frame`() {
        val refresh = source("PlayerRuntimeControllerIptvLinkRefresh.kt")
        assertTrue(refresh.contains("PlaybackStartPositionPolicy.targetAfterSourceSwap("))
        assertTrue(refresh.contains("requestedStartMs = requestedStartPositionMs"))
    }

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("missing $signature", start >= 0)
        val next = Regex("""\n(internal |private )?(suspend )?fun """).find(text, start + signature.length)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    private fun source(name: String): String = File(playerDir(), name).readText()

    private fun playerDir(): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
            it.parentFile
        }.firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV root")
        return File(root, "app/src/main/java/com/nuvio/tv/ui/screens/player")
    }
}
