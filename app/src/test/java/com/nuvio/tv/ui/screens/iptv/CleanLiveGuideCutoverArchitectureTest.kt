package com.nuvio.tv.ui.screens.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLiveGuideCutoverArchitectureTest {
    private val screen = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/XtreamLiveGuideScreen.kt")
    private val overlay = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/GuideFullscreenOverlay.kt")
    private val catalog = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/XtreamLiveGuideViewModel.kt")
    // Ordering checks read the catalog with whitespace removed, so a reformat (a one-line
    // `_uiState.update { it.copy(...) }` split across lines by #36) cannot hide a real reorder.
    private val catalogFlat = squash(catalog)
    private val owner = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/CleanLiveGuidePlaybackViewModel.kt")

    @Test
    fun `guide screen owns one stable surface and sends identities to the clean owner`() {
        assertTrue(screen.contains("remember(context) { FrameLayout(context) }"))
        assertTrue(screen.contains("AndroidView(factory = { surfaceOwner }"))
        assertTrue(screen.contains("VideoDimensions(width = heightPx * 16 / 9, height = heightPx)"))
        assertTrue(screen.contains("previewViewport = previewViewport"))
        assertTrue(screen.contains("playbackViewModel.attachGuide("))
        assertTrue(screen.contains("selectChannel(ch.contentId)"))
        assertTrue(screen.contains("GuidePlaybackInteractionPolicy.Click.TUNE -> commitPreview(contentId)"))
        assertTrue(screen.contains("playbackViewModel::requestSettledTune"))
        assertTrue(screen.contains("viewModel.moveChannelFocus(LiveZapDirection."))
        assertTrue(screen.contains("playbackViewModel.requestPromote()"))
        assertTrue(screen.contains("playbackViewModel.requestCollapse()"))
        assertTrue(screen.contains("playbackViewModel.detachGuide()"))
    }

    @Test
    fun `fullscreen overlay state is read by the overlay scope and never by the guide body`() {
        // Event-time reads are fine: they sit inside the BACK handler, the root key handler and the
        // overlay's channel-list lambda. Any other `controls.<state>` in the screen file is a
        // composition-time read that restarts the whole hidden guide on every zap press.
        fun span(start: String, end: String): IntRange {
            val a = screen.indexOf(start)
            assertTrue("anchor missing: $start", a >= 0)
            val b = screen.indexOf(end, startIndex = a + start.length)
            assertTrue("anchor end missing: $end", b > a)
            return a..b
        }
        val eventScopes = listOf(
            span("BackHandler(enabled = fullscreen) {", "\n    }\n"),
            span(".onPreviewKeyEvent { event ->", "                true\n            }"),
            span("onOpenChannelList = {", "},\n"),
        )
        val reads = Regex("""controls\.(visible|tick|moreOpen|zapReveal)\b""").findAll(screen).toList()
        assertTrue("expected the event-handler reads to be found", reads.isNotEmpty())
        reads.forEach { m ->
            assertTrue(
                "Guide body reads `${m.value}` in composition (offset ${m.range.first}); move it into " +
                    "GuideFullscreenOverlay / GuideFullscreenControlsEffects or an event handler",
                eventScopes.any { m.range.first in it },
            )
        }
        assertTrue(overlay.contains("if (!fullscreen || !controls.visible) return"))
        assertTrue(screen.contains("GuideFullscreenOverlay(\n                    fullscreen = fullscreen,"))
    }

    @Test
    fun `opening a guide including Stalker never blindly commits channel one`() {
        assertFalse(screen.contains("val first = uiState.channels.firstOrNull()"))
        assertFalse(screen.contains("initialContentId = ProviderSelectionId(first.contentId)"))
        assertTrue(screen.contains("single-use providers are touched only by an explicit"))
    }

    @Test
    fun `guide screen contains no legacy player engine transport or restart owner`() {
        listOf(
            "androidx.media3.exoplayer",
            "androidx.media3.ui.PlayerView",
            "is.xyz.mpv",
            "ExoPlayer",
            "PlayerView(",
            "MPVLib",
            "createGuideMpvSurface",
            "previewPlayback",
            "previewMimeOverride",
            "GuidePreviewFreezePolicy",
            "LifecycleEventObserver",
            "streamUrl",
            "prepared.url",
        ).forEach { forbidden ->
            assertFalse("Guide screen regained legacy playback symbol: $forbidden", screen.contains(forbidden))
            assertFalse("Guide overlay regained legacy playback symbol: $forbidden", overlay.contains(forbidden))
        }
    }

    @Test
    fun `catalog publishes the profile-bound URL-free lineup before exposing channels`() {
        val networkCommit = catalogFlat.indexOf(squash("publishPlaybackLineup(acc.id, token, channels)"))
        val stateCommit = catalogFlat.indexOf(squash("_uiState.update { it.copy(channels = channels, channelsLoad ="),
            startIndex = networkCommit,
        )
        assertTrue(networkCommit >= 0)
        assertTrue(stateCommit > networkCommit)
        assertTrue(catalog.contains("profileManager.activeProfileId.value.takeIf { it > 0 }"))
        assertTrue(catalog.contains("XtreamLiveChannelIdentity.from(channel.contentId"))
        assertFalse(catalog.contains("fun playPreview("))
        assertFalse(catalog.contains("fun tunePreview("))
        assertFalse(catalog.contains("fun stopPreview("))
    }

    @Test
    fun `provider switch cancels account work and fences every asynchronous catalog commit`() {
        val switch = catalogFlat.indexOf(squash("fun setAccount(acc: XtreamAccount)"))
        val cancelCategories = catalogFlat.indexOf(squash("categoriesJob?.cancel()"), startIndex = switch)
        val cancelChannels = catalogFlat.indexOf(squash("channelsJob?.cancel()"), startIndex = switch)
        val categoryFence = catalogFlat.indexOf(squash("if (!isCurrentAccount(accountToken)) return@launch"),
            startIndex = cancelChannels,
        )
        val channelFence = catalogFlat.indexOf(squash("if (!isCurrentAccount(token)) return@launch"),
            startIndex = categoryFence,
        )
        val publish = catalogFlat.indexOf(squash("if (!publishPlaybackLineup(acc.id, token, channels))"),
            startIndex = channelFence,
        )
        val expose = catalogFlat.indexOf(squash("_uiState.update { it.copy(channels = channels, channelsLoad ="),
            startIndex = publish,
        )
        val epg = catalogFlat.indexOf(squash("primeEpgFor(channels)"), startIndex = expose)

        assertTrue(cancelCategories > switch)
        assertTrue(cancelChannels > cancelCategories)
        assertTrue(categoryFence > cancelChannels)
        assertTrue(channelFence > categoryFence)
        assertTrue(publish > channelFence)
        assertTrue(expose > publish)
        assertTrue(epg > expose)
        assertTrue(catalog.contains("private fun selectCategoryFor("))
        assertTrue(catalog.contains("account?.id == token.accountId"))
        assertTrue(catalog.contains("_uiState.value.accountId == token.accountId"))
    }

    @Test
    fun `guide owner has a nonterminal detach barrier and terminal clear release`() {
        assertTrue(owner.contains("fun detachGuide()"))
        assertTrue(owner.contains("current.release()"))
        assertTrue(owner.contains("mutableState.value = CleanLiveGuidePlaybackState.Detached"))
        assertTrue(owner.contains("suspend fun releaseBeforeExit()"))
        assertTrue(owner.contains("override fun onCleared()"))
    }

    private fun source(relativePath: String): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(projectRoot, relativePath).readText()
    }

    private fun squash(text: String): String = text.filterNot(Char::isWhitespace)
}
