package com.nuvio.tv.ui.screens.player.clean

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLivePlayerArchitectureTest {
    private val source = sourceFile().readText()
    private val routeSource = sourceFile("CleanLivePlayerRoute.kt").readText()

    @Test
    fun `screen imports no engine provider request or legacy playback authority`() {
        val imports = Regex("""(?m)^import\s+([^\s]+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
        val forbidden = Regex(
            """(?:androidx\.media3|(?:`is`|is)\.xyz\.mpv|okhttp|java\.net|""" +
                """com\.nuvio\.tv\.(?:player|core\.iptv)|""" +
                """com\.nuvio\.tv\.playback\.(?:media3|mpv|provider)|""" +
                """PlayerView|NuvioMpvSurfaceView|PlayerRuntimeController|PlayerViewModel|""" +
                """PlaybackSessionController|PlaybackSession|PlaybackEngine|PlaybackRequest)""",
        )

        assertTrue(
            "Forbidden clean-screen imports: ${imports.filter(forbidden::containsMatchIn)}",
            imports.none(forbidden::containsMatchIn),
        )
    }

    @Test
    fun `route wrapper imports no engine provider network or legacy playback authority`() {
        val imports = Regex("""(?m)^import\s+([^\s]+)""")
            .findAll(routeSource)
            .map { it.groupValues[1] }
            .toList()
        val forbidden = Regex(
            """(?:androidx\.media3|(?:`is`|is)\.xyz\.mpv|okhttp|java\.net|""" +
                """com\.nuvio\.tv\.(?:player|core\.iptv)|""" +
                """com\.nuvio\.tv\.playback\.(?:media3|mpv|provider)|""" +
                """PlayerView|NuvioMpvSurfaceView|PlayerRuntimeController|PlayerViewModel)""",
        )

        assertTrue(
            "Forbidden clean-route imports: ${imports.filter(forbidden::containsMatchIn)}",
            imports.none(forbidden::containsMatchIn),
        )
        assertFalse(routeSource.contains(".addView("))
        assertFalse(routeSource.contains(".removeView("))
        assertFalse(routeSource.contains("viewModel.initialize("))
        assertTrue(routeSource.contains("viewModel.attachDestination("))
    }

    @Test
    fun `Compose creates one empty FrameLayout handoff and never mutates surface children`() {
        assertEquals(1, Regex("""FrameLayout\(context\)""").findAll(source).count())
        assertTrue(source.contains("FrameLayout(context).also(latestSurfaceOwnerReady)"))
        assertFalse(source.contains(".addView("))
        assertFalse(source.contains(".removeView("))
        assertFalse(source.contains("PlayerView("))
        assertFalse(source.contains("NuvioMpvSurfaceView("))
    }

    @Test
    fun `screen accepts only sanitized labels UI state and engine-neutral actions`() {
        val signature = source.substringAfter("internal fun CleanLivePlayerScreen(")
            .substringBefore(") {")
        listOf(
            "sanitizedTitle: String",
            "sanitizedSubtitle: String?",
            "sanitizedStation: String?",
            "uiState: LivePlaybackUiState",
            "onSurfaceOwnerReady: (FrameLayout) -> Unit",
            "onPause: () -> Unit",
            "onResume: () -> Unit",
            "onRetry: () -> Unit",
            "onZapPrevious: () -> Unit",
            "onZapNext: () -> Unit",
            "onExitRequested: () -> Unit",
        ).forEach { expected -> assertTrue("Missing $expected", signature.contains(expected)) }
        assertFalse(signature.contains("url", ignoreCase = true))
        assertFalse(signature.contains("header", ignoreCase = true))
        assertFalse(signature.contains("request: PlaybackRequest"))
    }

    private fun sourceFile(fileName: String = "CleanLivePlayerScreen.kt"): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(
            projectRoot,
            "app/src/main/java/com/nuvio/tv/ui/screens/player/clean/$fileName",
        ).also { check(it.isFile) { "Clean live player screen source is missing" } }
    }
}
