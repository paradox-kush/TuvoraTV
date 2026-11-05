package com.nuvio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring guards for upstream player fixes that were merged as ancestors of the fork's main but
 * whose call sites were dropped while resolving upstream-merge conflicts (2026-10-25 audit). The
 * policies themselves have unit tests; these assert the legacy VOD controller still calls them,
 * so the next conflict resolution can't silently drop the wiring again.
 */
class RestoredUpstreamPlayerFixesWiringTest {

    @Test
    fun `audio failures climb the shared fallback ladder instead of an inline copy`() {
        val init = source("PlayerRuntimeControllerInitialization.kt")
        val recovery = source("PlayerRuntimeControllerErrorRecovery.kt")

        assertEquals(
            "inline ladder sets hasTriedAudioPcmFallback without arming the rebuild",
            0,
            Regex("""if \(!hasTriedAudioPcmFallback\)""").findAll(init).count()
        )
        assertTrue(
            "audio-track/decoder and stuck-playing failures must use tryNextAudioFallbackStep",
            Regex("""tryNextAudioFallbackStep\(currentPosition\)""").findAll(init).count() >= 2
        )
        val helper = recovery.substringAfter("fun PlayerRuntimeController.tryNextAudioFallbackStep", "")
        assertTrue("tryNextAudioFallbackStep must exist", helper.isNotEmpty())
        assertTrue(
            "FORCE_PCM step must arm the PCM rebuild",
            helper.substringAfter("Step.FORCE_PCM").substringBefore("Step.DISABLE_AUDIO")
                .contains("pendingAudioPcmFallbackRebuild = true")
        )
    }

    @Test
    fun `VOD exo player builds the hardware-first video renderer`() {
        val init = source("PlayerRuntimeControllerInitialization.kt")
        assertTrue(
            "SubtitleOffsetRenderersFactory must swap in HardwareFirstVideoRenderer",
            init.contains("out.add(HardwareFirstVideoRenderer(")
        )
        assertTrue(
            "the replacement renderer must carry the DV7-to-HEVC mapping",
            init.contains("mapDv7ToHevc = mapDv7ToHevcEnabled")
        )
    }

    @Test
    fun `mpv VOD ends on eof-reached and retries failures through the shared policy`() {
        val events = source("PlayerRuntimeControllerPlaybackEvents.kt")
        val link = source("PlayerRuntimeControllerIptvLinkRefresh.kt")
        val view = source("NuvioMpvSurfaceView.kt")
        assertTrue("progress loop must ask the policy for the end", events.contains("MpvVodRecoveryPolicy.isAtEnd("))
        assertTrue("progress loop must read the eof-reached mirror", events.contains("isEofReachedNow()"))
        assertTrue("eof-reached must be observed", view.contains("\"eof-reached\" to MPV.mpvFormat.MPV_FORMAT_FLAG"))
        assertTrue("VOD end-file errors must try the auto-retry first", link.contains("attemptMpvVodAutoRetry(detailedError)"))
        assertTrue("retries must be earned back", events.contains("MpvVodRecoveryPolicy.shouldResetRetries("))
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
