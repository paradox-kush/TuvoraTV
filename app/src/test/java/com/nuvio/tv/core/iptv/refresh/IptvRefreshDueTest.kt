package com.nuvio.tv.core.iptv.refresh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the IPTV auto-refresh worker's due-selection: which playlists are due given each playlist's
 * autoRefreshHours + last-refresh time. This is the exact pure decision the worker runs, so it guards
 * "refresh nothing before its window" + "off means never" without WorkManager/DB/network.
 */
class IptvRefreshDueTest {

    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L

    private fun candidate(
        hours: Int,
        lastRefreshMs: Long?,
        enabled: Boolean = true,
        id: String = "p"
    ) = RefreshCandidate(id, enabled, hours, lastRefreshMs)

    @Test
    fun `never-refreshed enabled playlist is due`() {
        assertTrue(IptvRefreshDue.isDue(candidate(hours = 24, lastRefreshMs = null), now))
    }

    @Test
    fun `off (zero hours) is never due even if never refreshed`() {
        assertFalse(IptvRefreshDue.isDue(candidate(hours = 0, lastRefreshMs = null), now))
        assertFalse(IptvRefreshDue.isDue(candidate(hours = 0, lastRefreshMs = now - 100 * hour), now))
    }

    @Test
    fun `disabled playlist is never due`() {
        assertFalse(IptvRefreshDue.isDue(candidate(hours = 24, lastRefreshMs = null, enabled = false), now))
    }

    @Test
    fun `not due until the window fully elapses`() {
        val h = 24
        // Refreshed 23h ago → not due; exactly 24h ago → due; 25h ago → due.
        assertFalse(IptvRefreshDue.isDue(candidate(hours = h, lastRefreshMs = now - 23 * hour), now))
        assertTrue(IptvRefreshDue.isDue(candidate(hours = h, lastRefreshMs = now - 24 * hour), now))
        assertTrue(IptvRefreshDue.isDue(candidate(hours = h, lastRefreshMs = now - 25 * hour), now))
    }

    @Test
    fun `boundary is inclusive at exactly the interval`() {
        assertTrue(IptvRefreshDue.isDue(candidate(hours = 6, lastRefreshMs = now - 6 * hour), now))
        assertFalse(IptvRefreshDue.isDue(candidate(hours = 6, lastRefreshMs = now - (6 * hour - 1)), now))
    }

    @Test
    fun `duePlaylists returns only the due enabled ones`() {
        val candidates = listOf(
            candidate(hours = 24, lastRefreshMs = null, id = "never"),          // due
            candidate(hours = 24, lastRefreshMs = now - 25 * hour, id = "old"), // due
            candidate(hours = 24, lastRefreshMs = now - 1 * hour, id = "fresh"),// not due
            candidate(hours = 0, lastRefreshMs = null, id = "off"),             // off
            candidate(hours = 6, lastRefreshMs = null, enabled = false, id = "disabled"), // disabled
        )
        val due = IptvRefreshDue.duePlaylists(candidates, now).map { it.playlistId }.toSet()
        assertEquals(setOf("never", "old"), due)
    }

    @Test
    fun `shortestIntervalHours is the min enabled positive interval`() {
        val candidates = listOf(
            candidate(hours = 24, lastRefreshMs = null, id = "a"),
            candidate(hours = 6, lastRefreshMs = null, id = "b"),
            candidate(hours = 48, lastRefreshMs = null, id = "c"),
            candidate(hours = 0, lastRefreshMs = null, id = "off"),          // ignored (off)
            candidate(hours = 1, lastRefreshMs = null, enabled = false, id = "disabled"), // ignored
        )
        assertEquals(6, IptvRefreshDue.shortestIntervalHours(candidates))
    }

    @Test
    fun `shortestIntervalHours is null when nothing opts in`() {
        val candidates = listOf(
            candidate(hours = 0, lastRefreshMs = null, id = "off"),
            candidate(hours = 24, lastRefreshMs = null, enabled = false, id = "disabled"),
        )
        assertNull(IptvRefreshDue.shortestIntervalHours(candidates))
        assertNull(IptvRefreshDue.shortestIntervalHours(emptyList()))
    }
}
