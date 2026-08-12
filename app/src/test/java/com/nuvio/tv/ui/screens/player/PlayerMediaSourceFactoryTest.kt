package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerMediaSourceFactoryTest {

    @Test
    fun `inferMimeType prefers response content type for manifest urls without extension`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=UTF-8")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType uses content disposition filename when content type is missing`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Disposition" to "attachment; filename=manifest.mpd")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType ignores generic playlist path without manifest evidence`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/api/playlist/stream",
            filename = null
        )

        assertNull(mimeType)
    }

    @Test
    fun `inferMimeType recognizes explicit format query values`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?format=m3u8",
            filename = null
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `normalizeMimeType recognizes redirected matroska file responses`() {
        val mimeType = PlayerMediaSourceFactory.normalizeMimeType("video/x-matroska")

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType uses filename star content disposition for octet stream responses`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/extract?id=42",
            filename = null,
            responseHeaders = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Disposition" to "attachment; filename*=UTF-8''episode-04.mkv"
            )
        )

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType prefers URL extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/stream.m3u8",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType prefers filename extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = "movie.mpd",
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType recognizes playlist endpoints with numeric or token ids as HLS`() {
        val urls = listOf(
            "https://example.com/playlist/759755?token=mock_token_123&expires=1788170323&h=1&lang=it",
            "https://example.com/playlist/123456",
            "https://example.com/playlist/a1b2c3d4e5f6?h=1",
            "https://example.com/hls/759755",
            "https://example.com/manifest/abc123456",
            "https://example.com/master/stream99",
            "https://example.com/live/stream.m3u",
            "https://example.com/playback?protocol=hls"
        )

        for (url in urls) {
            val mimeType = PlayerMediaSourceFactory.inferMimeType(
                url = url,
                filename = null
            )
            assertEquals("Expected HLS mimeType for $url", MimeTypes.APPLICATION_M3U8, mimeType)
        }
    }

    // --- Learned container types (panels that 302 a `.ts` live URL to an HLS playlist) ----------

    @Test
    fun `learned container type is remembered per host and extension`() {
        val url = "https://panel.example.com/live/user/pass/1300597937.ts"
        assertNull(PlayerMediaSourceFactory.learnedContainerMimeType(url))

        PlayerMediaSourceFactory.rememberContainerMimeType(url, MimeTypes.APPLICATION_M3U8)

        // Every other channel on the same panel is the point — one failure teaches the provider,
        // not the channel.
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            PlayerMediaSourceFactory.learnedContainerMimeType(
                "https://panel.example.com/live/user/pass/999.ts"
            )
        )
    }

    @Test
    fun `learned live container does not leak into VOD on the same host`() {
        PlayerMediaSourceFactory.rememberContainerMimeType(
            "https://vodpanel.example.com/live/user/pass/1.ts",
            MimeTypes.APPLICATION_M3U8
        )

        // Same panel, real progressive mp4 — must not inherit what `.ts` learned.
        assertNull(
            PlayerMediaSourceFactory.learnedContainerMimeType(
                "https://vodpanel.example.com/movie/user/pass/55.mp4"
            )
        )
    }

    @Test
    fun `learned container type is not shared across hosts`() {
        PlayerMediaSourceFactory.rememberContainerMimeType(
            "https://one.example.com/live/user/pass/1.ts",
            MimeTypes.APPLICATION_M3U8
        )

        assertNull(
            PlayerMediaSourceFactory.learnedContainerMimeType(
                "https://two.example.com/live/user/pass/1.ts"
            )
        )
    }

    @Test
    fun `extensionless stalker links share one bucket per host despite rotating tokens`() {
        // create_link hands back a different token every play, so the host is the only stable part.
        PlayerMediaSourceFactory.rememberContainerMimeType(
            "http://portal.example.com/ch/12345_?token=aaaa",
            MimeTypes.APPLICATION_M3U8
        )

        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            PlayerMediaSourceFactory.learnedContainerMimeType(
                "http://portal.example.com/ch/98765_?token=bbbb"
            )
        )
    }

    @Test
    fun `learned container key ignores query and credentials and is case insensitive on host`() {
        assertEquals(
            "panel.example.com|ts",
            PlayerMediaSourceFactory.learnedContainerKey(
                "https://Panel.Example.COM/live/u/p/7.ts?token=abc#frag"
            )
        )
        assertEquals(
            "panel.example.com|",
            PlayerMediaSourceFactory.learnedContainerKey("https://panel.example.com/ch/7_")
        )
        assertNull(PlayerMediaSourceFactory.learnedContainerKey("not a url"))
    }
}
