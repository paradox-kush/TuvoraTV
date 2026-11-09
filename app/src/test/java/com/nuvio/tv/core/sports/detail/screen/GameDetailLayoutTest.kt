package com.nuvio.tv.core.sports.detail.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression for the Onn pass 11-08: the win-chance card pushed By Period down and its team rows were clipped. */
class GameDetailLayoutTest {
    @Test fun `plenty of room keeps the full card with its own title`() {
        val f = ByPeriodFit.layout(availableDp = 200, rows = 2)!!
        assertTrue("title", f.titleRow)
        assertTrue("header", f.header)
        assertEquals("ideal rows", ByPeriodFit.ROW_IDEAL_DP, f.rowDp)
    }

    @Test fun `the Onn case - win chance above leaves about 73dp and both team rows still fit`() {
        val f = ByPeriodFit.layout(availableDp = 73, rows = 2)
        assertNotNull("never clipped into nothing", f)
        assertEquals("two rows of at least the minimum fit inside the card", true, ByPeriodFit.heightOf(f!!, 2) <= 73)
    }

    @Test fun `for every height the chosen layout fits or the card is dropped - never clipped`() {
        for (rows in 2..3) for (h in 0..260) {
            val f = ByPeriodFit.layout(h, rows) ?: continue
            assertTrue("rows=$rows h=$h fits", ByPeriodFit.heightOf(f, rows) <= h)
            assertTrue("rows=$rows h=$h row never below the legible minimum", f.rowDp >= ByPeriodFit.ROW_MIN_DP)
        }
    }

    @Test fun `the separate title goes before the rows shrink and the header goes last`() {
        val tight = ByPeriodFit.layout(availableDp = 100, rows = 2)!!
        assertFalse("title folded into the header row", tight.titleRow)
        assertTrue("header kept", tight.header)
        val tighter = ByPeriodFit.layout(availableDp = 66, rows = 2)!!
        assertFalse("header dropped only when even minimum rows can't sit under it", tighter.header)
    }

    @Test fun `too little room drops the card rather than clipping it - the scoreboard already has the score`() =
        assertNull("dropped", ByPeriodFit.layout(availableDp = 40, rows = 2))

    private val chan = WatchCandidate("c1", "NHL | 01 - 1pm Panthers @ Bruins", 0, 10)

    @Test fun `Play is a short verb - the provider's long channel title never sits in the button`() {
        assertEquals("play", "▶  Play", WatchCopy.primaryLabel(PrimaryAction.Play(chan)))
        assertEquals("switch", "⇄  Switch", WatchCopy.primaryLabel(PrimaryAction.Switch(chan)))
        assertEquals("replay", "↩  Watch replay", WatchCopy.primaryLabel(PrimaryAction.Replay(chan)))
        assertEquals("retry", "Retry", WatchCopy.primaryLabel(PrimaryAction.Rematch(failed = true)))
        assertEquals("find", "Find channels", WatchCopy.primaryLabel(PrimaryAction.Rematch(failed = false)))
        assertEquals("add", "Add IPTV provider", WatchCopy.primaryLabel(PrimaryAction.AddProvider))
        assertNull("none", WatchCopy.primaryLabel(PrimaryAction.None))
    }

    @Test fun `the line under the buttons names the channel Play or Switch will open`() {
        val browse = WatchUi(WatchMode.BROWSING, null, PrimaryAction.Play(chan), WatchStatus.READY, 39)
        assertEquals("browse", "On NHL | 01 - 1pm Panthers @ Bruins", WatchCopy.statusLine(browse))
        val watching = WatchUi(WatchMode.WATCHING, "Sky Sports 1", PrimaryAction.Switch(chan), WatchStatus.READY, 0)
        assertEquals("switch", "Watching Sky Sports 1 · switch to NHL | 01 - 1pm Panthers @ Bruins", WatchCopy.statusLine(watching))
        val replay = browse.copy(primary = PrimaryAction.Replay(chan))
        assertEquals("replay", "Replay from kick-off on NHL | 01 - 1pm Panthers @ Bruins", WatchCopy.statusLine(replay))
    }

    @Test fun `the other status lines are unchanged`() {
        val base = WatchUi(WatchMode.BROWSING, null, PrimaryAction.None, WatchStatus.FINDING, 0)
        assertEquals("finding", "Finding channels…", WatchCopy.statusLine(base))
        assertEquals("failed", "Couldn't check your channels", WatchCopy.statusLine(base.copy(status = WatchStatus.FAILED)))
        assertEquals("on it", "Watching this game on X", WatchCopy.statusLine(base.copy(status = WatchStatus.ALREADY_ON_IT, currentChannelName = "X")))
        assertEquals("hidden", "", WatchCopy.statusLine(base.copy(status = WatchStatus.HIDDEN)))
    }
}
