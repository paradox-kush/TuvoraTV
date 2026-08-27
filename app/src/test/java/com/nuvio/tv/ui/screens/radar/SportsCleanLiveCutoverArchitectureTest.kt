package com.nuvio.tv.ui.screens.radar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsCleanLiveCutoverArchitectureTest {
    private val viewModel = source("ui/screens/radar/SportsHubViewModel.kt")
    private val screen = source("ui/screens/radar/SportsHubScreen.kt")
    private val navigation = source("ui/navigation/NuvioNavHost.kt")
    private val matcher = source("core/radar/RadarChannelMatcher.kt")

    @Test
    fun `Sports live click publishes profile-bound identity then emits only content id`() {
        val playMatch = viewModel.substringAfter("fun playMatch(")
            .substringBefore("private companion object")

        assertTrue(playMatch.contains("onPlay: (contentId: String) -> Unit"))
        assertTrue(playMatch.contains("profileManager.activeProfileId.value"))
        assertTrue(playMatch.contains("XtreamLiveChannelIdentity.from("))
        assertTrue(playMatch.contains("onPlay(match.channel.contentId)"))
        assertTrue(playMatch.indexOf("livePlaylist.set(") < playMatch.indexOf("closeMatch()"))
        assertTrue(playMatch.indexOf("closeMatch()") < playMatch.indexOf("onPlay(match.channel.contentId)"))
        listOf(
            "playbackUrlFor",
            "ensurePlayable",
            "HttpURLConnection",
            "isStreamAlive",
            "streamUrl: String",
        ).forEach { forbidden -> assertFalse(forbidden, playMatch.contains(forbidden)) }
    }

    @Test
    fun `Sports screen and navigation carry stable identity into shared clean dispatcher`() {
        val screenContract = screen.substringAfter("fun SportsHubScreen(")
            .substringBefore("viewModel: SportsHubViewModel")
        val sportsRoute = navigation.substringAfter("composable(Screen.SportsHub.route)")
            .substringBefore("composable(Screen.ManageProfiles.route)")
        val liveCallback = sportsRoute.substringAfter("onPlayChannel =")
            .substringBefore("onAddProvider =")
        val sharedDispatcher = navigation.substringAfter("fun dispatchLiveOrElse(")
            .substringBefore("fun isStreamToPlayer(")

        assertTrue(screenContract.contains("onPlayChannel: (contentId: String) -> Unit"))
        assertFalse(screenContract.contains("streamUrl"))
        assertTrue(liveCallback.contains("dispatchLiveOrElse("))
        assertTrue(liveCallback.contains("owner = backStackEntry"))
        assertTrue(liveCallback.contains("origin = CleanLiveLaunchOrigin.SPORTS"))
        assertTrue(sharedDispatcher.contains("Screen.CleanLivePlayer.createRoute(result.token)"))
        assertFalse(liveCallback.contains("Screen.Player.createRoute"))
        assertFalse(liveCallback.contains("streamUrl"))
    }

    @Test
    fun `Sports no longer owns pre-play transport resolution or byte probing`() {
        listOf(
            "playbackUrlFor",
            "ensurePlayable",
        ).forEach { removed -> assertFalse(removed, matcher.contains(removed)) }
        listOf(
            "radarChannelNeedsHealthProbe",
            "PROBE_TIMEOUT_MS",
            "PROBE_CAP",
            "HttpURLConnection",
            "java.net.URL",
            "probingContentId",
            "deadContentIds",
        ).forEach { removed ->
            assertFalse("Sports still owns $removed", viewModel.contains(removed))
            assertFalse("Sports screen still owns $removed", screen.contains(removed))
        }
    }

    private fun source(relativePath: String): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(projectRoot, "app/src/main/java/com/nuvio/tv/$relativePath").readText()
    }
}
