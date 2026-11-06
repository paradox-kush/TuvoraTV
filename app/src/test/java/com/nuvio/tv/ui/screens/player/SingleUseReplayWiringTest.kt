package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.functionBody
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.mainSource
import com.nuvio.tv.ui.screens.player.PlayerSourceWiring.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller glue around a single-use (Stalker archive) replay link needs an Android player to run,
 * so the order and presence of each guard is pinned on the source. Each of these failed before the
 * 2026-11-05 review fixes: the AFR preflight and DoVi probe spent the link, parallel Range and the VOD
 * cache re-read it, an mpv init failure fell back to Exo on the same URL, and the hold was taken on
 * the first playhead tick.
 */
class SingleUseReplayWiringTest {

    /** Body of a class member (4-space indent) up to the next member function. */
    private fun memberBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val next = Regex("\n    (private |internal |override )?(suspend )?fun ").find(text, start + signature.length)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    private val init get() = source("PlayerRuntimeControllerInitialization.kt")
    private val initBody get() = functionBody(init, "internal fun PlayerRuntimeController.initializePlayer(")

    @Test
    fun `single-use is decided once at the top of initializePlayer before any probe`() {
        val decided = initBody.indexOf("val singleUseLink = ")
        assertTrue("initializePlayer must compute singleUseLink", decided >= 0)
        assertEquals("computed exactly once", decided, initBody.lastIndexOf("val singleUseLink = "))
        val afr = initBody.indexOf("runAfrPreflightIfEnabled(")
        assertTrue("the decision comes before the AFR preflight", decided < afr)
        assertTrue("the decision comes before the DoVi probe", decided < initBody.indexOf("DoviBridge.probeRealtimeConversionSupport(url)"))
    }

    @Test
    fun `the AFR preflight and the DoVi probe are skipped for a single-use link`() {
        val beforeAfr = initBody.substring(0, initBody.indexOf("runAfrPreflightIfEnabled("))
        assertTrue("AFR is gated on allowsOpeningProbes(singleUseLink)",
            beforeAfr.substringAfterLast("val afrJob").contains("allowsOpeningProbes(singleUseLink)"))
        val doviLine = initBody.lines().first { it.contains("DoviBridge.probeRealtimeConversionSupport(url)") }
        val doviBlock = initBody.substring(initBody.indexOf("val dv7ToDv81SettingActive"), initBody.indexOf(doviLine))
        assertTrue("the DoVi probe is gated", doviBlock.contains("allowsOpeningProbes(singleUseLink)"))
    }

    @Test
    fun `parallel range and the vod cache are forced off for a single-use link`() {
        assertTrue("controller gates parallel", initBody.contains("allowsParallelRange(") && initBody.contains("singleUseLink"))
        assertTrue("controller gates the cache", initBody.contains("allowsVodCache("))
        val factory = memberBody(source("PlayerMediaSourceFactory.kt"), "    fun createMediaSource(")
        assertTrue("the factory defends itself too (parallel)", factory.contains("allowsParallelRange(useParallelConnections, singleUseLink)"))
        assertTrue("the factory defends itself too (cache)", factory.contains("allowsVodCache(vodCacheEnabled, singleUseLink)"))
    }

    @Test
    fun `the factory is told the same decision the controller made`() {
        assertTrue(initBody.contains("singleUseLink = singleUseLink,"))
    }

    @Test
    fun `a startup engine failover ends a single-use replay instead of re-opening its url`() {
        val body = functionBody(source("PlayerRuntimeControllerEngineFailover.kt"),
            "internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(")
        val guard = body.indexOf("failSingleUseCatchUp(")
        assertTrue("guarded", guard >= 0)
        assertTrue("before the engine switch re-initialises", guard < body.indexOf("initializePlayer("))
    }

    @Test
    fun `an exo error reaches the single-use end before the iptv link refresh`() {
        val listener = init.substring(init.indexOf("override fun onPlayerError(error: PlaybackException)"))
        assertTrue(listener.indexOf("advanceCatchUpDialect(") in 0 until listener.indexOf("attemptIptvLinkRefresh("))
        val advance = functionBody(source("PlayerRuntimeControllerCatchUp.kt"), "internal fun PlayerRuntimeController.advanceCatchUpDialect(")
        assertTrue("and the route is the pure policy's", advance.contains("SingleUseReplayPolicy.routeError("))
    }

    @Test
    fun `an mpv error reaches the single-use end before any link refresh or auto retry`() {
        val body = functionBody(source("PlayerRuntimeControllerMpvEvents.kt"), "internal fun PlayerRuntimeController.handleMpvPlaybackError(")
        val guard = body.indexOf("failSingleUseCatchUp(")
        assertTrue("guarded", guard >= 0)
        assertTrue(guard < body.indexOf("attemptIptvLinkRefresh("))
        assertTrue(guard < body.indexOf("mpvErrorHandlingInProgress.compareAndSet"))
    }

    @Test
    fun `the replay is held from player start not from the first playhead tick`() {
        assertTrue("initializePlayer takes the hold", initBody.contains("holdSingleUseCatchUpAtStart()"))
        val follow = functionBody(source("PlayerRuntimeControllerCatchUp.kt"), "internal fun PlayerRuntimeController.followCatchUpPlayhead(")
        assertFalse("the first tick no longer decides the single-use hold", follow.contains("singleUseFailure("))
    }

    @Test
    fun `the retry path takes its step from the pure policy`() {
        val retry = functionBody(source("PlayerRuntimeControllerCatchUp.kt"), "internal fun PlayerRuntimeController.retrySingleUseCatchUp(")
        assertTrue(retry.contains("SingleUseReplayPolicy.retryStep("))
    }

    @Test
    fun `every user seek event is gated by the single-use policy`() {
        val events = source("PlayerRuntimeControllerPlaybackEvents.kt")
        for (branch in listOf("is PlayerEvent.OnSeekBy ->", "is PlayerEvent.OnPreviewSeekBy ->", "PlayerEvent.OnCommitPreviewSeek ->", "is PlayerEvent.OnSeekTo ->")) {
            val start = events.indexOf("        $branch")
            assertTrue("missing $branch", start >= 0)
            val end = Regex("\n        (is )?PlayerEvent\\.").find(events, start + branch.length)?.range?.first ?: events.length
            assertTrue("$branch must check allowsSeek first", events.substring(start, end).contains("SingleUseReplayPolicy.allowsSeek(isSingleUseCatchUp())"))
        }
    }

    @Test
    fun `the progress bar is drawn flat with a reason for a single-use replay`() {
        assertTrue("decided once with the link", initBody.contains("seekable = SingleUseReplayPolicy.allowsSeek(singleUseLink)"))
        val screen = source("PlayerScreen.kt")
        assertTrue("the controls pass it to the bar host", screen.contains("seekable = uiState.seekable"))
        assertTrue("the bar ignores LEFT/RIGHT when not seekable", screen.contains("if (!seekable) return@onPreviewKeyEvent true"))
        assertTrue("and says why", screen.contains("R.string.player_replay_not_seekable"))
    }
}
