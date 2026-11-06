package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.DownloadSniff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSniffTest {
    private val ts = byteArrayOf(0x47, 0x40, 0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val playlist = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()

    @Test
    fun `an mpegurl content type is a playlist whatever the bytes`() {
        assertTrue("apple", DownloadSniff.isHlsPlaylist("application/vnd.apple.mpegurl", ts))
        assertTrue("x-mpegurl", DownloadSniff.isHlsPlaylist("application/x-mpegURL", ts))
        assertTrue("audio", DownloadSniff.isHlsPlaylist("audio/mpegurl; charset=utf-8", ByteArray(0)))
    }

    @Test
    fun `the extm3u header is a playlist even when the type says text or nothing`() {
        assertTrue("text/plain", DownloadSniff.isHlsPlaylist("text/plain", playlist))
        assertTrue("no type", DownloadSniff.isHlsPlaylist(null, playlist))
        assertTrue("octet-stream", DownloadSniff.isHlsPlaylist("application/octet-stream", playlist))
    }

    @Test
    fun `a utf8 byte order mark before the header still counts`() {
        assertTrue(DownloadSniff.isHlsPlaylist(null, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + playlist))
    }

    @Test
    fun `transport stream and mp4 are files`() {
        assertFalse("ts", DownloadSniff.isHlsPlaylist("video/mp2t", ts))
        assertFalse("octet ts", DownloadSniff.isHlsPlaylist("application/octet-stream", ts))
        assertFalse("no type", DownloadSniff.isHlsPlaylist(null, ts))
        assertFalse("empty", DownloadSniff.isHlsPlaylist(null, ByteArray(0)))
        assertFalse("mp4", DownloadSniff.isHlsPlaylist("video/mp4", byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)))
    }
}
