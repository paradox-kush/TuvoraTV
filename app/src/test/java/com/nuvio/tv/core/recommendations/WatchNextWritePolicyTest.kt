package com.nuvio.tv.core.recommendations

import com.nuvio.tv.core.recommendations.WatchNextWritePolicy.WriteMode
import com.nuvio.tv.core.recommendations.WatchNextWritePolicy.WriteSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** JUnit argument order: assertEquals(message, expected, actual). */
class WatchNextWritePolicyTest {

    @Test
    fun `played item with an existing row is replaced so a launcher-hidden row comes back`() {
        assertEquals("played+exists", WriteMode.REPLACE, WatchNextWritePolicy.modeFor(WriteSource.PLAYED, true))
    }

    @Test
    fun `played item without a row is inserted`() {
        assertEquals("played+absent", WriteMode.INSERT, WatchNextWritePolicy.modeFor(WriteSource.PLAYED, false))
    }

    @Test
    fun `reconcile with an existing row updates in place so a removed item stays removed`() {
        assertEquals("reconcile+exists", WriteMode.UPDATE, WatchNextWritePolicy.modeFor(WriteSource.RECONCILE, true))
    }

    @Test
    fun `reconcile without a row is inserted`() {
        assertEquals("reconcile+absent", WriteMode.INSERT, WatchNextWritePolicy.modeFor(WriteSource.RECONCILE, false))
    }
}
