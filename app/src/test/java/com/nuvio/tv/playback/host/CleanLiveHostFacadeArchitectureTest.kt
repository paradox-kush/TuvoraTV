package com.nuvio.tv.playback.host

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLiveHostFacadeArchitectureTest {
    private val facade = source(
        "app/src/main/java/com/nuvio/tv/playback/host/AndroidCleanLiveHostFactory.kt",
    )
    private val fullscreenOwner = source(
        "app/src/main/java/com/nuvio/tv/ui/screens/player/clean/CleanLivePlayerViewModel.kt",
    )

    @Test
    fun `shared facade exposes the complete engine neutral live command contract`() {
        val contract = facade.substringAfter("internal interface CleanLiveHost")
            .substringBefore("internal class AndroidCleanLiveHostInput")

        listOf(
            "val snapshot: StateFlow<PlaybackSnapshot>",
            "suspend fun tune(",
            "suspend fun zap(",
            "suspend fun pause()",
            "suspend fun resume()",
            "suspend fun retry()",
            "suspend fun changeProfile(profile: SessionProfile)",
            "suspend fun stop()",
            "suspend fun release()",
        ).forEach { expected -> assertTrue("Missing $expected", contract.contains(expected)) }
        val tuneContract = contract.substringAfter("suspend fun tune(")
            .substringBefore("suspend fun zap(")
        val zapContract = contract.substringAfter("suspend fun zap(")
            .substringBefore("suspend fun pause()")
        assertTrue("Tune must return its accepted generation", tuneContract.contains("): Long"))
        assertTrue("Zap must return its accepted generation", zapContract.contains("): Long"))
    }

    @Test
    fun `Android factory composes clean ports without importing an engine API`() {
        assertTrue(facade.contains("CleanLiveSurfaceCoordinator("))
        assertTrue(facade.contains("AndroidPlaybackOutputController("))
        assertTrue(facade.contains("AndroidPlaybackLifecyclePort("))
        assertTrue(facade.contains("CleanLivePlaybackHost.create("))
        listOf("androidx.media3", "is.xyz.mpv", "ExoPlayer", "MPVLib").forEach { forbidden ->
            assertFalse("Factory imports $forbidden", facade.contains("import $forbidden"))
        }
    }

    @Test
    fun `Android facade delegates every command without changing semantics`() {
        listOf(
            "host.tune(selection, profile, metadata)",
            "host.zap(selection, profile, metadata)",
            "host.pause()",
            "host.resume()",
            "host.retry()",
            "host.changeProfile(profile)",
            "host.stop()",
            "host.release()",
        ).forEach { delegation ->
            assertTrue("Missing direct delegation: $delegation", facade.contains(delegation))
        }
        assertFalse(facade.contains("withTimeout"))
        assertFalse(facade.contains("delay("))
    }

    @Test
    fun `fullscreen owner consumes shared facade and owns no Android host composition`() {
        assertTrue(fullscreenOwner.contains("hostFactory: CleanLiveHostFactory"))
        assertTrue(fullscreenOwner.contains("AndroidCleanLiveHostInput("))
        listOf(
            "CleanLiveSurfaceCoordinator",
            "AndroidPlaybackOutputController",
            "AndroidPlaybackLifecyclePort",
            "ProductionPlaybackSessionFactory",
            "CleanLivePlaybackHost.create(",
        ).forEach { forbidden -> assertFalse(forbidden, fullscreenOwner.contains(forbidden)) }
    }

    private fun source(relativePath: String): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(projectRoot, relativePath).readText()
    }
}
