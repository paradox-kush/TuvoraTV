package com.nuvio.tv.core.telemetry.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTelemetryKeysTest {

    @Test
    fun `count keys have the exact contract strings`() {
        assertEquals("ttff key", "ttff:live:media3:lt2000", PlaybackTelemetryKeys.ttff(PlayKind.LIVE, PlayEngine.MEDIA3, "lt2000"))
        assertEquals("fallback key", "fallback:media3:libmpv:decoder_failed",
            PlaybackTelemetryKeys.fallback(PlayEngine.MEDIA3, PlayEngine.LIBMPV, FallbackTrigger.DECODER_FAILED))
        assertEquals("stall key", "stall:multiview_tile:gave_up", PlaybackTelemetryKeys.stall(PlayKind.MULTIVIEW_TILE, StallOutcome.GAVE_UP))
        assertEquals("stall_ms key", "stall_ms:vod:lt5000", PlaybackTelemetryKeys.stallMs(PlayKind.VOD, "lt5000"))
        assertEquals("teardown key", "teardown:libmpv:slow", PlaybackTelemetryKeys.teardown(PlayEngine.LIBMPV, TeardownOutcome.SLOW))
        assertEquals("multiview key", "multiview:tile_failed", PlaybackTelemetryKeys.multiview(MultiviewEvent.TILE_FAILED))
        assertEquals("tiles max key", "multiview_tiles_max:4", PlaybackTelemetryKeys.multiviewTilesMax(4))
        assertEquals("tiles max is clamped low", "multiview_tiles_max:1", PlaybackTelemetryKeys.multiviewTilesMax(0))
        assertEquals("tiles max is clamped high", "multiview_tiles_max:9", PlaybackTelemetryKeys.multiviewTilesMax(40))
    }

    @Test
    fun `failure names map to a trigger from the clean pipeline Media3 and mpv`() {
        val cases = mapOf(
            "MANIFEST_INVALID" to FallbackTrigger.MANIFEST_INVALID,
            "ERROR_CODE_PARSING_MANIFEST_MALFORMED" to FallbackTrigger.MANIFEST_INVALID,
            "DEMUX_FAILED" to FallbackTrigger.DEMUX_FAILED,
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED" to FallbackTrigger.DEMUX_FAILED,
            "MPV_ERROR_UNKNOWN_FORMAT" to FallbackTrigger.DEMUX_FAILED,
            "VIDEO_DECODER_FAILED" to FallbackTrigger.DECODER_FAILED,
            "VIDEO_DECODER_UNAVAILABLE" to FallbackTrigger.DECODER_FAILED,
            "ERROR_CODE_DECODER_INIT_FAILED" to FallbackTrigger.DECODER_FAILED,
            "AUDIO_DECODER_FAILED" to FallbackTrigger.DECODER_FAILED,
            "VIDEO_RENDERER_FAILED" to FallbackTrigger.RENDERER_FAILED,
            "SURFACE_LOST" to FallbackTrigger.RENDERER_FAILED,
            "NETWORK_TIMEOUT" to FallbackTrigger.TIMEOUT,
            "NO_PROGRESS" to FallbackTrigger.TIMEOUT,
            "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" to FallbackTrigger.TIMEOUT,
            "NETWORK_UNREACHABLE" to FallbackTrigger.NETWORK,
            "ERROR_CODE_IO_BAD_HTTP_STATUS" to FallbackTrigger.NETWORK,
            "SOURCE_NOT_FOUND" to FallbackTrigger.NETWORK,
            "WATCHDOG_EXPIRED" to FallbackTrigger.WATCHDOG,
            // Not an IO error even though the letters "IO_" appear inside AUDIO_.
            "AUDIO_OUTPUT_FAILED" to FallbackTrigger.OTHER,
            "IllegalStateException" to FallbackTrigger.OTHER,
        )
        cases.forEach { (name, trigger) ->
            assertEquals("trigger for $name", trigger, PlaybackTelemetryKeys.triggerForFailureName(name))
        }
        assertEquals("no failure at all", FallbackTrigger.OTHER, PlaybackTelemetryKeys.triggerForFailureName(null))
    }

    @Test
    fun `ext class and content type class are closed enums`() {
        assertEquals("m3u8", PlaybackTelemetryKeys.extClass("m3u8"))
        assertEquals("hls container is an m3u8", "m3u8", PlaybackTelemetryKeys.extClass("hls"))
        assertEquals("none", PlaybackTelemetryKeys.extClass("unknown"))
        assertEquals("none", PlaybackTelemetryKeys.extClass(null))
        assertEquals("other", PlaybackTelemetryKeys.extClass("webm"))
        assertEquals("hls", PlaybackTelemetryKeys.contentTypeClass("application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals("mp2t", PlaybackTelemetryKeys.contentTypeClass("video/MP2T"))
        assertEquals("octet", PlaybackTelemetryKeys.contentTypeClass("application/octet-stream"))
        assertEquals("html", PlaybackTelemetryKeys.contentTypeClass("text/html"))
        assertEquals("text", PlaybackTelemetryKeys.contentTypeClass("text/plain"))
        assertEquals("video", PlaybackTelemetryKeys.contentTypeClass("video/mp4"))
        assertEquals("other", PlaybackTelemetryKeys.contentTypeClass("application/json"))
        assertNull("no header", PlaybackTelemetryKeys.contentTypeClass(null))
    }

    @Test
    fun `engine labels from breadcrumbs map including the legacy names`() {
        assertEquals(PlayEngine.MEDIA3, PlayEngine.fromLabel("media3"))
        assertEquals(PlayEngine.MEDIA3, PlayEngine.fromLabel("exoplayer"))
        assertEquals(PlayEngine.LIBMPV, PlayEngine.fromLabel("mpv"))
        assertEquals(PlayEngine.OTHER, PlayEngine.fromLabel("unknown"))
        assertEquals(PlayEngine.OTHER, PlayEngine.fromLabel(null))
    }
}
