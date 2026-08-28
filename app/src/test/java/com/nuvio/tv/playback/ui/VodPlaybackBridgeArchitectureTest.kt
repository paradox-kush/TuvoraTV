package com.nuvio.tv.playback.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPlaybackBridgeArchitectureTest {
    @Test
    fun `VOD presentation bridge stays engine provider and transport neutral`() {
        val source = source("app/src/main/java/com/nuvio/tv/playback/ui/VodPlaybackPresentationBridge.kt")
        val forbidden = listOf(
            "androidx.media3",
            "is.xyz.mpv",
            "ExoPlayer",
            "MpvEngine",
            "Media3Engine",
            "PlayerRuntimeController",
            "ProviderPlaybackResolver",
            "StreamRepository",
            "OkHttp",
            "currentStreamUrl",
            "headers",
        )

        forbidden.forEach { symbol ->
            assertFalse("VOD bridge regained forbidden authority: $symbol", source.contains(symbol))
        }
        assertTrue(source.contains("CleanVodHost"))
        assertTrue(source.contains("PlaybackSnapshot"))
    }

    @Test
    fun `unfinished clean VOD route cannot shadow play beside legacy VOD`() {
        val screen = source("app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt")
        val navigation = source("app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt")

        listOf(screen, navigation).forEach { productionRoute ->
            assertFalse(productionRoute.contains("AndroidCleanVodHostFactory"))
            assertFalse(productionRoute.contains("VodPlaybackPresentationBridge"))
        }
    }

    @Test
    fun `media session metadata cannot create a placeholder playback item`() {
        val metadata = source(
            "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSessionMetadata.kt"
        )

        assertFalse(metadata.contains("player.setMediaItem("))
        assertFalse(metadata.contains("MediaItem.Builder()"))
        assertTrue(metadata.contains("player.replaceMediaItem("))
    }

    private fun source(relative: String): String {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) {
            it.parentFile
        }.firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV root")
        return File(root, relative).readText()
    }
}
