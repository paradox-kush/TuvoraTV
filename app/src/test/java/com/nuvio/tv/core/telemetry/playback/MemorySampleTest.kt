package com.nuvio.tv.core.telemetry.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySampleTest {

    @Test
    fun `compact form round trips and rejects garbage`() {
        val s = MemorySample(5L, 412, 88, 120, 301, 96, 210)
        assertEquals("round trip", s.copy(atMs = 0L), MemorySample.fromCompact(s.toCompact()))
        assertNull(MemorySample.fromCompact("1,2,3"))
        assertNull(MemorySample.fromCompact("a,b,c,d,e,f"))
        assertNull(MemorySample.fromCompact(null))
    }

    @Test
    fun `unknown fields are left out never invented`() {
        val props = MemorySample(1L, 400, 80, 100, availMb = -1, threads = -1, fds = 150).toProperties()
        assertEquals(setOf("mem_pss_mb", "mem_java_mb", "mem_native_mb", "mem_fds"), props.keys)
        assertEquals(150, props["mem_fds"])
    }

    @Test
    fun `sampling is never faster than every 10 seconds`() {
        assertTrue("first sample", MemorySamplePolicy.due(null, 5L))
        assertFalse("9.9 s later", MemorySamplePolicy.due(1_000L, 10_900L))
        assertTrue("10 s later", MemorySamplePolicy.due(1_000L, 11_000L))
    }

    @Test
    fun `ring keeps only the newest few`() {
        val ring = MemorySampleRing(capacity = 3)
        assertNull(ring.last())
        (1..5).forEach { ring.add(MemorySample(it.toLong(), it, 0, 0)) }
        assertEquals("bounded", 3, ring.size())
        assertEquals("newest wins", 5, ring.last()?.pssMb)
    }

    @Test
    fun `proc text parsers read the documented lines`() {
        assertEquals(96, ProcMemoryParser.threads("Name:\tapp\nState:\tS (sleeping)\nThreads:\t96\nVmRSS:\t1 kB\n"))
        assertEquals(412_345L, ProcMemoryParser.pssKb("Rss:  500000 kB\nPss:       412345 kB\nPss_Anon: 1 kB\n"))
        assertEquals(301_000L, ProcMemoryParser.memAvailableKb("MemTotal: 2000000 kB\nMemFree: 1 kB\nMemAvailable:  301000 kB\n"))
        assertNull(ProcMemoryParser.threads("nothing here"))
        assertEquals(402, ProcMemoryParser.kbToMb(412_345L))
    }

    @Test
    fun `exit memory prefers the persisted sample and falls back to the older breadcrumbs`() {
        assertEquals(412, MemoryContext.forExit("412,88,120,301,96,210", 1L, 1L, 1L, 1)["mem_pss_mb"])
        val fallback = MemoryContext.forExit(null, 410_000L, 90_000L, 120_000L, 180)
        assertEquals(400, fallback["mem_pss_mb"])
        assertEquals(180, fallback["mem_fds"])
        assertFalse("avail is never invented", fallback.containsKey("mem_avail_mb"))
        assertFalse("threads are never invented", fallback.containsKey("mem_threads"))
    }

    @Test
    fun `previous run state comes from the breadcrumbs and the tile count`() {
        val live = MemoryContext.previousRun(true, "live", "fullscreen", "libmpv", 0)
        assertEquals(mapOf<String, Any>("prev_playing" to true, "prev_tiles" to 0, "prev_kind" to "live", "prev_engine" to "libmpv"), live)
        assertEquals("preview surface", "preview", MemoryContext.previousRun(true, "live", "preview", "media3", 0)["prev_kind"])
        val idle = MemoryContext.previousRun(false, "live", "fullscreen", "media3", 0)
        assertEquals("idle run carries only the flag and tiles", mapOf<String, Any>("prev_playing" to false, "prev_tiles" to 0), idle)
        val tiles = MemoryContext.previousRun(false, null, null, null, 4)
        assertEquals("multiview counts as playing", true, tiles["prev_playing"])
        assertEquals("multiview_tile", tiles["prev_kind"])
        assertEquals(4, tiles["prev_tiles"])
        assertEquals("tile mode is Media3", "media3", tiles["prev_engine"])
    }
}
