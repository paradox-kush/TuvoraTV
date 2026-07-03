package com.nuvio.tv.core.iptv.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the M3U classification + #EXTINF attribute-extraction rules (pure, no Android). */
class M3UParserTest {

    // --- classification by URL path segment ---------------------------------

    @Test
    fun `path segment classifies live movie series outright`() {
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h:80/live/u/p/123.ts", "ts"))
        assertEquals(M3UKind.VOD, M3UParser.classify("http://h:80/movie/u/p/385215.mp4", "mp4"))
        assertEquals(M3UKind.SERIES, M3UParser.classify("http://h:80/series/u/p/900.mkv", "mkv"))
        // path wins even when the extension would say otherwise (a .ts under /movie/ is still VOD)
        assertEquals(M3UKind.VOD, M3UParser.classify("http://h/movie/u/p/1.ts", "ts"))
    }

    // --- classification by container extension (no path hint) ----------------

    @Test
    fun `extension classifies when no path segment hint`() {
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h:8080/stream/123.ts", "ts"))
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h/x/playlist.m3u8", "m3u8"))
        assertEquals(M3UKind.VOD, M3UParser.classify("http://h/x/film.mp4", "mp4"))
        assertEquals(M3UKind.VOD, M3UParser.classify("http://h/x/film.mkv", "mkv"))
        assertEquals(M3UKind.VOD, M3UParser.classify("http://h/x/film.avi", "avi"))
        // bare channel URL with no extension -> live (the common "live" shape)
        assertEquals(M3UKind.LIVE, M3UParser.classify("http://h:8080/channel/42", null))
    }

    @Test
    fun `extOf pulls container extension from url tail ignoring query`() {
        assertEquals("mp4", M3UParser.extOf("https://onnipsite.site:443/movie/fifi/buoyant7map/385215.mp4"))
        assertEquals("mkv", M3UParser.extOf("http://h/movie/u/p/553416.mkv"))
        assertEquals("ts", M3UParser.extOf("http://h/live/u/p/402957.ts?token=abc"))
        assertNull(M3UParser.extOf("http://h:8080/channel/42"))
        // a dot in the host/path but not a real extension segment
        assertNull(M3UParser.extOf("http://h/live/stream"))
    }

    // --- #EXTINF attribute extraction ---------------------------------------

    @Test
    fun `parseExtInf extracts tvg-id logo group-title and display name`() {
        val line = """#EXTINF:-1 tvg-id="bbc.uk" tvg-name="BBC One" tvg-logo="http://img/bbc.png" group-title="UK NEWS",BBC One HD"""
        val e = M3UParser.parseExtInf(line, "http://h:8080/live/u/p/42.ts")!!
        assertEquals("BBC One HD", e.name)             // display name (after the last unquoted comma)
        assertEquals("bbc.uk", e.tvgId)
        assertEquals("BBC One", e.tvgName)
        assertEquals("http://img/bbc.png", e.logo)
        assertEquals("UK NEWS", e.group)
        assertEquals(M3UKind.LIVE, e.kind)
        assertEquals("ts", e.ext)
    }

    @Test
    fun `parseExtInf falls back to tvg-name when display name blank`() {
        val line = """#EXTINF:-1 tvg-name="Fallback Title" group-title="G","""
        val e = M3UParser.parseExtInf(line, "http://h/movie/u/p/1.mp4")!!
        assertEquals("Fallback Title", e.name)
    }

    @Test
    fun `parseExtInf handles commas inside attribute values`() {
        // a comma inside group-title must NOT be treated as the display-name separator
        val line = """#EXTINF:-1 tvg-logo="x" group-title="Movies, Action",Die Hard (1988)"""
        val e = M3UParser.parseExtInf(line, "http://h/movie/u/p/1.mp4")!!
        assertEquals("Die Hard (1988)", e.name)
        assertEquals("Movies, Action", e.group)
    }

    @Test
    fun `parseExtInf drops the placeholder tmdb logo prefix`() {
        // the sample.m3u ships a bare truncated tmdb prefix as a logo for some rows — treat as null
        val line = """#EXTINF:-1 tvg-name="Hiram (2025)" tvg-logo="https://image.tmdb.org/t/p/w600_and_h900_bestv2" group-title="ENGLISH FHD (2025)",Hiram (2025)"""
        val e = M3UParser.parseExtInf(line, "http://h/movie/u/p/553429.mkv")!!
        assertNull(e.logo)
    }

    @Test
    fun `parseExtInf returns null for a non-EXTINF line`() {
        assertNull(M3UParser.parseExtInf("#EXTGRP:Sports", "http://h/x.ts"))
    }

    // --- series-episode detection from a display name ------------------------

    @Test
    fun `seriesEpisodeOf recognizes SxxExx and NxM name patterns`() {
        assertEquals(Triple("The Grand Tour", 1, 2), M3UParser.seriesEpisodeOf("The Grand Tour S01E02"))
        assertEquals(Triple("Breaking Bad", 5, 14), M3UParser.seriesEpisodeOf("Breaking Bad - s05e14"))
        assertEquals(Triple("Friends", 3, 7), M3UParser.seriesEpisodeOf("Friends 3x07"))
        // a plain movie name -> null (stays VOD)
        assertNull(M3UParser.seriesEpisodeOf("Alien: Romulus (2024)"))
    }

    // --- streaming parse (plain + gzip) -------------------------------------

    private val SAMPLE = """
        #EXTM3U
        #EXTINF:-1 tvg-id="bbc.uk" tvg-logo="http://img/bbc.png" group-title="UK NEWS",BBC One
        http://h:8080/live/u/p/1.ts
        #EXTGRP:ignored directive
        #EXTINF:-1 tvg-name="Alien Romulus (2024)" tvg-logo="http://img/a.jpg" group-title="MOVIES",Alien Romulus (2024)
        https://h:443/movie/u/p/385215.mp4

        http://h/stray-url-with-no-extinf.ts
        #EXTINF:-1 group-title="SERIES",The Grand Tour S01E02
        http://h/series/u/p/11.mkv
    """.trimIndent()

    private fun parseAll(reader: java.io.BufferedReader): List<M3UEntry> =
        buildList { M3UParser.parseStream(reader) { add(it) } }

    @Test
    fun `parseStream reads pairs skips directives and stray urls`() {
        val entries = parseAll(SAMPLE.reader().buffered())
        // 3 valid #EXTINF+URL pairs; the #EXTGRP directive, blank line, and stray URL are ignored
        assertEquals(3, entries.size)
        assertEquals(M3UKind.LIVE, entries[0].kind)
        assertEquals("BBC One", entries[0].name)
        assertEquals("UK NEWS", entries[0].group)
        assertEquals(M3UKind.VOD, entries[1].kind)
        assertEquals("mp4", entries[1].ext)
        assertEquals(M3UKind.SERIES, entries[2].kind)   // /series/ path
        assertEquals("The Grand Tour S01E02", entries[2].name)
    }

    @Test
    fun `gzip body parses identically to plain (transparent decode through the reader)`() {
        val gzBytes = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.GZIPOutputStream(bos).use { it.write(SAMPLE.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val gzReader = java.io.BufferedReader(
            java.io.InputStreamReader(java.util.zip.GZIPInputStream(gzBytes.inputStream()), Charsets.UTF_8)
        )
        val fromGzip = parseAll(gzReader)
        val fromPlain = parseAll(SAMPLE.reader().buffered())
        assertEquals(fromPlain.map { it.name to it.kind }, fromGzip.map { it.name to it.kind })
        assertEquals(3, fromGzip.size)
    }
}
