package com.nuvio.tv.core.iptv.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XMLTV ingest kept every programme a feed contained — no time bound at all. Survivable while
 * it ran for M3U playlists only; not survivable as the general rule, and the whole-guide lane for
 * Xtream panels would have multiplied it across every account. These pin the bound.
 *
 * NOTE assertion order: this is JUnit — `assertTrue(message, condition)`. The commonTest twin in
 * NuvioMobile/NuvioDesktop is kotlin.test and puts the message LAST. Written out by hand rather
 * than ported, because a regex that moves the argument silently produces passing nonsense.
 */
class XmltvIngestWindowTest {

    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private fun keeps(startOffsetMs: Long, durationMs: Long = hour) =
        XmltvIngestWindow.keeps(now + startOffsetMs, now + startOffsetMs + durationMs, now)

    @Test
    fun `a programme airing now is kept`() {
        assertTrue(keeps(-30 * 60 * 1000))
    }

    @Test
    fun `recent history is kept so the guide opens with a visible past`() {
        // The docked guide anchors an hour back, so that hour has to be on disk.
        assertTrue("two hours ago is inside the six-hour lookback", keeps(-2 * hour))
        assertTrue("five hours ago is still inside it", keeps(-5 * hour))
    }

    @Test
    fun `history older than the lookback is refused`() {
        assertFalse("eight hours ago is past the bound", keeps(-8 * hour))
        assertFalse("deep history belongs to the catch-up lane, not this one", keeps(-3 * day))
    }

    @Test
    fun `two days ahead is kept and a week ahead is refused`() {
        assertTrue("a day and a half ahead is inside the forward horizon", keeps(36 * hour))
        assertFalse("a week of schedule is rows a budget device cannot afford", keeps(7 * day))
    }

    @Test
    fun `a programme straddling the window edge is kept`() {
        // Started before the lookback but still running: the guide needs it to fill the row.
        assertTrue(XmltvIngestWindow.keeps(now - 9 * hour, now - 1 * hour, now))
    }

    @Test
    fun `a zero length programme is refused`() {
        // A feed with a broken date format produces thousands of these, and every window read
        // that spans the instant returns them.
        assertFalse(XmltvIngestWindow.keeps(now, now, now))
    }

    @Test
    fun `a backwards programme is refused`() {
        assertFalse(XmltvIngestWindow.keeps(now + hour, now, now))
    }

    @Test
    fun `an epoch-zero programme is refused`() {
        assertFalse(XmltvIngestWindow.keeps(0L, hour, now))
    }
}
