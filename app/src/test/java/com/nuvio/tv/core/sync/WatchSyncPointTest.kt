package com.nuvio.tv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two invariants behind the watch-sync-point data-loss fixes
 * (upstream 6f0e66459 / 308e12ba1 / 6d2afe8f7).
 */
class WatchSyncPointTest {

    @Test
    fun advanceNeverMovesThePointBackwards() {
        assertEquals("a newer read time wins", 100L, WatchSyncPoint.advance(50L, 100L))
        assertEquals(
            "an out-of-order older push must not retract a newer point",
            100L,
            WatchSyncPoint.advance(100L, 50L),
        )
        assertEquals("equal is a no-op", 100L, WatchSyncPoint.advance(100L, 100L))
        assertEquals("from zero", 100L, WatchSyncPoint.advance(0L, 100L))
    }

    @Test
    fun preservesLocalEntriesTouchedAfterThePoint() {
        assertTrue("touched after the point is unsynced -> keep", WatchSyncPoint.preservesLocal(200L, 100L))
        assertFalse("touched at/before the point was covered by the push -> may drop", WatchSyncPoint.preservesLocal(100L, 100L))
        assertFalse(WatchSyncPoint.preservesLocal(50L, 100L))
    }

    @Test
    fun atPointZeroEveryRealEntryIsPreserved() {
        // A device that never pushed has nothing on remote; a pull omitting its entries proves
        // nothing, so every real entry (touched at > 0) must survive. This is the missing case the
        // old `point > 0L` gate in WatchedItemsPreferences got wrong.
        assertTrue(WatchSyncPoint.preservesLocal(1L, 0L))
        assertTrue(WatchSyncPoint.preservesLocal(Long.MAX_VALUE, 0L))
        assertFalse("a zero-timestamp entry at point 0 is not newer", WatchSyncPoint.preservesLocal(0L, 0L))
    }
}
