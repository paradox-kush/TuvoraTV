package com.nuvio.tv.ui.screens.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLiveGuideCutoverArchitectureTest {
    private val screen = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/XtreamLiveGuideScreen.kt")
    private val catalog = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/XtreamLiveGuideViewModel.kt")
    private val owner = source("app/src/main/java/com/nuvio/tv/ui/screens/iptv/CleanLiveGuidePlaybackViewModel.kt")

    @Test
    fun `guide screen owns one stable surface and sends identities to the clean owner`() {
        assertTrue(screen.contains("remember(context) { FrameLayout(context) }"))
        assertTrue(screen.contains("AndroidView(factory = { surfaceOwner }"))
        assertTrue(screen.contains("VideoDimensions(width = heightPx * 16 / 9, height = heightPx)"))
        assertTrue(screen.contains("previewViewport = previewViewport"))
        assertTrue(screen.contains("playbackViewModel.attachGuide("))
        assertTrue(screen.contains("playbackViewModel.requestTune(ProviderSelectionId(ch.contentId))"))
        assertTrue(screen.contains("playbackViewModel.requestZap(LiveZapDirection."))
        assertTrue(screen.contains("playbackViewModel.requestPromote()"))
        assertTrue(screen.contains("playbackViewModel.requestCollapse()"))
        assertTrue(screen.contains("playbackViewModel.detachGuide()"))
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
        }
    }

    @Test
    fun `catalog publishes the profile-bound URL-free lineup before exposing channels`() {
        val networkCommit = catalog.indexOf("publishPlaybackLineup(acc.id, token, channels)")
        val stateCommit = catalog.indexOf(
            "_uiState.update { it.copy(channels = channels, loadingChannels = false",
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
        val switch = catalog.indexOf("fun setAccount(acc: XtreamAccount)")
        val cancelCategories = catalog.indexOf("categoriesJob?.cancel()", startIndex = switch)
        val cancelChannels = catalog.indexOf("channelsJob?.cancel()", startIndex = switch)
        val categoryFence = catalog.indexOf(
            "if (!isCurrentAccount(accountToken)) return@launch",
            startIndex = cancelChannels,
        )
        val channelFence = catalog.indexOf(
            "if (!isCurrentAccount(token)) return@launch",
            startIndex = categoryFence,
        )
        val publish = catalog.indexOf(
            "if (!publishPlaybackLineup(acc.id, token, channels)) return@launch",
            startIndex = channelFence,
        )
        val expose = catalog.indexOf(
            "_uiState.update { it.copy(channels = channels, loadingChannels = false",
            startIndex = publish,
        )
        val epg = catalog.indexOf("primeEpgFor(channels)", startIndex = expose)

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
}
